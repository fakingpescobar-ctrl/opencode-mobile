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
    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299

    fun stats(): Result<MemoryWire.Stats> = call("local_memory_stats", JSONObject()).mapCatching(MemoryWire::stats)

    fun list(limit: Int = LIST_LIMIT): Result<List<MemoryWire.Record>> =
        call("local_memory_list", JSONObject().put("limit", limit)).mapCatching(MemoryWire::records)

    fun forget(id: String): Result<Unit> {
        val args = JSONObject().put("id", id)
        return call("local_memory_forget", args).map { }
    }

    private fun call(
        name: String,
        args: JSONObject,
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
                        connectTimeout = TIMEOUT_MS
                        readTimeout = TIMEOUT_MS
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
