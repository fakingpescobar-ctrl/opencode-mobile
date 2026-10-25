package org.opencode.mobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencode.mobile.ui.ChatMsg
import org.opencode.mobile.ui.ChatNotice
import org.opencode.mobile.ui.ChatSnapshot
import org.opencode.mobile.ui.RETRY_ATTEMPT_LIMIT

/**
 * Порядок применения правил в одном тике опроса.
 *
 * До выноса это жило прямо в Composable: зависание, бюджет повторов, защёлка,
 * дельта-публикация, счётчик ответов и пауза вызывались по очереди и передавали
 * друг другу результаты. Проверить эту цепочку можно было только на устройстве,
 * дождавшись реального сбоя провайдера. Здесь часы подставлены числом, поэтому
 * проверяется ровно граница (119_999 против 120_000) и ровно тот кадр, в котором
 * снимается спиннер.
 */
class ChatPollTickTest {
    private fun assistant(
        text: String = "ответ",
        notice: String? = null,
    ) = ChatMsg(role = "assistant", text = text, notice = notice)

    private fun user(text: String = "вопрос") = ChatMsg(role = "user", text = text)

    private fun snap(
        vararg messages: ChatMsg,
        thinking: Boolean = false,
        notice: ChatNotice? = null,
    ) = ChatSnapshot(
        messages = messages.toList(),
        label = "Сессия",
        activeId = "ses_test",
        thinking = thinking,
        notice = notice,
    )

    // --- публикация в UI ---------------------------------------------------

    @Test
    fun `первый тик публикует снапшот и снимает базовую линию один раз`() {
        val fresh = snap(user(), assistant(), thinking = false)

        val first = advancePoll(ChatPollState(), shown = null, fresh = fresh, now = 1_000L)

        assertNotNull("первый снапшот обязан дойти до UI", first.publish)
        assertEquals(1, first.knownMsgs)
        assertEquals("индекс завершённого ответа для вибрации", 1, first.baselineRendered)
        assertTrue(first.state.baselineDone)

        val second = advancePoll(first.state, shown = first.publish, fresh = fresh, now = 1_400L)

        assertNull("базовая линия снимается ровно один раз", second.baselineRendered)
    }

    @Test
    fun `неизменившийся снапшот не публикуется, но пауза растёт до idle`() {
        val fresh = snap(user(), assistant())
        var state = ChatPollState()
        var shown: ChatSnapshot? = null

        val delays = mutableListOf<Long>()
        // Первый тик публикует снапшот, а публикация считается активностью и
        // сбрасывает счётчик стабильных тиков. Поэтому idle наступает не на
        // STABLE_POLL_ROUNDS-м тике, а на следующем за ним.
        repeat(STABLE_POLL_ROUNDS + 1) { i ->
            val decision = advancePoll(state, shown, fresh, now = 1_000L + i * 400L)
            state = decision.state
            shown = decision.publish ?: shown
            delays += decision.delayMs
        }

        assertEquals(
            "после публикации лента стоит — тики должны растянуться",
            listOf(POLL_INTERVAL_MS, POLL_INTERVAL_MS, POLL_INTERVAL_MS, POLL_IDLE_MS),
            delays,
        )
        assertNull("второй и третий тики показывать нечего", advancePoll(state, shown, fresh, 5_000L).publish)
    }

    @Test
    fun `думание сбрасывает счётчик стабильных тиков`() {
        val idle = ChatPollState(stableRounds = STABLE_POLL_ROUNDS)

        val decision = advancePoll(idle, shown = null, fresh = snap(user(), thinking = true), now = 1_000L)

        assertEquals("активный ход опрашивается быстро", POLL_INTERVAL_MS, decision.delayMs)
        assertEquals(0, decision.state.stableRounds)
    }

    @Test
    fun `молчание сервера не двигает счётчики и растягивает паузу`() {
        val previous = ChatPollState(knownMsgs = 3, stableRounds = STABLE_POLL_ROUNDS)

        val decision = advancePoll(previous, shown = null, fresh = null, now = 1_000L)

        assertNull(decision.publish)
        assertEquals("известное число ответов не сбрасывается", 3, decision.knownMsgs)
        assertEquals(POLL_IDLE_MS, decision.delayMs)
        assertNull(decision.abort)
    }

