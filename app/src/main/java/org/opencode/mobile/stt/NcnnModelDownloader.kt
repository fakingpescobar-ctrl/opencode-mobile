package org.opencode.mobile.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Скачивание ncnn-turbo набора моделей из GitHub Releases по требованию.
 *
 * В отличие от ggml-моделей (ModelDownloader) ncnn-набор — это 16 файлов в
 * подкаталоге models/ncnn-turbo/ (encoder int8 + fp32, decoder, fbank,
 * embed_token/position, proj_out, vocab). Точные размеры ФИНАЛЬНОГО релиза
 * [REL_TAG] зашиты в манифест [ASSETS] — поэтому:
 *  - готовность проверяется жёстко: существование каждого файла И длина ==
 *    размер из манифеста (оборванный на 99% файл готовым не считается);
 *  - после скачивания дополнительно пишется постоянный SHA-256 sidecar
 *    (механика ModelDownloader.writeManifest) — следующий запуск сверяет
 *    содержимое, а не только размер.
 *
 * APK остаётся маленьким: набор (≈2.49 ГБ) НЕ в assets, качается user-ом
 * один раз в filesDir (само-обновление релиза — через манифест-хеши в
 * будущей версии; текущий [REL_TAG] фиксирован).
 */
object NcnnModelDownloader {
    private const val TAG = "NcnnDL"

    /** Тег GitHub-релиза с опубликованными моделями (16 assets, 2370 MiB). */
    const val REL_TAG = "models-ncnn-turbo-v1"

    private const val GITHUB_RELEASES =
        "https://github.com/fakingpescobar-ctrl/opencode-mobile/releases/download/$REL_TAG"

    /** Файл набора: имя в каталоге модели → точный размер (байт) релиза. Размеры
     *  сняты с локальной копии tools/ncnn-int8/turbo (25.09.2026) и совпадают с
     *  опубликованными assets. */
    private data class Asset(val name: String, val size: Long)

    private val ASSETS: List<Asset> =
        listOf(
            Asset("whisper_turbo_encoder.ncnn.bin", 1_278_526_472L),
            Asset("whisper_turbo_encoder.ncnn.param", 26_779L),
            Asset("whisper_turbo_encoder_int8.ncnn.bin", 728_023_304L),
            Asset("whisper_turbo_encoder_int8.ncnn.param", 27_782L),
            Asset("whisper_turbo_encoder.table", 1_457_688L),
            Asset("whisper_turbo_decoder.ncnn.bin", 210_063_552L),
            Asset("whisper_turbo_decoder.ncnn.param", 5_503L),
            Asset("whisper_turbo_fbank.ncnn.bin", 102_912L),
            Asset("whisper_turbo_fbank.ncnn.param", 871L),
            Asset("whisper_turbo_embed_token.ncnn.bin", 132_776_964L),
            Asset("whisper_turbo_embed_token.ncnn.param", 163L),
            Asset("whisper_turbo_embed_position.ncnn.bin", 1_146_884L),
            Asset("whisper_turbo_embed_position.ncnn.param", 159L),
            Asset("whisper_turbo_proj_out.ncnn.bin", 132_776_964L),
            Asset("whisper_turbo_proj_out.ncnn.param", 178L),
            Asset("whisper_vocab.txt", 444_543L),
        )

    /** Суммарный размер набора (для прогресса). */
    val TOTAL_BYTES: Long = ASSETS.sumOf { it.size }

    /** Минимально необходимое свободное место ПЕРЕД началом скачивания: набор
     *  ≈2.49 ГБ + запас на .part-хвосты и sidecar-ы. */
    private const val MIN_FREE_BYTES = 3_000L * 1024 * 1024 // ~3 ГБ

    /** Каталог набора: <filesDir>/models/ncnn-turbo/. */
    fun dir(context: Context): File = File(ModelDownloader.modelsDir(context), "ncnn-turbo").apply { mkdirs() }

    /**
     * Готовность набора:
     *  1. [NcnnModelValidator.checkTurbo] — все обязательные файлы присутствуют
     *     (ровно тот набор, что грузит ncnn_jni.cpp);
     *  2. каждый файл манифеста существует И длина == эталону релиза (обрыв/подмена
     *     размера = не готов);
     *  3. если есть постоянный SHA-256 sidecar — файл не повреждён (проверка
     *     содержимого; для вручную adb push-нутых моделей sidecar нет — верим
     *     манифесту размеров, как и валидатору).
     */
    fun isTurboReady(context: Context): Boolean {
        if (!NcnnModelValidator.isTurboReady(context)) return false
        val d = dir(context)
        return ASSETS.all { asset ->
            val f = File(d, asset.name)
            if (!f.isFile || f.length() != asset.size) {
                if (f.exists()) Log.w(TAG, "не готов: ${asset.name} длина=${f.length()} эталон=${asset.size}")
                return@all false
            }
            ModelDownloader.checkIntegrity(f) != ModelDownloader.ModelIntegrity.CORRUPT
        }
    }

