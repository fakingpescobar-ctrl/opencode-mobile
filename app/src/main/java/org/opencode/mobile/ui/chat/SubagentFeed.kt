package org.opencode.mobile.ui.chat

import org.json.JSONArray
import org.json.JSONObject

/** Одна работающая сессия сабагента — строка индикатора под «Модель думает…». */
internal data class SubagentWork(
    val id: String,
    val label: String,
    val agent: String,
)

/**
 * Разбор списка сессий для нативного чата.
 *
 * Зачем отдельный объект. Сервер отдаёт сессии сабагентов в том же `/api/session`,
 * что и обычные, но с полем `parentID`. Приложение выбирало сессию с максимальным
 * `time.updated`, и пока сабагент работал, лента перескакивала на его сессию:
 * в наш чат попадали и промпт, который ему передали, и его ответы, и служебные
 * ответы инструментов. Здесь две честные функции: отсечь дочерние сессии от
 * выбора активной и собрать список работающих из них для индикатора.
 */
internal object SubagentFeed {
    private const val PARENT_V2 = "parentID"
    private const val PARENT_V1 = "parent_id"

    /** Родитель сессии: v2 пишет `parentID`, старые ответы — `parent_id`. */
    internal fun parentOf(session: JSONObject): String =
        session.optString(PARENT_V2, "").ifBlank { session.optString(PARENT_V1, "") }

    /** Дочерняя сессия — это и есть сессия сабагента, а не наш чат. */
    internal fun isChild(session: JSONObject): Boolean = parentOf(session).isNotBlank()

    /**
     * Работающие сабагенты в поддереве [activeId].
     *
     * Сессия считается работающей, пока у неё нет `outcome` либо `time.busy`
     * новее `time.idle`: сервер ставит `idle` в момент завершения хода. Готовые
     * дочерние сессии (`outcome:"succeeded"`) в индикатор не попадают.
     */
    internal fun activeOf(
        sessions: JSONArray,
        activeId: String?,
    ): List<SubagentWork> {
        if (activeId.isNullOrBlank()) return emptyList()
        val byId = HashMap<String, JSONObject>()
        for (i in 0 until sessions.length()) {
            val s = sessions.optJSONObject(i) ?: continue
            val id = s.optString("id", "")
            if (id.isNotBlank()) byId[id] = s
        }
        val out = ArrayList<SubagentWork>()
        for (i in 0 until sessions.length()) {
            val s = sessions.optJSONObject(i) ?: continue
            if (!belongsTo(s, activeId, byId)) continue
            val time = s.optJSONObject("time")
            val busy = time?.optLong("busy") ?: 0L
            val idle = time?.optLong("idle") ?: 0L
            val outcome = s.optString("outcome", "")
            if (outcome.isNotBlank() && idle >= busy) continue
            val agent = s.optString("agent", "")
            val label = s.optString("title", "").ifBlank { agent.ifBlank { "subagent" } }
            out.add(SubagentWork(s.optString("id", ""), label, agent))
        }
        return out
    }

    /** Сессия относится к нашему чату, если её предок по цепочке — [activeId]. */
    private fun belongsTo(
        session: JSONObject,
        activeId: String,
        byId: Map<String, JSONObject>,
    ): Boolean {
        var parent = parentOf(session)
        val seen = HashSet<String>()
        while (parent.isNotBlank() && seen.add(parent)) {
            if (parent == activeId) return true
            parent = byId[parent]?.let { parentOf(it) } ?: ""
        }
        return false
    }
}
