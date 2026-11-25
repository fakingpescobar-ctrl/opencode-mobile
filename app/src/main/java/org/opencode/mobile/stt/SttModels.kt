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
    SHERPA_SENSE_VOICE,
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

    /** sherpa-onnx nemo_transducer (Parakeet). */
    const val PARAKEET_ID = "parakeet-tdt-0.6b-v3"

    /** GigaAM v3 E2E RNNT, sherpa-onnx nemo_transducer. RU-first. */
    const val GIGAAM_RNNT_ID = "gigaam-v3-e2e-rnnt"

    /** SenseVoice small int8, sherpa-onnx sense_voice. */
    const val SENSEVOICE_SMALL_ID = "sensevoice-small-int8"

    private const val HF = "https://huggingface.co"
    private const val MB = 1024L * 1024L

    private fun sherpaDir(context: Context, dir: String): File = File(File(context.filesDir, "stt"), dir)

    private fun sherpaReady(context: Context, model: SttModel): Boolean = ModelDownloader.sttModelReady(context, model)

    private fun sherpaFetch(model: SttModel): suspend (Context, (Long, Long) -> Unit) -> Unit = { ctx, on ->
        ModelDownloader.downloadSttModel(ctx, model, on)
    }

    val all: List<SttModel> =
        listOf(
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
                id = SENSEVOICE_SMALL_ID,
                note = "Мультиязычный · sherpa-onnx SenseVoice · ~239 МБ · RU есть, не RU-first",
                bytes = 239L * MB,
                isReady = { ctx -> byIdStatic(ctx, SENSEVOICE_SMALL_ID) },
                fileOf = { ctx -> sherpaDir(ctx, SENSEVOICE_SMALL_ID) },
                fetch = { ctx, on -> byFetchStatic(ctx, SENSEVOICE_SMALL_ID, on) },
                sizeLabel = "~239 МБ",
                flatFileName = null,
                manual = false,
                engineKind = SttEngineKind.SHERPA_SENSE_VOICE,
                dirName = SENSEVOICE_SMALL_ID,
                files =
                    listOf(
                        SttModelFile("model.int8.onnx", "$HF/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx"),
                        SttModelFile("tokens.txt", "$HF/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt"),
                    ),
                recommendedForRu = false,
                languages = "multilingual (вкл. ru)",
                runnable = true,
            ),
        )

    private fun byIdStatic(context: Context, id: String): Boolean {
        val m = all.firstOrNull { it.id == id } ?: return false
        return ModelDownloader.sttModelReady(context, m)
    }

    private suspend fun byFetchStatic(context: Context, id: String, on: (Long, Long) -> Unit) {
        val m = all.firstOrNull { it.id == id } ?: return
        ModelDownloader.downloadSttModel(context, m, on)
    }

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
