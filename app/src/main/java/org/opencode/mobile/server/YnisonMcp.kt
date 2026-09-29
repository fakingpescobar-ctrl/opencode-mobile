package org.opencode.mobile.server

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Реестр локального MCP-сервера Яндекс.Музыки: имя в конфиге serve -> маршрут в ynison.js.
 *
 * Устроен как [MemoryMcp], но про отдельный процесс на отдельном порту и отдельный bearer
 * ([YnisonAuth]) — общий с памятью код опроса `tools/list` жил бы в двух местах и разъехался
 * бы при первой же правке таймаута.
 *
 * Имя из конфига — это то, что видит пользователь в списке «MCP-серверы», поэтому пара
 * «имя -> маршрут» объявлена здесь и используется и генератором opencode.jsonc, и UI.
 *
 * toolCounts() спрашивает у САМОГО сервера, а не держит числа в коде: состав инструментов
 * меняется вместе с ynison.js, и захардкоженный счётчик тихо разъехался бы с реальностью.
 */
object YnisonMcp {
    const val NAME = "music"
    const val PATH = "/mcp"

    private const val COUNT_TIMEOUT_MS = 1_500
    private const val COUNT_CACHE_MS = 30_000L
    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299
    private const val TOOLS_LIST_REQUEST = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""

    private val lock = ReentrantLock()
    private var cached: Map<String, Int> = emptyMap()
    private var cachedAt = 0L

    /**
     * Сколько инструментов отдаёт сервер (имя -> количество).
     *
     * Кэш на COUNT_CACHE_MS — тот же довод, что у памяти: лента чата опрашивает серверы
     * каждые ~2.5с, а состав инструментов меняется только при обновлении приложения.
     * При недоступной музыке отдаём последний известный ответ, чтобы не мигали нулями.
     */
    fun toolCounts(): Map<String, Int> {
        val now = System.currentTimeMillis()
        val snapshot = cached
        if (snapshot.isNotEmpty() && now - cachedAt < COUNT_CACHE_MS) return snapshot
        return lock.withLock {
            val again = cached
            if (again.isNotEmpty() && System.currentTimeMillis() - cachedAt < COUNT_CACHE_MS) {
                return@withLock again
            }
            val fresh = fetchCounts()
            if (fresh.isNotEmpty()) {
                cached = fresh
                cachedAt = System.currentTimeMillis()
                return@withLock fresh
            }
            again
        }
    }

    private fun fetchCounts(): Map<String, Int> {
        val authorization = YnisonAuth.bearerHeader() ?: return emptyMap()
        return toolCount(authorization, PATH)?.let { mapOf(NAME to it) } ?: emptyMap()
    }

    @Suppress("SwallowedException") // Диагностика/UI: любой отказ = «счётчик неизвестен».
    private fun toolCount(
        authorization: String,
        path: String,
    ): Int? =
        try {
            val conn =
                (URL("http://127.0.0.1:${OpencodeRuntime.YNISON_PORT}$path").openConnection() as HttpURLConnection)
                    .apply {
                        requestMethod = "POST"
                        connectTimeout = COUNT_TIMEOUT_MS
                        readTimeout = COUNT_TIMEOUT_MS
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Accept", "application/json, text/event-stream")
                        setRequestProperty("Authorization", authorization)
                    }
            try {
                conn.outputStream.use { it.write(TOOLS_LIST_REQUEST.toByteArray(Charsets.UTF_8)) }
                val body =
                    (if (conn.responseCode in HTTP_OK_MIN..HTTP_OK_MAX) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()
                JSONObject(body).optJSONObject("result")?.optJSONArray("tools")?.length()
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            null
        } catch (e: org.json.JSONException) {
            null
        }
}
