package org.opencode.mobile.tts

import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Синтезатор одного предложения. Вызовы идут строго последовательно. */
interface SpeechSynth {
    val sampleRate: Int

    /** null — движок не смог (модель выгрузилась), озвучка молча выключается. */
    fun synthesize(text: String, sid: Int, speed: Float): TtsAudio?
}

/**
 * Озвучка по очереди: один поток синтеза, один поток воспроизведения.
 *
 * Два потока, а не один, потому что синтез (1.3 с на предложение) должен идти
 * параллельно с игрой (4 с). При последовательном исполнении длинный ответ
 * накапливал бы отставание и между предложениями появлялись бы паузы.
 * §12.5 требует последовательную очередь СИНТЕЗА — она здесь одна.
 *
 * Отмена: поколение бампается, обе очереди чистятся, играющее гасится за 120 мс.
 */
class TtsSpeaker(private val synth: SpeechSynth) {
    private val pending = LinkedBlockingQueue<String>()
    private val audioQueue = LinkedBlockingQueue<TtsAudio>()
    private val generation = AtomicLong(0)
    private val player = AudioTrackPlayer(synth.sampleRate)

    private var synthThread: Thread? = null
    private var playThread: Thread? = null

    /** Поколение, под которое подняты потоки. */
    @Volatile private var threadsGeneration = -1L

    @Volatile private var sid = 0
    @Volatile private var speed = 1.0f

    val isSpeaking: Boolean get() = pending.isNotEmpty() || audioQueue.isNotEmpty() || player.isActive

    

    /**
     * Поднимает потоки под текущее поколение.
     *
     * Проверка именно по поколению, а не по живости потока: после stop() потоки
     * ещё доигрывают цикл опроса (300 мс) и выглядят живыми. Если бы start()
     * доверял isAlive, он вернулся бы без новых потоков, старые увидели бы смену
     * поколения и вышли — и озвучка замолчала бы навсегда.
     */
    fun start(sid: Int, speed: Float) {
        this.sid = sid
        this.speed = speed
        val myGen = generation.get()
        if (threadsGeneration == myGen && synthThread?.isAlive == true && playThread?.isAlive == true) return
        synthThread?.interrupt()
        playThread?.interrupt()
        threadsGeneration = myGen
        synthThread = Thread({ synthLoop(myGen) }, "tts-synth").apply { isDaemon = true; start() }
        playThread = Thread({ playLoop(myGen) }, "tts-play").apply { isDaemon = true; start() }
        Log.i(TAG, "speaker started gen=$myGen sid=$sid speed=$speed rate=${synth.sampleRate}")
    }

    /** Предложение в очередь синтеза. Больше MAX_PENDING не копим: лучше потерять хвост, чем говорить через минуту. */
    fun enqueue(sentence: String) {
        if (sentence.isBlank()) return
        while (pending.size >= MAX_PENDING && pending.poll() == null) {
            Log.w(TAG, "очередь переполнена, отброшено: ${sentence.take(30)}")
        }
        pending.put(sentence)
    }

    /** Стоп озвучки: очередь чистим, играющее гасим. */
    fun stop(fadeOutMs: Int = 120) {
        generation.incrementAndGet()
        pending.clear()
        audioQueue.clear()
        player.stop(fadeOutMs)
        Log.i(TAG, "speaker stopped fade=${fadeOutMs}ms")
    }

    fun release() {
        stop(fadeOutMs = 0)
        synthThread?.interrupt()
        playThread?.interrupt()
        player.release()
        synthThread = null
        playThread = null
    }

    private fun synthLoop(myGen: Long) {
        while (generation.get() == myGen) {
            val sentence =
                try {
                    pending.poll(300, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    // Старый поток прерван нами же в start() после смены поколения —
                    // это штатная ситуация, а не падение.
                    Thread.currentThread().interrupt()
                    return
                } ?: continue
            if (generation.get() != myGen) return

            val startedAt = System.currentTimeMillis()
            val audio =
                try {
                    synth.synthesize(sentence, sid, speed)
                } catch (t: Throwable) {
                    Log.e(TAG, "синтез упал на «${sentence.take(40)}»: ${t.message}")
                    null
                }
            // Пока шёл синтез, юзер мог нажать стоп — результат больше не нужен.
            if (generation.get() != myGen) return
            if (audio == null || audio.isEmpty) {
                Log.w(TAG, "пустой синтез, предложение пропущено: ${sentence.take(40)}")
                continue
            }
            val tookMs = System.currentTimeMillis() - startedAt
            Log.d(
                TAG,
                "синтез ${tookMs}ms audio=${"%.2f".format(audio.durationSec)}s " +
                    "rtf=${"%.3f".format(tookMs / 1000f / audio.durationSec)}",
            )
            audioQueue.put(audio)
        }
    }

    private fun playLoop(myGen: Long) {
        var failsInRow = 0
        while (generation.get() == myGen) {
            val audio =
                try {
                    audioQueue.poll(300, TimeUnit.MILLISECONDS) ?: continue
                } catch (e: InterruptedException) {
                    return
                }
            if (generation.get() != myGen) return
            val played =
                try {
                    player.play(audio)
                } catch (t: Throwable) {
                    Log.e(TAG, "воспроизведение упало: ${t.message}", t)
                    false
                }
            // Отказ раньше просто игнорировался: один упавший трек молча уводил в
            // тишину всю очередь. После нескольких отказов подряд трек пересоздаём —
            // иначе он и следующие куски не починятся сами.
            if (played) {
                failsInRow = 0
            } else {
                failsInRow++
                Log.w(TAG, "отказ воспроизведения $failsInRow подряд")
                if (failsInRow >= MAX_PLAY_FAILS) {
                    player.release()
                    failsInRow = 0
                }
            }
        }
    }

    private companion object {
        const val TAG = "TTS"
        const val MAX_PENDING = 16
        const val MAX_PLAY_FAILS = 3
    }
}