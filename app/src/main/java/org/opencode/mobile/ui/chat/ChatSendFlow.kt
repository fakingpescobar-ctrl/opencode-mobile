package org.opencode.mobile.ui.chat

import android.content.Context

/**
 * Что нужно решению об отправке, без порта, контекста и HTTP.
 *
 * Отдельный интерфейс, а не прямые вызовы, потому что интересен не сам запрос,
 * а порядок «создать → отправить → проверить → откатить» и то, что при сбое
 * откат нельзя делать вслепую. Проверять это на живом сервере можно только
 * подсунув ему несуществующую модель и дождавшись 500.
 */
internal interface ChatSendTransport {
    fun createSession(): String?

    fun postPrompt(
        sessionId: String,
        text: String,
    ): Boolean

    fun sessionHasMessages(sessionId: String): Boolean

    fun deleteSession(sessionId: String): Boolean
}

/**
 * Чем закончилась отправка.
 *
 * [createdSession] — сессия, созданная ЭТИМ вызовом и оставшаяся после сбоя.
 * Она не всегда удаляется (см. [deliverPrompt]), поэтому имя говорит «создана
 * здесь», а не «откачена»: вызывающему она нужна только для журнала.
 */
internal data class SendOutcome(
    val delivered: Boolean,
    val createdSession: String? = null,
)

/**
 * Отправка сообщения: в существующую сессию или в новую, созданную здесь же.
 *
 * [sessionId] == null значит «активной сессии нет» — тогда создаём новую, и
 * создание с отправкой считаются одной операцией: иначе при сбое отправки
 * остаётся сирота (одно `session.created.1`, ноль сообщений).
 */
internal fun deliverPrompt(
    transport: ChatSendTransport,
    sessionId: String?,
    text: String,
): SendOutcome =
    if (sessionId != null) {
        SendOutcome(delivered = transport.postPrompt(sessionId, text))
    } else {
        deliverInNewSession(transport, text)
    }

/**
 * Новая сессия: создание и отправка как одна операция.
 *
 * Раньше сессия создавалась отдельно, а отправка могла не уйти — и оставалась
 * сирота с одним `session.created.1` и нулём сообщений (три такие нашлись в базе
 * при разборе).
 */
private fun deliverInNewSession(
    transport: ChatSendTransport,
    text: String,
): SendOutcome {
    val fresh = transport.createSession() ?: return SendOutcome(delivered = false)
    val delivered = transport.postPrompt(fresh, text)
    // postAsync отдаёт true сразу после записи тела, поэтому false означает
    // «успешного ответа не было». Но сервер мог успеть сохранить сообщение и
    // упасть уже после этого — проверено на живой сессии: неизвестная модель даёт
    // HTTP 500, а user-сообщение уже лежит в базе. Стирать такую сессию нельзя,
    // поэтому сначала спрашиваем сервер.
    if (!delivered && !transport.sessionHasMessages(fresh)) transport.deleteSession(fresh)
    return SendOutcome(delivered = delivered, createdSession = if (delivered) null else fresh)
}

/**
 * Транспорт поверх живого сервера: единственное место, где решение касается сети.
 *
 * Порт и контекст захвачены, чтобы вызывающий собирал объект одной строкой и не
 * повторял их в четырёх лямбдах.
 */
internal class ServerSendTransport(
    private val port: Int,
    private val context: Context,
) : ChatSendTransport {
    override fun createSession(): String? = createSession(port, context)

    override fun postPrompt(
        sessionId: String,
        text: String,
    ): Boolean = postMessage(port, sessionId, text)

    override fun sessionHasMessages(sessionId: String): Boolean = sessionHasMessages(port, sessionId)

    override fun deleteSession(sessionId: String): Boolean = deleteSession(port, sessionId)
}
