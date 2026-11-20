package org.opencode.mobile.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencode.mobile.net.FileFetcher
import org.opencode.mobile.stt.ModelDownloader
import java.io.File

/**
 * Хранение GGUF-моделей рядом с нативным `.so` (`files/musl/`).
 *
 * Модель НЕ входит в APK: 400 МБ в APK означали бы 400 МБ на каждом
 * обновлении даже для тех, кто LLM не использует. В APK лежит только
 * `assets/llm/models.json`, а файл приезжает по кнопке в диагностике.
 *
 * Готовность = маркер `.ok` + точный размер. Маркер ставится только после
 * сверки sha256, поэтому прерванная или подменённая закачка «готовой» не
 * считается. Хеш 400 МБ не пересчитывается при каждом опросе экрана —
 * ровно та же схема, что у [EngineStore].
 */
internal object GgufStore {
    private const val TAG = "GgufStore"
    private const val REL = "musl"
    private const val OK_SUFFIX = ".ok"

    /** Каталог общий с `libllama-bench.so`: рантайм и модель лежат рядом. */
    fun dir(context: Context): File = File(context.filesDir, REL).apply { mkdirs() }

    fun fileOf(
        context: Context,
        model: GgufModel,
    ): File = File(dir(context), model.file)

    private fun markerOf(
        context: Context,
        model: GgufModel,
    ): File = File(dir(context), model.file + OK_SUFFIX)

    /** Быстрая проверка без хеширования — только для UI и частых опросов. */
    fun isReady(
        context: Context,
        model: GgufModel,
    ): Boolean {
        val marker = markerOf(context, model)
        if (!marker.isFile || marker.readText().trim() != model.sha256) return false
        val target = fileOf(context, model)
        return target.isFile && target.length() == model.size
    }

    /** Сколько байт уже на диске: 0, если файла нет (для строк «готов/нет»). */
    fun bytesOnDisk(
        context: Context,
        model: GgufModel,
    ): Long = fileOf(context, model).length()

    fun problemOf(
        context: Context,
        model: GgufModel,
    ): String? =
        when {
            isReady(context, model) -> null
            ModelDownloader.freeBytes(context) <= model.size ->
                "Мало места: нужно ${"%.0f".format(model.size / MB)} МБ"
            else -> "модель не установлена"
        }

    /**
     * Скачать и принять модель. Докачка — через [FileFetcher] (`.part` + Range),
     * затем обязательная сверка размера И sha256. При расхождении файл удаляется:
     * оставить его «на всякий случай» значит однажды загрузить битый GGUF.
     */
    suspend fun download(
        context: Context,
        model: GgufModel,
        onProgress: (done: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val target = fileOf(context, model)
        if (ModelDownloader.freeBytes(context) <= model.size) {
            error("Мало свободного места: нужно ${"%.0f".format(model.size / MB)} МБ")
        }
        if (target.isFile && target.length() == model.size) {
            onProgress(model.size, model.size)
        } else {
            FileFetcher.fetch(model.url, target) { written -> onProgress(written, model.size) }
        }
        if (target.length() != model.size) {
            target.delete()
            error("Размер ${model.file} не совпал: ждали ${model.size}, получили ${target.length()}")
        }
        val got = ModelDownloader.sha256Of(target)
        if (got != model.sha256) {
            target.delete()
            markerOf(context, model).delete()
            Log.e(TAG, "sha256 mismatch: ${model.file}, got=$got")
            error("sha256 не совпал: ${model.file}. Файл удалён, попробуйте ещё раз.")
        }
        markerOf(context, model).writeText(model.sha256)
        Log.i(TAG, "gguf готов: ${model.file}, ${target.absolutePath}")
    }

    private const val MB = 1048576.0
}
