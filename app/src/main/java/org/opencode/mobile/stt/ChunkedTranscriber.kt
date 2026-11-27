package org.opencode.mobile.stt

import android.content.Context
import android.util.Log

/**
 * Сегментная транскрипция (ЭКСП-5): длинный клип → VAD-сегменты → каждый
 * сегмент через [WhisperTranscribeService.transcribe] (существующая FIFO-очередь)
 * → склейка текстов.
 *
 * Зачем (см. [SpeechSegmenter]):
 * - тишина и шум не попадают в движок — галлюцинации («Продолжение следует…»)
 *   отсекаются до распознавания;
 * - короткая речь не делится на сегменты: whisper.cpp врёт на клипах короче
 *   трёх секунд, поэтому [prepareAudio] добивает их нулями до минимума.
 *
 * Склейка — простая конкатенация с пробелом: контекст между сегментами не
 * переносится, для набора артефактов на стыках это приемлемо (проверено в
 * benchChunkedLong).
 */
object ChunkedTranscriber {
    private const val TAG = "CHUNKED"
    private const val SAMPLE_RATE = 16_000

    /**
     * Сегментирует клип и транскрибирует каждый сегмент через сервис.
     * Возвращает склеенный текст, пустую строку (речи не найдено) или
     * "ОШИБКА WHISPER: ..." (первая ошибка движка/таймаута).
     */
    suspend fun transcribe(
        context: Context,
        samples: FloatArray,
        model: String,
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

        val t0 = System.currentTimeMillis()
        val sileroAvailable = SileroVadSegmenter.isAvailable(context)
        val sileroSegments = if (sileroAvailable) SileroVadSegmenter.split(context, samples) else null
        val vadUsed = sileroSegments != null
        val segments = sileroSegments ?: SpeechSegmenter().split(samples)
        val speech = segments.map { segment -> prepareAudio(segment.samples, model) }
        logSegments(speech, samples.size, vadUsed)
        val out = transcribeSegments(context, segments, speech, model)
        val passMs = (System.currentTimeMillis() - t0).coerceAtLeast(0)
        val audioMs = (samples.size * 1000L) / SAMPLE_RATE
        val err = if (out.startsWith("ОШИБКА")) out else null
        SttRuntimeStatus.update(
            SttRuntimeStatus.Snapshot(
                atMs = System.currentTimeMillis(),
                model = model,
                engine = SttModels.byId(model)?.engineKind?.name?.lowercase() ?: "unknown",
                vadBackend = if (vadUsed) "silero-v6" else "rms",
                segments = speech.size,
                audioMs = audioMs,
                passMs = passMs,
                rtf = if (audioMs > 0) passMs.toFloat() / audioMs else 0f,
                textChars = if (err == null) out.length else 0,
                error = err,
            )
        )
        return out
    }

    /**
     * whisper.cpp врёт на клипах короче 3 секунд — добиваем нулями до минимума.
     * Для sherpa-onnx (GigaAM/Parakeet/T-one/Qwen3/SenseVoice) паддинг не нужен:
     * он только добавляет тишину в decode и лишнюю latency на коротких сегментах.
     */
    private fun prepareAudio(samples: FloatArray, model: String): FloatArray {
        val kind = SttModels.byId(model)?.engineKind
        return if (kind == null || kind == SttEngineKind.WHISPER_GGML) {
            padToMin(samples, minSamples = 3 * SAMPLE_RATE)
        } else {
            samples
        }
    }

    private fun logSegments(
        speech: List<FloatArray>,
        totalSamples: Int,
        vadUsed: Boolean,
    ) {
        if (speech.isEmpty()) {
            Log.d(TAG, "VAD: речи не обнаружено (${totalSamples / SAMPLE_RATE}с) — пустой результат")
            return
        }
        val metas = speech.map { "%.1fс".format(it.size / SAMPLE_RATE.toFloat()) }
        val vadTag = if (vadUsed) "silero-v6" else "rms"
        Log.d(TAG, "VAD=$vadTag сегментов: ${speech.size} из ${totalSamples / SAMPLE_RATE}с: ${metas.joinToString()}")
    }

    private suspend fun transcribeSegments(
        context: Context,
        segments: List<SpeechSegmenter.Segment>,
        speech: List<FloatArray>,
        model: String,
    ): String {
        val parts = ArrayList<String>()
        val plan = planParts(segments, speech)
        for ((index, part) in plan.withIndex()) {
            val text = WhisperTranscribeService.transcribe(
                context = context,
                samples = part.audio,
                model = model,
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