    // --- зависание ---------------------------------------------------------

    @Test
    fun `зависание попадает в тот же кадр, что и снятый спиннер`() {
        val fresh = snap(user(), assistant(), thinking = true)
        val started = advancePoll(ChatPollState(), shown = null, fresh = fresh, now = 1_000L)
        assertFalse("на старте хода зависания нет", started.publish?.stalled ?: false)

        val stuck =
            advancePoll(
                started.state,
                shown = started.publish,
                fresh = fresh,
                now = 1_000L + STALL_TIMEOUT_MS,
            )

        val published = stuck.publish
        assertNotNull("зависание обязано дойти до UI", published)
        assertTrue(published!!.stalled)
        assertFalse(published.stalledDead)
    }

    @Test
    fun `мёртвый ход отличается от зависшего думания`() {
        // Ход мёртв, когда последним осталось наше собственное сообщение:
        // ассистент не создал ни одного шага.
        val fresh = snap(user(), thinking = true)
        val started = advancePoll(ChatPollState(), shown = null, fresh = fresh, now = 1_000L)

        val dead =
            advancePoll(
                started.state,
                shown = started.publish,
                fresh = fresh,
                now = 1_000L + STALL_DEAD_MS,
            )

        assertTrue(dead.publish?.stalled ?: false)
        assertTrue("для мёртвого хода текст в UI другой", dead.publish?.stalledDead ?: false)
    }

    // --- бюджет повторов ---------------------------------------------------

    @Test
    fun `исчерпанный бюджет отдаёт сессию для обрыва ровно один раз`() {
        val exhausted = ChatNotice(title = "Модель повторяет запрос", message = "провайдер молчит", attempt = RETRY_ATTEMPT_LIMIT)
        val fresh = snap(user(), assistant(), thinking = true, notice = exhausted)

        val first = advancePoll(ChatPollState(), shown = null, fresh = fresh, now = 1_000L)

        assertEquals("обрывать надо ту сессию, которую назвал сервер", "ses_test", first.abort?.sessionId)
        assertEquals(RETRY_ATTEMPT_LIMIT, first.abort?.attempt)
        assertEquals("плашка «Ход остановлен»", "Ход остановлен", first.latch?.title)

        val second = advancePoll(first.state, shown = first.publish, fresh = fresh, now = 1_400L)

        assertNull(
            "без этой защиты abortSession уходил бы на каждые 400мс, пока висит плашка",
            second.abort,
        )
    }

    @Test
    fun `живой ход не обрывается`() {
        val retrying = ChatNotice(title = "Модель повторяет запрос", message = "провайдер молчит", attempt = 2)
        val fresh = snap(user(), assistant(), thinking = true, notice = retrying)

        val decision = advancePoll(ChatPollState(), shown = null, fresh = fresh, now = 1_000L)

        assertNull("две попытки из восьми — рано", decision.abort)
        assertEquals("плашка повторов видна пользователю", retrying, decision.latch)
    }

    // --- счётчик уведомлений ----------------------------------------------

    @Test
    fun `провалившийся ход уменьшает счётчик завершённых ответов`() {
        // Так и происходит на живой сессии: ответ шёл как обычный, а после отказа
        // провайдера у него появляется диагностика и он перестаёт считаться.
        val fresh = snap(user(), assistant(notice = "нет ответа"), thinking = false)

        val decision = advancePoll(ChatPollState(knownMsgs = 2), shown = null, fresh = fresh, now = 1_000L)

        assertEquals(0, decision.knownMsgs)
    }

    @Test
    fun `растущий счётчик доезжает до UI`() {
        val fresh = snap(user(), assistant("раз"), assistant("два"), thinking = false)

        val decision = advancePoll(ChatPollState(knownMsgs = 1), shown = null, fresh = fresh, now = 1_000L)

        assertEquals(2, decision.knownMsgs)
    }
}
