package org.opencode.mobile.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Оригинал под переводом — лишний абзац, а не контекст.
 *
 * Макс сформулировал прямо: «сообщения что вылазит с переводом под ними текст на
 * английском или других языках они не нужны только русский язык». Проверяется
 * решение, а не вёрстка: при наличии перевода оригинал не показывается никогда;
 * без перевода — показывается, иначе вопрос выглядел бы пустым.
 */
class MoltbookPendingTextTest {
    @Test
    fun `есть перевод — оригинал не показываем`() {
        assertFalse(showOriginal(bodyRu = "Привет, как дела?", body = "Hi, how are you?"))
    }

    @Test
    fun `перевода нет — показываем оригинал`() {
        assertTrue(showOriginal(bodyRu = "", body = "Hi, how are you?"))
    }

    @Test
    fun `пустой вопрос не рисует пустую строку`() {
        assertFalse(showOriginal(bodyRu = "", body = ""))
    }

    @Test
    fun `пробельный перевод считается отсутствующим`() {
        assertTrue(showOriginal(bodyRu = "   ", body = "Hi"))
    }
}
