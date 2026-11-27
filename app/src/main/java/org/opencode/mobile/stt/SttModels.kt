package org.opencode.mobile.stt

import android.content.Context
import java.io.File

/**
 * Каталог STT-моделей. Единый источник правды для UI, диагностики, загрузки и
 * runtime-маршрутизации (P2).
 *
 * Приоритет продукта: русская речь. Поэтому GigaAM v3 E2E RNNT помечен как
 * recommendedForRu и должен быть первым рекомендуемым локальным выбором.
 */
@Suppress("LongParameterList")
data class SttModelFile(
    val name: String,
    val url: String,
    val bytes: Long = -1L,
    val sha256: String? = null,
)

enum class SttEngineKind {
    WHISPER_GGML,
    SHERPA_TRANSDUCER,
    SHERPA_QWEN3_ASR,
    /** sherpa-onnx OnlineRecognizer (streaming/CTC, напр. T-one). */
    SHERPA_ONLINE_CTC,
}

@Suppress("LongParameterList")
data class SttModel(
    /** Идентификатор, который пишется в prefs и понимает runtime. */
    val id: String,
    /** Что показываем пользователю. */
    val note: String,
    /** Оценочный/известный размер модели на диске. */
    val bytes: Long,
    /** Готова ли модель: файлы на месте, размер/маркеры валидны. */
    val isReady: (Context) -> Boolean,
    /** Файл/каталог модели на диске. */
    val fileOf: (Context) -> File,
    /** Скачивание с колбэком прогресса (сделано, всего). */
    val fetch: suspend (Context, (Long, Long) -> Unit) -> Unit,
    /** Короткая подпись размера. */
    val sizeLabel: String,
    /** Имя одиночного файла в modelsDir, или null для multi-file каталога. */
    val flatFileName: String?,
    /** Модель ставится вручную, а не кнопкой скачивания. */
    val manual: Boolean = false,
    /** Runtime-семейство модели (нужно P2). */
    val engineKind: SttEngineKind = SttEngineKind.WHISPER_GGML,
    /** Каталог в filesDir/stt/<dirName> для multi-file моделей. */
    val dirName: String? = null,
    /** Файлы multi-file модели. */
    val files: List<SttModelFile> = emptyList(),
    /** Рекомендована для русского диктования. */
    val recommendedForRu: Boolean = false,
    /** Языки/фокус модели для UI. */
    val languages: String = "ru",
    /** Можно ли запускать в текущем APK (runtime-gate для P2). */
    val runnable: Boolean = true,
    /** Скрыть из основного chat picker; модель остаётся в каталоге/диагностике/bench. */
    val hiddenFromPicker: Boolean = false,
)

object SttModels {
    const val DEFAULT_ID = WhisperTranscribeService.MODEL_SMALL
    const val TINY_ID = "tiny"
    const val BASE_ID = "base"

    /** sherpa-onnx nemo_transducer (Parakeet). */
    const val PARAKEET_ID = "parakeet-tdt-0.6b-v3"

    /** GigaAM v3 E2E RNNT, sherpa-onnx nemo_transducer. RU-first. */
    const val GIGAAM_RNNT_ID = "gigaam-v3-e2e-rnnt"

    /** whisper.cpp ggml, квантованный форк bond005/whisper-podlodka-turbo. */
    const val PODLODKA_Q5_ID = "whisper-podlodka-turbo-q5_0"
    const val PODLODKA_Q8_ID = "whisper-podlodka-turbo-q8_0"

    /** Доп. STT-кандидаты: фактчек/интеграция поэтапно. */
    const val VOSK_RU_054_ID = "vosk-model-ru-0.54"
    const val VOSK_RU_052_ID = "vosk-model-small-ru-0.52"
    /** Публичная замена битому csukuangfj/sherpa-onnx-zipformer-small-ru-2024-09-18-int8 (401). */
    const val ZIPFORMER_RU_ID = "zipformer-ru-int8-2025-04-20"
    const val TONE_CTC_ID = "t-one-ctc-ru-streaming"
    const val QWEN3_ASR_06B_ID = "qwen3-asr-0.6b"
    const val QWEN3_ASR_17B_ID = "qwen3-asr-1.7b"

