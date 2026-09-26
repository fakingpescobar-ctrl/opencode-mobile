package org.opencode.mobile.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
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

    private const val ENCODER_BIN_BYTES = 1_278_526_472L
    private const val ENCODER_PARAM_BYTES = 26_779L
    private const val ENCODER_INT8_BIN_BYTES = 728_023_304L
    private const val ENCODER_INT8_PARAM_BYTES = 27_782L
    private const val ENCODER_TABLE_BYTES = 1_457_688L
    private const val DECODER_BIN_BYTES = 210_063_552L
    private const val DECODER_PARAM_BYTES = 5_503L
    private const val FBANK_BIN_BYTES = 102_912L
    private const val FBANK_PARAM_BYTES = 871L
    private const val EMBED_TOKEN_BIN_BYTES = 132_776_964L
    private const val EMBED_TOKEN_PARAM_BYTES = 163L
    private const val EMBED_POSITION_BIN_BYTES = 1_146_884L
    private const val EMBED_POSITION_PARAM_BYTES = 159L
    private const val PROJ_OUT_BIN_BYTES = 132_776_964L
    private const val PROJ_OUT_PARAM_BYTES = 178L
    private const val VOCAB_BYTES = 444_543L

    /** Файлы набора и точные размеры (байт) из манифеста релиза. */
    private val ASSETS: Map<String, Long> =
        linkedMapOf(
            "whisper_turbo_encoder.ncnn.bin" to ENCODER_BIN_BYTES,
            "whisper_turbo_encoder.ncnn.param" to ENCODER_PARAM_BYTES,
            "whisper_turbo_encoder_int8.ncnn.bin" to ENCODER_INT8_BIN_BYTES,
            "whisper_turbo_encoder_int8.ncnn.param" to ENCODER_INT8_PARAM_BYTES,
            "whisper_turbo_encoder.table" to ENCODER_TABLE_BYTES,
            "whisper_turbo_decoder.ncnn.bin" to DECODER_BIN_BYTES,
            "whisper_turbo_decoder.ncnn.param" to DECODER_PARAM_BYTES,
            "whisper_turbo_fbank.ncnn.bin" to FBANK_BIN_BYTES,
            "whisper_turbo_fbank.ncnn.param" to FBANK_PARAM_BYTES,
            "whisper_turbo_embed_token.ncnn.bin" to EMBED_TOKEN_BIN_BYTES,
            "whisper_turbo_embed_token.ncnn.param" to EMBED_TOKEN_PARAM_BYTES,
            "whisper_turbo_embed_position.ncnn.bin" to EMBED_POSITION_BIN_BYTES,
            "whisper_turbo_embed_position.ncnn.param" to EMBED_POSITION_PARAM_BYTES,
            "whisper_turbo_proj_out.ncnn.bin" to PROJ_OUT_BIN_BYTES,
            "whisper_turbo_proj_out.ncnn.param" to PROJ_OUT_PARAM_BYTES,
            "whisper_vocab.txt" to VOCAB_BYTES,
        )

    /** Суммарный размер набора (для прогресса). */
    val TOTAL_BYTES: Long = ASSETS.values.sum()

    /** Минимально необходимое свободное место ПЕРЕД началом скачивания: набор
     *  ≈2.49 ГБ + запас на .part-хвосты и sidecar-ы. */
    private const val MIN_FREE_BYTES = 3_000L * 1024 * 1024 // ~3 ГБ
    private const val BYTES_PER_MIB = 1024L
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val DOWNLOAD_BUFFER_BYTES = 256 * 1024
    private const val HTML_SNIFF_BYTES = 512

    /** Как часто во время скачивания переспрашивать свободное место. Чаще - только stat(). */
    private const val SPACE_CHECK_INTERVAL_MS = 5_000L

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
        val directory = dir(context)
        return ASSETS.all { (name, expectedSize) ->
            val file = File(directory, name)
            if (!file.isFile || file.length() != expectedSize) {
                if (file.exists()) {
                    Log.w(TAG, "не готов: $name длина=${file.length()} эталон=$expectedSize")
                }
                return@all false
            }
            ModelDownloader.checkIntegrity(file) != ModelDownloader.ModelIntegrity.CORRUPT
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
        check(free <= 0L || free >= MIN_FREE_BYTES) {
            "Недостаточно места для ncnn-моделей: нужно ~2.9 ГБ, " +
                "свободно ${free / BYTES_PER_MIB / BYTES_PER_MIB} МБ"
        }

        var doneBytes = 0L
        // Уже скачанные (полные) файлы входят в прогресс с самого старта.
        ASSETS.forEach { (name, expectedSize) ->
            val file = File(d, name)
            if (file.isFile && file.length() == expectedSize) {
                doneBytes += expectedSize
                Log.d(TAG, "уже есть: $name")
            }
        }
        Log.d(TAG, "старт: скачано $doneBytes / $TOTAL_BYTES байт")

        // Страховка видит и будущие файлы, а не только текущий, - за счёт этого стоит
        // один require() на весь набор, а не пересчёт остатка в цикле.
        val spaceGuard = FreeSpaceGuard(d)
        spaceGuard.require()

        for ((name, expectedSize) in ASSETS) {
            val dest = File(d, name)
            if (dest.isFile && dest.length() == expectedSize) {
                // файл уже тут: если sha-sidecar есть — сверяем содержимое; если
                // CORRUPT — перекачиваем его (размер мог совпасть, байты чужие)
                if (ModelDownloader.checkIntegrity(dest) == ModelDownloader.ModelIntegrity.CORRUPT) {
                    Log.w(TAG, "$name: содержимое не сошлось — перекачка")
                    ModelDownloader.deleteModel(dest)
                } else {
                    continue
                }
            }
            downloadAsset(name, expectedSize, dest, spaceGuard::require) { added ->
                doneBytes += added
                onProgress(doneBytes, TOTAL_BYTES)
            }
        }
        return d
    }

    /**
     * Сторож свободного места на время скачивания.
     *
     * Проверка [MIN_FREE_BYTES] в начале набора честная ровно до первого чужого процесса:
     * за два с половиной гигабайта место успевает забрать и кэш, и «USB-подключение», и
     * простое фото. Тогда набор падал на 1.8 ГБ, а следующий заход упирался в остаток и
     * не мог даже начать. Сторож спрашивает `usableSpace` раз в [SPACE_CHECK_INTERVAL_MS] и
     * сравнивает с тем, что реально осталось докачать, а не с полным размером набора.
     *
     * Бросает, а не предупреждает: доиспользовать диск до состояния, при котором система
     * сама начинает отдавать еде, заметно хуже, чем честная ошибка с понятным текстом.
     * `.part` при этом остаётся, и повторный заход докачивает с места обрыва.
     */
    private class FreeSpaceGuard(
        private val dir: File,
        private val clock: () -> Long = System::currentTimeMillis,
    ) {
        private var nextCheckAt = 0L

        /** Бросает, если места меньше, чем нужно докачать СУММАРНО по всему набору. */
        fun require() {
            val now = clock()
            if (now < nextCheckAt) return
            nextCheckAt = now + SPACE_CHECK_INTERVAL_MS
            val free = dir.usableSpace
            val needed = remainingToDownload()
            if (!spaceIsShort(free, needed)) return
            error(
                "Место закончилось: осталось докачать ~" +
                    "${needed / BYTES_PER_MIB / BYTES_PER_MIB} МБ, свободно " +
                    "${free / BYTES_PER_MIB / BYTES_PER_MIB} МБ. Частично скачанное " +
                    "сохранено - освободи место и повтори, докачает с места обрыва.",
            )
        }

        /**
         * Сколько байт ещё нужно докачать. Полный файл не считается дважды: `.part` и
         * будущий `.bin` на его месте — это одни и те же байты, поэтому берётся меньшее
         * из двух. Считается по ВСЕМУ набору, а не по текущему файлу: страховка, которая
         * видит только текущий файл, последние мегабайты проверяет в вакууме.
         */
        private fun remainingToDownload(): Long =
            ASSETS.entries.sumOf { (assetName, size) ->
                val dest = File(dir, assetName)
                val have =
                    when {
                        dest.isFile && dest.length() == size -> size
                        else -> minOf(File(dir, "$assetName.part").length(), size)
                    }
                (size - have).coerceAtLeast(0L)
            }
    }

    /** Скачивает ОДИН файл с resume: .part + Range-хвост; при 200 (полный ответ)
     *  пересоздаёт .part с нуля; строгая сверка размера с манифестом. */
    private suspend fun downloadAsset(
        assetName: String,
        expectedSize: Long,
        dest: File,
        ensureSpace: () -> Unit,
        onAdded: (Long) -> Unit,
    ) {
        check(dest.parentFile != null) { "нет parent у ${dest.name}" }
        // .part выводится из dest, а не тащится параметром: он всегда "<имя>.part" рядом,
        // и лишний параметр рано или поздно разошёлся бы с dest при переименовании.
        val tmp = File(dest.parentFile, "$assetName.part")
        val existingBytes = tmp.length()
        val resuming = tmp.isFile && existingBytes in 1 until expectedSize
        val startAt = if (resuming) existingBytes else 0L
        val connection = openConnection(assetName, startAt, resuming)

        try {
            connection.connect()
            val responseCode = connection.responseCode
            validateResponse(connection, assetName, responseCode, resuming, startAt)

            // 200 при наличии Range: сервер отдал полный файл заново (или ревизия
            // сменилась) — хвост не склеиваем, строим с нуля.
            val effectiveStartAt = if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                startAt
            } else {
                tmp.delete()
                0L
            }
            val done = copyResponse(connection, tmp, effectiveStartAt, ensureSpace, onAdded)
            completeDownload(tmp, dest, expectedSize)
            Log.d(TAG, "скачан: $assetName ($done байт, sha256 ok)")
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(
        assetName: String,
        startAt: Long,
        resuming: Boolean,
    ): HttpURLConnection =
        (URL("$GITHUB_RELEASES/$assetName").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "Mozilla/5.0")
            if (resuming) setRequestProperty("Range", "bytes=$startAt-")
        }

    private fun validateResponse(
        connection: HttpURLConnection,
        assetName: String,
        responseCode: Int,
        resuming: Boolean,
        startAt: Long,
    ) {
        val unsupportedCode =
            responseCode != HttpURLConnection.HTTP_PARTIAL && responseCode != HttpURLConnection.HTTP_OK
        check(!unsupportedCode) {
            val range = if (resuming) startAt.toString() else "-"
            "HTTP $responseCode при скачивании $assetName (Range=$range)"
        }
        check(responseCode != HttpURLConnection.HTTP_PARTIAL || resuming) {
            "HTTP 206 без Range при скачивании $assetName"
        }

        val contentType = connection.contentType ?: ""
        val isHtmlResponse = contentType.contains("text/html", ignoreCase = true)
        val isTextResponse = contentType.contains("text/plain", ignoreCase = true)
        check(!isHtmlResponse && !isTextResponse) {
            "сервер вернул HTML/текст вместо $assetName " +
                "(content-type=$contentType) — проверь сеть/URL"
        }
    }

    private class CopyState(
        val file: File,
        val startAt: Long,
        var done: Long = startAt,
    )

    private suspend fun copyResponse(
        connection: HttpURLConnection,
        tmp: File,
        startAt: Long,
        ensureSpace: () -> Unit,
        onAdded: (Long) -> Unit,
    ): Long {
        val append = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
        val state = CopyState(tmp, startAt)
        connection.inputStream.use { input ->
            FileOutputStream(tmp, append).use { output ->
                copyInput(input, output, state, ensureSpace, onAdded)
            }
        }
        return state.done
    }

    private suspend fun copyInput(
        input: InputStream,
        output: OutputStream,
        state: CopyState,
        ensureSpace: () -> Unit,
        onAdded: (Long) -> Unit,
    ) {
        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
        while (true) {
            coroutineContext.ensureActive()
            // Дёшево: сторож внутри сам throttle-ится по времени, поэтому звать на каждом
            // буфере безопасно - между проверками остаётся ровно один System.currentTimeMillis.
            ensureSpace()
            val bytesRead = input.read(buffer)
            if (bytesRead < 0) break
            if (state.done == state.startAt && looksLikeHtml(buffer, bytesRead)) {
                state.file.delete()
                error("сервер вернул HTML вместо модели ${state.file.name} — проверь сеть/URL")
            }
            output.write(buffer, 0, bytesRead)
            state.done += bytesRead
            onAdded(bytesRead.toLong())
        }
    }

    private fun completeDownload(
        tmp: File,
        dest: File,
        expectedSize: Long,
    ) {
        // Жёсткая сверка с эталоном манифеста: обрыв на 99% файла сюда не пройдёт.
        check(tmp.length() == expectedSize) {
            "${tmp.name}: оборван (${tmp.length()} из $expectedSize байт) — повторный заход докачает"
        }

        // SHA-256 считается по tmp ДО rename: содержимое то же, sidecar пишется
        // уже под финальным именем.
        val digest = checkNotNull(ModelDownloader.sha256Of(tmp)) {
            "SHA-256 не посчитался для ${tmp.name}"
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            error("не удалось перенести ${tmp.name} в финальное имя")
        }
        ModelDownloader.writeManifest(dest, digest)
    }

    /** HTML вместо бинарного файла: первые 512 байт содержат маркеры страницы
     *  (GitHub error-page/капчи); у .param/.txt ложно-сработать не может — там
     *  текст без "<html", у .bin '<' юридически не в начале. */
    private fun looksLikeHtml(
        buf: ByteArray,
        len: Int,
    ): Boolean {
        val head = String(buf, 0, minOf(len, HTML_SNIFF_BYTES), Charsets.UTF_8)
        return head.contains("<!DOCTYPE", ignoreCase = true) ||
            head.contains("<html", ignoreCase = true)
    }
}

/**
 * Хватает ли места на остаток набора.
 *
 * Живёт вне `NcnnModelDownloader` не по вкусу, а ради честности: объект и так на пределе
 * detekt по числу функций, и проверка, которую обязаны покрыть тесты, не должна была бы
 * конкурировать с private-мелочами за место в лимите.
 *
 * Два края, где ответ «места хватает», и оба - по делу:
 *  - `usableSpace == 0` означает «не удалось определить», а не «мест нет»: на части
 *    окружений stat отдаёт ноль, и трактовка нуля как нехватки заблокировала бы
 *    скачивание там, где оно нормально работает;
 *  - когда качать нечего (`neededBytes == 0`), жаловаться не на что: запас в 100 МБ иначе
 *    превратил бы в ошибку обычный вход в скачивание при уже полном наборе.
 */
internal const val NCNN_SPACE_MARGIN_BYTES = 100L * 1024 * 1024

internal fun spaceIsShort(
    freeBytes: Long,
    neededBytes: Long,
): Boolean =
    neededBytes > 0L &&
        freeBytes > 0L &&
        freeBytes < neededBytes + NCNN_SPACE_MARGIN_BYTES
