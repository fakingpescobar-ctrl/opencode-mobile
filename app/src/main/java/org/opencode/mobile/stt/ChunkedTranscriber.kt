package org.opencode.mobile.stt

import android.content.Context
import android.util.Log

/**
 * Сегментная транскрипция (ЭКСП-5): длинный клип → VAD-сегменты → каждый
 * сегмент через [WhisperTranscribeService.transcribe] (существующая FIFO-очередь)
 * → склейка текстов.
 *
 * Зачем (см. [SpeechSegmenter]):
 * - клип > 30 с у ncnn-encoder обрезает хвост — сегменты ≤ 28 с;
 * - тишина/шум не попадают в движок — галлюцинации («Продолжение следует…»)
 *   отсекаются до распознавания;
 * - клипы ≤ 28 с (ncnn) выполняются ОДНИМ прогоном без VAD — encoder ncnn берёт
 *   фиксированные ~6.5с на любой вход, делить короткую речь на сегменты дороже
 *   (R5-бенч, 25.09.2026); «живость» первых частичек приносится в жертву скорости.
 *
 * Склейка — простая конкатенация с пробелом: ncnn-адаптация не умеет
 * initial_prompt (декодер запускается с фиксированного [sot, lang, transcribe,
 * notimestamps]), поэтому контекст между сегментами не переносится. Для набора
 * артефактов на стыках это приемлемо (проверено в benchChunkedLong).
 */
object ChunkedTranscriber {
    private const val TAG = "CHUNKED"
    private const val SAMPLE_RATE = 16_000

    /**
     * Клипы не длиннее этого — НЕ сегментируются: вся запись гонится в движок
     * одним прогоном. Причина (R5-бенч, 25.09.2026): ncnn-encoder платит
     * фиксированные ~6.5с на ЛЮБОЙ вход; VAD-разбиение короткой фразы с
     * микропаузами на 2+ сегмента умножает эту стоимость (8с фраза → 2×encoder
     * ≈ 15с). До 28с (запас под 30с-лимит ncnn) выгоднее один прогон.
     */
    private const val MAX_SINGLE_PASS_SAMPLES = 28 * SAMPLE_RATE

    /**
     * Сегментирует клип и транскрибирует каждый сегмент через сервис.
     * Возвращает склеенный текст, пустую строку (речи не найдено) или
     * "ОШИБКА WHISPER: ..." (первая ошибка движка/таймаута).
     */
    suspend fun transcribe(
        context: Context,
        samples: FloatArray,
        model: String,
        engine: String,
    ): String {
        if (samples.isEmpty()) {
            return "ОШИБКА WHISPER: пустые сэмплы"
        }

        // Lazy-путь: короткий клип → один прогон без VAD (энкодер ncnn — константа
        // ~6.5с, сегментирование короткой речи только умножает её). Для whisper.cpp
        // сохраняем прежнее поведение (ему нужен padToMin > 3с и чанкинг привычен).
        return when {
            shouldUseSinglePass(engine, samples.size) -> {
                Log.d(TAG, "lazy single-pass: ${samples.size / SAMPLE_RATE}с ≤ 28с — без VAD-сегментации")
                WhisperTranscribeService.transcribe(
                    context = context,
                    samples = samples,
                    model = model,
                    engine = engine,
                )
            }
            else -> {
                val segments = SpeechSegmenter().split(samples)
                val speech = segments.map { segment -> prepareAudio(segment.samples, engine) }
                logSegments(speech, samples.size)
                transcribeSegments(context, segments, speech, model, engine)
            }
        }
    }

    private fun shouldUseSinglePass(
        engine: String,
        sampleCount: Int,
    ): Boolean = engine == WhisperTranscribeService.ENGINE_NCNN && sampleCount <= MAX_SINGLE_PASS_SAMPLES

    private fun prepareAudio(
        samples: FloatArray,
        engine: String,
    ): FloatArray =
        if (engine == WhisperTranscribeService.ENGINE_WHISPER) {
            padToMin(samples, minSamples = 3 * SAMPLE_RATE)
        } else {
            samples
        }

    private fun logSegments(
        speech: List<FloatArray>,
        totalSamples: Int,
    ) {
        if (speech.isEmpty()) {
            Log.d(TAG, "VAD: речи не обнаружено (${totalSamples / SAMPLE_RATE}с) — пустой результат")
            return
        }
        val metas = speech.map { "%.1fс".format(it.size / SAMPLE_RATE.toFloat()) }
        Log.d(TAG, "сегментов: ${speech.size} из ${totalSamples / SAMPLE_RATE}с: ${metas.joinToString()}")
    }

    private suspend fun transcribeSegments(
        context: Context,
        segments: List<SpeechSegmenter.Segment>,
        speech: List<FloatArray>,
        model: String,
        engine: String,
    ): String {
        val parts = ArrayList<String>()
        for ((index, audio) in speech.withIndex()) {
            val text = WhisperTranscribeService.transcribe(
                context = context,
                samples = audio,
                model = model,
                engine = engine,
            )
            if (text.startsWith("ОШИБКА")) return text

            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) parts.add(trimmed)
            val segment = segments[index]
            val durationSeconds = audio.size / SAMPLE_RATE
            val rms = "%.3f".format(segment.rms)
            Log.d(
                TAG,
                "сегмент ${index + 1}/${speech.size} " +
                    "(${segment.startMs}мс, ${durationSeconds}с, rms=$rms): '$trimmed'",
            )
        }
        return parts.joinToString(" ")
    }

    /** whisper.cpp (vanilla) врёт на клипах < 3с — добиваем нулями до минимума. */
    private fun padToMin(
        audio: FloatArray,
        minSamples: Int,
    ): FloatArray {
        if (audio.size >= minSamples) return audio
        val padded = FloatArray(minSamples)
        System.arraycopy(audio, 0, padded, 0, audio.size)
        return padded
    }
}
