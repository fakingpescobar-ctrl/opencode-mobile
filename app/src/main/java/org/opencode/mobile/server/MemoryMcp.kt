package org.opencode.mobile.server

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Реестр локальных MCP-серверов: имя в конфиге serve -> маршрут в memory.js.
 *
 * Один процесс memory.js отдаёт два независимых MCP на одном порту (/mcp — память,
 * /mobile — управление телефоном). Имя из конфига — это то, что видит пользователь
 * в списке «MCP-серверы», поэтому пара «имя -> маршрут» объявлена ровно в одном
 * месте: её используют и генератор opencode.jsonc, и UI для подсчёта инструментов.
 *
 * toolCounts() спрашивает у САМОГО сервера (`tools/list`), а не держит числа в
 * коде: состав инструментов меняется вместе с memory.js, и захардкоженный счётчик
 * тихо разъехался бы с реальностью.
 */
object MemoryMcp {
    const val MEMORY_NAME = "memory"
    const val TOOLS_NAME = "mobile_tools"
    const val MEMORY_PATH = "/mcp"
    const val TOOLS_PATH = "/mobile"

    /** Имя сервера из opencode.jsonc -> путь на локальном порту. */
    val byName: Map<String, String> =
        mapOf(
            MEMORY_NAME to MEMORY_PATH,
            TOOLS_NAME to TOOLS_PATH,
        )

    private const val COUNT_TIMEOUT_MS = 1_500
    private const val COUNT_CACHE_MS = 30_000L
    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299
    private const val TOOLS_LIST_REQUEST = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""

    private val lock = ReentrantLock()
    private var cached: Map<String, Int> = emptyMap()
    private var cachedAt = 0L

    /**
     * Сколько инструментов отдаёт каждый наш сервер (имя -> количество).
     * Кэш на COUNT_CACHE_MS: лента чата опрашивает сервер каждые ~2.5с, а состав
     * инструментов меняется только при обновлении приложения. При недоступной
     * памяти отдаём последний известный ответ, а не пустую карту — чтобы не
     * мигали нулями у живых серверов.
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
        val authorization = MemoryAuth.bearerHeader() ?: return emptyMap()
        val counts = LinkedHashMap<String, Int>(byName.size)
        for ((name, path) in byName) {
            toolCount(authorization, path)?.let { counts[name] = it }
        }
        return counts
    }

    @Suppress("SwallowedException") // Диагностика/UI: любой отказ = «счётчик неизвестен».
    private fun toolCount(
        authorization: String,
        path: String,
    ): Int? =
        try {
            val conn =
                (URL("http://127.0.0.1:${OpencodeRuntime.MEMORY_PORT}$path").openConnection() as HttpURLConnection)
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
