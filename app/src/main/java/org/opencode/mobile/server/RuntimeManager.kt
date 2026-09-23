package org.opencode.mobile.server

import android.content.Context
import org.opencode.mobile.OpencodeApp
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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

                // Новый виток: сбрасываем ошибку/причину прошлого цикла, иначе
                // lastError от CRASHED мигал бы на STARTING_* фазах при рестарте.
                emit {
                    copy(
                        stage = RuntimeStage.PREPARING,
                        restartCount = consecutiveFailures,
                        lastError = null,
                        stopReason = null,
                    )
                }

                // Рабочая директория (workspace): без неё у opencode serve нет ни одного
                // проекта — SPA показывал "Здесь пока ничего нет". Эта версия serve не
                // понимает --dir, поэтому директория задаётся через CWD (workDir).
                val wsResult = runCatching {
                    val w = Workspace.resolve(context)
                    val readme = File(w, "README.md")
                    if (!readme.exists()) {
                        readme.writeText("# OpenCode Terminal\n\nРабочая директория на внешнем хранилище (Documents/OpencodeTerminal).\n")
                    }
                    w to Workspace.usingExternal(context)
                }
                if (wsResult.isFailure) {
                    consecutiveFailures++
                    emit {
                        copy(
                            stage = RuntimeStage.CRASHED,
                            lastError = RuntimeError(
                                stage = RuntimeStage.PREPARING,
                                code = RuntimeErrorCode.UNKNOWN,
                                message = "workspace resolve: ${wsResult.exceptionOrNull()?.message}",
                                recoverable = true,
                            ),
                            restartCount = consecutiveFailures,
                        )
                    }
                    if (consecutiveFailures >= MAX_RESTART_ATTEMPTS) {
                        emitTerminalRestartLimit()
                        return
                    }
                    delay(backoff(consecutiveFailures))
                    continue
                }
                val (workspace, ext) = wsResult.getOrThrow()
                android.util.Log.i("OpencodeServer", "workspace=${workspace.absolutePath} external=$ext")

                // Локальная память MCP как HTTP/TCP-сервер (MEMORY_PORT) — ДО serve.
                // Не стартовала — НЕ фатал: serve продолжит, статус DEGRADED.
                emit { copy(stage = RuntimeStage.STARTING_MEMORY, workspaceExternal = ext) }
                val memProc = OpencodeRuntime.startMemoryServer(context, logFile = logFile, workDir = workspace)
                val memoryStarted = memProc != null
                if (memoryStarted) {
                    memory.setProcess(memProc)
                } else {
                    emit {
                        copy(
                            lastError = RuntimeError(
                                stage = RuntimeStage.STARTING_MEMORY,
                                code = RuntimeErrorCode.MEMORY_START_FAILED,
                                message = "локальная память MCP не поднялась — чат работает без неё",
                                recoverable = true,
                            )
                        )
                    }
                }

                emit { copy(stage = RuntimeStage.STARTING_SERVER) }
                val proc = OpencodeRuntime.startServe(context, logFile = logFile, workDir = workspace)
                if (proc == null) {
                    consecutiveFailures++
                    emit {
                        copy(
                            stage = RuntimeStage.CRASHED,
                            lastError = RuntimeError(
                                stage = RuntimeStage.STARTING_SERVER,
                                code = RuntimeErrorCode.SERVER_START_FAILED,
                                message = "startServe вернул null (runtime не собрался)",
                                recoverable = true,
                            ),
                            restartCount = consecutiveFailures,
                        )
                    }
                    if (consecutiveFailures >= MAX_RESTART_ATTEMPTS) {
                        emitTerminalRestartLimit()
                        return
                    }
                    delay(backoff(consecutiveFailures))
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
                    emit {
                        copy(
                            stage = RuntimeStage.CRASHED,
                            lastError = RuntimeError(
                                stage = RuntimeStage.STARTING_SERVER,
                                code = RuntimeErrorCode.HEALTH_TIMEOUT,
                                message = "opencode serve не ответил по HTTP за 30s",
                                recoverable = true,
                            ),
                            restartCount = consecutiveFailures,
                        )
                    }
                } else {
                    consecutiveFailures = 0
                    emit {
                        copy(
                            stage = if (memoryStarted) RuntimeStage.HEALTHY else RuntimeStage.DEGRADED,
                            restartCount = 0,
                            lastError = if (!memoryStarted) {
                                RuntimeError(
                                    stage = RuntimeStage.STARTING_MEMORY,
                                    code = RuntimeErrorCode.MEMORY_START_FAILED,
                                    message = "локальная память MCP не работает — чат работает без неё",
                                    recoverable = true,
                                )
                            } else null,
                            stopReason = null,
                        )
                    }

                    // Живём, пока процесс жив и цикл не остановлен. Респавн поверх живого
                    // запрещён (иначе порт занят и рестарт каждые ~2 сек).
                    // restartRequested выводит мгновенно (не ждём смерти процесса
                    // от daemon-треда — флаг уже обработан ниже).
                    while (serve.isAlive && running && !restartRequested) {
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
                        emit {
                            copy(
                                stage = RuntimeStage.CRASHED,
                                lastError = RuntimeError(
                                    stage = RuntimeStage.HEALTHY,
                                    code = RuntimeErrorCode.SERVER_CRASHED,
                                    message = "процесс serve умер сам (exit=${serve.lastExitCode ?: serve.currentExitCode()})",
                                    recoverable = true,
                                ),
                                restartCount = consecutiveFailures,
                            )
                        }
                    }
                }

                if (consecutiveFailures >= MAX_RESTART_ATTEMPTS) {
                    emitTerminalRestartLimit()
                    return
                }
                // При явном стопе выходим сразу; backoff только после реальных фейлов
                // (consecutiveFailures > 0: мягкий рестарт — без паузы, мгновенно).
                if (consecutiveFailures > 0 && running) {
                    delay(backoff(consecutiveFailures))
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
            // (CancellationException из delay). НО: CRASHED не затираем НИКОГДА —
            // информация о терминальном отказе важнее (RESTART_LIMIT / INVALID_RUNTIME /
            // просто последний краш, который юзер прервал кнопкой Stop).
            if (currentState.stage != RuntimeStage.CRASHED) {
                emit { copy(stage = RuntimeStage.STOPPED, stopReason = StopReason.USER_STOP) }
            }
        }
    }

    private fun emitTerminalInvalidRuntime() {
        emit {
            copy(
                stage = RuntimeStage.CRASHED,
                lastError = RuntimeError(
                    stage = RuntimeStage.PREPARING,
                    code = RuntimeErrorCode.MISSING_NATIVE_BIN,
                    message = "libopencode.so / libldmusl.so отсутствуют в nativeLibraryDir",
                    recoverable = false,
                ),
                stopReason = StopReason.INVALID_RUNTIME,
            )
        }
        running = false
    }

    private fun emitTerminalRestartLimit() {
        // lastError проставляем явно: после HEALTHY он может быть null, и UI
        // увидел бы CRASHED без причины. recoverable=false — терминально.
        emit {
            copy(
                stage = RuntimeStage.CRASHED,
                lastError = RuntimeError(
                    stage = currentState.stage,
                    code = RuntimeErrorCode.SERVER_CRASHED,
                    message = "превышен лимит рестартов ($MAX_RESTART_ATTEMPTS) — runtime не поднялся",
                    recoverable = false,
                ),
                stopReason = StopReason.RESTART_LIMIT,
            )
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
        Thread { serve.stop() }.apply { isDaemon = true; name = "runtime-restart" }.start()
    }

    /**
     * Backoff после неудачного витка: 2s, 4s, 8s, 15s (cap). Первый фейл = 2s.
     */
    private fun backoff(failures: Int): Long {
        val shift = if (failures > 0) failures else 1
        return (1000L shl minOf(shift, 4)).coerceAtMost(15_000L)
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
        (1..MAX_LOG_FILES).map { File(parent, "opencode.log.$it") }
            .filter { it.exists() }
            .forEach { total += it.length() }
        var idx = MAX_LOG_FILES
        while (total > LOG_TOTAL_BUDGET && idx > 0) {
            val f = File(parent, "opencode.log.$idx")
            if (f.exists()) {
                total -= f.length()
                f.delete()
                android.util.Log.i("OpencodeServer", "pruned ${f.name} (total ${total / 1024 / 1024}MB)")
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

    private fun pingOk(port: Int): Boolean = try {
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