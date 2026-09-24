package org.opencode.mobile.server

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Единый HTTP-клиент локального opencode serve (порт 4096). Вынесен из
 * ChatOverlay (PR2-хвост): все запросы к serve (лента, сессии, MCP, abort,
 * reply) ходят через один код и один согласованный источник авторизации —
 * [ServerAuth.basicHeader]. До выноса каждый вызов дублировал
 * HttpURLConnection + Authorization вручную, и любой новый путь мог забыть
 * заголовок (получив молчаливый 401, как было с /mcp).
 *
 * Соглашения:
 *  - Таймауты: connect/read 2 000/4 000 мс (локальный loopback).
 *  - get/post возвращают тело ТОЛЬКО при 2xx, иначе null (вызывающий не
 *    различает 401/404/таимаут — для UI это одинаково «сервер не готов»).
 *  - postAsync — fire-and-forget для долгих POST (message/reply): opencode
 *    отвечает только после завершения генерации, рвать соединение нельзя —
 *    фоновый поток дочитывает ответ и закрывает conn (как было в ChatOverlay).
 *  - Все методы синхронны (блокируют вызывающий поток) — звать с
 *    Dispatchers.IO, как и раньше.
 */
object LocalOpenCodeClient {
    private const val TAG = "OpenCodeClient"

    /** Таймаут установки соединения, мс (локальный loopback — щедро). */
    private const val CONNECT_TIMEOUT_MS = 2000

    /** Таймаут чтения ответа, мс. */
    private const val READ_TIMEOUT_MS = 4000

    /** Успешный HTTP-код ответа serve. */
    private const val HTTP_OK = 200

    private fun auth(conn: HttpURLConnection) {
        val h = ServerAuth.basicHeader()
        if (h != null) {
            conn.setRequestProperty("Authorization", h)
        } else {
            Log.w(TAG, "ServerAuth.password не инициализирован — запрос без Authorization (ожидается 401)")
        }
    }

    /** GET: 200 → тело, иначе null (в т.ч. MCP: GET /mcp). */
    fun get(
        port: Int,
        path: String,
    ): String? {
        val conn = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection)
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            auth(conn)
            val ok = conn.responseCode == HTTP_OK
            return if (ok) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (_: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** DELETE: true при 200 (иначе false). */
    fun delete(
        port: Int,
        path: String,
    ): Boolean {
        val conn = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "DELETE"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            auth(conn)
            return conn.responseCode == HTTP_OK
        } catch (_: Exception) {
            return false
        } finally {
            conn.disconnect()
        }
    }

    /**
     * POST с телом: 200 → тело ответа, иначе null. Пустое body пишется без
     * doOutput (POST без тела, как abort-эндпоинт).
     */
    fun post(
        port: Int,
        path: String,
        body: String,
        contentType: String = "application/json",
    ): String? {
        val conn = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            auth(conn)
            if (body.isNotEmpty()) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val ok = conn.responseCode == HTTP_OK
            return if (ok) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (_: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * ПОСТ fire-and-forget для долгих эндпоинтов (message/reply): возвращает
     * true сразу после отправки, фоновый поток дочитывает ответ (сервер
     * считает запрос завершённым) и закрывает соединение.
     */
    fun postAsync(
        port: Int,
        path: String,
        body: String,
        contentType: String = "application/json",
    ): Boolean {
        try {
            val conn = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection)
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            auth(conn)
            conn.outputStream.use { it.write(body.toByteArray()) }
            thread(isDaemon = true) {
                try {
                    conn.responseCode
                } catch (_: Exception) {
                } finally {
                    conn.disconnect()
                }
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }
}
