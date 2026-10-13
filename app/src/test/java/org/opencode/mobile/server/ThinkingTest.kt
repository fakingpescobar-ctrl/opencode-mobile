package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Размышление модели приходит в текстовый канал и закрывается тегом `</think>`
 * без открывающего. На живом сервере это видел `opencode/big-pickle`:
 * английский монолог, затем `</think>`, затем ответ на русском.
 */
class ThinkingTest {
    private fun assistant(
        raw: String,
        finished: Boolean = true,
    ) = answerText(THINKING_TYPE, raw, finished)

    @Test
    fun `razmyshlenie pered otvetom otrezaetsya`() {
        val raw = "The user asks in Russian: hello\n\nI should answer briefly.\n</think>Привет, брат."
        assertEquals("Привет, брат.", assistant(raw))
    }

    @Test
    fun `tekst bez tega u zavershennogo otveta ne menyaetsya`() {
        val raw = "Привет, брат. Озвучка работает."
        assertEquals(raw, assistant(raw))
    }

    @Test
    fun `otvet mozhet nachinalsya s tezapov probelov`() {
        val raw = "</think>\n\nВот это вопросик."
        assertEquals("Вот это вопросик.", assistant(raw))
    }

    @Test
    fun `kogda otveta ne ostalos pustaya stroka`() {
        // Модель дописала после тега только пробелы — реплики в ленте быть не должно.
        val raw = "Only reasoning here, </think>   \n  "
        assertEquals("", assistant(raw))
    }

    @Test
    fun `vo vremya potoka mysl ne pokazyvaetsya`() {
        // Тег ещё не пришёл: показывать нечего, и озвучка не должна успеть
        // произнести начало мысли по инкременту.
        val raw = "The user asks in Russian: hello"
        assertEquals("", assistant(raw, finished = false))
    }

    @Test
    fun `potok s uzhshe zakrytoy mysl pokazyvaet otvet`() {
        val raw = "reasoning</think>Привет."
        assertEquals("Привет.", assistant(raw, finished = false))
    }

    @Test
    fun `tekst polzovatelya ne rezhetsya`() {
        // Человек вправе спросить про сам тег — обрезать его вопрос нельзя.
        val raw = "Что значит </think> в ответе модели?"
        assertEquals(raw, answerText("user", raw, finished = true))
        assertEquals(raw, answerText("user", raw, finished = false))
    }

    @Test
    fun `sluzhebnoe soobshchenie ne rezhetsya`() {
        val raw = "The available tools have changed </think>"
        assertEquals(raw, answerText("system", raw, finished = true))
    }

    @Test
    fun `pustaya chast v lentu ne popadaet`() {
        val out = org.json.JSONArray()
        putText(out, assistant("razmyshlenie</think>"))
        assertEquals(0, out.length())
    }

    @Test
    fun `ne pustaya chast popadaet kak text`() {
        val out = org.json.JSONArray()
        putText(out, assistant("razmyshlenie</think>Otvet"))
        assertEquals(1, out.length())
        assertEquals("text", out.getJSONObject(0).optString("type"))
        assertEquals("Otvet", out.getJSONObject(0).optString("text"))
    }

    @Test
    fun `tekst probelov ne popadaet`() {
        val out = org.json.JSONArray()
        putText(out, "   \n  ")
        assertEquals(0, out.length())
    }
}
