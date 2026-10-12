package org.opencode.mobile.server

import org.json.JSONArray
import org.json.JSONObject

/**
 * Форма ответа `GET /mcp` — плоский список `{name, status}`.
 *
 * Живут тут три формы, и все три настоящие:
 *
 * - v2: `{location:{directory}, data:[{name, status:{status:"connected"}}]}` —
 *   статус вложен объектом, а не строкой;
 * - v1: `Record<имя, {name, status}>` — ключ объекта и есть имя;
 * - v1, другой ответ: массив `[{name, status}]`.
 *
 * Наружу отдаётся одна форма — иначе шапка «N MCP» молча показывала бы ноль,
 * и выглядело бы это как «серверы отвалились», а не как «форма не та».
 */
internal object ApiMcp {
    /** null — тело не разобралось или это не список серверов. */
    fun servers(raw: String?): JSONArray? {
        val obj = asJsonObject(raw)
        val data = obj?.optJSONArray("data")
        val out = JSONArray()
        if (data != null) {
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                out.put(flat(item.optString("name"), item.optJSONObject("status")?.optString("status") ?: ""))
            }
        } else if (obj != null) {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = obj.optJSONObject(key)
                val name = item?.optString("name")?.takeIf { it.isNotBlank() } ?: key
                out.put(flat(name, item?.optString("status") ?: ""))
            }
        } else {
            val arr = asJsonArray(raw) ?: return null
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                out.put(flat(item.optString("name"), item.optString("status")))
            }
        }
        return out
    }

    private fun flat(
        name: String,
        status: String,
    ): JSONObject = JSONObject().put("name", name).put("status", status)
}
