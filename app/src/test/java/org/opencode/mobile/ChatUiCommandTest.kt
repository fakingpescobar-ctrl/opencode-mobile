package org.opencode.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила состояния панели чата: «открой tts» и «эта просьба применена один раз».
 *
 * Зачем тест на такую мелочь: мост отвечает агенту тем, что лежит в этом
 * состоянии. Если `seq` не растёт, повторная просьба про ту же панель выглядит
 * принятой, а на экране ничего не меняется — то есть агент запишет «открыто» по
 * эху. Ровно та же болезнь была у фокуса секций диагностики (17.11).
 */
class ChatUiCommandTest {
    @Test
    fun `имя панели нормализуется и запоминается`() {
        val request = ChatUiCommand.open("  TTS ")
        assertEquals("tts", request.panel)
        assertEquals(request, ChatUiCommand.request.value)
    }

    @Test
    fun `null закрывает панель`() {
        ChatUiCommand.open("stt")
        val closed = ChatUiCommand.open(null)
        assertNull(closed.panel)
        assertNull(ChatUiCommand.request.value?.panel)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `неизвестная панель не принимается`() {
        ChatUiCommand.open("секретная")
    }

    @Test
    fun `номер растёт, и одна просьба применяется один раз`() {
        val first = ChatUiCommand.open("tts")
        val second = ChatUiCommand.open("tts")
        assertTrue(second.seq > first.seq)
        assertTrue(ChatUiCommand.claim(second))
        assertFalse(ChatUiCommand.claim(second))
        assertEquals(second, ChatUiCommand.applied.value)
    }
}
