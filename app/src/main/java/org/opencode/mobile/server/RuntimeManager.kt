package org.opencode.mobile.server

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.opencode.mobile.OpencodeApp
import org.opencode.mobile.account.YandexAccountController
import org.opencode.mobile.installer.AppInstallBridge
import java.io.File
import java.util.UUID

/**
 * Оркестратор жизненного цикла opencode runtime (Этап 2).
 *
 * Собирает в одном месте всё, что раньше жило в runServerLoop сервиса:
 *  - стадии: PREPARING -> STARTING_MEMORY -> STARTING_SERVER -> HEALTHY
 *  - валидация нативного runtime (бинарь, musl-libs) — терминальный отказ
 *  - деградация: память не поднялась -> serve стартует, статус DEGRADED
 *  - bounded backoff рестартов (2^attempt, cap 15s) + ЛИМИТ попыток
 *  - структурированные ошибки и причины остановки (RuntimeState)
 *  - мягкий рестарт (requestRestart) НЕ увеличивает счётчик отказов
 *
 * Явный стоп (requestStop) и мягкий рестарт не блокируют вызывающий поток —
 * остановка процессов уходит в daemon-тред.
 */
class RuntimeManager(
    private val context: Context,
    private val onState: (RuntimeState) -> Unit,
) {
    companion object {
        /** Максимум подряд идущих витков без HEALTHY (лимит рестартов). */
        const val MAX_RESTART_ATTEMPTS = 5

        /**
         * Запасной id для Ynison, если хранилище недоступно.
         *
         * Лучше один известный id, чем device-flow без device_id: сервер всё равно примет
         * сессию, а «новое устройство» останется единственным, предсказуемым.
         */
        private const val DEFAULT_YNISON_DEVICE_ID = "opencode-mobile"

        /** Предел роста одного opencode.log; по достижении — сдвиг цепочки .N. */
        private const val MAX_LOG_BYTES = 8L * 1024 * 1024

        /** Глубина ротации: opencode.log + .1 + .2 + .3 (4 файла в цепочке максимум). */
        private const val MAX_LOG_FILES = 3

        /** Общий бюджет всех лог-файлов цепочки; хвосты сверх бюджета удаляются. */
        private const val LOG_TOTAL_BUDGET = 30L * 1024 * 1024

        /** Окно ожидания подъёма локального MCP-сервера (память или музыка). */
        private const val STARTUP_HEALTH_TIMEOUT_MS = 10_000L
        private const val STARTUP_HEALTH_POLL_MS = 500L

        /** Таймаут TCP-коннекта к сокету памяти. */
        private const val MEMORY_TCP_TIMEOUT_MS = 1_000

        /**
         * Сколько раз за жизнь serve можно поднять умерший локальный MCP обратно.
         *
         * Ограничение существует из-за симптома «умирает сразу после старта»: без него цикл
         * наблюдения респавнил бы процесс каждые 3 секунды и сам стал бы причиной падений.
         * Счётчик обнуляется, как только сервер встал, поэтому редкий разбойный уход
         * переживает сколько угодно раз, а зацикленная смерть - нет.
         */
        private const val MAX_LOCAL_MCP_RECOVERIES = 3
    }

    private val logFile = File(context.filesDir, "opencode.log")
    private val serve = ProcessSupervisor("serve")
    private val memory = ProcessSupervisor("memory")
    private val ynison = ProcessSupervisor("ynison")

    @Volatile
    private var running = false

    /** Флаг мягкого рестарта: requestRestart() убил serve, но цикл может быть НЕ в HEALTHY-фазе
     *  (например, ждёт waitForHttp) — без флага это было бы HEALTH_TIMEOUT, а не рестарт. */
    @Volatile
    private var restartRequested = false

    private var currentState = RuntimeState()

    private fun emit(transform: RuntimeState.() -> RuntimeState) {
        currentState = currentState.transform()
        onState(currentState)
    }

    /** Коды, относящиеся к подсистеме локальной памяти (фиксируют lastMemoryFailureAt). */
    private val memoryCodes =
        setOf(
            RuntimeErrorCode.MEMORY_SCRIPT_FAILED,
            RuntimeErrorCode.MEMORY_START_FAILED,
            RuntimeErrorCode.MEMORY_DIED,
        )

    /** Фиксация сбоя: пушит RuntimeError в lastError + кольцо errorHistory.
     *  Память-коды сами фиксируют lastMemoryFailureAt (отдельный флаг не нужен).
     *  transition применяется к базовому состоянию ПОВЕРХ фиксации (сдвиг стадии,
     *  счётчика рестартов или stopReason) — одиночный emit без промежуточных тиков.
     */
    private fun emitFailure(
        stage: RuntimeStage,
        code: RuntimeErrorCode,
        message: String,
        recoverable: Boolean,
        transition: RuntimeState.() -> RuntimeState = { this },
    ) {
        val at = System.currentTimeMillis()
        val err = RuntimeError(stage, code, message, recoverable, at = at)
        emit {
            copy(
                lastError = err,
                lastMemoryFailureAt = if (code in memoryCodes) at else this.lastMemoryFailureAt,
                errorHistory = (errorHistory + err).takeLast(RuntimeState.MAX_ERROR_HISTORY),
            ).transition()
        }
    }

    /** Последняя активная ошибка помечается resolvedAt (момент восстановления),
     *  НЕ стирается: UI видит «ошибка (разрешена)», история остаётся.
     *  [stageTo] — целевая стадия цикла: HEALTHY при успешном старте serve и при
     *  recovery DEGRADED→HEALTHY (без него стадия зависала на STARTING_SERVER,
     *  и UI/уведомление навсегда показывали «Starting OpenCode server…»). */
    private fun resolveActiveError(stageTo: RuntimeStage? = null) {
        val at = System.currentTimeMillis()
        emit {
            copy(
                lastError = lastError?.takeIf { it.resolvedAt == null }?.copy(resolvedAt = at) ?: lastError,
                lastRecoveredAt = at,
                stage = stageTo ?: this.stage,
            )
        }
    }

    /** Главный цикл. Вызывается из корутины сервиса; завершается сам по running=false. */
    suspend fun run() {
        running = true
        emit { copy(restartCount = 0) }

        try {
            // Терминальная валидация: бинарь/лоадер обязаны быть, иначе рестарты
            // бессмысленны (каждый виток упал бы на startServe).
            if (!OpencodeRuntime.isAssembled(context)) {
                emitTerminalInvalidRuntime()
                return
            }

            var consecutiveFailures = 0

            // Сколько раз за текущий виток serve мы подняли умерший локальный MCP
            // (память или музыка). Не путать с consecutiveFailures: тот про serve.
            var localMcpRecoveries = 0
            while (running) {
                // Гасим ПРОЦЕССЫ прошлого витка (serve тоже — см. continue из restart-ветки:
                // там процесс мог ещё не умереть, а новый виток уже пошёл) ДО ротации:
                // fd старого serve держит старый inode opencode.log, и после rename
                // он дописывал бы хвост в .1.
                serve.stop()
                memory.stop()
                ynison.stop()
                rotateLogFile(logFile)

                // Новый виток: прошлая ошибка (если была активна) помечается
                // resolved — новый виток «берёт её на себя», но не стирает из
                // истории (Diagnostics видит весь ряд сбоев).
                emit {
                    copy(
                        stage = RuntimeStage.PREPARING,
                        restartCount = consecutiveFailures,
                        lastError =
                            lastError
                                ?.takeIf { it.resolvedAt == null }
                                ?.copy(resolvedAt = System.currentTimeMillis()) ?: lastError,
                        stopReason = null,
                    )
                }

                // Рабочая директория (workspace): без неё у opencode serve нет ни одного
                // проекта — SPA показывал "Здесь пока ничего нет". Эта версия serve не
                // понимает --dir, поэтому директория задаётся через CWD (workDir).
                val wsResult =
                    runCatching {
                        val w = Workspace.resolve(context)
                        val readme = File(w, "README.md")
                        if (!readme.exists()) {
                            readme.writeText(
                                "# OpenCode Terminal\n\nРабочая директория на внешнем хранилище (Documents/OpencodeTerminal).\n",
                            )
                        }
                        w to Workspace.usingExternal(context)
                    }
                if (wsResult.isFailure) {
                    consecutiveFailures++
                    emitFailure(
                        stage = RuntimeStage.PREPARING,
                        code = RuntimeErrorCode.UNKNOWN,
                        message = "workspace resolve: ${wsResult.exceptionOrNull()?.message}",
                        recoverable = true,
                    ) { copy(stage = RuntimeStage.CRASHED, restartCount = consecutiveFailures) }
                    if (consecutiveFailures >= MAX_RESTART_ATTEMPTS) {
                        emitTerminalRestartLimit()
                        return
                    }
                    restartBackoff(consecutiveFailures)
                    continue
                }
                val (workspace, ext) = wsResult.getOrThrow()
                android.util.Log.i("OpencodeServer", "workspace=${workspace.absolutePath} external=$ext")

                val memoryToken = UUID.randomUUID().toString()
                // Тот же токен отдаём проверкам приложения (RuntimeValidation в
                // диагностике): без него GET /mcp даёт 401 и валидация красная
                // на живом сервере. Сбрасывается в finally вместе с процессом.
                MemoryAuth.set(memoryToken)

                // Музыка — отдельный bearer и отдельный токен Яндекса. Берём ДО регистрации
                // в конфиге: зарегистрированный, но не поднятый сервер хуже отсутствующего,
                // потому что модель получает шесть инструментов, которые не отвечают.
                val ynisonAccessToken = YandexAccountController.runtimeAccessToken()
                val ynisonToken = ynisonAccessToken?.let { UUID.randomUUID().toString() }
                if (ynisonToken != null) {
                    YnisonAuth.set(ynisonToken)
                }

                // Регистрируем локальную память в конфиге serve как remote MCP
                // (иначе serve о ней не знает — индикатор «0 MCP», инструменты
                // памяти недоступны модели). До старта serve: он читает конфиг
                // при инициализации MCP. Не фатал — память продолжит работать
                // как TCP-сервер, просто без регистрации.
                OpencodeRuntime.ensureMcpConfig(memoryToken, ynisonToken)

                // Локальная память MCP как HTTP/TCP-сервер (MEMORY_PORT) — ДО serve.
                // Не стартовала/умерла — НЕ фатал: serve продолжит, статус DEGRADED.
                // Живой process != поднятая память: startMemoryServer возвращает процесс,
                // который может мгновенно упасть или не открыть сокет — верифицируем
                // isAlive + TCP-коннект на MEMORY_PORT (окно ~5s).
                emit { copy(stage = RuntimeStage.STARTING_MEMORY, workspaceExternal = ext) }
                var memoryStarted = startMemoryAndVerify(context, logFile, workspace, memoryToken)
                if (!memoryStarted) {
                    emitFailure(
                        stage = RuntimeStage.STARTING_MEMORY,
                        code = RuntimeErrorCode.MEMORY_START_FAILED,
                        message = "Локальная память MCP не поднялась - порт или процесс",
                        recoverable = true,
                    )
                }

                // Яндекс.Музыка (YNISON_PORT) — тоже ДО serve, по тем же двум причинам,
                // что и память: конфиг читается на старте, а мёртвый порт в конфиге врёт.
                // Без подключённого Яндекса не запускаемся вовсе (это не поломка, см.
                // startYnisonServer) и запись music из конфига убирается сама.
                var ynisonStarted = false
                if (ynisonAccessToken != null && ynisonToken != null) {
                    // Живой процесс != поднятая музыка: verify делает startYnisonAndVerify
                    // (isAlive + TCP-коннект), иначе модель видела бы в конфиге инструменты,
                    // которые не отвечают.
                    ynisonStarted =
                        startYnisonAndVerify(
                            context = context,
                            logFile = logFile,
                            workspace = workspace,
                            accessToken = ynisonAccessToken,
                            mcpToken = ynisonToken,
                        )
                    if (!ynisonStarted) {
                        emitFailure(
                            stage = RuntimeStage.STARTING_MEMORY,
                            code = RuntimeErrorCode.YNISON_START_FAILED,
                            message = "Музыка MCP не поднялась - порт или процесс",
                            recoverable = true,
                        )
                    }
                }

                emit { copy(stage = RuntimeStage.STARTING_SERVER) }
                val proc =
                    OpencodeRuntime.startServe(
                        context,
                        logFile = logFile,
                        workDir = workspace,
                        extraEnv =
                            mapOf(
                                "MCP_MEMORY_TOKEN" to memoryToken,
                                // Ссылка {env:MCP_YNISON_TOKEN} в конфиге разворачивается
                                // именно в окружении serve, поэтому переменная нужна и ему.
                                // Само значение — тот же UUID, что у ynison.js.
                                "MCP_YNISON_TOKEN" to (ynisonToken ?: ""),
                            ),
                    )
                if (proc == null) {
                    consecutiveFailures++
                    emitFailure(
                        stage = RuntimeStage.STARTING_SERVER,
                        code = RuntimeErrorCode.SERVER_START_FAILED,
                        message = "startServe вернул null (runtime не собрался)",
                        recoverable = true,
                    ) { copy(stage = RuntimeStage.CRASHED, restartCount = consecutiveFailures) }
                    if (consecutiveFailures >= MAX_RESTART_ATTEMPTS) {
                        emitTerminalRestartLimit()
                        return
                    }
                    restartBackoff(consecutiveFailures)
                    continue
                }
                serve.setProcess(proc)

                val healthy = waitForHttp(OpencodeApp.ServerConfig.PORT)
                // Штатная отмена (стоп сервиса / рестарт юзера) во время health-ожидания
                // не должна трактоваться как HEALTH_TIMEOUT и растить счётчик крашей.
                if (!running || !currentCoroutineContext().isActive) break
                // requestRestart мог убить serve во время waitForHttp (рестарт из UI):
                // это НЕ ошибка старта, а мягкий рестарт — выходим на новый виток.
                if (restartRequested) {
                    restartRequested = false
                    consecutiveFailures = 0
                    continue
                }

                if (!healthy) {
                    // Сервер не ответил за окно — старт не удался: рвём процесс, счётчик++.
                    serve.stop()
                    consecutiveFailures++
                    emitFailure(
                        stage = RuntimeStage.STARTING_SERVER,
                        code = RuntimeErrorCode.HEALTH_TIMEOUT,
                        message = "opencode serve не ответил по HTTP за 30s",
                        recoverable = true,
                    ) { copy(stage = RuntimeStage.CRASHED, restartCount = consecutiveFailures) }
                } else {
                    consecutiveFailures = 0
                    when {
                        memoryStarted && ynisonStarted ->
                            resolveActiveError(RuntimeStage.HEALTHY)
                        !memoryStarted ->
                            // Serve поднялся, память нет — DEGRADED с причиной в lastError.
                            emitFailure(
                                stage = RuntimeStage.STARTING_MEMORY,
                                code = RuntimeErrorCode.MEMORY_START_FAILED,
                                message = "локальная память MCP не работает - чат работает без неё",
                                recoverable = true,
                            ) { copy(stage = RuntimeStage.DEGRADED, restartCount = 0, stopReason = null) }
                        else ->
                            // Serve поднялся, память жива, но музыка не стартовала/умерла
                            // в окно ожидания: DEGRADED, чат работает без музыки.
                            emitFailure(
                                stage = RuntimeStage.STARTING_MEMORY,
                                code = RuntimeErrorCode.YNISON_START_FAILED,
                                message = "музыка MCP не работает - чат работает без неё",
                                recoverable = true,
                            ) { copy(stage = RuntimeStage.DEGRADED, restartCount = 0, stopReason = null) }
                    }

                    // Живём, пока процесс жив и цикл не остановлен. Респавн поверх живого
                    // запрещён (иначе порт занят и рестарт каждые ~2 сек).
                    // restartRequested выводит мгновенно (не ждём смерти процесса
                    // от daemon-треда — флаг уже обработан ниже).
                    while (serve.isAlive && running && !restartRequested) {
                        // Мониторинг локальных MCP-серверов: деградация только из HEALTHY
                        // (не спамим lastError каждые 3s), восстановление DEGRADED→HEALTHY
                        // при оживлении всех, кто был поднят (процесс жив и TCP-порт отвечает).
                        // Не поднятая вовсе музыка (нет Яндекса) деградацией не считается.
                        if (memoryStarted || ynisonStarted) {
                            // Яндекс отключён, а музыка ещё жива со старым токеном: гасим её
                            // живьём, иначе инструменты продолжат работать с отозванной сессией
                            // и врать в чат. Повторное подключение поднимет музыку следующим
                            // витком (мягкий рестарт) — новый токен возьмётся на старте.
                            // status() читает префы, без сети; в отличие от runtimeAccessToken(),
                            // сюда можно ходить каждые 3 секунды.
                            if (ynisonStarted && !YandexAccountController.status().connected) {
                                ynisonStarted = false
                                ynison.stop()
                                emitFailure(
                                    stage = RuntimeStage.HEALTHY,
                                    code = RuntimeErrorCode.YNISON_DIED,
                                    message = "Яндекс отключён - музыка остановлена",
                                    recoverable = true,
                                ) { copy(stage = RuntimeStage.DEGRADED) }
                            }
                            val memAlive =
                                !memoryStarted || (memory.isAlive && tcpOk(OpencodeRuntime.MEMORY_PORT))
                            val ynAlive =
                                !ynisonStarted || (ynison.isAlive && tcpOk(OpencodeRuntime.YNISON_PORT))
                            val allAlive = memAlive && ynAlive
                            when (currentState.stage) {
                                RuntimeStage.HEALTHY ->
                                    if (!allAlive) {
                                        val (code, message) =
                                            if (!memAlive) {
                                                RuntimeErrorCode.MEMORY_DIED to
                                                    "Память (MCP-сервер) умерла - отключаем на лету"
                                            } else {
                                                RuntimeErrorCode.YNISON_DIED to
                                                    "Музыка (MCP-сервер) умерла - чат работает без неё"
                                            }
                                        emitFailure(
                                            stage = RuntimeStage.HEALTHY,
                                            code = code,
                                            message = message,
                                            recoverable = true,
                                        ) { copy(stage = RuntimeStage.DEGRADED) }
                                    }
                                RuntimeStage.DEGRADED ->
                                    if (allAlive) {
                                        resolveActiveError(RuntimeStage.HEALTHY)
                                    }
                                else -> Unit
                            }

                            // Мёртвый локальный MCP сам не воскреснет: виток цикла дальше
                            // идёт только когда умирает serve, поэтому до перезапуска
                            // приложения инструменты молчали в DEGRADED, отвечая
                            // "Unable to connect". Поднимаем на месте.
                            //
                            // Условие - «поднимали И процесс мёртв», а не «не поднимали»:
                            // ynisonStarted означает «сервер в принципе был нужен», он не
                            // сбрасывается при смерти процесса. Живой процесс не трогаем:
                            // вторая копия поверх него сразу упрётся в занятый порт.
                            //
                            // Попытки ограничены: смерть сразу после старта - это не отсветка,
                            // а повод не молотить респавн каждые 3 секунды.
                            val ynDead = ynisonStarted && !ynison.isAlive
                            val memDead = memoryStarted && !memory.isAlive
                            if ((ynDead || memDead) && localMcpRecoveries < MAX_LOCAL_MCP_RECOVERIES) {
                                if (ynDead && ynisonAccessToken != null && ynisonToken != null) {
                                    localMcpRecoveries++
                                    android.util.Log.i(
                                        "OpencodeServer",
                                        "ynison умер, поднимаем заново (попытка $localMcpRecoveries)",
                                    )
                                    ynisonStarted =
                                        startYnisonAndVerify(
                                            context = context,
                                            logFile = logFile,
                                            workspace = workspace,
                                            accessToken = ynisonAccessToken,
                                            mcpToken = ynisonToken,
                                        )
                                    if (ynisonStarted) {
                                        localMcpRecoveries = 0
                                        resolveActiveError(RuntimeStage.HEALTHY)
                                    }
                                } else if (memDead) {
                                    localMcpRecoveries++
                                    android.util.Log.i(
                                        "OpencodeServer",
                                        "память умерла, поднимаем заново (попытка $localMcpRecoveries)",
                                    )
                                    memoryStarted = startMemoryAndVerify(context, logFile, workspace, memoryToken)
                                    if (memoryStarted) {
                                        localMcpRecoveries = 0
                                        resolveActiveError(RuntimeStage.HEALTHY)
                                    }
                                }
                            }
                        }
                        delay(3000)
                    }
                    if (!running) break

                    // Мягкий рестарт из HEALTHY: флаг выигрывает у разбора краша
                    // (гонка: process мог умереть от stop() daemon-треда requestRestart).
                    if (restartRequested) {
                        restartRequested = false
                        consecutiveFailures = 0
                        continue
                    }

                    if (serve.explicitlyStopped && serve.stoppedWhileAlive) {
                        // Мягкий рестарт по запросу (ACTION_RESTART / права на файлы):
                        // процесс был ЖИВ, когда его убили — это НЕ краш. Счётчик отказов
                        // сбрасываем, чтобы накопленный бэкофф не применился к нему.
                        consecutiveFailures = 0
                    } else {
                        consecutiveFailures++
                        emitFailure(
                            stage = RuntimeStage.HEALTHY,
                            code = RuntimeErrorCode.SERVER_CRASHED,
                            message = "процесс serve умер сам (exit=${serve.lastExitCode ?: serve.currentExitCode()})",
                            recoverable = true,
                        ) { copy(stage = RuntimeStage.CRASHED, restartCount = consecutiveFailures) }
                    }
                }

                if (consecutiveFailures >= MAX_RESTART_ATTEMPTS) {
                    emitTerminalRestartLimit()
                    return
                }
                // При явном стопе выходим сразу; backoff только после реальных фейлов
                // (consecutiveFailures > 0: мягкий рестарт — без паузы, мгновенно).
                if (consecutiveFailures > 0 && running) {
                    restartBackoff(consecutiveFailures)
                }
            }
        } finally {
            // Выход из цикла в любом случае означает конец рантайма: сбрасываем
            // флаг (при отмене корутины его не сбрасывает ни один emitTerminal*),
            // процессы гасём (двойной stop безопасен — synchronized; при
            // RESTART_LIMIT memory осталась бы жить без этого).
            running = false
            serve.stop()
            memory.stop()
            ynison.stop()
            // Токен памяти живёт один виток: погасили процесс — погасили токен,
            // иначе проверка ходила бы с мёртвым bearer и врала бы в диагностике.
            MemoryAuth.clear()
            // С музыкой так же. Токен Яндекса при этом НЕ трогаем: он хранится в
            // YandexTokenStore и переживает рантайм, иначе каждый рестарт serve
            // требовал бы от юзера новой авторизации.
            YnisonAuth.clear()
            // Публикуем STOPPED при штатном выходе (requestStop) и при отмене корутины
            // (CancellationException из delay). НО: CRASHED/FAILED_PERMANENTLY не затираем
            // НИКОГДА — информация о терминальном отказе важнее (RESTART_LIMIT /
            // INVALID_RUNTIME / просто последний краш, который юзер прервал кнопкой Stop).
            if (currentState.stage != RuntimeStage.CRASHED &&
                currentState.stage != RuntimeStage.FAILED_PERMANENTLY
            ) {
                emit { copy(stage = RuntimeStage.STOPPED, stopReason = StopReason.USER_STOP) }
            }
        }
    }

    private fun emitTerminalInvalidRuntime() {
        emitFailure(
            stage = RuntimeStage.PREPARING,
            code = RuntimeErrorCode.MISSING_NATIVE_BIN,
            message = "libopencode.so / libldmusl.so отсутствуют в nativeLibraryDir",
            recoverable = false,
        ) {
            copy(stage = RuntimeStage.FAILED_PERMANENTLY, stopReason = StopReason.INVALID_RUNTIME)
        }
        running = false
    }

    private fun emitTerminalRestartLimit() {
        // lastError проставляем явно: после HEALTHY он может быть null, и UI
        // увидел бы CRASHED без причины. recoverable=false — терминально.
        emitFailure(
            stage = currentState.stage,
            code = RuntimeErrorCode.SERVER_CRASHED,
            message = "превышен лимит рестартов ($MAX_RESTART_ATTEMPTS) — runtime не поднялся",
            recoverable = false,
        ) {
            copy(stage = RuntimeStage.FAILED_PERMANENTLY, stopReason = StopReason.RESTART_LIMIT)
        }
        running = false
    }

    /**
     * Явный стоп. Не блокирует вызывающий поток: флаг running=false — цикл сам
     * выйдет на ближайшей проверке, процессы погасит finally run() (отдельный
     * daemon-тред не нужен и создавал бы гонку с finally-stop по explicitlyStopped).
     */
    fun requestStop() {
        if (!running) return
        running = false
    }

    /**
     * Синхронная остановка процессов — для onDestroy сервиса: daemon-тред там
     * может не успеть до убийства процесса приложения, и дети (serve/memory)
     * выживут. Блокирует вызывающий поток на время kill-timeout (~6s максимум).
     */
    fun stopNow() {
        running = false
        serve.stop()
        memory.stop()
        ynison.stop()
    }

    /**
     * Мягкий рестарт serve: убивает текущий процесс (цикл перезапустит с заново
     * резолвнутым workspace — нужно для подхвата внешнего хранилища). Счётчик
     * отказов не растёт: флаг restartRequested перехватывает цикл в ЛЮБОЙ фазе
     * (не только HEALTHY), плюс ProcessSupervisor.stop ставит explicitlyStopped
     * + stoppedWhileAlive. Не блокирует поток.
     */
    fun requestRestart() {
        restartRequested = true
        Thread { serve.stop() }
            .apply {
                isDaemon = true
                name = "runtime-restart"
            }.start()
    }

    /**
     * Backoff после неудачного витка: 2s, 4s, 8s, 15s (cap). Первый фейл = 2s.
     */
    private fun backoff(failures: Int): Long {
        val shift = if (failures > 0) failures else 1
        return (1000L shl minOf(shift, 4)).coerceAtMost(15_000L)
    }

    /** Backoff-пауза с явной фазой RESTARTING: UI видит «перезапускается», а не
     *  зависший CRASHED. Пауза отменяемая (стоп/рестарт выходят сразу). */
    private suspend fun restartBackoff(failures: Int) {
        emit { copy(stage = RuntimeStage.RESTARTING) }
        delay(backoff(failures))
    }

    /**
     * Поднимает сервер памяти и регистрирует процесс у супервизора.
     *
     * Регистрация идёт СРАЗУ после запуска: даже если TCP-порт не поднимется
     * (таймаут/быстрая смерть), супервизор обязан знать о процессе — иначе
     * stop() в finally не погасит orphan и порт 4199 останется занят на следующий виток.
     *
     * Токен берётся параметром, а не генерируется здесь: ensureMcpConfig и окружение serve
     * уже прописаны со [memoryToken] при старте, новый UUID развёл бы их с тем, что
     * реально слушает порт.
     */
    private suspend fun startMemoryAndVerify(
        context: Context,
        logFile: File,
        workspace: File,
        memoryToken: String,
    ): Boolean {
        val proc =
            OpencodeRuntime.startMemoryServer(
                context,
                logFile = logFile,
                workDir = workspace,
                extraEnv = mapOf("MCP_MEMORY_TOKEN" to memoryToken) + AppInstallBridge.environment(),
            ) ?: return false
        memory.setProcess(proc)
        val ok = waitForPort(proc, OpencodeRuntime.MEMORY_PORT)
        // Гасим явно: процесс мог стартовать, но не поднять MCP-порт (битый старт).
        // Без stop() следующая попытка создала бы ещё один поверх занятого порта.
        if (!ok) memory.stop()
        return ok
    }

    /**
     * Поднимает ynison.js и регистрирует процесс у супервизора. Проверка та же, что и у
     * памяти: процесс жив И порт отвечает, иначе инструменты музыки в конфиге не работают.
     *
     * Токены — параметрами по той же причине, что и в [startMemoryAndVerify]: и MCP-конфиг,
     * и окружение serve уже содержат именно эти значения.
     */
    private suspend fun startYnisonAndVerify(
        context: Context,
        logFile: File,
        workspace: File,
        accessToken: String,
        mcpToken: String,
    ): Boolean {
        val proc =
            OpencodeRuntime.startYnisonServer(
                context = context,
                logFile = logFile,
                workDir = workspace,
                credentials =
                    YnisonCredentials(
                        accessToken = accessToken,
                        mcpToken = mcpToken,
                        deviceId = YandexAccountController.runtimeDeviceId() ?: DEFAULT_YNISON_DEVICE_ID,
                    ),
            ) ?: return false
        ynison.setProcess(proc)
        val ok = waitForPort(proc, OpencodeRuntime.YNISON_PORT)
        if (!ok) ynison.stop()
        return ok
    }

    /**
     * Ждём, пока локальный MCP-сервер реально поднимется: процесс жив И открыл TCP
     * на порту (окно ~10s = 20 × 500ms). startMemoryServer/startYnisonServer возвращают
     * живой process и в случае, когда тот мгновенно падает или сокет не открыт, —
     * без этой проверки сервер ошибочно считался бы стартовавшим (P0-3).
     */
    private suspend fun waitForPort(
        proc: Process,
        port: Int,
    ): Boolean {
        val deadline = System.currentTimeMillis() + STARTUP_HEALTH_TIMEOUT_MS
        var ok = false
        while (!ok && System.currentTimeMillis() < deadline) {
            if (!currentCoroutineContext().isActive || !running || restartRequested) break
            if (proc.isAlive && tcpOk(port)) {
                ok = true
            } else {
                delay(STARTUP_HEALTH_POLL_MS)
            }
        }
        return ok
    }

    /** TCP-коннект до localhost:port (память слушает сокет, не HTTP). */
    private fun tcpOk(port: Int): Boolean =
        try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), MEMORY_TCP_TIMEOUT_MS)
            }
            true
        } catch (e: Exception) {
            false
        }

    /**
     * Ротация opencode.log по размеру: при превышении MAX_LOG_BYTES текущий лог
     * сдвигается по цепочке opencode.log.N (N=1..MAX_LOG_FILES): .2→.3, .1→.2,
     * .log→.1. Старые хвосты затираются. После сдвига — прунинг по общему бюджету
     * (LOG_TOTAL_BUDGET). Вызывается перед стартом процесса serve — сам процесс
     * пишет в открытый fd, живая ротация невозможна без его перезапуска.
     */
    private fun rotateLogFile(logFile: File) {
        // Общий бюджет пруним при КАЖДОМ вызове (не только после сдвига): хвосты
        // могли раздуть извне, а .log ещё не дорос до порога MAX_LOG_BYTES.
        pruneLogs(logFile)
        if (!logFile.exists()) return
        if (logFile.length() < MAX_LOG_BYTES) return
        val parent = logFile.parentFile ?: return
        // Сдвиг хвостов от старшего к младшему: .2→.3, .1→.2 (старый хвост затираем).
        for (i in MAX_LOG_FILES - 1 downTo 1) {
            val cur = File(parent, "opencode.log.$i")
            val next = File(parent, "opencode.log.${i + 1}")
            if (!cur.exists()) continue
            // Не затираем next заранее: при неудаче renameTo пробуем delete+ретрай —
            // иначе потеряли бы и данные next, и (при неудаче) содержимое cur.
            if (!cur.renameTo(next)) {
                next.delete()
                if (!cur.renameTo(next)) {
                    android.util.Log.w("OpencodeServer", "сдвиг ${cur.name} -> ${next.name} не удался")
                }
            }
        }
        val rotated = File(parent, "opencode.log.1")
        if (logFile.renameTo(rotated)) {
            android.util.Log.i("OpencodeServer", "rotated opencode.log -> opencode.log.1")
        } else {
            android.util.Log.w("OpencodeServer", "rotate opencode.log не удался (renameTo=false)")
        }
    }

    /**
     * Общий лимит логов: пока сумма всех файлов цепочки (opencode.log + .1..MAX)
     * превышает LOG_TOTAL_BUDGET, удаляем самые старые из имеющихся (.N с конца).
     * Пик во время самой ротации на ≤8MB выше бюджета (4×8MB=32MB vs 30MB),
     * сразу после — прунинг до 24MB. Вызывается после каждого сдвига.
     */
    private fun pruneLogs(logFile: File) {
        val parent = logFile.parentFile ?: return
        var total = logFile.length()
        (1..MAX_LOG_FILES)
            .map { File(parent, "opencode.log.$it") }
            .filter { it.exists() }
            .forEach { total += it.length() }
        var idx = MAX_LOG_FILES
        while (total > LOG_TOTAL_BUDGET && idx > 0) {
            val f = File(parent, "opencode.log.$idx")
            if (f.exists()) {
                // Длину берём ДО удаления; вычитаем только при успехе — иначе
                // неудачный delete (файл занят) ложно «соблюдал» бы бюджет.
                val len = f.length()
                if (f.delete()) {
                    total -= len
                    android.util.Log.i("OpencodeServer", "pruned ${f.name} (total ${total / 1024 / 1024}MB)")
                } else {
                    android.util.Log.w("OpencodeServer", "не удалось удалить ${f.name}")
                }
            }
            idx--
        }
        if (total > LOG_TOTAL_BUDGET) {
            android.util.Log.w("OpencodeServer", "бюджет логов всё ещё превышен: $total байт")
        }
    }

    /** Пингует HTTP localhost:port, ждёт пока сервер ответит (до 30s).
     *  Выходит раньше при стопе (running=false) или мягком рестарте (restartRequested). */
    private suspend fun waitForHttp(port: Int): Boolean {
        repeat(60) {
            if (!currentCoroutineContext().isActive || !running || restartRequested) return false
            if (pingOk(port)) return true
            delay(500)
        }
        return false
    }

    private fun pingOk(port: Int): Boolean =
        try {
            val url = java.net.URL("http://127.0.0.1:$port/")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            conn.requestMethod = "HEAD"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..499
        } catch (e: Exception) {
            false
        }
}
