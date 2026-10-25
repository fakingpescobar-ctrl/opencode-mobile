package org.opencode.mobile.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.foundation.layout.size
import java.io.File
import kotlin.concurrent.thread

// Создаёт системный распознаватель речи, если он доступен на устройстве.
internal fun createSpeechRecognizer(context: Context): SpeechRecognizer? =
    if (SpeechRecognizer.isRecognitionAvailable(context)) {
        SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
    } else {
        null
    }

/** Чтение PCM16 WAV → FloatArray 16кГц моно (микс каналов средним + ресемпл линейной интерполяцией). */
internal fun readWavPcm16(
    f: File,
    targetRate: Int = 16000,
): FloatArray {
    val b = f.readBytes()

    fun le16(o: Int) = ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)

    fun le32(o: Int) =
        ((b[o + 3].toInt() and 0xFF) shl 24) or ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)
    if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") {
        throw RuntimeException("не WAV-файл")
    }
    var pos = 12
    var channels = 1
    var rate = 16000
    var bits = 16
    var dataStart = -1
    var dataLen = 0
    while (pos + 8 <= b.size) {
        val id = String(b, pos, 4, Charsets.US_ASCII)
        val len = le32(pos + 4)
        if (id == "fmt ") {
            channels = le16(pos + 10)
            rate = le32(pos + 12)
            bits = le16(pos + 22)
        } else if (id == "data") {
            dataStart = pos + 8
            dataLen = len
        }
        pos += 8 + len + (len and 1)
    }
    if (dataStart < 0 || channels < 1 || bits != 16) throw RuntimeException("WAV: нет data/не PCM16 (bits=$bits)")
    val n = (dataLen / (2 * channels)).coerceAtMost((b.size - dataStart) / (2 * channels))
    val mono =
        FloatArray(n) { i ->
            var acc = 0
            for (c in 0 until channels) acc += le16(dataStart + (i * channels + c) * 2).toShort().toInt()
            (acc / channels) / 32768f
        }
    if (rate == targetRate) return mono
    // линейный ресемпл
    val ratio = rate.toDouble() / targetRate
    val outLen = (n / ratio).toInt()
    return FloatArray(outLen) { i ->
        val src = i * ratio
        val i0 = src.toInt()
        val i1 = (i0 + 1).coerceAtMost(n - 1)
        val frac = (src - i0).toFloat()
        mono[i0] * (1 - frac) + mono[i1] * frac
    }
}

