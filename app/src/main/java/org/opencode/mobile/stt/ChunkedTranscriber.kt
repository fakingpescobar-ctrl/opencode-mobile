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
     * одним прогоном. Плюс (R5-бенч, 25.09.2026): ncnn-encoder платит
     * фиксированные ~6.5с на ЛЮБОЙ вход, а VAD-разбиение короткой фразы с
     * микропаузами на 2+ сегмента умножает эту стоимость (8с фраза → 2×encoder
     * ≈ 15с).
     *
     * ПОРОГ ИЗМЕРЕН, а не выбран «на глаз» (29.09.2026,
     * BenchSttTest#benchLongSinglePassTruncation). Прежние 28с обосновывались
     * словами «запас под 30с-лимит ncnn» — и это обоснование ОКАЗАЛОСЬ ЛОЖНЫМ.
     * Замер по long.wav (37с; по данным VAD фразы стоят на 5.2 / 18.2 / 31.2с):
     * single-pass возвращает всё при 11 / 15 / 18с, но уже на 22с теряет ВТОРУЮ
     * фразу (marker 2 → 1), а на 37с отдаёт 1 из 3. То есть дело не в окне 30с:
     * энкодер режет по 480000 сэмплов (cnn_jni.cpp:658), но контент начинает
     * теряться сильно раньше. Механизм (штраф за повтор, cnn_jni.cpp:633,
     * гасит тождественную фразу — либо модель сама ставит EOT) не разведён,
     * и на константу это не влияет: 18с — последнее измеренное полное
     * значение, 22с уже нет.
     *
     * Цена отката с 28с на 18с — длинные клипы идут через VAD-чанки и платят
     * encoder дважды. Размен осознанный: лишний encoder виден в логе, а
     * потерянная половина диктовки не видна нигде.
     */
    private const val MAX_SINGLE_PASS_SAMPLES = 18 * SAMPLE_RATE

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

        // Новая запись = новая сессия языка. Защёлка живёт ровно один клип:
        // первый ПРОХОД движка определяет язык, остальные берут токен. Проход -
        // не обязательно сегмент: ведущие короткие сегменты склеиваются до
        // порога детекции (см. [planParts]). Без сброса язык первой записи
        // утекал бы во все следующие до перезапуска процесса.
        WhisperTranscribeService.resetLanguageLatch()

        // Lazy-путь: короткий клип → один прогон без VAD (энкодер ncnn — константа
        // ~6.5с, сегментирование короткой речи только умножает её). Для whisper.cpp
        // сохраняем прежнее поведение (ему нужен padToMin > 3с и чанкинг привычен).
        return when {
            shouldUseSinglePass(engine, samples.size) -> {
                Log.d(
                    TAG,
                    "lazy single-pass: ${samples.size / SAMPLE_RATE}с ≤ " +
                        "${MAX_SINGLE_PASS_SAMPLES / SAMPLE_RATE}с — без VAD-сегментации",
                )
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
        val plan = planParts(segments, speech)
        for ((index, part) in plan.withIndex()) {
            val text = WhisperTranscribeService.transcribe(
                context = context,
                samples = part.audio,
                model = model,
                engine = engine,
            )
            if (text.startsWith("ОШИБКА")) return text

            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) parts.add(trimmed)
            val durationSeconds = part.audio.size / SAMPLE_RATE
            val label = if (part.merged == 1) "сегмент" else "сегменты x${part.merged}"
            Log.d(
                TAG,
                "$label ${index + 1}/${plan.size} " +
                    "(${part.startMs}мс, ${durationSeconds}с, rms=${"%.3f".format(rms(part.audio))}): '$trimmed'",
            )
        }
        return parts.joinToString(" ")
    }

    /** Один проход движка: кусок аудио + откуда он начался + сколько VAD-сегментов склеено. */
    private data class Part(
        val audio: FloatArray,
        val startMs: Int,
        val merged: Int,
    )

    /**
     * Раскладка сессии на проходы. Язык решается ОДИН раз, поэтому ведущие
     * короткие сегменты склеиваются до порога детекции.
     *
     * Детектор языка отказывается работать на клипах короче
     * [WhisperTranscribeService.MIN_LANG_DETECT_SECONDS]. Раньше такой сегмент
     * не просто молчал, а уходил в декодер с токеном "ru" - и весь текст
     * записи начинался с русской расшифровки иностранной речи: на long.wav
     * первые два сегмента из девяти дали "И так, мои дорогие американцы" и
     * "«Аск not!»" вместо английского, и только с третьего сегмента защёлка
     * ловила en. Склейка это чинит бесплатно: VAD разрезал один непрерывный
     * кусок записи, и объединённый кусок ничем не отличается от целого.
     */
    private fun planParts(
        segments: List<SpeechSegmenter.Segment>,
        speech: List<FloatArray>,
    ): List<Part> {
        if (speech.size <= 1 || WhisperTranscribeService.languageDecided()) {
            return speech.mapIndexed { index, audio -> Part(audio, segments[index].startMs, 1) }
        }
        val minSamples = (WhisperTranscribeService.MIN_LANG_DETECT_SECONDS * SAMPLE_RATE).toInt()
        var taken = 0
        var total = 0
        while (taken < speech.size && total < minSamples) {
            total += speech[taken].size
            taken++
        }
        val head =
            if (taken > 1) {
                Log.i(TAG, "склеиваю первые $taken сегмента до порога детекции языка (${total / SAMPLE_RATE}с)")
                merge(speech.subList(0, taken))
            } else {
                speech[0]
            }
        return buildList {
            add(Part(head, segments.first().startMs, taken))
            for (index in taken until speech.size) {
                add(Part(speech[index], segments[index].startMs, 1))
            }
        }
    }

    private fun merge(parts: List<FloatArray>): FloatArray {
        val joined = FloatArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, joined, offset, part.size)
            offset += part.size
        }
        return joined
    }

    /** RMS для лога сегмента; считаем по фактическому куску, а не по исходному. */
    private fun rms(audio: FloatArray): Float {
        if (audio.isEmpty()) return 0f
        var sum = 0.0
        for (sample in audio) {
            sum += sample * sample
        }
        return Math.sqrt(sum / audio.size).toFloat()
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
