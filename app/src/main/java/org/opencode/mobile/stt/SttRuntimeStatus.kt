package org.opencode.mobile.stt

import java.util.concurrent.atomic.AtomicReference

/**
 * Лёгкий процесс-локальный снимок последнего STT-прогона для диагностики.
 * Не персистится: нужен только чтобы в UI видеть, какой путь реально сработал
 * (silero/rms, сегменты, latency/RTF, ошибка/fallback) без чтения logcat.
 */
object SttRuntimeStatus {
    data class Snapshot(
        val atMs: Long = System.currentTimeMillis(),
        val model: String = "",
        val engine: String = "",
        val vadBackend: String = "",
        val segments: Int = 0,
        val audioMs: Long = 0L,
        val passMs: Long = 0L,
        val loadMs: Long = 0L,
        val rtf: Float = 0f,
        val textChars: Int = 0,
        val error: String? = null,
    )

    private val ref = AtomicReference<Snapshot?>(null)

    fun update(snapshot: Snapshot) {
        ref.set(snapshot)
    }

    fun get(): Snapshot? = ref.get()
}
