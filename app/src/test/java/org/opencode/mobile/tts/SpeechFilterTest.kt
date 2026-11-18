package org.opencode.mobile.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Правила «что из ответа агента читается вслух».
 *
 * Зачем тест: до 18.11 фраза с одним `бэктиком` выбрасывалась целиком, и агент,
 * который пишет «Проверил `sha256` — совпало», молчал всем предложением. Замер
 * по восьми реальным ответам (42 куска) дал 26% годных: 31 кусок терялся из-за
 * разметки. Эти четыре свойства — ровно то, что изменилось.
 */
class SpeechFilterTest {
    @Test
    fun `фраза с инлайн-кодом читается без кода`() {
        val spoken = SpeechFilter.speechableText("Проверил `sha256` — совпало.")
        assertNotNull(spoken)
        assertEquals("Проверил — совпало.", spoken)
    }

    @Test
    fun `строка таблицы не читается`() {
        assertNull(SpeechFilter.speechableText("| POSTED | 255 |"))
    }

    @Test
    fun `код-блок не читается`() {
        assertNull(SpeechFilter.speechableText("```\nval x = 1\n```"))
    }

    @Test
    fun `обычная проза не меняется`() {
        val text = "Тик закрыт, ответов шесть, провалов нет."
        assertEquals(text, SpeechFilter.speechableText(text))
    }

    @Test
    fun `строгий isSpeechable остался строгим`() {
        assertFalse(SpeechFilter.isSpeechable("Проверил `sha256` — совпало."))
    }
}
