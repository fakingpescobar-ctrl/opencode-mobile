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
        if (samples.isEmpty()) return "ОШИБКА WHISPER: пустые сэмплы"

        // Lazy-путь: короткий клип → один прогон без VAD (энкодер ncnn — константа
        // ~6.5с, сегментирование короткой речи только умножает её). Для whisper.cpp
        // сохраняем прежнее поведение (ему нужен padToMin > 3с и чанкинг привычен).
        val lazilySinglePass = engine == WhisperTranscribeService.ENGINE_NCNN &&
            samples.size <= MAX_SINGLE_PASS_SAMPLES
        if (lazilySinglePass) {
            Log.d(TAG, "lazy single-pass: ${samples.size / SAMPLE_RATE}с ≤ 28с — без VAD-сегментации")
            return WhisperTranscribeService.transcribe(
                context = context,
                samples = samples,
                model = model,
                engine = engine,
            )
        }

        val speech = ArrayList<FloatArray>()
        val metas = ArrayList<String>()
        val segments = SpeechSegmenter().split(samples)
        for ((idx, seg) in segments.withIndex()) {
            val audio = if (engine == WhisperTranscribeService.ENGINE_WHISPER) {
                padToMin(seg.samples, minSamples = 3 * SAMPLE_RATE)
            } else {
                seg.samples
            }
            speech.add(audio)
            metas.add("%.1fс".format(audio.size / SAMPLE_RATE.toFloat()))
        }
        if (speech.isEmpty()) {
            Log.d(TAG, "VAD: речи не обнаружено (${samples.size / SAMPLE_RATE}с) — пустой результат")
        } else {
            Log.d(TAG, "сегментов: ${speech.size} из ${samples.size / SAMPLE_RATE}с: ${metas.joinToString()}")
        }

        // Прогоняем сегменты по одному: ошибка любого — валит весь запрос.
        var firstError: String? = null
        val parts = ArrayList<String>()
        for ((idx, audio) in speech.withIndex()) {
            val text = WhisperTranscribeService.transcribe(
                context = context,
                samples = audio,
                model = model,
                engine = engine,
            )
            if (text.startsWith("ОШИБКА")) {
                firstError = text
                break
            }
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) parts.add(trimmed)
            Log.d(
                TAG,
                "сегмент ${idx + 1}/${speech.size} " +
                    "(${segments[idx].startMs}мс, ${audio.size / SAMPLE_RATE}с, " +
                    "rms=%.3f): '%s'".format(segments[idx].rms, trimmed),
            )
        }
        return firstError ?: parts.joinToString(" ")
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
