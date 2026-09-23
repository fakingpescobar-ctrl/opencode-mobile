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

    @Synchronized
    fun setProcess(proc: Process?) {
        lastExitCode = null
        explicitlyStopped = false
        stoppedWhileAlive = false
        process = proc
    }

    val isAlive: Boolean get() = process?.isAlive == true

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
            lastExitCode = try { proc.exitValue() } catch (e: Exception) { null }
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
    fun currentExitCode(): Int? = try {
        process?.takeIf { !it.isAlive }?.exitValue()
    } catch (e: Exception) {
        null
    }
}