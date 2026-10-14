// Размеры файлов модели — это ДАННЫЕ, а не настроечные числа: они пришли из
// манифеста релиза, и их правка руками означала бы, что каталог перестал
// соответствовать тому, что лежит на сервере. Ровно так же сделано в
// EngineCatalog.kt для размеров движков.
@file:Suppress("MagicNumber")

package org.opencode.mobile.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencode.mobile.net.FileFetcher
import org.opencode.mobile.stt.ModelDownloader
import java.io.File

/** Один файл модели озвучки: имя, точный размер и sha256. */
internal data class TtsModelFile(
    val name: String,
    val bytes: Long,
    val sha256: String,
)

/**
 * Скачивание офлайн-модели озвучки Supertonic.
 *
 * Модель весит 139 МБ, в APK её класть нельзя (там уже 229 МБ, из них
 * 196 — сервер opencode), поэтому она качается по кнопке, как и всё
 * остальное тяжёлое: движки ([org.opencode.mobile.engine.EngineStore]) и
 * модель распознавания ([org.opencode.mobile.stt.ModelDownloader]).
 *
 * Откуда: `sherpa-onnx` держит модель только внутри
 * `sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2`, а распаковать bzip2
 * на Android нечем. Поэтому файлы выложены отдельно в релиз `tts-supertonic-v1`
 * этого же репозитория, а точные размеры и хеши зашиты здесь: файл, скачанный
 * на 99%, «готовым» не считается, и при обрыве закачка продолжается с места
 * обрыва, а не начинается заново.
 *
 * Происхождение модели: `k2-fsa/sherpa-onnx`, релиз `tts-models` (архив
 * `sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2`, 128 774 318 б),
 * распакован локально в `bench/models/` 03.10.2026.
 */
internal object TtsModelDownloader {
    private const val TAG = "TtsModel"
    private const val REL = "tts-supertonic-v1"
    private const val BASE = "https://github.com/fakingpescobar-ctrl/opencode-mobile/releases/download/$REL/"

    /** На сколько больше файла нужно, чем сам файл: запас на докачку и `.part`. */
    private const val FREE_HEADROOM = 64L * 1024 * 1024

    val FILES = listOf(
        TtsModelFile("duration_predictor.int8.onnx", 3_700_147L, "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db"),
        TtsModelFile("text_encoder.int8.onnx", 36_416_150L, "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff"),
        TtsModelFile("vector_estimator.int8.onnx", 78_400_833L, "20cd86fa5c6effedfda0e7cffe5b0569ca401c440a0c3a1d72bf39286c0db3fd"),
        TtsModelFile("vocoder.int8.onnx", 25_991_073L, "e923d60f53f95eb1ce235f1dc33ec56d9c057823c96fa6f8acf98f32b0da6152"),
        TtsModelFile("tts.json", 8_253L, "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09"),
        TtsModelFile("unicode_indexer.bin", 262_144L, "8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30"),
        TtsModelFile("voice.bin", 517_168L, "67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8"),
    )

    val TOTAL_BYTES: Long = FILES.sumOf { it.bytes }

    /** «139 МБ» для кнопки в настройках. */
    fun sizeLabel(): String = "%.0f МБ".format(TOTAL_BYTES / MB)

    /** Каталог модели: тот же, что ждёт движок ([TtsModels.SUPERTONIC]). */
    fun dir(context: Context): File = File(TtsModels.root(context), TtsModels.SUPERTONIC).apply { mkdirs() }

    /**
     * Готова ли модель. Проверяем ТОЧНЫЙ размер каждого файла, а не
     * «файл существует»: оборванная закачка оставляет файл почти правильного
     * размера, и движок потом падает с невнятной ошибкой внутри линкера.
     */
    fun isReady(context: Context): Boolean = FILES.all { f -> File(dir(context), f.name).length() == f.bytes }

    /** Человеческое объяснение, почему озвучка молчит, либо null если модель на месте. */
    fun problemOf(context: Context): String? =
        when {
            isReady(context) -> null
            !freeSpaceOk(context) -> "Мало места: нужно ${sizeLabel()}"
            else -> "Модель озвучки не скачана"
        }

    /** Сколько модель занимает на диске, для диагностики. */
    fun bytesOnDisk(context: Context): Long =
        TtsModels
            .root(context)
            .walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

    private fun freeSpaceOk(context: Context): Boolean = ModelDownloader.freeBytes(context) > TOTAL_BYTES + FREE_HEADROOM

    /**
     * Скачать недостающие файлы. Уже скачанные пропускаются, после каждого
     * файла сверяется sha256: не сошёлся — файл удаляется и закачка падает с
     * просьбой повторить. В конце снимается запрет повторной загрузки движка
     * ([SupertonicTts.resetLoadAttempt]), иначе после прошлой неудачи озвучка
     * молчала бы ещё минуту после только что скачанной модели.
     */
    suspend fun download(
        context: Context,
        onProgress: (done: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        // Место проверяем ДО старта: оборванная на середине закачка 138 МБ
        // оставит мусор в .part и выдаст ошибку уже в середине, когда
        // пользователь ничего не может сделать. Здесь он узнаёт сразу.
        if (!freeSpaceOk(context)) error("Мало свободного места: нужно ${sizeLabel()}")
        val d = dir(context)
        var done = 0L
        for (file in FILES) {
            val target = File(d, file.name)
            if (target.isFile && target.length() == file.bytes) {
                done += file.bytes
                onProgress(done, TOTAL_BYTES)
                continue
            }
            FileFetcher.fetch(BASE + file.name, target) { written -> onProgress(done + written, TOTAL_BYTES) }
            val got = ModelDownloader.sha256Of(target)
            if (got != file.sha256) {
                target.delete()
                Log.e(TAG, "sha256 не совпал: ${file.name}, получено ${got ?: "нет"}")
                error("Модель повреждена при скачивании: ${file.name}. Попробуйте ещё раз.")
            }
            done += file.bytes
            onProgress(done, TOTAL_BYTES)
        }
        SupertonicTts.resetLoadAttempt()
        Log.i(TAG, "модель озвучки готова: ${d.absolutePath}, ${bytesOnDisk(context)} б")
    }

    private const val MB = 1048576.0
}
