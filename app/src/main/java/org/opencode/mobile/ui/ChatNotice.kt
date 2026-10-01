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
