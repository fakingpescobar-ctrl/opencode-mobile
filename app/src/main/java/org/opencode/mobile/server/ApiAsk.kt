package org.opencode.mobile.server

import org.json.JSONArray
import org.json.JSONObject

/**
 * Один вариант ответа на вопрос, предложенный инструментом `question`.
 */
internal data class AskOption(
    val label: String,
    val description: String,
)

/**
 * Запрос, на котором сервер ждёт ответа пользователя.
 *
 * [callId] — идентификатор вызова инструмента из `source.id`. Он же `id` части
 * ленты сообщений; по нему добывается текст вопроса, см. [ApiAsk.card].
 */
internal data class PendingAsk(
    val id: String,
    val callId: String,
    val action: String,
    val resources: List<String>,
)

/**
 * Что показать в карточке. [options] пуст у любого запроса, кроме `question`:
 * варианты придумал инструмент, а не сервер.
 */
internal data class AskCard(
    val ask: PendingAsk,
    val title: String,
    val body: String,
    val options: List<AskOption>,
)

/**
 * Запросы, на которых opencode ждёт пользователя: разрешения И вопросы.
 *
 * ЗАЧЕМ ОДИН РАЗБОР, А НЕ ДВА. В v2 они приходят одним списком,
 * `GET /api/session/{id}/permission`, и различаются полем `action`:
 * `question` — это вопрос, всё остальное (`shell`, `websearch`, …) — разрешение
 * на действие. План `docs/MIGRATION-V2.md` держал их раздельно (карточка
 * вопросов через `/form`, карточка разрешений через `/permission`), но на живом
 * 2.0.27 проверено: при висящем вопросе `/form` остаётся `{"data":[]}`, а
 * вопрос лежит в `/permission` с `action:"question"`. Отдельной формы на
 * вопросы в v2 нет, поэтому и карточка одна.
 *
 * ГДЕ ЛЕЖИТ ТЕКСТ ВОПРОСА. В самом запросе его нет: у вопроса `resources:["*"]`,
 * то есть «всё что угодно», и разбирать это нечего. Текст лежит в ленте — в
 * части инструмента `question` поле `state.input.questions[0]`. Сцепка —
 * `source.id` запроса и `id` части. Поэтому [card] получает ленту сообщений.
 * У разрешений наоборот: команда приходит в самом запросе, в `resources`, и
 * лента им не нужна.
 *
 * ЧИСТОЕ И СЕТЕВОЕ. [pending], [replyBody] и [card] сети не трогают — их
 * сколько угодно и они покрыты юнит-тестами на настоящих телах. Сеть живёт
 * ровно в двух функциях, [askPending] и [replyAsk]; они видны по имени и по
 * сигнатуре — там обязательно есть `port`.
 */
internal object ApiAsk {
    /** `action` вопроса. Всё остальное — разрешение на действие. */
    const val QUESTION = "question"

    /** Решение «разрешить один раз» — для команды под «ask». */
    const val ONCE = "once"

    /**
     * Решение «отклонить». Для вопроса это не отказ отвечать, а способ
     * разблокировать ход: см. [answerPendingAsk].
     */
    const val REJECT = "reject"

    /**
     * Таймаут ответа на разрешение. Отличается от общего клиентского: сервер
     * после принятия решения продолжает ход, и короткий таймаут обрывал
     * успешный запрос, выдавая его за неуспешный.
     */
    private const val REPLY_READ_TIMEOUT_MS = 20000

