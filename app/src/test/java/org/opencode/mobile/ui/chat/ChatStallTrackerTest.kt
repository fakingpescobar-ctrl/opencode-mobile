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
import org.opencode.mobile.ui.ChatTool

/**
 * Детектор зависания и бюджет повторов — единственное место, где решается
 * «пора снимать спиннер» и «пора самим оборвать ход».
 *
 * Живьём эти пороги проверяются минутами на устройстве и только при реальном
 * сбое провайдера. Здесь — за наносекунды: часы подставлены числом, поэтому
 * можно проверить ровно границу (119_999 против 120_000), а не «примерно».
 */
class ChatStallTrackerTest {
    private fun assistant(text: String = "ответ") = ChatMsg(role = "assistant", text = text)

    private fun user(text: String = "вопрос") = ChatMsg(role = "user", text = text)

    private fun snap(
        vararg messages: ChatMsg,
        thinking: Boolean = true,
        liveTool: ChatTool? = null,
    ) = ChatSnapshot(
        messages = messages.toList(),
        label = "Сессия",
        activeId = "ses_test",
        thinking = thinking,
        liveTool = liveTool,
    )

    // --- зависание ---------------------------------------------------------

    @Test
    fun `ход без думания не зависает и обнуляет метки`() {
        val verdict =
            advanceStall(
                previous = StallState(since = 1L, emptySince = 2L),
                progressed = false,
                snap = snap(user(), assistant(), thinking = false),
                now = 5_000L,
            )

        assertFalse(verdict.stalled)
        assertFalse(verdict.dead)
        assertEquals(StallState(), verdict.state)
    }

    @Test
    fun `обычное думание стартует с текущего момента`() {
        val first =
            advanceStall(
                previous = StallState(),
                progressed = false,
                snap = snap(assistant()),
                now = 1_000L,
            )

        assertEquals(1_000L, first.state.since)
        assertFalse(first.stalled)
    }

    @Test
    fun `обычное думание зависает ровно на 120 секундах`() {
        val before =
            advanceStall(
                previous = StallState(since = 1_000L),
                progressed = false,
                snap = snap(assistant()),
                now = 1_000L + STALL_TIMEOUT_MS - 1,
            )
        val at =
            advanceStall(
                previous = StallState(since = 1_000L),
                progressed = false,
                snap = snap(assistant()),
                now = 1_000L + STALL_TIMEOUT_MS,
            )

        assertFalse(before.stalled)
        assertTrue(at.stalled)
        assertFalse(at.dead)
    }

    @Test
    fun `любой прогресс перезапускает отсчёт`() {
        val verdict =
            advanceStall(
                previous = StallState(since = 1_000L, emptySince = 1_000L),
                progressed = true,
                snap = snap(assistant()),
                now = 90_000L,
            )

        assertEquals(90_000L, verdict.state.since)
        assertEquals(0L, verdict.state.emptySince)
        assertFalse(verdict.stalled)
    }

    @Test
    fun `пустой assistant-шаг даёт 90 секунд`() {
        val state = StallState(emptySince = 1_000L)

        val before =
            advanceStall(
                previous = state,
                progressed = false,
                snap = snap(assistant("")),
                now = 1_000L + STALL_EMPTY_MS - 1,
            )
        val at =
            advanceStall(
                previous = state,
                progressed = false,
                snap = snap(assistant("")),
                now = 1_000L + STALL_EMPTY_MS,
            )

        assertFalse(before.stalled)
        assertTrue(at.stalled)
        assertFalse(at.dead)
        assertEquals(1_000L, before.state.emptySince)
    }

    @Test
    fun `живой тул в пустом шаге не ускоряет зависание`() {
        val verdict =
            advanceStall(
                previous = StallState(since = 1_000L),
                progressed = false,
                snap = snap(assistant(""), liveTool = ChatTool(name = "bash", detail = "sleep 30")),
                now = 1_000L + STALL_EMPTY_MS,
            )

        assertFalse("тул может работать долго — порог общий", verdict.stalled)
        assertEquals(1_000L, verdict.state.since)
    }

    @Test
    fun `висящий user без ответа — мёртвый ход с порогом 180 секунд`() {
        val state = StallState(emptySince = 1_000L)

        val before =
            advanceStall(
                previous = state,
                progressed = false,
                snap = snap(user()),
                now = 1_000L + STALL_DEAD_MS - 1,
            )
        val at =
            advanceStall(
                previous = state,
                progressed = false,
                snap = snap(user()),
                now = 1_000L + STALL_DEAD_MS,
            )

        assertFalse(before.stalled)
        assertTrue(at.stalled)
        assertTrue("мёртвый ход показывается отдельным текстом", at.dead)
    }

