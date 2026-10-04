package org.opencode.mobile.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.File

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
        // Ничего не доигрываем: конец озвучки наступит сразу, а не через остаток буфера.
        playUntil.set(0)
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

    /**
     * Пишет кусок в трек и возвращает управление немедленно.
     *
     * Конец куска НЕ ждём: раньше здесь стоял синхронный `drain()`, а после него
     * `idle()` = `pause()` + `flush()`, то есть буфер выбрасывался после каждой
     * фразы. На клипе 7 с ожидание упиралось в таймаут 120 с — 17-кратное превышение,
     * и конвейер вставал: озвучка произносила первое предложение и замолкала.
     *
     * Обратное давление даёт сам `WRITE_BLOCKING` — если HAL не успевает, запись
     * просто подождёт внутри write(), и следующий кусок пойдёт в освободившийся
     * буфер. Громкость на кусках не трогаем: fade-in живёт в [ensureTrack],
     * fade-out — в [stop].
     */
    fun play(audio: TtsAudio): Boolean {
        if (audio.isEmpty) return false
        return try {
            val t = ensureTrack(audio.sampleRate)
            // Предохранитель от отставания. Синтез идёт с rtf ~0.2, то есть
            // опережает речь вчетверо, а воспроизведение идёт ровно в 1x. На
            // длинном ответе разрыв растёт сам и неукротимо: замечено 17с, 59с,
            // 70с за полминуты, а дальше упирается в память. Ни pending с
            // лимитом 16, ни audioQueue тут не спасали — ограничен был не тот.
            val backlogSec = audioFramesLeftHint.toDouble() / audio.sampleRate
            if (backlogSec > MAX_BACKLOG_SEC) {
                // Накопленное выбрасываем целиком и продолжаем с текущей фразы.
                // Догонять каждое накопленное слово нельзя — тогда озвучка
                // только всё сильнее отстаёт. Лучше пропуск, чем минута отставания.
                t.pause()
                t.flush()
                playUntil.set(0)
                audioFramesLeftHint = 0
                Log.w(
                    TAG,
                    "отставание ${"%.1f".format(backlogSec)}с > ${MAX_BACKLOG_SEC}с, буфер сброшен",
                )
            }
            // После stop() трек остаётся на паузе: без play() write() уйдёт в буфер,
            // но из него не прозвучит.
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
            val headBefore = playedFrames(t)
            val written = t.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                Log.w(TAG, "AudioTrack.write вернул $written")
                return false
            }
            audioFramesLeftHint += written.toLong()
            val until = headBefore + written
            playUntil.set(until)
            ensureMonitor(headBefore)
            writeDump(audio, written)
            Log.d(TAG, "записано $written, в очереди трека $audioFramesLeftHint")
            true
        } catch (t: Throwable) {
            // Раньше try накрывал только write() и ловил IllegalStateException, а
            // ensureTrack() бросал мимо — отсюда «воспроизведение упало: null».
            Log.e(TAG, "Ошибка записи PCM", t)
            false
        }
    }

    /** Играет ли сейчас трек: есть что доигрывать, а не просто «трек создан». */
    val isActive: Boolean
        get() {
            val t = track ?: return false
            val until = playUntil.get()
            return until > playedFrames(t)
        }

    /**
     * Абсолютная позиция в [AudioTrack.getPlaybackHeadPosition], до которой HAL
     * должен отдать звук: сколько уже записали плюс то, что было в буфере.
     *
     * Именно по ней считается конец озвучки. Раньше конец ждал сломанный `drain()`
     * прямо в [play], а без него «играет» значило «трек создан» — и озвучка никогда
     * не выглядела завершённой.
     */
    private val playUntil = java.util.concurrent.atomic.AtomicLong(0)
    private var monitor: Thread? = null

private fun writeDump(audio: TtsAudio, written: Int) {
        val out = dumpOut ?: return
        val b = java.nio.ByteBuffer.allocate(written * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until written) b.putFloat(audio.samples[i])
        runCatching { out.write(b.array()) }
    }

    private fun playedFrames(t: AudioTrack): Long = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    /** Следит, пока буфер не доигран, и логирует фактическое время звучания. */
    private fun ensureMonitor(first: Long) {
        if (monitor?.isAlive == true) return
        monitor =
            Thread(
                {
                    val startedAt = System.currentTimeMillis()
                    while (true) {
                        val t = track
                        val until = playUntil.get()
                        if (t == null || until <= 0) break
                        if (playedFrames(t) >= until) {
                            Log.d(
                                TAG,
                                "буфер доигран за ${System.currentTimeMillis() - startedAt}ms " +
                                    "(${until - first} фреймов)",
                            )
                            break
                        }
                        Thread.sleep(150)
                    }
                },
                "tts-drain",
            ).apply {
                isDaemon = true
                start()
            }
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        setVolumeQuietly(volume)
    }

    fun release() {
        track?.let { hardStop(it) }
        track = null
        playUntil.set(0)
    }

    private fun ensureTrack(rate: Int): AudioTrack {
        track?.let { if (it.sampleRate == rate) return it }
        track?.let { hardStop(it) }
        // Новый трек — счётчик с нуля: позиция head у него снова от нуля.
        playUntil.set(0)
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
        track = t
        t.play()
        // Рампа только на создании трека: на кусках каждое предложение ныряло бы
        // с 25% громкости и ещё 32 мс сна на потоке игры.
        fadeIn(t)
        return t
    }

    /** Плавно поднимает громкость нового трека с 25% до рабочей. */
    private fun fadeIn(t: AudioTrack) {
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
        runCatching { t.pause() }
        runCatching { t.flush() }
        runCatching { t.stop() }
        runCatching { t.release() }
        if (track === t) track = null
    }

companion object {
const val TAG = "TTS"

        /**
         * Сколько секунд непроигранного звука допускаем в буфере трека.
         *
         * Больше этого — озвучка уже настолько отстала от текста, что догонять
         * бессмысленно: синтез всё равно успевает вперёд, и разрыв только растёт.
         * При сбросе теряется начало сказанного, зато голос возвращается в
         * реальное время. Порог намеренно большой — он страхует от патологии,
         * а не режет нормальный разговор.
         */
        const val MAX_BACKLOG_SEC = 20.0

        /**
         * Дамп сырого PCM в файл — то, что реально ушло в динамик, float32 LE, без
         * заголовка. Нужен, чтобы проверить озвучку без ушей: по файлу видно
         * длительность каждого фрагмента, паузы между ними и тишину, а лог этого
         * не показывает. Включается ключом `tts_dump_pcm` в prefs.
         */
        @Volatile private var dumpOut: java.io.BufferedOutputStream? = null

        fun startDump(file: File) {
            stopDump()
            dumpOut = java.io.BufferedOutputStream(java.io.FileOutputStream(file), 1 shl 16)
            Log.i(TAG, "дамп PCM включён: ${file.absolutePath}")
        }

        fun stopDump() {
            dumpOut?.let {
                runCatching { it.flush() }
                runCatching { it.close() }
            }
            dumpOut = null
        }

        /** Идёт ли дамп прямо сейчас: нужно переключателю, чтобы не открыть файл дважды. */
        fun isDumping(): Boolean = dumpOut != null
    }
}