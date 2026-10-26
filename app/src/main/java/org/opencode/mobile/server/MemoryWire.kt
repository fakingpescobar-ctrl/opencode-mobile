package org.opencode.mobile.server

import org.json.JSONObject

/**
 * Формы JSON-RPC локальной памяти: конверт `tools/call` -> текст -> запись.
 *
 * Почему это отдельный объект, а не разбор на месте в UI. Любой отказ памяти
 * приходит с одним и тем же HTTP 200, и разница только внутри тела: ошибка
 * JSON-RPC в корне, `result.isError` в результате инструмента и, наконец,
 * обычный `result.content[0].text`. Секция диагностики, которая разбирает это
 * сама, проверяется только руками на устройстве, а «пустая память» и
 * «сломанный разбор» выглядят на экране одинаково - пустым списком. Здесь
 * разбор чистый (ни сети, ни Android), поэтому покрыт JVM-тестами
 * [MemoryWireTest].
 */
internal object MemoryWire {
    /** Одна запись в том виде, в каком её отдаёт `local_memory_list`. */
    data class Record(
        val id: String,
        val content: String,
        val type: String,
        val project: String,
        val tags: String,
        val created: Long,
        val trust: String,
        val superseded: Boolean,
    )

    /** Счётчики из `local_memory_stats`. */
    data class Stats(
        val memories: Int,
        val edges: Int,
        val superseded: Int,
        val autoApplyable: Int,
        val needsHuman: Int,
    )

    private const val CONTENT = "content"
    private const val TEXT = "text"
    private const val NOT_JSON = "ответ памяти - не JSON"

    /** Тело запроса `tools/call` для одного инструмента. */
    fun call(
        name: String,
        args: JSONObject = JSONObject(),
        id: Int = 1,
    ): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", "tools/call")
            .put("params", JSONObject().put("name", name).put("arguments", args))
            .toString()

    /**
     * Достаёт текст ответа инструмента из конверта.
     *
     * Отказ инструмента - это НЕ пустой результат: `isError` без текста всё
     * равно бросает, иначе UI показал бы «записей нет» там, где память
     * отказалась отвечать.
     */
    fun toolText(body: String): String {
        val envelope = envelope(parse(body))
        val items = envelope.optJSONArray(CONTENT)
        val frame = items?.optJSONObject(0)
        val text = frame?.optString(TEXT).orEmpty()
        val failed = envelope.optBoolean("isError")
        val reason = text.ifBlank { "ошибка памяти" }
        check(!failed) { reason }
        return text
    }

    /** Список записей из ответа `local_memory_list`. */
    fun records(text: String): List<Record> {
        val array = parse(text).optJSONArray("memories") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::record) }
    }

    /** Счётчики из ответа `local_memory_stats`. */
    fun stats(text: String): Stats {
        val root = parse(text)
        return Stats(
            memories = root.optInt("memories"),
            edges = root.optInt("edges"),
            superseded = root.optInt("superseded"),
            autoApplyable = root.optInt("auto_applyable"),
            needsHuman = root.optInt("needs_human"),
        )
    }

    private fun envelope(root: JSONObject): JSONObject {
        root.optJSONObject("error")?.let { error(it.optString("message", "ошибка памяти")) }
        return checkNotNull(root.optJSONObject("result")) { "в ответе памяти нет result" }
    }

    private fun record(row: JSONObject): Record =
        Record(
            id = row.optString("id"),
            content = row.optString(CONTENT),
            type = row.optString("type"),
            project = row.optString("project"),
            tags = row.optString("tags"),
            created = row.optLong("created"),
            trust = row.optString("trust"),
            // Вытесненная запись приходит двумя признаками: числом superseded и
            // идентификатором замены. Верю обоим: сервер может прислать только один.
            superseded = row.optInt("superseded") == 1 || row.optString("superseded_by").isNotEmpty(),
        )

    private fun parse(text: String): JSONObject =
        runCatching { JSONObject(text) }.getOrElse { throw IllegalStateException("$NOT_JSON: ${it.message}") }
}
