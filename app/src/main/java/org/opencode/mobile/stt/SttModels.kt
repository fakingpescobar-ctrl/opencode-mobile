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
