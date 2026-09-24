package org.opencode.mobile.server

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.opencode.mobile.OpencodeApp
import java.io.File

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

        /** Предел роста одного opencode.log; по достижении — сдвиг цепочки .N. */
        private const val MAX_LOG_BYTES = 8L * 1024 * 1024

        /** Глубина ротации: opencode.log + .1 + .2 + .3 (4 файла в цепочке максимум). */
        private const val MAX_LOG_FILES = 3

        /** Общий бюджет всех лог-файлов цепочки; хвосты сверх бюджета удаляются. */
        private const val LOG_TOTAL_BUDGET = 30L * 1024 * 1024

        /** Окно ожидания подъёма локальной памяти (10 попыток × 500 мс). */
        private const val MEMORY_HEALTH_TIMEOUT_MS = 10_000L
        private const val MEMORY_HEALTH_POLL_MS = 500L

        /** Таймаут TCP-коннекта к сокету памяти. */
        private const val MEMORY_TCP_TIMEOUT_MS = 1_000
    }

    private val logFile = File(context.filesDir, "opencode.log")
    private val serve = ProcessSupervisor("serve")
    private val memory = ProcessSupervisor("memory")

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
     *  НЕ стирается: UI видит «ошибка (разрешена)», история остаётся. */
    private fun resolveActiveError() {
        val at = System.currentTimeMillis()
        emit {
            copy(
                lastError = lastError?.takeIf { it.resolvedAt == null }?.copy(resolvedAt = at) ?: lastError,
                lastRecoveredAt = at,
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
            while (running) {
                // Гасим ПРОЦЕССЫ прошлого витка (serve тоже — см. continue из restart-ветки:
                // там процесс мог ещё не умереть, а новый виток уже пошёл) ДО ротации:
                // fd старого serve держит старый inode opencode.log, и после rename
                // он дописывал бы хвост в .1.
                serve.stop()
                memory.stop()
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

                // Локальная память MCP как HTTP/TCP-сервер (MEMORY_PORT) — ДО serve.
                // Не стартовала/умерла — НЕ фатал: serve продолжит, статус DEGRADED.
                // Живой process != поднятая память: startMemoryServer возвращает процесс,
                // который может мгновенно упасть или не открыть сокет — верифицируем
                // isAlive + TCP-коннект на MEMORY_PORT (окно ~5s).
                emit { copy(stage = RuntimeStage.STARTING_MEMORY, workspaceExternal = ext) }
                val memProc = OpencodeRuntime.startMemoryServer(context, logFile = logFile, workDir = workspace)
                // Регистрируем процесс СРАЗУ после запуска: даже если TCP-порт не
                // поднимется (timeout/быстрая смерть), ProcessSupervisor обязан знать
                // о процессе — иначе memory.stop() в finally не погасит orphan, и порт
                // 4199 останется занят для следующего витка.
                if (memProc != null) {
                    memory.setProcess(memProc)
                }
                val memoryStarted = memProc != null && waitForMemory(memProc)
                if (!memoryStarted) {
                    // Гасим явно: процесс мог стартовать, но не поднять MCP-порт
                    // (битый старт). Без stop() следующий виток создал бы ещё один.
                    memory.stop()
                    emitFailure(
                        stage = RuntimeStage.STARTING_MEMORY,
                        code = RuntimeErrorCode.MEMORY_START_FAILED,
                        message = "Локальная память MCP не поднялась - порт или процесс",
                        recoverable = true,
                    )
                }

                emit { copy(stage = RuntimeStage.STARTING_SERVER) }
                val proc = OpencodeRuntime.startServe(context, logFile = logFile, workDir = workspace)
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
                    if (memoryStarted) {
                        resolveActiveError()
                    } else {
                        // Serve поднялся, память нет — DEGRADED с причиной в lastError.
                        emitFailure(
                            stage = RuntimeStage.STARTING_MEMORY,
                            code = RuntimeErrorCode.MEMORY_START_FAILED,
                            message = "локальная память MCP не работает - чат работает без неё",
                            recoverable = true,
                        ) { copy(stage = RuntimeStage.DEGRADED, restartCount = 0, stopReason = null) }
                    }

                    // Живём, пока процесс жив и цикл не остановлен. Респавн поверх живого
                    // запрещён (иначе порт занят и рестарт каждые ~2 сек).
                    // restartRequested выводит мгновенно (не ждём смерти процесса
                    // от daemon-треда — флаг уже обработан ниже).
                    while (serve.isAlive && running && !restartRequested) {
                        // Мониторинг памяти: деградация только из HEALTHY (не спамим
                        // lastError каждые 3s), восстановление DEGRADED→HEALTHY при
                        // оживлении (процесс жив и TCP-порт отвечает).
                        if (memoryStarted) {
                            val memAlive = memory.isAlive && tcpOk(OpencodeRuntime.MEMORY_PORT)
                            when (currentState.stage) {
                                RuntimeStage.HEALTHY ->
                                    if (!memAlive) {
                                        emitFailure(
                                            stage = RuntimeStage.HEALTHY,
                                            code = RuntimeErrorCode.MEMORY_DIED,
                                            message = "Память (MCP-сервер) умерла - отключаем на лету",
                                            recoverable = true,
                                        ) { copy(stage = RuntimeStage.DEGRADED) }
                                    }
                                RuntimeStage.DEGRADED ->
                                    if (memAlive) {
                                        resolveActiveError()
                                    }
                                else -> Unit
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
     * Ждём, пока локальная память реально поднимется: процесс жив И открыл TCP
     * на MEMORY_PORT (окно ~5s = 10 × 500ms). startMemoryServer возвращает живой
     * process и в случае, когда тот мгновенно падает или сокет не открыт, —
     * без этой проверки память ошибочно считалась бы стартовавшей (P0-3).
     */
    private suspend fun waitForMemory(proc: Process): Boolean {
        val deadline = System.currentTimeMillis() + MEMORY_HEALTH_TIMEOUT_MS
        var ok = false
        while (!ok && System.currentTimeMillis() < deadline) {
            if (!currentCoroutineContext().isActive || !running || restartRequested) break
            if (proc.isAlive && tcpOk(OpencodeRuntime.MEMORY_PORT)) {
                ok = true
            } else {
                delay(MEMORY_HEALTH_POLL_MS)
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
