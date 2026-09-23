package org.opencode.mobile.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Скачивание whisper-моделей на устройство по требованию.
 *
 * APK остаётся маленьким: и base, и turbo НЕ кладём в assets, а качаем в
 * filesDir/models (по выбору в настройках STT). Загруженный файл живёт между
 * запусками — повторно качаем только если его нет.
 *
 * - base  = ggerganov/whisper.cpp ggml-base.bin      (141 047 000 б ≈ 141 МБ)
 * - turbo = ggerganov/whisper.cpp ggml-large-v3-turbo-q5_0.bin (574 МБ)
 */
object ModelDownloader {
    private const val TAG = "ModelDL"

    private const val HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
    private const val URL_TURBO = "$HF_BASE/ggml-large-v3-turbo-q5_0.bin"
    private const val URL_BASE = "$HF_BASE/ggml-base.bin"

    /** Имена файлов моделей на диске устройства. */
    const val TURBO_FILE = "ggml-large-v3-turbo-q5_0.bin"
    const val BASE_FILE = "ggml-base.bin"

    /**
     * Fallback-пороги «не оборван», когда sidecar с точным размером ещё нет
     * (см. [expectedSizeFile]): чуть НИЖЕ реальных размеров (~134.5MiB / ~547MiB),
     * т.к. предел выше реального размера навсегда заблокировал бы *Ready(). Точные
     * сверки после скачивания идут по sidecar-размеру (см. downloadTo).
     */
    private const val MIN_BASE = 130L * 1024 * 1024
    private const val MIN_TURBO = 540L * 1024 * 1024

    /** Папка каталога моделей внутри filesDir: <filesDir>/models/. */
    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    /**
     * Сериализация скачиваний: downloadBase/downloadTurbo могут прийти параллельно
     * (UI + сервис); общий .part-файл и общий выходной файл — общие ресурсы.
     */
    private val downloadMutex = Mutex()

    // ---- base ----

    fun baseFile(context: Context): File = File(modelsDir(context), BASE_FILE)

    /** Докачана ли base (файл существует и >130MB — не оборванный; точный
     * размер — из sidecar, если он есть). */
    fun baseReady(context: Context): Boolean {
        val f = baseFile(context)
        return f.exists() && f.length() >= readyThreshold(f, MIN_BASE)
    }

    /** Скачивает base-модель в filesDir/models. Блокирующий (suspend). */
    suspend fun downloadBase(
        context: Context,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File = downloadTo(context, URL_BASE, baseFile(context), MIN_BASE, onProgress)

    // ---- turbo ----

    fun turboFile(context: Context): File = File(modelsDir(context), TURBO_FILE)

    /** Докачана ли turbo (файл существует и >540MB — не оборванный; точный
     * размер — из sidecar, если он есть). */
    fun turboReady(context: Context): Boolean {
        val f = turboFile(context)
        return f.exists() && f.length() >= readyThreshold(f, MIN_TURBO)
    }

    /** Скачивает turbo-модель в filesDir/models. Блокирующий (suspend). */
    suspend fun downloadTurbo(
        context: Context,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File = downloadTo(context, URL_TURBO, turboFile(context), MIN_TURBO, onProgress)

    /**
     * Общий загрузчик: качает url в dest (с resume и progress). При любом сбое
     * бросает Exception; временный файл чистится только при полном срыве.
     */
    private suspend fun downloadTo(
        context: Context,
        url: String,
        dest: File,
        minBytes: Long,
        onProgress: (Long, Long) -> Unit
    ): File = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            if (dest.exists() && dest.length() > minBytes) {
                Log.d(TAG, "уже докачана: ${dest.absolutePath}")
                return@withContext dest
            }

            val tmp = File(modelsDir(context), "${dest.name}.part")
            val partial = tmp.exists() && tmp.length() > 0
            // Один снапшот длины на весь запрос: и для Range, и для сверок ниже
            // (защита от TOCTOU, если файл урезали извне между чтениями).
            val startAt = if (partial) tmp.length() else 0L
            // ETag первого скачивания (sidecar рядом с .part) → If-Range при resume:
            // сервер вернёт 206 только если ревизия НЕ менялась; если файл перезалили —
            // 200 полного файла, и Guard 1 ниже пересоберёт с нуля. Это единственный
            // полный способ отличить «тот же файл» от «чужой ревизии той же длины».
            val etagFile = File(modelsDir(context), "${dest.name}.part.etag")
            // Точный ожидаемый размер (из Content-Length первого 200-ответа): после
            // скачивания сверяем done с ним ±0.5%. Без этого обрыв на 99% файла мог бы
            // пройти константный порог MIN_TURBO и «валидная» битая модель упала бы в whisper.
            val sizeFile = File(modelsDir(context), "${dest.name}.part.size")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "Mozilla/5.0")
                // Дозапись оборванной части (resume) снижает трафик при разрывах.
                if (partial) {
                    setRequestProperty("Range", "bytes=$startAt-")
                    if (etagFile.exists()) {
                        val etag = etagFile.readText().trim()
                        if (etag.isNotEmpty()) setRequestProperty("If-Range", etag)
                    }
                }
            }

