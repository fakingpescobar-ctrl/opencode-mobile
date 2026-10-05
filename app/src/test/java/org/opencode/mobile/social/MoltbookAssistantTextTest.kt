package org.opencode.mobile.social

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Форма сообщений opencode снята с живого сервера телефона, а не придумана: роль
 * лежит в `info`, а не на верхнем уровне. На выдуманной форме тест был бы зелёным,
 * а на живой тик молчал бы 180 секунд и ничего не публиковал.
 */
class MoltbookAssistantTextTest {
    @Test
    fun `берёт текст завершённого ответа`() {
        val messages =
            JSONArray(
                """
                [
                  {
                    "info": { "role": "user", "time": { "created": 1 } },
                    "parts": [{ "type": "text", "text": "Ответь одним словом" }]
                  },
                  {
                    "info": {
                      "role": "assistant",
                      "time": { "created": 2, "completed": 3 },
                      "finish": "stop"
                    },
                    "parts": [
                      { "type": "step-start" },
                      { "type": "reasoning", "text": "размышляю" },
                      { "type": "text", "text": "Да, работает." }
                    ]
                  }
                ]
                """.trimIndent(),
            )

        assertEquals("Да, работает.", MoltbookTicker.lastAssistantText(messages))
    }

    @Test
    fun `незавершённый ответ не публикуем`() {
        val messages =
            JSONArray(
                """
                [{
                  "info": { "role": "assistant", "time": { "created": 2 } },
                  "parts": [{ "type": "text", "text": "обрванный черновик" }]
                }]
                """.trimIndent(),
            )

        assertNull(MoltbookTicker.lastAssistantText(messages))
    }

    @Test
    fun `ответ без текстовых частей не публикуем`() {
        val messages =
            JSONArray(
                """
                [{
                  "info": { "role": "assistant", "time": { "created": 2, "completed": 3 } },
                  "parts": [{ "type": "reasoning", "text": "только мысли" }]
                }]
                """.trimIndent(),
            )

        assertNull(MoltbookTicker.lastAssistantText(messages))
    }

    @Test
    fun `пустая лента сессии не роняет тик`() {
        assertNull(MoltbookTicker.lastAssistantText(JSONArray("[]")))
    }
}