    /**
     * Скачивает полный набор (последовательно, с resume по файлам) в
     * models/ncnn-turbo/. Блокирующий (suspend); при сбое бросает Exception —
     * частично скачанные .part-файлы ОСТАЮТСЯ (следующий заход докачает с места
     * обрыва, а не начнёт 2.5 ГБ заново).
     *
     * @param onProgress суммарный прогресс по ВСЕМУ набору: (doneBytes, TOTAL_BYTES)
     */
    suspend fun downloadTurbo(
        context: Context,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        val d = dir(context)
        val free = context.filesDir.usableSpace
        if (free > 0 && free < MIN_FREE_BYTES) {
            throw IllegalStateException(
                "Недостаточно места для ncnn-моделей: нужно ~2.9 ГБ, свободно ${free / 1024 / 1024} МБ",
            )
        }

        var doneBytes = 0L
        // Уже скачанные (полные) файлы входят в прогресс с самого старта.
        ASSETS.forEach { asset ->
            val f = File(d, asset.name)
            if (f.isFile && f.length() == asset.size) {
                doneBytes += asset.size
                Log.d(TAG, "уже есть: ${asset.name}")
            }
        }
        Log.d(TAG, "старт: скачано $doneBytes / $TOTAL_BYTES байт")

        for (asset in ASSETS) {
            val dest = File(d, asset.name)
            if (dest.isFile && dest.length() == asset.size) {
                // файл уже тут: если sha-sidecar есть — сверяем содержимое; если
                // CORRUPT — перекачиваем его (размер мог совпасть, байты чужие)
                if (ModelDownloader.checkIntegrity(dest) == ModelDownloader.ModelIntegrity.CORRUPT) {
                    Log.w(TAG, "${asset.name}: содержимое не сошлось — перекачка")
                    ModelDownloader.deleteModel(dest)
                } else {
                    continue
                }
            }
            val part = File(d, "${asset.name}.part")
            downloadAsset(context, asset, dest, part) { added ->
                doneBytes += added
                onProgress(doneBytes, TOTAL_BYTES)
            }
        }
        return d
    }

    /** Скачивает ОДИН файл с resume: .part + Range-хвост; при 200 (полный ответ)
     *  пересоздаёт .part с нуля; строгая сверка done == эталон манифеста. */
    private suspend fun downloadAsset(
        context: Context,
        asset: Asset,
        dest: File,
        tmp: File,
        onAdded: (Long) -> Unit,
    ) {
        val dir = dest.parentFile ?: error("нет parent у ${dest.name}")
        var resuming = tmp.isFile && tmp.length() in 1 until asset.size
        var startAt = if (resuming) tmp.length() else 0L

        val conn = (URL("$GITHUB_RELEASES/${asset.name}").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Mozilla/5.0")
            if (resuming) setRequestProperty("Range", "bytes=$startAt-")
        }

        try {
            conn.connect()
            val code = conn.responseCode
            if ((code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) ||
                (code == HttpURLConnection.HTTP_PARTIAL && !resuming)
            ) {
                throw IllegalStateException("HTTP $code при скачивании ${asset.name} (Range=${if (resuming) startAt else "-"})")
            }
            val ct = conn.contentType ?: ""
            if (ct.contains("text/html", ignoreCase = true) || ct.contains("text/plain", ignoreCase = true)) {
                throw IllegalStateException("сервер вернул HTML/текст вместо ${asset.name} (content-type=$ct) — проверь сеть/URL")
            }

            // 200 при наличии Range: сервер отдал полный файл заново (или ревизия
            // сменилась) — хвост не склеиваем, строим с нуля.
            val append = code == HttpURLConnection.HTTP_PARTIAL
            if (!append) {
                tmp.delete()
                startAt = 0L
            }

            var done = startAt
            val out = FileOutputStream(tmp, append)
            conn.inputStream.use { input ->
                out.use { fos ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        if (done == startAt && looksLikeHtml(buf, n)) {
                            tmp.delete()
                            throw IllegalStateException("сервер вернул HTML вместо модели ${asset.name} — проверь сеть/URL")
                        }
                        fos.write(buf, 0, n)
                        done += n
                        onAdded(n.toLong())
                    }
                }
            }

            // Жёсткая сверка с эталоном манифеста: обрыв на 99% файла сюда не пройдёт.
            if (done != asset.size) {
                throw IllegalStateException("${asset.name}: оборван ($done из ${asset.size} байт) — повторный заход докачает")
            }

            // SHA-256 снапшот (по tmp ДО rename — содержимое то же) через общую
            // механику ModelDownloader; манифест пишется под финальным именем.
            val digest = ModelDownloader.sha256Of(tmp)
            if (digest == null) {
                throw IllegalStateException("SHA-256 не посчитался для ${tmp.name}")
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                throw IllegalStateException("не удалось перенести ${tmp.name} в финальное имя")
            }
            ModelDownloader.writeManifest(dest, digest)
            Log.d(TAG, "скачан: ${asset.name} (${asset.size} байт, sha256 ok)")
        } finally {
            conn.disconnect()
        }
    }

    /** HTML вместо бинарного файла: первые 512 байт содержат маркеры страницы
     *  (GitHub error-page/капчи); у .param/.txt ложно-сработать не может — там
     *  текст без "<html", у .bin '<' юридически не в начале. */
    private fun looksLikeHtml(
        buf: ByteArray,
        len: Int,
    ): Boolean {
        val head = String(buf, 0, minOf(len, 512), Charsets.UTF_8)
        return head.contains("<!DOCTYPE", ignoreCase = true) ||
            head.contains("<html", ignoreCase = true)
    }
}