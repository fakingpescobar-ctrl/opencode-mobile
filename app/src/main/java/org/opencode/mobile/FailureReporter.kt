package org.opencode.mobile

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import org.json.JSONObject
import org.opencode.mobile.server.OpencodeServerService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Автоматическая обратная связь по сбоям сервера.
 *
 * Ничего не отправляет, пока в BuildConfig не заданы TELEGRAM_BOT_TOKEN и
 * TELEGRAM_CHAT_ID. Даже с заданными — только компактный санитизированный
 * отчёт: без хвоста opencode.log, токенов, путей и логинов. Отчёт сначала
 * ложится в filesDir/failures, затем досылается при наличии сети.
 */
@Suppress("TooManyFunctions")
object FailureReporter {
    private const val TAG = "FailureReporter"
    private const val PREFS = "failure_reporter"
    private const val KEY_LAST_SENT = "last_sent_at"
    private const val KEY_LAST_FP = "last_fingerprint"
    private const val KEY_INSTALL_ID = "install_id"
    private const val RATE_LIMIT_MS = 60L * 60 * 1000
    private const val DEDUP_MS = 24L * 60 * 60 * 1000
    private const val MAX_TEXT = 3500
    private const val MAX_SANITIZED = 400
    private const val HISTORY_LIMIT = 3
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 10000
    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299
    private const val BYTES_PER_MB = 1024L * 1024L

    @Synchronized
    fun report(context: Context, state: OpencodeServerService.ServerState) {
        val report = buildReport(context, state)
        val prefs = prefs(context)
        val fp = report.optString("fingerprint")
        if (!allowed(prefs, fp)) return
        persist(context, report)
        // Отмечаем время сразу при записи в очередь: без сети отправка не
        // состоится, но лимит 1/час и дедуп 24ч должны работать уже сейчас,
        // иначе следующий сбой создаст ещё одну копию того же отчёта.
        markSent(prefs, fp)
        flush(context)
    }

    /** Досылка очереди. Возвращает true, если хотя бы одно сообщение ушло. */
    @Synchronized
    fun flush(context: Context): Boolean {
        val token = BuildConfig.TELEGRAM_BOT_TOKEN
        val chat = BuildConfig.TELEGRAM_CHAT_ID
        val pending = File(context.filesDir, "failures")
            .listFiles { f -> f.name.endsWith(".json") }
        return when {
            token.isBlank() || chat.isBlank() -> false
            pending.isNullOrEmpty() -> false
            else -> flushPending(token, chat, pending.sortedBy { it.name })
        }
    }

    private fun flushPending(token: String, chat: String, pending: List<File>): Boolean {
        var sent = false
        for (file in pending) {
            val text = readText(file)
            if (text == null) {
                file.delete()
            } else if (send(token, chat, text)) {
                file.delete()
                sent = true
            } else {
                // Сети нет — не спамим, досылаем в следующий запуск.
                break
            }
        }
        return sent
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun allowed(prefs: SharedPreferences, fingerprint: String): Boolean {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_SENT, 0L)
        if (prefs.getString(KEY_LAST_FP, "") == fingerprint && now - last < DEDUP_MS) return false
        return now - last >= RATE_LIMIT_MS
    }

    private fun markSent(prefs: SharedPreferences, fingerprint: String) {
        prefs.edit()
            .putLong(KEY_LAST_SENT, System.currentTimeMillis())
            .putString(KEY_LAST_FP, fingerprint)
            .apply()
    }

    private fun persist(context: Context, report: JSONObject) {
        val dir = File(context.filesDir, "failures")
        if (!dir.exists() && !dir.mkdirs()) return
        val file = File(dir, "${System.currentTimeMillis()}-${UUID.randomUUID()}.json")
        runCatching { file.writeText(report.toString()) }
            .onFailure { Log.w(TAG, "не записал отчёт: ${it.message}") }
    }

    private fun readText(file: File): String? = runCatching {
        JSONObject(file.readText()).optString("text").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun buildReport(context: Context, state: OpencodeServerService.ServerState): JSONObject {
        val err = state.lastError
        val code = err?.code?.name ?: "UNKNOWN"
        val stage = err?.stage?.name ?: state.stage?.name ?: "UNKNOWN"
        val fingerprint = "$code|${BuildConfig.VERSION_CODE}|${BuildConfig.GIT_SHA}"
        val lines = ArrayList<String>()
        lines.add("OpenCode Mobile crash report")
        lines.add("build: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.GIT_SHA}")
        lines.add("reporter: ${installId(context)}")
        lines.add("role: ${deviceRole()}")
        lines.add("stage: $stage")
        lines.add("code: $code (recoverable=${err?.recoverable ?: false})")
        lines.add("message: ${sanitize(err?.message ?: "no lastError")}")
        if (state.errorHistory.isNotEmpty()) {
            val tail = state.errorHistory.takeLast(HISTORY_LIMIT).joinToString("; ") {
                "${it.code}: ${sanitize(it.message)}"
            }
            lines.add("history: $tail")
        }
        lines.add(deviceLine(context))
        lines.add("time: ${stamp()}")
        return JSONObject()
            .put("fingerprint", fingerprint)
            .put("text", lines.joinToString("\n").take(MAX_TEXT))
    }

    /** Наш рабочий девайс помечаем как DEV/ADMIN, остальные — USER. */
    private fun deviceRole(): String = if (Build.FINGERPRINT == BuildConfig.DEV_DEVICE_FINGERPRINT) {
        "DEV/ADMIN"
    } else {
        "USER"
    }

    /**
     * Стабильный ID установки. Не IMEI и не ANDROID_ID: случайный UUID в
     * SharedPreferences — не требует разрешений, живет до переустановки APK
     * и позволяет понять в чате, от какого именно девайса пришел отчёт.
     */
    private fun installId(context: Context): String {
        val saved = prefs(context).getString(KEY_INSTALL_ID, "")
        if (!saved.isNullOrBlank()) return saved
        val fresh = UUID.randomUUID().toString()
        prefs(context).edit().putString(KEY_INSTALL_ID, fresh).apply()
        return fresh
    }

    private fun deviceLine(context: Context): String {
        val mi = android.app.ActivityManager.MemoryInfo()
        runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE)
            if (manager is android.app.ActivityManager) manager.getMemoryInfo(mi)
        }
        val ram = if (mi.totalMem > 0L) "${mb(mi.availMem)}/${mb(mi.totalMem)}" else "?"
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        return "device: ${Build.BRAND} ${Build.MODEL} API ${Build.VERSION.SDK_INT} $abi RAM $ram"
    }

    private fun mb(bytes: Long): String = "${bytes / BYTES_PER_MB}MB"

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /** Вырезаем пути и длинные токены: в отчёт уходит только суть сбоя. */
    private fun sanitize(raw: String): String = raw
        .replace(Regex("(/storage|/data|/sdcard)/[^\\s]+"), "<path>")
        .replace(Regex("[A-Za-z0-9_-]{32,}"), "<redacted>")
        .take(MAX_SANITIZED)

    private fun send(token: String, chat: String, text: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://api.telegram.org/bot$token/sendMessage")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val body = "chat_id=${URLEncoder.encode(chat, "UTF-8")}" +
                "&text=${URLEncoder.encode(text, "UTF-8")}" +
                "&disable_web_page_preview=true"
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode in HTTP_OK_MIN..HTTP_OK_MAX
        } catch (e: java.io.IOException) {
            Log.w(TAG, "отправка не удалась: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
