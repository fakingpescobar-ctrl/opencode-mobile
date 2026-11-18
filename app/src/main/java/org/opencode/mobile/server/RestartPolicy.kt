package org.opencode.mobile.server

/**
 * Политика рестартов: экспоненциальный backoff и предел попыток.
 *
 * Вынесено из RuntimeManager без изменения поведения. Чистая арифметика:
 * ни процессов, ни состояния — поэтому проверяется без Android и без задержек.
 */
internal class RestartPolicy(
    private val maxAttempts: Int,
    private val baseDelayMs: Long,
    private val capDelayMs: Long,
    private val shiftCap: Int,
) {
    /**
     * Пауза после неудачного витка: base × 2^failures, но не больше cap.
     * Нулевой и отрицательный счётчик трактуются как первый фейл (2 с при
     * base=1 с) — иначе `shl` на отрицательном сдвиге вёл бы себя неочевидно.
     */
    fun backoffMs(failures: Int): Long {
        val shift = if (failures > 0) failures else 1
        return (baseDelayMs shl minOf(shift, shiftCap)).coerceAtMost(capDelayMs)
    }

    /** Лимит считается достигнутым на maxAttempts-м подряд отказе. */
    fun isLimitReached(failures: Int): Boolean = failures >= maxAttempts
}
