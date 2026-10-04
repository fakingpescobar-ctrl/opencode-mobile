package org.opencode.mobile.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Воспроизведение PCM-флота в AudioTrack. Один трек на весь сеанс озвучки.
 *
 * Fade-out сделан через setVolume, а не переписыванием сэмплов: уже отправленные
 * в трек данные нельзя забрать назад, а громкость у Android гасится плавно —
 * ровно то, что нужно при отмене, чтобы не резать фразу на полуслове.
 */
class AudioTrackPlayer(private val sampleRate: Int) {
    private var track: AudioTrack? = null
    private var volume: Float = 1.0f

    /** Сколько фреймов записали в последний write() — нужно для ожидания конца. */
    private var audioFramesLeftHint: Long = 0

    /** Гасит и останавливает трек с плавным спадом. */
    fun stop(fadeOutMs: Int = 0) {
        val t = track ?: return
        if (fadeOutMs <= 0) {
            hardStop(t)
            return
        }
        Thread({
            val steps = 10
            val stepMs = (fadeOutMs / steps).coerceAtLeast(1)
            for (i in steps downTo 0) {
                setVolumeQuietly(i.toFloat() / steps)
                Thread.sleep(stepMs.toLong())
            }
            hardStop(t)
        }, "tts-fadeout").apply { isDaemon = true }.start()
    }

    fun play(audio: TtsAudio): Boolean {
        if (audio.isEmpty) return false
        val t = ensureTrack(audio.sampleRate)
        setVolumeQuietly(0f)
        fadeIn(t)
        val startedAt = System.currentTimeMillis()
        // Отмена в соседнем потоке могла освободить трек прямо во время write().
        val written =
            try {
                t.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
            } catch (t2: IllegalStateException) {
                Log.i(TAG, "write отменён: ${t2.message}")
                return false
            }
        if (written < 0) {
            Log.w(TAG, "AudioTrack.write вернул $written")
            return false
        }
        // WRITE_BLOCKING возвращает, когда буфер принят, а не когда звук доигран.
        // Ждём по playbackHeadPosition, иначе pause() срежет хвост фразы.
        audioFramesLeftHint = written.toLong()
        drain(t)
        idle(t)
        Log.i(
            TAG,
            "проиграно ${System.currentTimeMillis() - startedAt}ms из ${"%.2f".format(audio.durationSec)}s",
        )
        return true
    }

    /** Ждёт, пока трек реально отдаст все записанные фреймы в HAL. */
    private fun drain(t: AudioTrack) {
        val frames = audioFramesLeftHint
        audioFramesLeftHint = 0
        if (frames <= 0) return
        // start отмены в соседнем потоке мог уже освободить трек — тогда и ждать нечего,
        // а обращение к getPosition() бросает IllegalStateException.
        val target =
            try {
                t.playbackHeadPosition.toLong()
            } catch (t2: IllegalStateException) {
                Log.i(TAG, "трек уже освобождён, ждать конца не нужно")
                return
            }
        val deadline = System.currentTimeMillis() + PLAYBACK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val played =
                try {
                    t.playbackHeadPosition.toLong() - target
                } catch (t2: IllegalStateException) {
                    return
                }
            if (played >= frames) return
            Thread.sleep(20)
        }
        Log.w(TAG, "дождаться конца не успели")
    }

    /** Тихо глушит трек между фразами: буфер пустой, HAL-нить спит. */
    private fun idle(t: AudioTrack) {
        runCatching { t.pause() }
        runCatching { t.flush() }
        setVolumeQuietly(0f)
    }

    /** Играет ли сейчас трек (или был инициализирован). */
    val isActive: Boolean get() = track != null

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        setVolumeQuietly(volume)
    }

    fun release() {
        track?.let { hardStop(it) }
        track = null
    }

    private fun ensureTrack(rate: Int): AudioTrack {
        track?.let { if (it.sampleRate == rate) return it }
        track?.let { hardStop(it) }
        val minBuf = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBuf <= 0) throw IllegalStateException("AudioTrack: getMinBufferSize вернул $minBuf")
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            // Буфер держим втрое больше фрейма: хватает на jitter, write() не рвётся.
            .setBufferSizeInBytes(minBuf * 3)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            throw IllegalStateException("AudioTrack не инициализирован")
        }
        t.play()
        track = t
        return t
    }

    private fun fadeIn(t: AudioTrack) {
        // Трек стоял на pause после предыдущей фразы — сначала буфер, потом громкость,
        // иначе первые миллисекунды проглатываются молча.
        runCatching { t.play() }
        val steps = 4
        val stepMs = 8L
        for (i in 1..steps) {
            setVolumeQuietly(i.toFloat() / steps)
            Thread.sleep(stepMs)
        }
        setVolumeQuietly(volume)
    }

    private fun setVolumeQuietly(v: Float) {
        try {
            track?.setVolume(v.coerceIn(0f, 1f))
        } catch (t: Throwable) {
            Log.w(TAG, "setVolume не сработал: ${t.message}")
        }
    }

    private fun hardStop(t: AudioTrack) {
        runCatching { t.stop() }
        runCatching { t.flush() }
        runCatching { t.release() }
        if (track === t) track = null
    }

    private companion object {
        const val TAG = "TTS"
        const val PLAYBACK_TIMEOUT_MS = 120_000L
    }
}