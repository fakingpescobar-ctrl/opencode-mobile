package org.opencode.mobile.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Системный Android TTS как фолбэк: работает всегда, стоит ноль, качество
 * зависит от установленного движка. Синтезируем в WAV и читаем тем же
 * плеером, что и Supertonic, чтобы отмена и fade-out работали одинаково.
 */
class SystemSpeechSynth(
    context: Context,
) : TextToSpeech.OnInitListener,
    SpeechSynth {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private val initLatch = CountDownLatch(1)

    init {
        tts = TextToSpeech(appContext, this)
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts?.language = Locale("ru")
        } else {
            Log.w(TAG, "системный TTS недоступен (status=$status)")
        }
        initLatch.countDown()
    }

    override val sampleRate: Int get() = 44100

    /**
     * Синтез в WAV и чтение обратно.
     *
     * Разбито на шаги не ради длины: у одного тела было шесть `return` при лимите
     * detekt два, и три из них отличались только «удалить файл и выйти». Теперь видно,
     * что шагов ровно три — заговорить, дождаться, прочитать — и что отказ на любом из
     * них сводится к одному и тому же: файла нет, значит и звука нет.
     */
    override fun synthesize(
        text: String,
        sid: Int,
        speed: Float,
    ): TtsAudio? {
        val engine = tts?.takeIf { isReady() } ?: return null
        val out = File(appContext.cacheDir, "tts_line_${System.nanoTime()}.wav")
        return if (spokenInto(engine, text, speed, out)) readWav(out) else null
    }

    /** Заговорить и дождаться конца. false — движок не ответил, файла можно не ждать. */
    private fun spokenInto(
        engine: TextToSpeech,
        text: String,
        speed: Float,
        out: File,
    ): Boolean {
        val done = CountDownLatch(1)
        var failed = false
        engine.setSpeechRate(speed.coerceIn(MIN_SPEED, MAX_SPEED))
        engine.setOnUtteranceProgressListener(progress(done) { failed = true })
        // Порядок как был: сперва «поехало ли вообще», потом ожидание, потом файл.
        val spoken =
            startSpeaking(engine, text, out) &&
                awaitUtterance(engine, done) &&
                !failed &&
                out.length() >= WAV_HEADER_BYTES
        if (!spoken) out.delete()
        return spoken
    }

    private fun startSpeaking(
        engine: TextToSpeech,
        text: String,
        out: File,
    ): Boolean =
        try {
            engine.synthesizeToFile(text, null, out, UTTERANCE_ID) == TextToSpeech.SUCCESS
        } catch (t: Throwable) {
            Log.w(TAG, "synthesizeToFile упал: ${t.message}")
            false
        }

    /** На таймауте движок останавливаем — иначе он будет говорить в никуда. */
    private fun awaitUtterance(
        engine: TextToSpeech,
        done: CountDownLatch,
    ): Boolean {
        if (done.await(WAIT_SECONDS, TimeUnit.SECONDS)) return true
        Log.w(TAG, "системный TTS не отдал результат за ${WAIT_SECONDS}с")
        engine.stop()
        return false
    }

    /** Файл удаляется в любом случае — и при неудачном чтении тоже, он больше не нужен. */
    private fun readWav(out: File): TtsAudio? =
        try {
            PcmWav.readMonoFloat(out)
        } catch (t: Throwable) {
            Log.w(TAG, "WAV не прочитан: ${t.message}")
            null
        } finally {
            out.delete()
        }

    /** [onFailed] ставится из [onError], [done] отпускается в любом исходе. */
    private fun progress(
        done: CountDownLatch,
        onFailed: () -> Unit,
    ): UtteranceProgressListener =
        object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit

            override fun onDone(id: String?) {
                done.countDown()
            }

            @Deprecated("required by API", ReplaceWith(""))
            override fun onError(id: String?) {
                onFailed()
                done.countDown()
            }

            override fun onError(
                id: String?,
                errorCode: Int,
            ) {
                onFailed()
                done.countDown()
            }
        }

    fun isReady(): Boolean {
        if (!ready) initLatch.await(3, TimeUnit.SECONDS)
        return ready
    }

    fun release() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    private companion object {
        const val TAG = "TTS"
        const val UTTERANCE_ID = "tts_utterance"
        const val WAIT_SECONDS = 30L
        const val WAV_HEADER_BYTES = 44L
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
    }
}

/** Чтение моно-WAV (PCM16/PCM8/float32) в FloatArray. Своего ридера у sherpa нет. */
object PcmWav {
    fun readMonoFloat(file: File): TtsAudio? {
        val bytes = file.readBytes()
        if (bytes.size < 44) return null
        var pos = 12
        var rate = 44100
        var channels = 1
        var bits = 16
        var format = 1
        var dataOff = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = littleInt(bytes, pos + 4)
            val body = pos + 8
            if (body + size > bytes.size) break
            when (id) {
                "fmt " -> {
                    format = littleShort(bytes, body)
                    channels = littleShort(bytes, body + 2)
                    rate = littleInt(bytes, body + 4)
                    bits = littleShort(bytes, body + 14)
                }
                "data" -> {
                    dataOff = body
                    dataLen = size
                }
            }
            pos = body + size + (size and 1)
        }
        if (dataOff < 0 || dataLen <= 0 || channels < 1) return null
        val bytesPerSample = bits / 8
        val total = dataLen / (bytesPerSample * channels)
        val out = FloatArray(total)
        var i = 0
        var p = dataOff
        while (i < total) {
            // Только первый канал: TTS моно, а System TTS может отдать стерео.
            val v = when {
                format == 3 && bits == 32 -> floatLe(bytes, p)
                bits == 8 -> (bytes[p].toInt() - 128) / 128f
                bits == 16 -> littleShort(bytes, p) / 32768f
                else -> 0f
            }
            out[i++] = v.coerceIn(-1f, 1f)
            p += bytesPerSample * channels
        }
        return TtsAudio(out, rate)
    }

    private fun littleInt(
        b: ByteArray,
        i: Int,
    ): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun littleShort(
        b: ByteArray,
        i: Int,
    ): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun floatLe(
        b: ByteArray,
        i: Int,
    ): Float = java.lang.Float.intBitsToFloat(littleInt(b, i))
}