            try {
                conn.connect()
                val code = conn.responseCode
                val total: Long = when (code) {
                    HttpURLConnection.HTTP_PARTIAL -> conn.contentLengthLong // resume-хвост
                    HttpURLConnection.HTTP_OK -> conn.contentLengthLong
                    else -> throw IllegalStateException("HTTP $code при скачивании ${dest.name}")
                }

                // Guard 1: сервер может проигнорировать Range и ответить 200 — тогда в теле
                // ПОЛНЫЙ файл с начала, а не хвост. Дозапись его к оборванному .part дала бы
                // битую модель. При 200 всегда пересоздаём .part с нуля. Если пришел 200
                // при наличии If-Range — значит ревизия изменилась, фиксируем новый ETag.
                var resuming = code == HttpURLConnection.HTTP_PARTIAL
                if (code == HttpURLConnection.HTTP_OK) {
                    if (partial) {
                        Log.w(TAG, "HTTP 200 (If-Range/Range → новая ревизия): пересобираю ${dest.name} с нуля")
                    }
                    tmp.delete()
                    saveEtag(conn.getHeaderField("ETag"), etagFile)
                    saveExpectedSize(total, sizeFile)
                }

                // Guard 2: без ETag-гарантии НЕ доверяем хвосту даже при совпавшем Content-Range
                // (ревизия могла смениться незаметно: длины совпали, байты другие),
                // и не доверяем, если начало хвоста не совпало с .part.
                if (resuming) {
                    val crStart = parseContentRangeStart(conn.getHeaderField("Content-Range"))
                    if (!etagFile.exists() || crStart != null && crStart != startAt) {
                        Log.w(
                            TAG,
                            "206 без etag-гарантии (start=$crStart, ждали $startAt, etag=${etagFile.exists()}) — перекачка с нуля"
                        )
                        resuming = false
                        tmp.delete()
                        etagFile.delete()
                        sizeFile.delete()
                    }
                }

                if (!tmp.exists() || tmp.length() == 0L) resuming = false

                val doneStart = if (resuming) startAt else 0L
                // contentLengthLong может быть -1 (chunked): прогресс без общего знаменателя.
                val reportedTotal = if (total < 0) 0L else total
                val totalBytes = if (resuming) doneStart + reportedTotal else reportedTotal
                Log.d(TAG, "скачиваю ${dest.name}, http=$code, осталось=$total, уже есть=$doneStart")

                var done = doneStart
                // append (true) ТОЛЬКО при честном 206 — иначе полный 200-ответ ляжет поверх хвоста.
                val out = FileOutputStream(tmp, resuming)
                conn.inputStream.use { input ->
                    out.use { fos ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            done += n
                            onProgress(done, totalBytes)
                        }
                    }
                }

                if (done < minBytes) {
                    throw IllegalStateException("файл оборван: $done байт (min=$minBytes)")
                }

                // Точная сверка с ожидаемым размером из sidecar (Content-Length первого
                // 200-ответа): Content-Length точен, поэтому равенство строгое — ловит
                // «чистый» обрыв, при котором сервер закрыл поток на 99% файла, а
                // константный порог MIN_TURBO такой обрыв бы пропустил.
                val expected = readExpectedSize(sizeFile)
                if (expected != null && expected > 0 && done != expected) {
                    throw IllegalStateException("размер не сошёлся: $done != $expected")
                }

                // Целиком скачан → атомарно переносим в финальное имя. Если renameTo
                // не дался (кросс-ФС/FUSE) — fallback копированием в временный
                // файл и rename; при сбое чистим всё, чтобы не оставить ни
                // недописанный dest, ни битый tmp.
                if (!tmp.renameTo(dest)) {
                    val destTmp = File(dest.parentFile, "${dest.name}.tmp-final")
                    try {
                        tmp.copyTo(destTmp, overwrite = true)
                        destTmp.renameTo(dest)
                    } catch (e: Exception) {
                        dest.delete()
                        destTmp.delete()
                        throw e
                    }
                    tmp.delete()
                }
                // Скачивание завершено: sidecar-ы больше не нужны (следующий полный
                // заход запишет свежие). Ошибка удаления не критична.
                etagFile.delete()
                sizeFile.delete()
                Log.d(TAG, "скачана: ${dest.absolutePath} (${dest.length() / 1024 / 1024}MB)")
                dest
            } finally {
                conn.disconnect()
            }
        }
    }

    /** "bytes 1234-999999/1000000" → 1234; null если заголовка/парсинга нет. */
    private fun parseContentRangeStart(header: String?): Long? {
        if (header == null) return null
        val m = Regex("""bytes\s+(\d+)-""").find(header) ?: return null
        return m.groupValues[1].toLongOrNull()
    }

    /**
     * Пишет ETag ответа в sidecar; если сервер ETag не отдал или он слабый
     * (W/"..." — не гарантирует байтовую идентичность) — удаляет sidecar,
     * чтобы resume не доверял недоказанной ревизии.
     */
    private fun saveEtag(etag: String?, etagFile: File) {
        try {
            if (etag != null && etag.isNotEmpty() && !etag.startsWith("W/")) {
                etagFile.writeText(etag)
            } else {
                etagFile.delete()
            }
        } catch (_: Exception) {
            // sidecar — best-effort, скачивание от него не зависит
        }
    }

    /** Пишет ожидаемый размер в sidecar; при -1 (chunked) размер неизвестен — удаляет. */
    private fun saveExpectedSize(total: Long, sizeFile: File) {
        try {
            if (total > 0) {
                sizeFile.writeText(total.toString())
            } else {
                sizeFile.delete()
            }
        } catch (_: Exception) {
            // best-effort
        }
    }

    /** Читает ожидаемый размер из sidecar; null если sidecar нет/битый. */
    private fun readExpectedSize(sizeFile: File): Long? {
        if (!sizeFile.exists()) return null
        return try {
            sizeFile.readText().trim().toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Порог «докачана» для *Ready(): точный размер из sidecar (если есть — значит
     * загрузка завершалась и размер известен), иначе fallback-константа.
     */
    private fun readyThreshold(dest: File, fallback: Long): Long {
        val sizeFile = File(dest.parentFile, "${dest.name}.part.size")
        return readExpectedSize(sizeFile) ?: fallback
    }
}