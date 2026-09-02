package org.opencode.mobile.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
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

    /** Порог «не оборван»: файл не считается готовым, пока меньше минимума байт. */
    private const val MIN_BASE = 140L * 1024 * 1024
    private const val MIN_TURBO = 500L * 1024 * 1024

    /** Папка каталога моделей внутри filesDir: <filesDir>/models/. */
    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    // ---- base ----

    fun baseFile(context: Context): File = File(modelsDir(context), BASE_FILE)

    /** Докачана ли base (файл существует и >140MB — не оборванный). */
    fun baseReady(context: Context): Boolean {
        val f = baseFile(context)
        return f.exists() && f.length() > MIN_BASE
    }

    /** Скачивает base-модель в filesDir/models. Блокирующий (suspend). */
    suspend fun downloadBase(
        context: Context,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File = downloadTo(context, URL_BASE, baseFile(context), MIN_BASE, onProgress)

    // ---- turbo ----

    fun turboFile(context: Context): File = File(modelsDir(context), TURBO_FILE)

    /** Докачана ли turbo (файл существует и >500MB — не оборванный). */
    fun turboReady(context: Context): Boolean {
        val f = turboFile(context)
        return f.exists() && f.length() > MIN_TURBO
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
    ): File = withContext(Dispatchers.IO) {
        if (dest.exists() && dest.length() > minBytes) {
            Log.d(TAG, "уже докачана: ${dest.absolutePath}")
            return@withContext dest
        }

        val tmp = File(modelsDir(context), "${dest.name}.part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Mozilla/5.0")
            // Дозапись оборванной части (resume) снижает трафик при разрывах.
            if (tmp.exists() && tmp.length() > 0) {
                setRequestProperty("Range", "bytes=${tmp.length()}-")
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

            val totalBytes: Long =
                if (tmp.exists() && code == HttpURLConnection.HTTP_PARTIAL) tmp.length() + total else total
            Log.d(TAG, "скачиваю ${dest.name}, http=$code, осталось=$total, уже есть=${if (code == 206) tmp.length() else 0}")

            var done = if (tmp.exists() && code == HttpURLConnection.HTTP_PARTIAL) tmp.length() else 0L
            val out = FileOutputStream(tmp, true) // append при resume
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

            // Целиком скачан → атомарно переносим в финальное имя.
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            Log.d(TAG, "скачана: ${dest.absolutePath} (${dest.length() / 1024 / 1024}MB)")
            dest
        } finally {
            conn.disconnect()
        }
    }
}