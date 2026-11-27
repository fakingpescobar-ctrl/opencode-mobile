package org.opencode.mobile.stt

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Лёгкий процесс-локальный снимок последнего STT-прогона.
 * Используется чат-UI для компактной мета-строки под распознанным сообщением.
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
    ) {
        fun chatLine(): String {
            val sec = audioMs / 1000f
            val rtfStr = String.format("%.2f", rtf)
            val secStr = String.format("%.1f", sec)
            val base = "VAD $vadBackend · ${segments}seg · ${secStr}s · load ${loadMs}ms · pass ${passMs}ms · RTF $rtfStr"
            return if (error.isNullOrBlank()) base else "$base · err ${error.take(80)}"
        }
    }

    private val ref = AtomicReference<Snapshot?>(null)

    fun update(snapshot: Snapshot) {
        ref.set(snapshot)
    }

    fun get(): Snapshot? = ref.get()
}
