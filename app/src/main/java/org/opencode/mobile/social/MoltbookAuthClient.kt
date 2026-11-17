package org.opencode.mobile.social

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Две обращения к сайту, которые нужны диагностике: зарегистрировать агента и
 * спросить его статус. Регистрация — единственная ручка без bearer-ключа, поэтому
 * у неё отдельный путь; статус шлёт уже полученный `api_key`.
 *
 * Сеть ходит через Android-резолвер (как и [MoltbookClient]): musl-Bun в песочнице
 * приложения не резолвит moltbook.com, а обход по IP упирается в SNI. Общий
 * дедлайн поверх connect/read нужен потому, что они не ограничивают зависший DNS.
 * Ключ никуда не логируется — только уходит в заголовке на этот же хост.
 */

private const val HOST = "https://www.moltbook.com"
private const val REGISTER_PATH = "/api/v1/agents/register"
private const val STATUS_PATH = "/api/v1/agents/status"
private const val CONNECT_MS = 10_000
private const val READ_MS = 20_000
private const val TOTAL_MS = 45_000L
private const val MILLIS_PER_SECOND = 1000L
private const val ERROR_CHARS = 200
private const val OK_MIN = 200
private const val OK_MAX = 299
private const val METHOD_POST = "POST"
private const val METHOD_GET = "GET"
private const val CONTENT_TYPE = "application/json; charset=utf-8"
private const val HEADER_AUTH = "Authorization"
private const val HEADER_BEARER = "Bearer "
private val AUTH_POOL =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "moltbook-auth").apply { isDaemon = true }
    }

/** Зарегистрировать нового агента. Ответ разбирается [parseRegistration]. */
internal fun registerAgent(
    name: String,
    description: String,
): MoltbookRegistration {
    val body = JSONObject().put("name", name).put("description", description).toString()
    return parseRegistration(requestAuth(REGISTER_PATH, METHOD_POST, body, null))
}

/** Статус уже зарегистрированного агента: `pending_claim` или `claimed`. */
internal fun agentStatus(key: String): String = parseAgentStatus(requestAuth(STATUS_PATH, METHOD_GET, null, key))

private fun requestAuth(
    path: String,
    method: String,
    body: String?,
    key: String?,
): String = boundedAuth { requestAuthBlocking(path, method, body, key) }

private fun <T> boundedAuth(block: () -> T): T =
    try {
        AUTH_POOL.submit(Callable { block() }).get(TOTAL_MS, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        throw IOException("Moltbook не ответил за ${TOTAL_MS / MILLIS_PER_SECOND} с", e)
    } catch (e: ExecutionException) {
        throw (e.cause ?: e) as? IOException ?: IOException("Moltbook: ${e.cause?.message}", e)
    }

private fun requestAuthBlocking(
    path: String,
    method: String,
    body: String?,
    key: String?,
): String {
    val conn = URL(HOST + path).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_MS
        conn.readTimeout = READ_MS
        conn.setRequestProperty("Accept", "application/json")
        if (key != null) conn.setRequestProperty(HEADER_AUTH, HEADER_BEARER + key)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", CONTENT_TYPE)
            val payload = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
        }
        val status = conn.responseCode
        val stream = if (status in OK_MIN..OK_MAX) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in OK_MIN..OK_MAX) {
            throw IOException("Moltbook $method $path → $status: ${text.take(ERROR_CHARS)}")
        }
        return text
    } finally {
        conn.disconnect()
    }
}