    // SenseVoice удалён: установленная модель zh/en/ja/ko/yue, RU не поддерживает.

    private const val HF = "https://huggingface.co"
    private const val MB = 1024L * 1024L

    private fun sherpaDir(context: Context, dir: String): File = File(File(context.filesDir, "stt"), dir)

    private fun byIdStatic(context: Context, id: String): Boolean {
        val m = all.firstOrNull { it.id == id } ?: return false
        return ModelDownloader.sttModelReady(context, m)
    }

    private suspend fun byFetchStatic(context: Context, id: String, on: (Long, Long) -> Unit) {
        val m = all.firstOrNull { it.id == id } ?: return
        ModelDownloader.downloadSttModel(context, m, on)
    }

    val all: List<SttModel> =
        listOf(
            SttModel(
                id = TINY_ID,
                note = "Самый быстрый whisper · слабее на RU · контрольный fallback",
                bytes = 77_691_713L,
                isReady = { ctx -> ModelDownloader.ggmlModelReady(ctx, ModelDownloader.TINY_FILE, ModelDownloader.MIN_TINY) },
                fileOf = { ctx -> ModelDownloader.ggmlModelFile(ctx, ModelDownloader.TINY_FILE) },
                fetch = { ctx, on -> ModelDownloader.downloadTiny(ctx, on) },
                sizeLabel = "74 МБ",
                flatFileName = ModelDownloader.TINY_FILE,
                engineKind = SttEngineKind.WHISPER_GGML,
                languages = "multilingual",
            ),
            SttModel(
                id = BASE_ID,
                note = "Быстрый whisper · базовый fallback · RU средний",
                bytes = 147_951_465L,
                isReady = { ctx -> ModelDownloader.ggmlModelReady(ctx, ModelDownloader.BASE_FILE, ModelDownloader.MIN_BASE) },
                fileOf = { ctx -> ModelDownloader.ggmlModelFile(ctx, ModelDownloader.BASE_FILE) },
                fetch = { ctx, on -> ModelDownloader.downloadBase(ctx, on) },
                sizeLabel = "141 МБ",
                flatFileName = ModelDownloader.BASE_FILE,
                engineKind = SttEngineKind.WHISPER_GGML,
                languages = "multilingual",
            ),
            SttModel(
                id = WhisperTranscribeService.MODEL_SMALL,
                note = "2,3 с · ошибка в 3 % слов на фразе до 4 секунд",
                bytes = ModelDownloader.SMALL_BYTES,
                isReady = ModelDownloader::smallReady,
                fileOf = ModelDownloader::smallFile,
                fetch = { ctx, on -> ModelDownloader.downloadSmall(ctx, on) },
                sizeLabel = "465 МБ",
                flatFileName = ModelDownloader.SMALL_FILE,
                engineKind = SttEngineKind.WHISPER_GGML,
                languages = "multilingual",
            ),
            SttModel(
                id = PODLODKA_Q5_ID,
                note = "RU whisper-podlodka-turbo ggml q5_0 · сторонний GGML-квант (JoaoZaokk), проверять качество на CPH2747",
                bytes = ModelDownloader.PODLODKA_Q5_BYTES,
                isReady = { ctx -> ModelDownloader.ggmlModelReady(ctx, ModelDownloader.PODLODKA_Q5_FILE, ModelDownloader.MIN_PODLODKA_Q5) },
                fileOf = { ctx -> ModelDownloader.ggmlModelFile(ctx, ModelDownloader.PODLODKA_Q5_FILE) },
                fetch = { ctx, on -> ModelDownloader.downloadPodlodkaQ5(ctx, on) },
                sizeLabel = "547 МБ",
                flatFileName = ModelDownloader.PODLODKA_Q5_FILE,
                engineKind = SttEngineKind.WHISPER_GGML,
                languages = "ru",
            ),
            SttModel(
                id = PODLODKA_Q8_ID,
                note = "RU whisper-podlodka-turbo ggml q8_0 · выше качество/размер, сторонний GGML-квант (JoaoZaokk)",
                bytes = ModelDownloader.PODLODKA_Q8_BYTES,
                isReady = { ctx -> ModelDownloader.ggmlModelReady(ctx, ModelDownloader.PODLODKA_Q8_FILE, ModelDownloader.MIN_PODLODKA_Q8) },
                fileOf = { ctx -> ModelDownloader.ggmlModelFile(ctx, ModelDownloader.PODLODKA_Q8_FILE) },
                fetch = { ctx, on -> ModelDownloader.downloadPodlodkaQ8(ctx, on) },
                sizeLabel = "834 МБ",
                flatFileName = ModelDownloader.PODLODKA_Q8_FILE,
                engineKind = SttEngineKind.WHISPER_GGML,
                languages = "ru",
            ),
            SttModel(
                id = GIGAAM_RNNT_ID,
                note = "RU-first · sherpa-onnx RNNT · ~226 МБ · быстрый на CPU",
                bytes = 226L * MB,
                isReady = { ctx -> byIdStatic(ctx, GIGAAM_RNNT_ID) },
                fileOf = { ctx -> sherpaDir(ctx, GIGAAM_RNNT_ID) },
                fetch = { ctx, on -> byFetchStatic(ctx, GIGAAM_RNNT_ID, on) },
                sizeLabel = "~226 МБ",
                flatFileName = null,
                manual = false,
                engineKind = SttEngineKind.SHERPA_TRANSDUCER,
                dirName = GIGAAM_RNNT_ID,
                files =
                    listOf(
                        SttModelFile("encoder.int8.onnx", "$HF/Alexxerm/gigaam-v3-e2e-rnnt-sherpa-onnx/resolve/main/encoder.int8.onnx"),
                        SttModelFile("decoder.onnx", "$HF/Alexxerm/gigaam-v3-e2e-rnnt-sherpa-onnx/resolve/main/decoder.onnx"),
                        SttModelFile("joiner.onnx", "$HF/Alexxerm/gigaam-v3-e2e-rnnt-sherpa-onnx/resolve/main/joiner.onnx"),
                        SttModelFile("tokens.txt", "$HF/Alexxerm/gigaam-v3-e2e-rnnt-sherpa-onnx/resolve/main/tokens.txt"),
                    ),
                recommendedForRu = true,
                languages = "ru",
            ),
            SttModel(
                id = PARAKEET_ID,
                note = "Мультиязычный · sherpa-onnx RNNT · ~670 МБ · RU поддерживается",
                bytes = 670L * MB,
                isReady = { ctx -> byIdStatic(ctx, PARAKEET_ID) },
                fileOf = { ctx -> sherpaDir(ctx, PARAKEET_ID) },
                fetch = { ctx, on -> byFetchStatic(ctx, PARAKEET_ID, on) },
                sizeLabel = "~670 МБ",
                flatFileName = null,
                manual = false,
                engineKind = SttEngineKind.SHERPA_TRANSDUCER,
                dirName = PARAKEET_ID,
                files =
                    listOf(
                        SttModelFile("encoder.int8.onnx", "$HF/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/encoder.int8.onnx"),
                        SttModelFile("decoder.int8.onnx", "$HF/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/decoder.int8.onnx"),
                        SttModelFile("joiner.int8.onnx", "$HF/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/joiner.int8.onnx"),
                        SttModelFile("tokens.txt", "$HF/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/tokens.txt"),
                    ),
                recommendedForRu = false,
                languages = "multilingual (вкл. ru)",
            ),
            SttModel(
                id = VOSK_RU_054_ID,
                note = "Vosk RU 0.54 · Alpha Cephei · официальный пакет · нужен libvosk JNI/runtime (в текущем APK нет) · manual",
                bytes = 1_877_000_000L,
                isReady = { ctx -> byIdStatic(ctx, VOSK_RU_054_ID) },
                fileOf = { ctx -> sherpaDir(ctx, VOSK_RU_054_ID) },
                fetch = { _, _ -> error("Vosk 0.54: нужен libvosk runtime; добавь модель вручную в files/stt/vosk-model-ru-0.54 после интеграции") },
                sizeLabel = "~1.8 ГБ",
                flatFileName = null,
                manual = true,
                engineKind = SttEngineKind.WHISPER_GGML,
                dirName = VOSK_RU_054_ID,
                files = emptyList(),
                recommendedForRu = false,
                languages = "ru",
                runnable = false,
            ),
            SttModel(
                id = VOSK_RU_052_ID,
                note = "Vosk small RU 0.52 · Alpha Cephei · легче 0.54 · нужен libvosk JNI/runtime (в текущем APK нет) · manual",
                bytes = 1_500_000_000L,
                isReady = { ctx -> byIdStatic(ctx, VOSK_RU_052_ID) },
                fileOf = { ctx -> sherpaDir(ctx, VOSK_RU_052_ID) },
                fetch = { _, _ -> error("Vosk 0.52: нужен libvosk runtime; добавь модель вручную в files/stt/vosk-model-small-ru-0.52 после интеграции") },
                sizeLabel = "~1.5 ГБ",
                flatFileName = null,
                manual = true,
                engineKind = SttEngineKind.WHISPER_GGML,
                dirName = VOSK_RU_052_ID,
                files = emptyList(),
                recommendedForRu = false,
                languages = "ru",
                runnable = false,
            ),
            SttModel(
                id = ZIPFORMER_RU_ID,
                note = "Zipformer RU int8 2025-04-20 · sherpa-onnx transducer (alphacep/vosk-model-ru) · публичный HF, runtime подключён экспериментально",
                bytes = 73_476_000L,
                isReady = { ctx -> byIdStatic(ctx, ZIPFORMER_RU_ID) },
                fileOf = { ctx -> sherpaDir(ctx, ZIPFORMER_RU_ID) },
                fetch = { ctx, on -> byFetchStatic(ctx, ZIPFORMER_RU_ID, on) },
                sizeLabel = "~70 МБ",
                flatFileName = null,
                manual = false,
                engineKind = SttEngineKind.SHERPA_TRANSDUCER,
                dirName = ZIPFORMER_RU_ID,
                files =
                    listOf(
                        SttModelFile("encoder.int8.onnx", "$HF/csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20/resolve/main/encoder.int8.onnx", 70_876_638L),
                        SttModelFile("decoder.onnx", "$HF/csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20/resolve/main/decoder.onnx", 2_093_080L),
                        SttModelFile("joiner.int8.onnx", "$HF/csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20/resolve/main/joiner.int8.onnx", 259_417L),
                        SttModelFile("tokens.txt", "$HF/csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20/resolve/main/tokens.txt", 6_388L),
                    ),
                recommendedForRu = false,
                languages = "ru",
                runnable = true,
            ),
            SttModel(
                id = TONE_CTC_ID,
                note = "T-one (T-Tech) · streaming CTC RU · sherpa-onnx OnlineRecognizer · экспериментально",
                bytes = 128_111_859L,
                isReady = { ctx -> byIdStatic(ctx, TONE_CTC_ID) },
                fileOf = { ctx -> sherpaDir(ctx, TONE_CTC_ID) },
                fetch = { ctx, on -> byFetchStatic(ctx, TONE_CTC_ID, on) },
                sizeLabel = "~122 МБ",
                flatFileName = null,
                manual = false,
                engineKind = SttEngineKind.SHERPA_ONLINE_CTC,
                dirName = TONE_CTC_ID,
                files =
                    listOf(
                        SttModelFile(
                            "model.int8.onnx",
                            "$HF/csukuangfj/sherpa-onnx-streaming-t-one-russian-2025-09-08/resolve/main/model.int8.onnx",
                            128_111_523L,
                            "5ded080e2a6c86ecc11bcb0902d77524eb3e8b0844cb0c0754347f5aafb4dabc",
                        ),
                        SttModelFile(
                            "tokens.txt",
                            "$HF/csukuangfj/sherpa-onnx-streaming-t-one-russian-2025-09-08/resolve/main/tokens.txt",
                            202L,
                            "1b142114bebfc804e668fcba49159c1be7b21d3f",
                        ),
                    ),
                recommendedForRu = false,
                languages = "ru",
                runnable = true,
            ),
            SttModel(
                id = QWEN3_ASR_06B_ID,
                note = "Qwen3-ASR 0.6B int8 · sherpa-onnx offline · экспериментально, скрыт из обычного выбора: 9-WAV apk-132 avg WER 24.5%/CER 16.2%/pass 2567ms, 3/9 >3s; GigaAM лучше для RU",
                bytes = 986_554_365L,
                isReady = { ctx -> byIdStatic(ctx, QWEN3_ASR_06B_ID) },
                fileOf = { ctx -> sherpaDir(ctx, QWEN3_ASR_06B_ID) },
                fetch = { ctx, on -> byFetchStatic(ctx, QWEN3_ASR_06B_ID, on) },
                sizeLabel = "~986 МБ",
                flatFileName = null,
                manual = false,
                engineKind = SttEngineKind.SHERPA_QWEN3_ASR,
                dirName = QWEN3_ASR_06B_ID,
                files =
                    listOf(
                        SttModelFile("conv_frontend.onnx", "$HF/aoiandroid/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/conv_frontend.onnx", 44_148_281L, "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e"),
                        SttModelFile("encoder.int8.onnx", "$HF/aoiandroid/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/encoder.int8.onnx", 182_491_662L, "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9"),
                        SttModelFile("decoder.int8.onnx", "$HF/aoiandroid/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/decoder.int8.onnx", 755_914_231L, "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771"),
                        SttModelFile("tokenizer/vocab.json", "$HF/aoiandroid/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/tokenizer/vocab.json", 2_776_833L),
                        SttModelFile("tokenizer/merges.txt", "$HF/aoiandroid/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/tokenizer/merges.txt", 1_671_853L),
                        SttModelFile("tokenizer/tokenizer_config.json", "$HF/aoiandroid/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/tokenizer/tokenizer_config.json", 12_487L),
                    ),
                recommendedForRu = false,
                languages = "multilingual (вкл. ru)",
                runnable = true,
                hiddenFromPicker = true,
            ),
            SttModel(
                id = QWEN3_ASR_17B_ID,
                note = "Qwen3-ASR 1.7B · очень тяжёлая модель · высокий риск ОЗУ/латентности на телефоне",
                bytes = 3_400L * MB,
                isReady = { ctx -> byIdStatic(ctx, QWEN3_ASR_17B_ID) },
                fileOf = { ctx -> sherpaDir(ctx, QWEN3_ASR_17B_ID) },
                fetch = { _, _ -> error("Qwen3-ASR 1.7B: артефакт/рантайм не подключены автоматически; нужна проверка формата и лицензии") },
                sizeLabel = "~3.4 ГБ",
                flatFileName = null,
                manual = true,
                engineKind = SttEngineKind.SHERPA_TRANSDUCER,
                dirName = QWEN3_ASR_17B_ID,
                files = emptyList(),
                recommendedForRu = false,
                languages = "multilingual",
                runnable = false,
            ),
        )

    fun byId(id: String): SttModel? = all.firstOrNull { it.id == id }

    fun labelOf(id: String): String = byId(id)?.id ?: id

    /** Имена одиночных ggml-файлов в modelsDir, которые каталог считает своими. */
    val fileNames: Set<String> =
        all
            .mapNotNull { it.flatFileName }
            .toSet()

    /** Известные каталоги multi-file моделей в filesDir/stt/. */
    val sttDirNames: Set<String> =
        all
            .mapNotNull { it.dirName }
            .toSet()

    /** Готовность и размер всех моделей разом — только из IO-ветки. */
    fun snapshotOf(context: Context): Map<String, Long> =
        all
            .filter { it.isReady(context) }
            .associate { it.id to modelSizeOnDisk(context, it) }

    private fun modelSizeOnDisk(context: Context, model: SttModel): Long {
        val f = model.fileOf(context)
        if (f.isFile) return f.length()
        if (f.isDirectory) return f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return 0L
    }
}
