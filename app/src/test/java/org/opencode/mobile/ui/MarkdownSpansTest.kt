package org.opencode.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Чистая логика подсветки: проверяем диапазоны, без Compose и Android. */
class MarkdownSpansTest {
    @Test
    fun `заголовок помечается целиком`() {
        val spans = markdownSpans("## Заголовок\nобычный текст")
        assertEquals(1, spans.size)
        assertEquals(MarkKind.HEADING, spans[0].kind)
        assertEquals(0, spans[0].start)
        assertEquals("## Заголовок".length, spans[0].end)
    }

    @Test
    fun `жирный код и ссылка размечаются внутри строки`() {
        val spans = markdownSpans("**жирный** и `код` плюс [ссылка](https://example.com)")
        val kinds = spans.map { it.kind }
        assertTrue(MarkKind.BOLD in kinds)
        assertTrue(MarkKind.INLINE_CODE in kinds)
        assertTrue(MarkKind.LINK in kinds)
        assertTrue(spans.all { it.start in 0..it.end && it.end <= "**жирный** и `код` плюс [ссылка](https://example.com)".length })
    }

    @Test
    fun `кодовый блок красит все строки включая ограждение`() {
        val text = "до\n```js\nconst a = 1;\n```\nпосле"
        val spans = markdownSpans(text)
        assertEquals(3, spans.size)
        assertTrue(spans.all { it.kind == MarkKind.CODE })
    }

    @Test
    fun `цитата список и разделитель размечены`() {
        val kinds = markdownSpans("> цитата\n- пункт\n---").map { it.kind }
        assertTrue(MarkKind.QUOTE in kinds)
        assertTrue(MarkKind.LIST in kinds)
        assertTrue(MarkKind.RULE in kinds)
    }

    @Test
    fun `обычный текст без разметки не даёт span`() {
        assertTrue(markdownSpans("просто предложение без звездочек").isEmpty())
    }

    @Test
    fun `диапазоны не пересекаются`() {
        val text = "# Заголовок\n**жирный** и *курсив*\n- пункт с `кодом`\n> цитата"
        val spans = markdownSpans(text)
        for (i in 1 until spans.size) assertTrue(spans[i - 1].end <= spans[i].start)
        assertTrue(spans.all { it.start in 0..it.end && it.end <= text.length })
    }
}
