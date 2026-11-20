package org.opencode.mobile.server

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Вызовы инструментов локальной памяти из приложения, а не из модели.
 *
 * Зачем это вообще нужно. Память - MCP-сервер, и до сих пор её трогал только
 * агент. Кнопка «забыть» в диагностике обязана удалять запись без модели в
 * цепочке: иначе человек не может убрать неверную запись, если модель не
 * согласится вызвать инструмент, а удаление зависит от того, что модель решит.
 *
 * Транспорт тот же, что у [MemoryMcp.toolCounts]: JSON-RPC по HTTP на
 * 127.0.0.1:[OpencodeRuntime.MEMORY_PORT]/mcp с bearer из [MemoryAuth].
 * Таймаут короткий: сервер локальный, а экран диагностики не должен подвисать
 * из-за мёртвой памяти - отказ лучше ожидания.
 */
internal object MemoryTools {
    /** Сколько последних записей показывать: экран читают глазами, а не скроллят. */
    const val LIST_LIMIT = 20

    private const val TIMEOUT_MS = 4_000
    /** LLM ищет дольше обычного вызова ядра: генерация + повторный проход FTS5. */
    private const val LLM_TIMEOUT_MS = 12_000
    private const val GRAPH_LIMIT = 200
    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299

    fun stats(): Result<MemoryWire.Stats> = call("local_memory_stats", JSONObject()).mapCatching(MemoryWire::stats)

    fun list(limit: Int = LIST_LIMIT): Result<List<MemoryWire.Record>> =
        call("local_memory_list", JSONObject().put("limit", limit)).mapCatching(MemoryWire::records)

    fun forget(id: String): Result<Unit> {
        val args = JSONObject().put("id", id)
        return call("local_memory_forget", args).map { }
    }

    // ---- Ядро памяти: те же вызовы, что делает агент, но по кнопке -------------
    // Счётчики НЕ хардкодим: список инструментов спрашиваем у живого сервера.
    fun coreStats(): Result<String> = call("memory_index_stats", JSONObject())

    fun corePack(
        query: String,
        budget: Int = 1024,
    ): Result<String> =
        call("memory_pack", JSONObject().put("query", query).put("budgetTokens", budget))

    fun coreBench(mode: String = "adaptive"): Result<String> =
        call("memory_bench", JSONObject().put("mode", mode).put("suite", "diag"))

    /** Полный снимок графа для 3D-визуализации в диагностике. */
    fun coreGraph(limit: Int = GRAPH_LIMIT): Result<List<MemoryWire.Edge>> =
        call("memory_graph", JSONObject().put("limit", limit)).mapCatching(MemoryWire::edges)

    // ---- Резидентный LLM-сопроцессор -----------------------------------------
    /** Статус прогретой модели: что выбрал оператор и почему она доступна/нет. */
    fun llmStatus(): Result<String> = call("memory_llm_status", JSONObject(), LLM_TIMEOUT_MS)

    /**
     * Точечный поиск: ядро даёт 5–12 кандидатов, LLM выбирает ID и синонимы.
     * Возвращает JSON с полем pack — детерминированный ответ ядра всегда внутри.
     */
    fun llmSearch(query: String, limit: Int = 5, force: Boolean = true): Result<String> =
        call(
            "memory_llm_search",
            JSONObject().put("query", query).put("limit", limit).put("force", force),
            LLM_TIMEOUT_MS,
        )

    /** Живой список инструментов ядра из `tools/list`, а не константа в Kotlin. */
    fun coreTools(): Result<List<String>> =
        runCatching {
            val authorization =
                MemoryAuth.bearerHeader()
                    ?: error("токен памяти неизвестен: сервер ещё не стартовал")
            val url = URL("http://127.0.0.1:${OpencodeRuntime.MEMORY_PORT}${MemoryMcp.MEMORY_PATH}")
            val payload =
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", 1)
                    .put("method", "tools/list")
                    .put("params", JSONObject())
            val conn =
                (url.openConnection() as HttpURLConnection)
                    .apply {
                        requestMethod = "POST"
                        connectTimeout = TIMEOUT_MS
                        readTimeout = TIMEOUT_MS
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Authorization", authorization)
                    }
            try {
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val body = conn.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in HTTP_OK_MIN..HTTP_OK_MAX) error("память ответила HTTP $code")
                val tools = JSONObject(body).getJSONObject("result").getJSONArray("tools")
                (0 until tools.length())
                    .map { tools.getJSONObject(it).getString("name") }
                    .filter { it.startsWith("memory_") }
            } finally {
                conn.disconnect()
            }
        }

    private fun call(
        name: String,
        args: JSONObject,
        timeoutMs: Int = TIMEOUT_MS,
    ): Result<String> =
        runCatching {
            val authorization =
                MemoryAuth.bearerHeader()
                    ?: error("токен памяти неизвестен: сервер ещё не стартовал")
            val url = URL("http://127.0.0.1:${OpencodeRuntime.MEMORY_PORT}${MemoryMcp.MEMORY_PATH}")
            val conn =
                (url.openConnection() as HttpURLConnection)
                    .apply {
                        requestMethod = "POST"
                        connectTimeout = timeoutMs
                        readTimeout = timeoutMs
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("Authorization", authorization)
                    }
            try {
                conn.outputStream.use { it.write(MemoryWire.call(name, args).toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val body =
                    (if (code in HTTP_OK_MIN..HTTP_OK_MAX) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()
                if (code !in HTTP_OK_MIN..HTTP_OK_MAX) error("память ответила HTTP $code")
                MemoryWire.toolText(body)
            } finally {
                conn.disconnect()
            }
        }
}
