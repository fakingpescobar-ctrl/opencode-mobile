package org.opencode.mobile.server

import java.util.concurrent.TimeUnit

/**
 * Наблюдатель за жизнью одного дочернего процесса (serve / memory MCP).
 *
 * Главная задача — навести порядок в «убийстве»: destroy() -> таймаут ->
 * destroyForcibly(), и при этом НЕ считать процесс мёртвым по одному isAlive
 * (Android может ещё не reap-нуть его — fd открыты). Все обращения к полям —
 * через synchronized, чтобы setProcess/stop из разных потоков (цикл менеджера
 * vs daemon-тред requestStop/requestRestart) не затирали друг друга.
 */
class ProcessSupervisor(
    private val name: String,
) {
    @Volatile
    var process: Process? = null
        private set

    /** Exit code последнего завершённого процесса (null — жив/никогда не стартовал). */
    @Volatile
    var lastExitCode: Int? = null
        private set

    /** true, если цикл явно остановил процесс (иначе упал сам). */
    @Volatile
    var explicitlyStopped: Boolean = false
        private set

    /** true, если при вызове stop() процесс был ЖИВ. Мягкий рестарт = жив + стоп. */
    @Volatile
    var stoppedWhileAlive: Boolean = false
        private set

    /** Момент старта текущего процесса — чтобы отличить "умер сразу" от "проработал час". */
    @Volatile
    private var startedAtMs: Long = 0L

    @Synchronized
    fun setProcess(proc: Process?) {
        lastExitCode = null
        explicitlyStopped = false
        stoppedWhileAlive = false
        startedAtMs = if (proc != null) System.currentTimeMillis() else 0L
        process = proc
        if (proc != null) watchExit(proc)
    }

    val isAlive: Boolean get() = process?.isAlive == true

    /**
     * Сторож, который ловит смерть процесса, случившуюся НЕ по нашей воле.
     *
     * Зачем он нужен: раньше exit code записывался только внутри stop(), то есть только
     * когда останавливали мы. Смерть процесса по своей воле не оставляла вообще ничего —
     * ни кода, ни момента времени, ни признака "упал сразу или прожил час". Из-за этого
     * диагностика собственной смерти упиралась в "он просто исчез", и найти причину
     * было нечем. Сторож ничего не чинит — он превращает молчание в факт.
     *
     * Два guard'а, чтобы не врать:
     *  - process !== proc — процесс уже заменён новым, это выход старого, состояние трогать нельзя;
     *  - explicitlyStopped — мы его сами остановили, stop() про это уже написал.
     */
    private fun watchExit(proc: Process) {
        Thread {
            val code = try {
                proc.waitFor()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }
            recordUnexpectedExit(proc, code)
        }.apply {
            isDaemon = true
            name = "supervisor-$name"
        }.start()
    }

    @Synchronized
    private fun recordUnexpectedExit(
        proc: Process,
        code: Int,
    ) {
        if (process !== proc) return
        if (explicitlyStopped) {
            android.util.Log.i(
                "ProcessSupervisor",
                "$name: умер позже, чем мы просили остановить, exit=$code",
            )
            return
        }
        lastExitCode = code
        val livedSec = (System.currentTimeMillis() - startedAtMs) / MILLIS_PER_SEC
        android.util.Log.w(
            "ProcessSupervisor",
            "$name: НЕОЖИДАННАЯ СМЕРТЬ exit=$code (${describeExit(code)}), жил ${livedSec}с",
        )
    }

    /**
     * Android/Linux отдаёт код как 128+сигнал, и там 137 — это не "глюк", а SIGKILL.
     * Без расшифровки 137 и 1 выглядят одинаково непонятно, хотя означают разное.
     *
     * Формулировки намеренно НЕ называют причину: SIGKILют даёт и lowmemorykiller, и
     * внешний kill, и стенд, и я сам при проверке. Диагностика, которая уверенно
     * называет не ту причину, хуже молчания - её потом перестают читать.
     */
    private fun describeExit(code: Int): String =
        when (code) {
            EXIT_SIGKILL -> "$SIGNAL_KILL SIGKILL, внешняя остановка: OOM, kill или стенд"
            EXIT_SIGSEGV -> "$SIGNAL_SEGV SIGSEGV, падение"
            EXIT_SIGABRT -> "$SIGNAL_ABRT SIGABRT, аварийный выход"
            EXIT_SIGTERM -> "$SIGNAL_TERM SIGTERM"
            EXIT_CLEAN -> "штатный выход с кодом 0"
            in SIGNAL_EXIT_BASE + 1..SIGNAL_EXIT_MAX - 1 -> "128+сигнал ${code - SIGNAL_EXIT_BASE}"
            else -> "код приложения"
        }

    private companion object {
        /** POSIX-правило: код завершения от сигнала = 128 + номер сигнала. */
        const val SIGNAL_EXIT_BASE = 128
        const val SIGNAL_KILL = 9
        const val SIGNAL_SEGV = 11
        const val SIGNAL_ABRT = 6
        const val SIGNAL_TERM = 15

        const val EXIT_SIGKILL = SIGNAL_EXIT_BASE + SIGNAL_KILL
        const val EXIT_SIGSEGV = SIGNAL_EXIT_BASE + SIGNAL_SEGV
        const val EXIT_SIGABRT = SIGNAL_EXIT_BASE + SIGNAL_ABRT
        const val EXIT_SIGTERM = SIGNAL_EXIT_BASE + SIGNAL_TERM
        const val EXIT_CLEAN = 0

        /** Верхняя граница диапазона "128+сигнал"; выше — уже код приложения. */
        const val SIGNAL_EXIT_MAX = 165

        const val MILLIS_PER_SEC = 1_000L
    }

    /**
     * Останавливает процесс жёстко, но аккуратно: destroy -> 3s -> destroyForcibly,
     * затем ещё ждём фактическую смерть (fd закрыты) с тем же таймаутом.
     * Идемпотентно и безопасно вызывать для уже мёртвого процесса.
     */
    @Synchronized
    fun stop(killTimeoutMs: Long = 3_000) {
        val proc = process ?: return
        explicitlyStopped = true
        stoppedWhileAlive = proc.isAlive
        try {
            if (proc.isAlive) proc.destroy()
            if (proc.isAlive && !proc.waitFor(killTimeoutMs, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                // после forcible ждём опять — чтобы fd гарантированно закрылись
                proc.waitFor(killTimeoutMs, TimeUnit.MILLISECONDS)
                android.util.Log.w("ProcessSupervisor", "$name: destroyForcibly needed")
            }
        } catch (e: Exception) {
            // процесс уже мёртв (reaped / закрыт) — ок
            android.util.Log.d("ProcessSupervisor", "$name.stop: ${e.message}")
        }
        if (!proc.isAlive) {
            lastExitCode = try {
                proc.exitValue()
            } catch (e: Exception) {
                null
            }
            // Зануляем ТОЛЬКО мёртвый процесс: живой (даже после destroyForcibly —
            // упрямый процесс) остаётся в поле, чтобы цикл менеджера увидел
            // isAlive=true и не стартовал новый serve поверх занятого порта.
            if (process === proc) {
                process = null
            }
        } else {
            // Не затираем и не перезапускаем поверх: даём циклу увидеть живой процесс.
            android.util.Log.w("ProcessSupervisor", "$name: процесс пережил destroyForcibly, остаётся под наблюдением")
        }
    }

    /** Для диагностики падения: exit code мёртвого процесса (null — жив или отсутствует). */
    fun currentExitCode(): Int? =
        try {
            process?.takeIf { !it.isAlive }?.exitValue()
        } catch (e: Exception) {
            null
        }
}
