package org.opencode.mobile.ui.chat

import org.json.JSONArray
import org.json.JSONObject

/**
 * `finish`, после которого ход ПРОДОЛЖАЕТСЯ: шаг модели закончился только для
 * того, чтобы выполнить инструменты, и следом придёт новый шаг.
 */
internal const val FINISH_CONTINUES = "tool-calls"

private const val TYPE_IDLE = "idle"
private const val TYPE_SYSTEM = "system"
private const val TYPE_ASSISTANT = "assistant"

/**
 * Идёт ли ещё ход модели — по СЫРОЙ ленте сервера.
 *
 * Зачем отдельное правило. Раньше «Модель думает…» считалась по последнему
 * разобранному сообщению: ход открыт, пока у assistant-шага нет `step-finish` и
 * `time.completed`. На живом сервере v2 это неверно: когда шаг заканчивается
 * ради вызова инструмента, сервер уже ставит `finish:"tool-calls"` и
 * `time.completed`, а следующий assistant-шаг появляется только когда модель
 * его сгенерирует. Всё это время (16.11 замерено 20 с: `10:20:44 → 10:21:04`)
 * старый разбор считал ход закрытым, и индикатор пропадал, хотя модель
 * работала. Макс описал ровно это: «надпись пропадает, и не понятно, зависла
 * она или нет».
 *
 * Правило простое и целиком по сырым полям:
 * - `idle` в ленте — ход закончен (это единственный явный маркер конца);
 * - `assistant` без `time.completed` — шаг идёт;
 * - `assistant` с `finish:"tool-calls"` — шаг закончился, ход нет;
 * - любой другой завершённый `assistant` (`stop`, `error`, `length`) — конец;
 * - последнее сообщение — наше (`user`) — ответа ещё нет, ход открыт.
 *
 * `system`-сообщения пропускаются: в начале ленты v2 может лежать сохранённое
 * «список инструментов изменился», и по нему нельзя решать ничего.
 *
 * `null` — ленту разобрать не удалось. Это НЕ «ход закончен»: вызывающий обязан
 * взять свой прежний разбор, иначе один битый ответ спрячет индикатор.
 */
internal fun turnAlive(rawMessages: String?): Boolean? {
    val newest = newestMessage(rawMessages) ?: return null
    val type = typeOf(newest)
    return when {
        type == TYPE_IDLE -> false
        type == TYPE_ASSISTANT -> assistantAlive(newest)
        type.isBlank() || type == TYPE_SYSTEM -> null
        else -> true
    }
}

/** Шаг assistant закончен — только если он завершён и не пауза ради инструментов. */
private fun assistantAlive(msg: JSONObject): Boolean {
    val completed = (timeOf(msg)?.optLong("completed") ?: 0L) > 0L
    return !completed || finishOf(msg) == FINISH_CONTINUES
}

/**
 * Новейшее «содержательное» сообщение ленты.
 *
 * Порядок в выдаче v2 — новые→старые, но полагаться на него нельзя: та же
 * лента приезжает и развёрнутой (v1-конверт). Поэтому выбираем по
 * `time.created`, а порядок используем только как крайний случай, когда времени
 * нет ни у кого.
 */
private fun newestMessage(rawMessages: String?): JSONObject? {
    val arr = arrayOf(rawMessages) ?: return null
    var best: JSONObject? = null
    var bestAt = -1L
    for (i in 0 until arr.length()) {
        val msg = arr.optJSONObject(i) ?: continue
        if (typeOf(msg) == TYPE_SYSTEM) continue
        val at = timeOf(msg)?.optLong("created") ?: 0L
        // `>=` при равном времени оставляет более поздний по порядку: у v1 без
        // `created` так выигрывает первое сообщение выдачи, то есть новейшее.
        if (at >= bestAt) {
            best = msg
            bestAt = at
        }
    }
    return best
}

/**
 * Лента из ответа сервера. Поддерживаются обе формы: массив и конверт с `data`.
 * Один объект сообщения тоже принимается — так приходит лента из одного
 * шага, и приравнивать это к «разобрать не удалось» незачем.
 */
private fun arrayOf(rawMessages: String?): JSONArray? {
    if (rawMessages.isNullOrBlank()) return null
    val obj = runCatching { JSONObject(rawMessages) }.getOrNull()
    return obj?.optJSONArray("data")
        ?: obj?.optJSONArray("message")
        ?: obj?.takeIf { it.has("type") || it.has("info") }?.let { JSONArray().put(it) }
        ?: runCatching { JSONArray(rawMessages) }.getOrNull()
}

/** Тип сообщения: `type` в v2, `info.role` в v1-конверте. */
private fun typeOf(msg: JSONObject): String {
    val direct = msg.optString("type")
    if (direct.isNotBlank()) return direct
    return msg.optJSONObject("info")?.optString("role") ?: ""
}

/** Время сообщения: `time` в v2, `info.time` в v1-конверте. */
private fun timeOf(msg: JSONObject): JSONObject? =
    msg.optJSONObject("time") ?: msg.optJSONObject("info")?.optJSONObject("time")

/** `finish` шага: в v1-конверте он не приходит, и это законно — значит «не знаем». */
private fun finishOf(msg: JSONObject): String = msg.optString("finish")
