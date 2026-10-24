package org.opencode.mobile.ui.chat

import org.opencode.mobile.server.ApiAsk
import org.opencode.mobile.server.ApiPaths
import org.opencode.mobile.server.ApiShape
import org.opencode.mobile.server.LocalOpenCodeClient
import org.opencode.mobile.server.PendingAsk

/**
 * Отправка промптов и ответы на запросы сервера.
 *
 * Вынесено из `ChatOverlay.kt` без изменения поведения: тело функций перенесено
 * дословно. Всё здесь — блокирующий HTTP через [LocalOpenCodeClient].
 */

/**
 * Отправка сообщения в сессию.
 *
 * [delivery] намеренно НЕ задаётся по умолчанию: без поля сервер ведёт себя как
 * `steer`, то есть сообщение вплетается в идущий ход. Так ведёт себя обычная
 * реплика пользователя, и менять это нельзя — человек должен уметь вмешаться в
 * генерацию. Очередь нужна только для ответа на вопрос агента, и её ставит
 * [answerPendingAsk] явно.
 */
internal fun postMessage(
    port: Int,
    sessionId: String,
    text: String,
    delivery: String? = null,
): Boolean {
    val body = ApiShape.promptBody(text, delivery).toString()
    return LocalOpenCodeClient.postAsync(port, ApiPaths.prompt(sessionId), body)
}

/**
 * Постановка ответа на вопрос в очередь — в отличие от обычной реплики, ждём
 * ответа сервера.
 *
 * [LocalOpenCodeClient.postAsync] возвращает true сразу после записи тела и
 * HTTP-код не читает. Для обычного сообщения это неважно (оно либо дойдёт,
 * либо пользователь увидит, что чат не обновился), а здесь — нет: если
 * сервер отклонит сообщение в очереди, а мы всё равно отклоним висящий
 * вопрос, инструмент упадёт, ход завершится, и ответ пропадёт молча.
 */
internal fun queueMessage(
    port: Int,
    sessionId: String,
    text: String,
): Boolean {
    val body = ApiShape.promptBody(text, ApiShape.DELIVERY_QUEUE).toString()
    return LocalOpenCodeClient.postAccepted(port, ApiPaths.prompt(sessionId), body)
}

/** Что получилось при ответе на запрос сервера. */
internal data class AskOutcome(
    val replied: Boolean,
    val answered: Boolean,
)

/**
 * Ответ на запрос, ждущий ответа.
 *
 * Для команды под «ask» всё просто: разрешить — и ход идёт дальше, это на
 * живом сервере проверено целиком.
 *
 * Для ВОПРОСА приходится идти в обход. Замерено на живом сервере 2.0.27:
 * ответ на вопрос в саму карточку (`decision:once` + `message`) запрос снимает,
 * но ход НЕ продолжается — инструмент навсегда остаётся `status=running`, а
 * сессия для `ApiStatus.isBusy` выглядит занятой навсегда, так что новые
 * сообщения в неё больше не доходят. Проверено и вручную, и из приложения.
 * Работает другая последовательность: ответ уходит обычным сообщением с
 * `delivery:"queue"` (его модель прочтёт следующим ходом), а висящий запрос
 * отклоняется — это единственный способ прекратить инструмент и дать очереди
 * отработать. На застрявшей сессии `ses_ecda34d…` это дало `pending` 1 → 0,
 * `TOOL question` → `status=error` и настоящий ответ модели `finish=stop`.
 * Порядок именно такой: сначала сообщение, чтобы ответ не потерялся, если
 * отклонение не пройдёт.
 *
 * Повторный вызов после частичного провала передаёт [message] равным null:
 * ответ тогда уже в очереди, и второй раз слать его нельзя — иначе модель
 * получила бы один и тот же ответ дважды. Отклонение при этом повторяется,
 * и именно оно снимает висящий инструмент.
 *
 * Для команды обе операции не нужны и не выполняются.
 */
internal fun answerPendingAsk(
    port: Int,
    sessionId: String,
    ask: PendingAsk,
    decision: String,
    message: String?,
): AskOutcome {
    val isQuestion = ask.action == ApiAsk.QUESTION
    val answer = if (isQuestion && !message.isNullOrBlank()) message else null
    if (answer != null && !queueMessage(port, sessionId, answer)) {
        return AskOutcome(replied = false, answered = false)
    }
    val replied = ApiAsk.replyAsk(port, sessionId, ask.id, if (isQuestion) ApiAsk.REJECT else decision, message)
    return AskOutcome(replied = replied, answered = answer != null)
}
