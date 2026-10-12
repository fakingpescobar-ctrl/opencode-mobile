package org.opencode.mobile.server

import org.json.JSONArray
import org.json.JSONObject

/**
 * Ветка ленты v1 — та, где ответ уже в нужном виде.
 *
 * Живёт отдельно от [ApiShape], потому что там уже одиннадцать функций, а лимит
 * detekt ровно на этом и срабатывает. Разбирать форму ответа и приводить чужую к
 * своей — разные задачи, и держать их рядом было бы только ради одного вызова.
 */

/**
 * Лента v1: сообщения уже в конверте `info`/`parts`, порядок менять не надо.
 *
 * Отсекаются два случая, которых в честной v1 не бывает, но которые приходят,
 * если отдать сюда ответ v2: служебный `idle` (иначе рисуется пустая строка
 * «Система») и сообщения, у которых после нормализации не осталось ни одной части.
 */
internal fun v1Passthrough(arr: JSONArray): JSONArray {
    val out = JSONArray()
    for (i in 0 until arr.length()) {
        val src = arr.optJSONObject(i) ?: continue
        val kept = keepOf(src)
        if (kept != null) out.put(kept)
    }
    return out
}

/**
 * Что оставить из одного сообщения: уже готовое v1-сообщение, нормализованный
 * конверт — либо null, если показывать нечего.
 *
 * Вынесено отдельно ради одного `continue` в цикле: три отказа подряд в теле
 * цикла detekt не пропускает.
 */
private fun keepOf(src: JSONObject): JSONObject? =
    when {
        // v1-сообщение узнаётся по `info`: `role` и `parts` лежат внутри.
        src.optJSONObject("info")?.has("role") == true -> src
        src.optString("type") == IDLE_TYPE -> null
        else -> ApiShape.toV1Envelope(src).takeUnless { isBlankEnvelope(it) }
    }

/**
 * Сообщение, у которого не осталось ни одной части, в ленте рисуется пустой
 * строкой с названием роли — «Система» или «Модель». Смысла в такой строке нет,
 * поэтому отсекается здесь, а не в потребителе.
 *
 * Источник — служебное idle с пустым объектом error. Второй случай (assistant с
 * пустым content и без error) на живом сервере не наблюдался: у провалившегося
 * assistant поле error всегда есть, — но полагаться на это в нормализации
 * нельзя, смена формы ответа должна ломаться тихо, а не сыпать пустыми
 * строками в чат.
 */
internal fun isBlankEnvelope(envelope: JSONObject): Boolean = (envelope.optJSONArray("parts")?.length() ?: 0) == 0