    @Test
    fun `прогресс между тиками не даёт ложного зависания`() {
        // Модель думает 3 минуты, но каждый тик приходит новый снапшот:
        // порог не должен сработать, потому что ход живой.
        var state = StallState()
        var stalled = false
        for (tick in 0 until 20) {
            val verdict =
                advanceStall(
                    previous = state,
                    progressed = true,
                    snap = snap(assistant()),
                    now = 1_000L + tick * 10_000L,
                )
            state = verdict.state
            stalled = stalled || verdict.stalled
        }

        assertFalse(stalled)
    }

    // --- бюджет повторов ---------------------------------------------------

    private fun notice(attempt: Int) = ChatNotice(title = "Модель повторяет запрос", message = "Free usage exceeded", attempt = attempt)

    @Test
    fun `тишина не запускает бюджет`() {
        val verdict = advanceRetry(RetryState(), seen = null, turnFinished = false, now = 1_000L)

        assertNull(verdict.state.latch)
        assertFalse(verdict.abort)
        assertEquals(0L, verdict.state.since)
    }

    @Test
    fun `первая плашка запускает отсчёт бюджета`() {
        val verdict = advanceRetry(RetryState(), seen = notice(attempt = 1), turnFinished = false, now = 5_000L)

        assertNotNull(verdict.state.latch)
        assertEquals(5_000L, verdict.state.since)
        assertFalse(verdict.abort)
    }

    @Test
    fun `бюджет по попыткам обрывает ход`() {
        val previous = RetryState(latch = notice(attempt = 7), since = 1_000L)

        val verdict = advanceRetry(previous, seen = notice(attempt = 8), turnFinished = false, now = 2_000L)

        assertTrue(verdict.abort)
        assertEquals("Ход остановлен", verdict.state.latch?.title)
        assertNotNull(verdict.state.stop)
    }

    @Test
    fun `бюджет по времени обрывает ход без роста попыток`() {
        val previous = RetryState(latch = notice(attempt = 2), since = 1_000L)

        val before =
            advanceRetry(previous, seen = notice(attempt = 2), turnFinished = false, now = 1_000L + 600_000L - 1)
        val at =
            advanceRetry(previous, seen = notice(attempt = 2), turnFinished = false, now = 1_000L + 600_000L)

        assertFalse(before.abort)
        assertTrue(at.abort)
    }

    @Test
    fun `остановленный ход не обрывается повторно`() {
        val first = advanceRetry(RetryState(latch = notice(attempt = 8), since = 1_000L), notice(attempt = 9), false, now = 2_000L)
        // Сервер про нашу остановку не знает и продолжает отдавать свой retry.
        val second = advanceRetry(first.state, notice(attempt = 10), false, now = 3_000L)

        assertTrue(first.abort)
        assertFalse("повторный abort крутил бы сессию каждые 400мс", second.abort)
        assertEquals("Ход остановлен", second.state.latch?.title)
    }

    @Test
    fun `успешный шаг гасит плашку и бюджет`() {
        val previous = RetryState(latch = notice(attempt = 3), since = 1_000L)

        val verdict = advanceRetry(previous, seen = null, turnFinished = true, now = 2_000L)

        assertNull(verdict.state.latch)
        assertNull(verdict.state.stop)
        assertEquals(0L, verdict.state.since)
        assertFalse(verdict.abort)
    }

    // --- адаптивный поллинг ------------------------------------------------

    @Test
    fun `активность сбрасывает счётчик стабильности`() {
        assertEquals(0, nextStableRounds(previous = 5, active = true))
    }

    @Test
    fun `стабильный тик увеличивает счётчик`() {
        assertEquals(4, nextStableRounds(previous = 3, active = false))
    }

    @Test
    fun `после трёх стабильных тиков опрос уходит в idle`() {
        assertEquals(POLL_INTERVAL_MS, pollDelayMs(0))
        assertEquals(POLL_INTERVAL_MS, pollDelayMs(STABLE_POLL_ROUNDS - 1))
        assertEquals(POLL_IDLE_MS, pollDelayMs(STABLE_POLL_ROUNDS))
    }
}
