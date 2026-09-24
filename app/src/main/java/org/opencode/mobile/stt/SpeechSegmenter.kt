package org.opencode.mobile.stt

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * VAD + сегментация длинного аудио на «высказывания» (ЭКСП-5).
 *
 * Две проблемы, которые решает:
 * 1. ncnn-encoder (ncnn_jni.cpp extract_fbank_feature) фиксирован на 30 секунд —
 *    клип длиннее просто молча обрезается (~480000 сэмплов). Длинная фраза
 *    теряет хвост. Сегментация по паузам даёт куски ≤ [MAX_SEGMENT_MS].
 * 2. Whisper-Turbo галлюцинирует на тишине/шуме (PR5: «Продолжение следует…»
 *    на silence и noise). VAD-порог отбрасывает не-речевые окна до движка.
 *
 * Принцип: анализ по кадрам 30 мс (RMS), порог = max(шумовой фон × 3, [MIN_RMS]).
 * Шумовой фон — 10-й перцентиль RMS по всему клипу (устойчив к мусору в начале
 * записи: тихий старт занижает среднее, перцентиль — нет). Сегмент открывается
 * речью, закрывается паузой ≥ [PAD_MS] или жёстким лимитом [MAX_SEGMENT_MS].
 * В начало сегмента возвращается [PRE_ROLL_MS] контекста (для транскрипции).
 * Валидный сегмент — не короче [MIN_SPEECH_MS] (щелчки/шлёпки не проходят).
 *
 * Чистый Kotlin без Android-зависимостей — покрыт JVM-тестами (app/src/test).
 */
