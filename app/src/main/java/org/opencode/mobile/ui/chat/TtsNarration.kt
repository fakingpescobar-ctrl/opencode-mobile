package org.opencode.mobile.ui.chat

/**
 * Курсор озвучки: какие реплики уже отданы нарратору и в какой сессии.
 *
 * Вынесено из `ChatOverlay`, где это был десяток `var` в композиции и ветвистый
 * `if/else` на каждый снапшот. Правила здесь такие, что проверяются только ушами
 * на телефоне, поэтому каждое закреплено тестом:
 *
 * 1. Первый снапшот после запуска НЕ читает историю — только запоминает счётчик
 *    вопросов. Иначе приложение на старте читает вслух последний ответ из ленты.
 * 2. Смена сессии — жёсткая граница: речь гаснет, курсор сбрасывается, вооружения
 *    нет. У новой сессии своя история, читать её без запроса нельзя.
 * 3. Новый вопрос — жёсткий сброс: очередь чистим, речь гасим, вооружаемся.
 * 4. Смена сессии и новый вопрос могут прийти ОДНИМ снапшотом (человек успевает
 *    отправить сообщение до следующего тика поллинга). Тогда счётчик уже включает
 *    новый вопрос, правило 3 его не видит — и первый ответ молча пропал бы.
 *    Ориентир: у живой переписки `thinking=true`, у истории он снят.
 *
 * Что и почему попадает в озвучку:
 *
 * - Пустой текст — это начало thinking или tool call, а не откат ответа. Отдать
 *   его нарратору значит принять снапшот короче уже озвученного: тот сбросит
 *   чанкер и погасит речь на каждом таком шаге (в логе 6 сбросов на 6 шагов).
 * - Берутся ВСЕ assistant-сообщения оборота, а не только последнее: агент часто
 *   пишет несколько предложений подряд (рассуждение -> tool call -> новый текст),
 *   и `lastOrNull` молча ронял предыдущие. Каждое новое доигрывает остаток
 *   предыдущего, а не гасит его.
 * - Последнее (растущее) сообщение отдаётся на КАЖДОМ снапшоте — по нему нарратор
 *   считает дельту. Пока `thinking=true`, оно уходит с `isFinal=false`, и чанкер
 *   держит текст до конца предложения; без повторной передачи этот текст никогда
 *   не доигран (регрессия a2e85b9 — озвучка молчала целиком).
 * - Срез ТОЛЬКО текущего оборота: сообщения после последнего вопроса. Фильтр по
 *   всей ленте читал историю заново на каждом вопросе, а при переключении сессий —
 *   прочитанное из чужой переписки.
 * - Список НЕ фильтруется по наличию диагностики. Причина отказа лежит в отдельном
 *   поле `notice`, в тексте её нет, читать нечего. А фильтр сдвигал бы индексы на
 *   ходу: пока ход стримится, ошибки в нём ещё нет и сообщение в список попадает,
 *   а после провала исчезает — хвост отъезжал назад, и последнее предложение
 *   начинало читаться заново с предыдущей реплики.
 */
internal class TtsNarration {
    private var seenUserMsgs = -1
    private var saidAssistants = -1
    private var sessionId: String? = null
    private var armed = false

    fun onSnapshot(input: NarrationInput): NarrationPlan {
        var stop = false
        if (sessionId != input.sessionId) {
            // Первая сессия после запуска: гасить нечего, но и читать историю нельзя.
            stop = sessionId != null
            sessionId = input.sessionId
            armed = false
            seenUserMsgs = input.userCount
            saidAssistants = -1
        } else if (seenUserMsgs < 0) {
            seenUserMsgs = input.userCount
        } else if (input.userCount != seenUserMsgs) {
            seenUserMsgs = input.userCount
            armed = true
            saidAssistants = -1
            stop = true
        } else if (!armed && input.thinking) {
            armed = true
            saidAssistants = -1
        }
        if (!armed) return NarrationPlan(stop = stop)

        val assistants = input.assistants
        val lastIdx = assistants.size - 1
        var finishPrevious = false
        val finalMessages = mutableListOf<String>()
        if (lastIdx > saidAssistants) {
            // Сообщение на позиции saidAssistants уже пошло в озвучку и расти больше
            // не будет: доигрываем его остаток. -1 означает «ещё ни одного не
            // начато», тогда доигрывать нечего, а все новые (включая первое, индекс
            // 0) озвучиваются целиком.
            finishPrevious = saidAssistants >= 0
            for (i in maxOf(saidAssistants + 1, 0) until lastIdx) {
                finalMessages += assistants[i]
            }
            saidAssistants = lastIdx
        }
        return NarrationPlan(
            stop = stop,
            speak = true,
            finishPrevious = finishPrevious,
            finalMessages = finalMessages,
            liveText = assistants.lastOrNull(),
            liveIsFinal = !input.thinking,
        )
    }
}

/** Всё, что нужно курсору из одного снапшота. */
internal data class NarrationInput(
    val sessionId: String?,
    val userCount: Int,
    val thinking: Boolean,
    /** Реплики assistant текущего оборота, пустые отброшены. */
    val assistants: List<String>,
)

/** Что сделать с нарратором по этому снапшоту. */
internal data class NarrationPlan(
    val stop: Boolean = false,
    val speak: Boolean = false,
    val finishPrevious: Boolean = false,
    val finalMessages: List<String> = emptyList(),
    val liveText: String? = null,
    val liveIsFinal: Boolean = false,
)
