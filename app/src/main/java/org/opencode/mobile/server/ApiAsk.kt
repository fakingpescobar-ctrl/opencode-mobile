package org.opencode.mobile.server

import org.json.JSONArray
import org.json.JSONObject

/**
 * Запрос, на котором сервер ждёт ответа пользователя.
 *
 * [callId] — идентификатор вызова инструмента из `source.id`. Он же `id` части
 * ленты сообщений; по нему добываются вопросы, см. [ApiAsk.card].
 */
internal data class PendingAsk(
    val id: String,
    val callId: String,
    val action: String,
    val resources: List<String>,
)

/**
 * Что показать в карточке.
 *
 * [questions] — всегда непустой список блоков. У вопроса их столько, сколько
 * элементов в `questions[]` инструмента; у разрешения на действие ровно один,
 * и в нём только то, что просят (для команды это `resources`).
 */
internal data class AskCard(
    val ask: PendingAsk,
    val title: String,
    val questions: List<AskQuestion>,
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
     * Карточка вопроса. Тексты и варианты берутся из части ленты, и вопросов
     * там может быть несколько — каждый становится отдельным блоком. Если
     * части ещё нет (гонка: запрос пришёл раньше, чем часть успела долететь,
     * либо лента временно недоступна), показывается то, что есть. Но пустое
     * тело молча выглядит как поломка, поэтому подставляем объяснение: без
     * него «Разрешить» остаётся заблокированной (для вопроса нужен непустой
     * ответ) и непонятно, что вообще печатать.
     */
    private fun questionCard(
        ask: PendingAsk,
        input: JSONObject?,
    ): AskCard {
        val arr = input?.optJSONArray("questions")
        val list =
            (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val item = arr?.optJSONObject(i) ?: return@mapNotNull null
                val block = questionOf(item)
                // Вопрос без текста всё равно должен что-то показывать.
                if (block.text.isBlank()) block.copy(text = NO_TEXT_HINT) else block
            }
        return AskCard(
            ask = ask,
            title = list.firstOrNull()?.header?.ifBlank { DEFAULT_TITLE } ?: DEFAULT_TITLE,
            questions = list.ifEmpty { listOf(AskQuestion("", NO_TEXT_HINT, emptyList())) },
        )
    }

    /** Заголовок карточки, когда вопросов несколько: их не назовёшь одним. */
    private const val DEFAULT_TITLE = "Вопрос"

    /** Подсказка вместо текста вопроса, которого у нас нет. */
    internal const val NO_TEXT_HINT = "Текст вопроса не долетел — ответь своим текстом ниже."

    /** Карточка разрешения на действие: в ней есть только то, что просят. */
    private fun actionCard(ask: PendingAsk): AskCard =
        AskCard(
            ask = ask,
            title = ask.action,
            questions = listOf(AskQuestion("", ask.resources.joinToString("\n"), emptyList())),
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
}
