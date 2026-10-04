package org.opencode.mobile.tts

import android.content.Context
import android.media.AudioFormat
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
class SystemSpeechSynth(context: Context) : TextToSpeech.OnInitListener, SpeechSynth {
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

    override fun synthesize(text: String, sid: Int, speed: Float): TtsAudio? {
        if (!isReady()) return null
        val engine = tts ?: return null
        val out = File(appContext.cacheDir, "tts_line_${System.nanoTime()}.wav")
        val done = CountDownLatch(1)
        var failed = false
        engine.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) {
                done.countDown()
            }

            @Deprecated("required by API", ReplaceWith(""))
            override fun onError(id: String?) {
                failed = true
                done.countDown()
            }

            override fun onError(id: String?, errorCode: Int) {
                failed = true
                done.countDown()
            }
        })
        val rc = try {
            engine.synthesizeToFile(text, null, out, "tts_utterance")
        } catch (t: Throwable) {
            Log.w(TAG, "synthesizeToFile упал: ${t.message}")
            -1
        }
        if (rc != TextToSpeech.SUCCESS) {
            out.delete()
            return null
        }
        if (!done.await(30, TimeUnit.SECONDS)) {
            Log.w(TAG, "системный TTS не отдал результат за 30с")
            engine.stop()
            out.delete()
            return null
        }
        if (failed || !out.exists() || out.length() < 44) {
            out.delete()
            return null
        }
        return try {
            PcmWav.readMonoFloat(out)
        } catch (t: Throwable) {
            Log.w(TAG, "WAV не прочитан: ${t.message}")
            null
        } finally {
            out.delete()
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

    private fun littleInt(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun littleShort(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun floatLe(b: ByteArray, i: Int): Float = java.lang.Float.intBitsToFloat(littleInt(b, i))
}