    /**
     * Запросы, ждущие ответа, по форме `{data:[…]}`.
     *
     * null и пустой список — РАЗНЫЕ ответы, и различать их обязательно. Пустой
     * список означает «спрашивать нечего», и карточку надо убрать. null —
     * «сервер не ответил или тело не разобралось», и карточку надо ОСТАВИТЬ:
     * не показать запрос, на который сервер ждёт, значит оставить ход висеть
     * навсегда, и пользователь не сможет его разблокировать ничем.
     */
    fun pending(raw: String?): List<PendingAsk>? {
        val arr = asJsonObject(raw)?.optJSONArray("data") ?: asJsonArray(raw) ?: return null
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { item -> pendingOf(item) } }
    }

    /**
     * Тело ответа на запрос.
     *
     * Ключ называется `decision`, и это проверено на живом сервере: `{reply:…}`
     * и пустое тело дают 400, `{decision:"once"}` — 204. Для вопроса
     * добавляется `message` с текстом ответа; разрешению он не нужен и сервер
     * его не принимает.
     */
    fun replyBody(
        decision: String,
        message: String? = null,
    ): String {
        val body = JSONObject().put("decision", decision)
        if (!message.isNullOrBlank()) body.put("message", message)
        return body.toString()
    }

    /**
     * Карточка для показа. [messages] — уже приведённая лента из
     * [ApiShape.messages]; для разрешений она не читается, для вопроса нужна.
     */
    fun card(
        ask: PendingAsk,
        messages: JSONArray?,
    ): AskCard {
        val input = ask.callId.takeIf { it.isNotEmpty() }?.let { toolInput(messages, it) }
        return if (ask.action == QUESTION) questionCard(ask, input) else actionCard(ask)
    }

    /** Запросы, ждущие ответа прямо сейчас. Сеть. null — как и в [pending]. */
    fun askPending(
        port: Int,
        sessionId: String,
    ): List<PendingAsk>? = pending(LocalOpenCodeClient.get(port, ApiPaths.permissions(sessionId)))

    /** Отправить ответ. Сеть. false — сервер не принял, карточку надо оставить. */
    fun replyAsk(
        port: Int,
        sessionId: String,
        requestId: String,
        decision: String,
        message: String? = null,
    ): Boolean =
        LocalOpenCodeClient.postAccepted(
            port,
            ApiPaths.permissionReply(sessionId, requestId),
            replyBody(decision, message),
            // Ответ на разрешение не обязан быть мгновенным: сервер принимает
            // решение и продолжает ход, а таймаут в 4 секунды обрывал такой
            // POST раньше, чем тот успевал дойти до кода, и карточка писала
            // «ответ не ушёл» при вполне принятом ответе.
            readTimeoutMs = REPLY_READ_TIMEOUT_MS,
        )

    private fun pendingOf(item: JSONObject): PendingAsk? {
        val id = item.optString("id").trim()
        if (id.isEmpty()) return null
        val resources = item.optJSONArray("resources")
        return PendingAsk(
            id = id,
            callId = item.optJSONObject("source")?.optString("id").orEmpty(),
            action = item.optString("action"),
            resources =
                (0 until (resources?.length() ?: 0)).mapNotNull {
                    resources?.optString(it)?.takeIf { text -> text.isNotBlank() }
                },
        )
    }

    /**
     * Карточка вопроса. Текст и варианты берутся из части ленты; если её ещё
     * нет (гонка: запрос пришёл раньше, чем часть успела долететь, либо лента
     * временно недоступна), показывается то, что есть. Но пустое тело молча
     * выглядит как поломка, поэтому подставляем объяснение: без него
     * «Разрешить» остаётся заблокированной (для вопроса нужен непустой ответ)
     * и непонятно, что вообще печатать.
     */
    private fun questionCard(
        ask: PendingAsk,
        input: JSONObject?,
    ): AskCard {
        val first = input?.optJSONArray("questions")?.optJSONObject(0)
        val body = first?.optString("question").orEmpty()
        return AskCard(
            ask = ask,
            title = first?.optString("header")?.takeIf { it.isNotBlank() } ?: "Вопрос",
            body = body.ifBlank { NO_TEXT_HINT },
            options = optionsOf(first),
        )
    }

    /** Подсказка вместо текста вопроса, которого у нас нет. */
    private const val NO_TEXT_HINT = "Текст вопроса не долетел — ответь своим текстом ниже."

    /** Карточка разрешения на действие: в ней есть только то, что просят. */
    private fun actionCard(ask: PendingAsk): AskCard =
        AskCard(
            ask = ask,
            title = ask.action,
            body = ask.resources.joinToString("\n"),
            options = emptyList(),
        )

    /** `state.input` части инструмента с указанным id вызова. */
    private fun toolInput(
        messages: JSONArray?,
        callId: String,
    ): JSONObject? {
        for (m in 0 until (messages?.length() ?: 0)) {
            val parts = messages?.optJSONObject(m)?.optJSONArray("parts") ?: continue
            for (p in 0 until parts.length()) {
                val part = parts.optJSONObject(p)
                if (part?.optString("type") == "tool" && part.optString("id") == callId) {
                    return part.optJSONObject("state")?.optJSONObject("input")
                }
            }
        }
        return null
    }

    private fun optionsOf(question: JSONObject?): List<AskOption> {
        val arr = question?.optJSONArray("options")
        return (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val option = arr?.optJSONObject(i)
            val label = option?.optString("label").orEmpty()
            if (label.isEmpty()) null else AskOption(label, option?.optString("description").orEmpty())
        }
    }
}
