package org.opencode.mobile.server

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Один вариант ответа, предложенный моделью. [label] уходит в сервер дословно —
 * именно по нему opencode сопоставляет ответ с вопросом, поэтому пустые метки
 * отбрасываются на разборе: отвечать «пустым» выбором бессмысленно.
 */
internal data class OpenCodeQuestionOption(
    val label: String,
    val description: String,
)

/**
 * Один вопрос внутри запроса. Сервер шлёт их массивом (`questions`), поэтому
 * вопросов бывает несколько, и ответ обязан быть массивом ответов в том же
 * порядке — иначе модель получит ответы не к тем вопросам.
 */
internal data class OpenCodeQuestionPrompt(
    val header: String,
    val question: String,
    val options: List<OpenCodeQuestionOption>,
    val multiple: Boolean,
    val custom: Boolean,
)

/** Висящий запрос вопроса: пока он не закрыт, модель ждёт и ход не завершается. */
internal data class OpenCodeQuestionRequest(
    val id: String,
    val sessionId: String,
    val prompts: List<OpenCodeQuestionPrompt>,
)

/**
 * Вопросы модели (`question` tool) — второй источник запросов, требующих ответа
 * пользователя, наряду с разрешениями ([OpenCodePermissionApi]). Разбирается
 * здесь, на границе, чтобы UI не знал про форму JSON: наружу отдаётся
 * [OpenCodeQuestionRequest] с уже проверенными полями.
 *
 * Эндпоинт именно `GET /question` (без `/session/...`), потому что он отдаёт
 * висящие вопросы по всем сессиям разом. Сессионный `/api/session/{id}/question`
 * — другая, пустая ветка API: на живом сервере он всегда отвечает `{"data":[]}`
 * и висящий вопрос через него не виден. Спек(opencode 1.18.25) объявляет ответ
 * массивом, но сервер при единственном вопросе отдаёт объект без обёртки —
 * [pendingForSession] принимает оба варианта.
 */
internal object OpenCodeQuestionApi {
    /**
     * Висящий вопрос именно этой сессии. Чужой игнорируем: `/question` общий на
     * все сессии каталога, а карточку вопроса рисуем только там, где модель
     * действительно ждёт нашего ответа.
     */
    fun pendingForSession(
        raw: String?,
        sessionId: String,
    ): OpenCodeQuestionRequest? {
        if (raw.isNullOrBlank() || sessionId.isBlank()) return null
        return runCatching {
            requestsIn(raw)
                .firstOrNull { it.optString("sessionID") == sessionId }
                ?.toQuestionRequest()
        }.getOrNull()
    }

/**
     * Ответ на вопрос. [labelsPerPrompt] — по одному списку выбранных меток на
     * вопрос, в том же порядке, что и `questions` в запросе: сервер сверяет
     * позиции, поэтому ответ на один вопрос из трёх сдвинет остальные.
     *
     * Путь ответа — `/question/{id}/reply`, БЕЗ `/session/...`, ровно как у
     * разбора вопросов. Сессионный вариант `/api/session/{id}/question/{id}/reply`
     * в спеке тоже объявлен, но на живом сервере 1.18.25 отдаёт 404: отвечать
     * по нему — значит молча терять выбор пользователя и снова показывать ту же
     * плашку.
     */
    fun reply(
        port: Int,
        requestId: String,
        labelsPerPrompt: List<List<String>>,
    ): Boolean {
        require(requestId.isNotBlank()) { "Question request id must not be blank" }
        return LocalOpenCodeClient.postAsync(port, actionPath(requestId, REPLY), replyBody(labelsPerPrompt))
    }

    /** Отклонение вопроса — аналог REJECT у разрешений, ход модели продолжается. */
    fun reject(
        port: Int,
        requestId: String,
    ): Boolean {
        require(requestId.isNotBlank()) { "Question request id must not be blank" }
        // Тела у reject нет (в спеке requestBody отсутствует), а postAsync требует
        // строку — шлём пустой объект, чтобы не выдумывать поля, которых сервер
        // не читает.
        return LocalOpenCodeClient.postAsync(port, actionPath(requestId, REJECT), "{}")
    }

    /**
     * Путь действия вынесен и покрыт тестом: в спеке объявлены ОБА варианта —
     * `/question/{id}/…` и `/api/session/{sessionID}/question/{id}/…` — и второй
     * на живом сервере 1.18.25 молча отдаёт 404. Ошибка тут стоила пользователю
     * выбора: карточка выглядела как сработавшая, а вопрос возвращался.
     */
    internal fun actionPath(
        requestId: String,
        action: String,
    ): String =
        "/question/${URLEncoder.encode(requestId, StandardCharsets.UTF_8.name())}/$action"

    private const val REPLY = "reply"

    private const val REJECT = "reject"

    /** Тело ответа: `answers` — массив массивов меток, по одному на вопрос. */
    fun replyBody(labelsPerPrompt: List<List<String>>): String {
        val answers = JSONArray()
        labelsPerPrompt.forEach { labels ->
            val chosen = JSONArray()
            labels.forEach { chosen.put(it) }
            answers.put(chosen)
        }
        return JSONObject().put("answers", answers).toString()
    }

/** Корень ответа — либо массив запросов (как в спеке), либо один объект. */
    private fun requestsIn(raw: String): List<JSONObject> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        return runCatching {
            when (trimmed.first()) {
                '[' ->
                    JSONArray(trimmed).objects()
                '{' -> listOf(JSONObject(trimmed))
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull(::optJSONObject)

    private fun JSONObject.toQuestionRequest(): OpenCodeQuestionRequest? {
        val requestId = optString("id")
        val prompts = optJSONArray("questions")?.objects()?.mapNotNull { it.toPrompt() }.orEmpty()
        // Без id отвечать нечем, а вопрос без текста нечего показать. Оба случая
        // отсекаем одним guard'ом: карточка должна появляться только когда ответ
        // действительно можно отправить.
        if (requestId.isBlank() || prompts.isEmpty()) return null
        return OpenCodeQuestionRequest(
            id = requestId,
            sessionId = optString("sessionID"),
            prompts = prompts,
        )
    }

    private fun JSONObject.toPrompt(): OpenCodeQuestionPrompt? {
        // Текст вопроса в QuestionV2 лежит в `question`; `text` — наследие v1,
        // его тут нет. Без текста показывать нечего, поэтому такой вопрос
        // отбрасываем, а не рисуем карточку с пустотой.
        val text = optString("question")
        if (text.isBlank()) return null
        return OpenCodeQuestionPrompt(
            header = optString("header"),
            question = text,
            options = optJSONArray("options")?.objects()?.mapNotNull { it.toOption() }.orEmpty(),
            multiple = optBoolean("multiple"),
            custom = optBoolean("custom"),
        )
    }

    private fun JSONObject.toOption(): OpenCodeQuestionOption? {
        val label = optString("label")
        if (label.isBlank()) return null
        return OpenCodeQuestionOption(label, optString("description"))
    }
}
