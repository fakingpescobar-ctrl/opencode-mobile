package org.opencode.mobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила курсора озвучки.
 *
 * Все они проверялись только ушами на телефоне: приложение читало вслух историю на
 * старте, теряло первый ответ при смене сессии, глотало предыдущее сообщение оборота
 * (регрессия a2e85b9) и гасило речь на каждом шаге thinking.
 */
class TtsNarrationTest {
    private val tts = TtsNarration()

    @Test
    fun `первый снапшот не читает историю`() {
        val plan = tts.onSnapshot(input(userCount = 3, assistants = listOf("старый ответ")))

        assertFalse(plan.speak)
        assertFalse(plan.stop)
    }

    @Test
    fun `новый вопрос вооружает и гасит старую речь`() {
        tts.onSnapshot(input(userCount = 3, assistants = listOf("старый ответ")))

        val plan = tts.onSnapshot(input(userCount = 4))

        assertTrue(plan.speak)
        assertTrue(plan.stop)
    }

    @Test
    fun `смена сессии гасит речь и не вооружает`() {
        tts.onSnapshot(input(userCount = 3))
        tts.onSnapshot(input(userCount = 4))
        tts.onSnapshot(input(userCount = 4, assistants = listOf("ответ")))

        val plan = tts.onSnapshot(input(session = "B", userCount = 1, assistants = listOf("чужой ответ")))

        assertTrue(plan.stop)
        assertFalse(plan.speak)
    }

    @Test
    fun `после смены сессии первый ответ вооружается по thinking`() {
        tts.onSnapshot(input(userCount = 3))
        // Смена сессии и новый вопрос приходят одним снапшотом: счётчик уже включает
        // вопрос, поэтому ветка «новый вопрос» его не увидит.
        tts.onSnapshot(input(session = "B", userCount = 1, thinking = true))

        val plan = tts.onSnapshot(input(session = "B", userCount = 1, thinking = true))

        assertTrue(plan.speak)
    }

    @Test
    fun `первое сообщение оборота озвучивается целиком`() {
        tts.onSnapshot(input(userCount = 3))
        tts.onSnapshot(input(userCount = 4))

        val plan = tts.onSnapshot(input(userCount = 4, assistants = listOf("раз")))

        assertFalse(plan.finishPrevious)
        assertTrue(plan.finalMessages.isEmpty())
        assertEquals("раз", plan.liveText)
    }

    @Test
    fun `новое сообщение доигрывает предыдущее`() {
        tts.onSnapshot(input(userCount = 3))
        tts.onSnapshot(input(userCount = 4))
        tts.onSnapshot(input(userCount = 4, assistants = listOf("раз")))

        val plan = tts.onSnapshot(input(userCount = 4, assistants = listOf("раз", "два")))

        assertTrue(plan.finishPrevious)
        assertTrue(plan.finalMessages.isEmpty())
        assertEquals("два", plan.liveText)
    }

    @Test
    fun `пропущенное сообщение отдаётся как final`() {
        tts.onSnapshot(input(userCount = 3))
        tts.onSnapshot(input(userCount = 4))
        tts.onSnapshot(input(userCount = 4, assistants = listOf("раз")))

        // Два новых сообщения за один снапшот: предыдущее доигрываем, пропущенное
        // отдаём целиком, последнее — как растущее.
        val plan = tts.onSnapshot(input(userCount = 4, assistants = listOf("раз", "два", "три")))

        assertTrue(plan.finishPrevious)
        assertEquals(listOf("два"), plan.finalMessages)
        assertEquals("три", plan.liveText)
    }

    @Test
    fun `растущее сообщение уходит на каждом снапшоте`() {
        tts.onSnapshot(input(userCount = 3))
        tts.onSnapshot(input(userCount = 4))
        tts.onSnapshot(input(userCount = 4, assistants = listOf("раз")))

        val same = tts.onSnapshot(input(userCount = 4, assistants = listOf("раз")))
        val grown = tts.onSnapshot(input(userCount = 4, assistants = listOf("раз и два")))

        assertEquals("раз", same.liveText)
        assertFalse(same.finishPrevious)
        assertEquals("раз и два", grown.liveText)
        assertFalse(grown.finishPrevious)
    }

    @Test
    fun `thinking не объявляет ответ финальным`() {
        tts.onSnapshot(input(userCount = 3))
        tts.onSnapshot(input(userCount = 4))

        val plan = tts.onSnapshot(input(userCount = 4, thinking = true, assistants = listOf("половина")))

        assertEquals("половина", plan.liveText)
        assertFalse(plan.liveIsFinal)
    }

    @Test
    fun `завершённый ход объявляет ответ финальным`() {
        tts.onSnapshot(input(userCount = 3))
        tts.onSnapshot(input(userCount = 4))

        val plan = tts.onSnapshot(input(userCount = 4, assistants = listOf("ответ")))

        assertTrue(plan.liveIsFinal)
    }

    @Test
    fun `пустой оборот ничего не отдаёт нарратору`() {
        tts.onSnapshot(input(userCount = 3))
        tts.onSnapshot(input(userCount = 4))

        val plan = tts.onSnapshot(input(userCount = 4))

        assertTrue(plan.speak)
        assertNull(plan.liveText)
        assertTrue(plan.finalMessages.isEmpty())
    }

    private fun input(
        session: String? = "A",
        userCount: Int,
        thinking: Boolean = false,
        assistants: List<String> = emptyList(),
    ) = NarrationInput(
        sessionId = session,
        userCount = userCount,
        thinking = thinking,
        assistants = assistants,
    )
}