// Запись голоса для локального Whisper.
// Пишем 48000 Гц (нативная частота телефона) и ресемплим в 16к СВОИМ FIR-фильтром:
// встроенный ресемплер OPPO сыпет паразитные пики 2.5/4/6/7.5 кГц, от которых
// whisper путает слова. Стерео: L/R — два микрофона, берём более громкий.
internal class AudioRecorder(
    private val context: Context,
    private val sampleRate: Int = 16000,
) {
    private val captureRate = 48000 // частота захвата (нативная)
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile private var running = false
    private val raw = mutableListOf<Short>() // interleaved (L,R,L,R…) если стерео
    private var channels = 1

    @Suppress("ReturnCount")
    private fun buildRecorder(
        fmt: Int,
        bufSize: Int,
    ): AudioRecord? {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        // UNPROCESSED (сырой тракт): MIC-тракт OPPO замусорен артефактами
        // ресемплинга (паразитные пики 2.5/4/7.5 кГц у Найквиста) — whisper
        // на таком сигнале путает слова. Если UNPROCESSED не поддержан — MIC.
        val sources =
            listOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // телефонный тракт с AGC: самый громкий и чистый
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.MIC,
            )
        for (src in sources) {
            try {
                val r = AudioRecord(src, captureRate, fmt, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d("VOICE", "recorder source=$src (${if (src == MediaRecorder.AudioSource.UNPROCESSED) "UNPROCESSED" else "MIC"})")
                    return r
                }
                r.release()
            } catch (_: Throwable) {
                // пробуем следующий source
            }
        }
        return null
    }

    fun start() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission is not granted")
        }
        val enc = AudioFormat.ENCODING_PCM_16BIT
        var fmt = AudioFormat.CHANNEL_IN_STEREO
        var minBuf = AudioRecord.getMinBufferSize(captureRate, fmt, enc)
        var r = if (minBuf > 0) buildRecorder(fmt, maxOf(minBuf * 4, 8192)) else null
        if (r != null) {
            channels = 2
        } else {
            fmt = AudioFormat.CHANNEL_IN_MONO
            minBuf = AudioRecord.getMinBufferSize(captureRate, fmt, enc)
            r = buildRecorder(fmt, maxOf(minBuf * 4, 8192)) ?: throw RuntimeException("микрофон не инициализирован")
            channels = 1
        }
        val bufSize = maxOf(minBuf * 4, 8192)
        recorder = r
        raw.clear()
        running = true
        r.startRecording()
        Log.d("VOICE", "recorder: channels=$channels, ${captureRate}Hz")
        thread =
            thread(name = "whisper-record") {
                val buf = ShortArray(bufSize / 2)
                while (running) {
                    val n = r.read(buf, 0, buf.size)
                    if (n > 0) synchronized(raw) { for (i in 0 until n) raw.add(buf[i]) }
                }
            }
    }

    fun stop(): FloatArray {
        running = false
        thread?.join(1500)
        val r = recorder ?: return FloatArray(0)
        try {
            r.stop()
        } catch (_: Exception) {
        }
        r.release()
        recorder = null
        val data = synchronized(raw) { raw.toShortArray() }
        if (data.isEmpty()) return FloatArray(0)
        var l: FloatArray
        var rr: FloatArray
        if (channels == 2 && data.size >= 2) {
            val n = data.size / 2
            l = resample4to1(FloatArray(n) { data[it * 2] / 32768f })
            rr = resample4to1(FloatArray(n) { data[it * 2 + 1] / 32768f })
        } else {
            l = resample4to1(FloatArray(data.size) { data[it] / 32768f })
            rr = FloatArray(0)
        }
        // High-pass 150 Гц: убираем сетевой фон 100 Гц.
        l = highPass(l)
        if (rr.isNotEmpty()) rr = highPass(rr)
        val rmsL = rms(l)
        val rmsR = if (rr.isNotEmpty()) rms(rr) else -1.0
        val best = if (rmsL >= rmsR) l else rr
        Log.d("VOICE", "48к→16к: rmsL=${"%.3f".format(rmsL)} rmsR=${"%.3f".format(rmsR)} → беру ${if (rmsL >= rmsR) "L" else "R"}")
        return best
    }

    /** Качественный ресемпл 48000→16000: FIR low-pass (Хэмминг, 63 тапа, срез 7.4к) + децимация 4:1. */
    private fun resample4to1(x: FloatArray): FloatArray {
        val taps = 63
        val cut = 7400.0 / 48000.0
        val h =
            DoubleArray(taps) { i ->
                val n = i - (taps - 1) / 2.0
                val sinc = if (n == 0.0) 2 * cut else kotlin.math.sin(2 * Math.PI * cut * n) / (Math.PI * n)
                sinc * (0.54 - 0.46 * kotlin.math.cos(2 * Math.PI * i / (taps - 1)))
            }
        val hSum = h.sum()
        for (i in h.indices) h[i] /= hSum
        val half = (taps - 1) / 2
        val outLen = x.size / 4
        val out = FloatArray(outLen)
        for (k in 0 until outLen) {
            var acc = 0.0
            val base = k * 4
            for (t in 0 until taps) {
                val idx = base - half + t
                if (idx in x.indices) acc += h[t] * x[idx]
            }
            out[k] = acc.toFloat()
        }
        return out
    }

    /** High-pass 150 Гц (биквад RBJ, Q=0.707) — срез сетевого фона 100 Гц. */
    private fun highPass(
        x: FloatArray,
        fc: Double = 150.0,
        q: Double = 0.707,
    ): FloatArray {
        val w0 = 2.0 * Math.PI * fc / sampleRate
        val cosw = Math.cos(w0)
        val sinw = Math.sin(w0)
        val alpha = sinw / (2.0 * q)
        val b0 = (1.0 + cosw) / 2.0
        val b1 = -(1.0 + cosw)
        val b2 = (1.0 + cosw) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosw
        val a2 = 1.0 - alpha
        val out = FloatArray(x.size)
        var x1 = 0f
        var x2 = 0f
        var y1 = 0f
        var y2 = 0f
        for (i in x.indices) {
            val y = ((b0 / a0) * x[i] + (b1 / a0) * x1 + (b2 / a0) * x2 - (a1 / a0) * y1 - (a2 / a0) * y2).toFloat()
            x2 = x1
            x1 = x[i]
            y2 = y1
            y1 = y
            out[i] = y
        }
        return out
    }

    private fun rms(x: FloatArray): Double {
        var s = 0.0
        for (v in x) s += v.toDouble() * v
        return kotlin.math.sqrt(s / x.size)
    }
}

internal fun vibrate(context: Context) {
    try {
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(90)
        }
    } catch (_: Exception) {
    }
}

// Системный звук уведомления — тот самый тон, что юзер выбрал в настройках
// Android для нотификаций. Играет при появлении нового ответа, чтобы оповещение
// было слышным (не только вибрация). Ringtone.play() может блокировать — гоняем
// на Dispatchers.IO. Не создаём NotificationChannel: просто воспроизводим тон.
internal fun playNotificationSound(context: Context) {
    try {
        val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (uri != null) {
            val rt = RingtoneManager.getRingtone(context.applicationContext, uri)
            if (rt != null) {
                if (Build.VERSION.SDK_INT >= 28) {
                    rt.audioAttributes =
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                }
                rt.play()
            }
        }
    } catch (_: Exception) {
    }
}