class SpeechSegmenter(
    private val sampleRate: Int = 16_000,
) {
    data class Segment(
        val startMs: Int,
        val samples: FloatArray,
        val rms: Float,
    )

    fun split(samples: FloatArray): List<Segment> {
        if (samples.size < frameSize) return emptyList()
        val rms = computeFrameRms(samples)
        val threshold = computeThreshold(rms)
        val voiced = computeVoicedMask(rms, threshold)
        return collectSegments(samples, rms, voiced)
    }

    /** RMS по кадрам [FRAME_MS]. Размер кадра — из [sampleRate]. */
    private fun computeFrameRms(samples: FloatArray): FloatArray {
        val frameCount = samples.size / frameSize
        val rms = FloatArray(frameCount)
        for (i in 0 until frameCount) {
            var sum = 0.0
            val base = i * frameSize
            for (j in base until base + frameSize) {
                val v = samples[j].toDouble()
                sum += v * v
            }
            rms[i] = sqrt(sum / frameSize).toFloat()
        }
        return rms
    }

    /** Порог речи: робастный шумовой фон (10-й перцентиль) × 3, но не ниже [MIN_RMS]. */
    private fun computeThreshold(rms: FloatArray): Float {
        val sorted = rms.clone().apply { sort() }
        val idx = (sorted.size * NOISE_PERCENTILE).toInt()
        val noiseFloor = sorted[idx.coerceAtLeast(0)]
        return max(noiseFloor * NOISE_FRACTION, MIN_RMS)
    }

    /**
     * Голосовые кадры с «прилипанием»: кадр речевой, если сам или хотя бы один
     * из двух соседей превышает порог — против дыр внутри слова.
     */
    private fun computeVoicedMask(
        rms: FloatArray,
        threshold: Float,
    ): BooleanArray {
        val frameCount = rms.size
        val voiced = BooleanArray(frameCount)
        for (i in 0 until frameCount) {
            val self = rms[i] > threshold
            val left = i > 0 && rms[i - 1] > threshold
            val right = i + 1 < frameCount && rms[i + 1] > threshold
            voiced[i] = self || left || right
        }
        return voiced
    }

    /** Сборка сегментов: открытие речью, закрытие паузой ≥ [PAD_MS] или лимитом. */
    private fun collectSegments(
        samples: FloatArray,
        rms: FloatArray,
        voiced: BooleanArray,
    ): List<Segment> {
        val frames = FrameInfo(rms, voiced)
        val segments = ArrayList<Segment>()
        var i = 0
        while (i < voiced.size) {
            if (!voiced[i]) {
                i++
                continue
            }
            val start = preRollStart(voiced, i)
            val end = captureRun(voiced, i)
            addSegmentIfValid(segments, samples, frames, start, end)
            i = end
        }
        return segments
    }

    /**
     * Жадный захват высказывания, начиная с голосового кадра [from]:
     * поглощаем речь и короткие паузы; выход при паузе ≥ [PAD_MS] либо
     * достижении жёсткого лимита [MAX_SEGMENT_MS]. Возвращает end-exclusive.
     */
    private fun captureRun(
        voiced: BooleanArray,
        from: Int,
    ): Int {
        var i = from
        var last = from
        var done = false
        while (i < voiced.size && !done) {
            val isVoice = voiced[i]
            if (isVoice) {
                last = i
                done = i - from >= maxFrames
            } else {
                done = i - last >= padFrames || i - from >= maxFrames
            }
            if (!done) i++
        }
        return i
    }

    /** Откат начала сегмента на несколько тихих кадров (pre-roll, лимит [PRE_ROLL_MS]). */
    private fun preRollStart(
        voiced: BooleanArray,
        firstVoiced: Int,
    ): Int {
        var start = firstVoiced
        var roll = 0
        while (start > 0 && roll < preRollFrames && !voiced[start - 1]) {
            start--
            roll++
        }
        return start
    }

    private fun addSegmentIfValid(
        segments: MutableList<Segment>,
        samples: FloatArray,
        frames: FrameInfo,
        startFrame: Int,
        endFrameUnsafe: Int,
    ) {
        val end = endFrameUnsafe.coerceAtMost(frames.voiced.size)
        val durMs = (end - startFrame) * frameMs.toInt()
        val from = startFrame * frameSize
        val len = min((end - startFrame) * frameSize, samples.size - from)
        if (startFrame >= 0 && durMs >= MIN_SPEECH_MS && startFrame <= lastVoicedOf(frames.voiced, end)) {
            if (len > 0) {
                val segSamples = samples.copyOfRange(from, from + len)
                val rms = segRmsOf(frames.rms, startFrame, end)
                segments.add(
                    Segment(
                        startMs = startFrame * frameMs.toInt(),
                        samples = segSamples,
                        rms = rms,
                    ),
                )
            }
        }
    }

    /** Пары RMS/voiced по кадрам — один объект для внутренних вызовов. */
    private data class FrameInfo(
        val rms: FloatArray,
        val voiced: BooleanArray,
    )

    private fun lastVoicedOf(
        voiced: BooleanArray,
        upTo: Int,
    ): Int {
        for (j in upTo - 1 downTo 0) {
            if (voiced[j]) return j
        }
        return -1
    }

    private fun segRmsOf(
        rms: FloatArray,
        from: Int,
        to: Int,
    ): Float {
        var sum = 0.0
        var n = 0
        for (i in from until min(to, rms.size)) {
            sum += rms[i].toDouble() * rms[i]
            n++
        }
        return if (n == 0) 0f else sqrt(sum / n).toFloat()
    }

    private val frameMs = FRAME_MS
    private val frameSize = (sampleRate * FRAME_MS / MS_PER_SEC).toInt()
    private val padFrames = (PAD_MS / FRAME_MS).toInt().coerceAtLeast(1)
    private val preRollFrames = (PRE_ROLL_MS / FRAME_MS).toInt().coerceAtLeast(1)
    private val maxFrames = (MAX_SEGMENT_MS / FRAME_MS).toInt()

    companion object {
        /** ncnn encoder фиксирован на 30s — сегмент короче лимита с запасом. */
        const val MAX_SEGMENT_MS = 28_000L

        /** Тишина этой длины закрывает сегмент (пауза = конец высказывания). */
        const val PAD_MS = 450L

        /** Сегмент короче — щелчок/шлёпок, выбрасываем. */
        const val MIN_SPEECH_MS = 400L

        /** Контекст тишины, возвращаемый в начало сегмента. */
        const val PRE_ROLL_MS = 150L

        /** Абсолютный минимум RMS (16-бит тишина ~0.002–0.02; ниже — уже шум/усиление). */
        const val MIN_RMS = 0.015f

        /** Длина кадра анализа, мс. */
        private const val FRAME_MS = 30L

        /** Мс в секунду (для пересчёта сэмплов в длительность). */
        private const val MS_PER_SEC = 1000

        /** Доля кадров, принимаемая за шумовой фон (10-й перцентиль). */
        private const val NOISE_PERCENTILE = 0.1

        /** Множитель шумового фона для порога речи. */
        private const val NOISE_FRACTION = 3f
    }
}
