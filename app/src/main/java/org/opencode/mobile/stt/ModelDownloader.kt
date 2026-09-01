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
 * Скачивание whisper-модели large-v3-turbo на устройство.
 *
 * APK остаётся маленьким: turbo (574MB) НЕ кладём в assets, а качаем по
 * требованию в filesDir (по выбору в настройках STT). Загруженный файл
 * живёт между запусками — повторно качаем только если его нет.
 *
 * URL: ggerganov/whisper.cpp ggml-large-v3-turbo-q5_0.bin (574 041 195 байт).
 */
object ModelDownloader {
    private const val TAG = "ModelDL"
    private const val URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin"

    /** Имя файла модели на диске устройства + в assets (base). */
    const val TURBO_FILE = "ggml-large-v3-turbo-q5_0.bin"
    const val BASE_ASSET = "ggml-base.bin"

    /** Папка каталога моделей внутри filesDir: <filesDir>/models/. */
    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    /** Финальный путь turbo-модели (или null, если ещё не скачана). */
    fun turboFile(context: Context): File = File(modelsDir(context), TURBO_FILE)

    /** Докачана ли turbo (файл существует и больше 500MB — не оборванный). */
    fun turboReady(context: Context): Boolean {
        val f = turboFile(context)
        return f.exists() && f.length() > 500L * 1024 * 1024
    }

    /**
     * Скачивает turbo-модель в filesDir/models. Блокирующий (suspend).
     * onProgress(bytesSoFar, totalBytes) вызывается из IO-потока по мере
     * записи. При любом сбое бросает Exception (временный файл чистится).
     */
    suspend fun download(
        context: Context,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val dest = turboFile(context)
        if (turboReady(context)) {
            Log.d(TAG, "turbo уже докачана: ${dest.absolutePath}")
            return@withContext dest
        }

        val tmp = File(modelsDir(context), "$TURBO_FILE.part")
        val conn = (URL(URL).openConnection() as HttpURLConnection).apply {
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
                else -> throw IllegalStateException("HTTP $code при скачивании модели")
            }

            val totalBytes: Long = if (tmp.exists() && code == HttpURLConnection.HTTP_PARTIAL) tmp.length() + total else total
            Log.d(TAG, "скачиваю turbo, http=$code, осталось=$total, уже есть=${if (code == 206) tmp.length() else 0}")

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

            if (done < 500L * 1024 * 1024) {
                throw IllegalStateException("файл оборван: $done байт")
            }

            // Целиком скачан → атомарно переносим в финальное имя.
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            Log.d(TAG, "turbo скачана: ${dest.absolutePath} (${dest.length() / 1024 / 1024}MB)")
            dest
        } finally {
            conn.disconnect()
        }
    }
}
