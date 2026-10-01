package org.opencode.mobile.ui

import org.json.JSONObject

/**
 * Статус сессии от самого opencode (GET /session/status): ретрай провайдера,
 * исчерпанная квота и т.п.
 *
 * Зачем: сервер честно отдаёт причину («Free usage exceeded, subscribe to Go»),
 * но ответ assistant при этом остаётся пустым, а thinking=true — без конца.
 * Без чтения этого эндпоинта пользователь видит только вечный спиннер и через
 * минуту — «зависло», из чего нельзя понять, что делать. Это молчание хуже
 * самой ошибки: тут нужен не кот, а конкретное действие.
 */
internal data class ChatNotice(
    val title: String,
    val message: String,
    val actionLabel: String? = null,
    val actionLink: String? = null,
    val attempt: Int = 0,
)

/**
 * Разбор одной записи /session/status в плашку. null — показывать нечего.
 *
 * Показываем только то, что реально мешает ходу (retry/error). Остальные статусы
 * отфильтровываются молча: про них и так видно индикатор думания, а лишняя плашка
 * учит человека её игнорировать.
 *
 * Вынесено отдельным файлом не ради красоты: функция чистая, и весь разбор
 * проверяется юнит-тестом на реальном ответе сервера, без ожидания, пока
 * провайдер реально исчерпает квоту.
 */
internal fun noticeOf(entry: JSONObject?): ChatNotice? {
    val type = entry?.optString("type", "").orEmpty()
    val action = entry?.optJSONObject("action")
    val msg = entry?.optString("message", "").orEmpty().trim()
    val actionMsg = action?.optString("message", "").orEmpty().trim()
    val detail = if (actionMsg.isBlank()) msg else actionMsg
    val interesting = type == "retry" || type == "error"
    val fallback = if (type == "error") "Модель ответила с ошибкой" else "Модель повторяет запрос"
    // Ничего внятного от сервера — лучше тишина, чем пустая плашка.
    return if (interesting && detail.isNotBlank()) {
        val actionTitle = action?.optString("title", "").orEmpty().trim()
        val title = if (actionTitle.isBlank()) fallback else actionTitle
        val label = action?.optString("label", "").orEmpty()
        val link = action?.optString("link", "").orEmpty()
        ChatNotice(
            title = title,
            message = if (msg.isBlank() || msg == detail) detail else "$detail ($msg)",
            actionLabel = label.ifBlank { null },
            actionLink = link.ifBlank { null },
            attempt = entry?.optInt("attempt", 0) ?: 0,
        )
    } else {
        null
    }
}

/**
 * Правило защёлки плашки причины.
 *
 * Retry — устойчивое состояние, а не событие: сервер отдаёт `free_tier_limit`
 * после любого TTL, пока квота не восстановится. Поэтому плашка не мигает, а
 * висит, пока идёт ход, и гаснет ровно на успешном assistant-шаге.
 *
 * Сделано функцией, а не inline в корутине, ради теста: переход «retry=true,
 * потом пришёл assistant-шаг» — это ровно тот случай, который нельзя поймать
 * живьём, не дожидаясь лимита квоты.
 *
 * [seen] — что пришло на этом тике, [held] — что висит сейчас,
 * [turnFinished] — opencode дошёл до успешного шага (thinking снят).
 */
internal fun nextNoticeLatch(
    held: ChatNotice?,
    seen: ChatNotice?,
    turnFinished: Boolean,
): ChatNotice? =
    when {
        turnFinished -> null
        seen != null -> seen
        else -> held
    }

/**
 * Решил ли abort проблему, или ход пора сбрасывать.
 *
 * Код ответа abort бесполезен: на несуществующей сессии тот же эндпоинт отдаёт
 * 200 + HTML (SPA-fallback на любой путь), то есть «успех» бывает всегда. Единственный
 * честный признак — уехала ли запись хода из /session/status.
 *
 * Тонкость, ради которой это отдельная функция, а не `!aborted`: если записи не
 * было до abort, останавливать нечего, состояние мёртвое само по себе и abort ничего
 * не изменил. Такой случай тоже требует сброса, хотя abort «прошёл».
 *
 * [runningBefore] — была ли сессия в /session/status до abort,
 * [runningAfter] — осталась ли после него.
 */
internal fun abortResolvedTurn(
    runningBefore: Boolean,
    runningAfter: Boolean,
): Boolean = runningBefore && !runningAfter

/**
 * GET /session/status → Record<sessionId, {...}>. Любая сессия без записи в карте
 * даёт null, то есть тихо и дёшево.
 */
internal fun noticeFromStatus(
    raw: String?,
    sessionId: String,
): ChatNotice? {
    val entry =
        try {
            raw?.trim()?.takeIf { it.startsWith("{") }?.let { JSONObject(it).optJSONObject(sessionId) }
        } catch (_: Exception) {
            null
        }
    return noticeOf(entry)
}
