package org.opencode.mobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Ход идёт» по сырой ленте сервера.
 *
 * Главный случай — не выдуманный: 16.11 на живом сервере между шагами приходит
 * assistant с `finish:"tool-calls"` и `time.completed`, а следующий шаг
 * появляется через 20 секунд. Старое правило считало это концом хода, и
 * «Модель думает…» пропадала посреди работы. Тесты закрепляют именно эту
 * границу: завершённый шаг ради инструментов — не конец хода.
 */
class ChatTurnAliveTest {
    private fun msg(
        type: String,
        created: Long,
        finish: String? = null,
        completed: Long = 0L,
    ): String {
        val time =
            if (completed > 0L) {
                "{\"created\":$created,\"completed\":$completed}"
            } else {
                "{\"created\":$created}"
            }
        val finishField = if (finish == null) "null" else "\"$finish\""
        return "{\"type\":\"$type\",\"time\":$time,\"finish\":$finishField}"
    }

    @Test
    fun `работающий шаг — ход открыт`() {
        assertTrue(turnAlive("[${msg("assistant", 1000)}]") == true)
    }

    @Test
    fun `шаг закончился ради инструментов — ход продолжается`() {
        val raw = "[${msg("assistant", 1000, finish = "tool-calls", completed = 1500)}]"
        assertTrue(turnAlive(raw) == true)
    }

    @Test
    fun `финальный ответ — ход закрыт`() {
        val raw = "[${msg("assistant", 1000, finish = "stop", completed = 1500)}]"
        assertFalse(turnAlive(raw) == true)
    }

    @Test
    fun `ход с ошибкой — закрыт, иначе индикатор висел бы на провале`() {
        val raw = "[${msg("assistant", 1000, finish = "error", completed = 1500)}]"
        assertFalse(turnAlive(raw) == true)
    }

    @Test
    fun `последнее сообщение наше — ответа ещё нет`() {
        assertTrue(turnAlive("[${msg("user", 1000)}]") == true)
    }

    @Test
    fun `idle — единственный явный признак конца хода`() {
        assertFalse(turnAlive("[${msg("idle", 2000)}]") == true)
    }

    @Test
    fun `system пропускается и ничего не решает`() {
        val raw = "[${msg("system", 3000)}, ${msg("assistant", 1000, finish = "tool-calls", completed = 1500)}]"
        assertTrue(turnAlive(raw) == true)
    }

    @Test
    fun `новейшее сообщение выбирается по времени, а не по порядку выдачи`() {
        // Старый финальный шаг идёт первым, новый работающий — вторым.
        val raw =
            "[${msg("assistant", 900, finish = "stop", completed = 950)}, ${msg("assistant", 1000)}]"
        assertTrue(turnAlive(raw) == true)
        val reversed =
            "[${msg("assistant", 1000)}, ${msg("assistant", 900, finish = "stop", completed = 950)}]"
        assertTrue(turnAlive(reversed) == true)
    }

    @Test
    fun `конверт с data разбирается так же, как массив`() {
        val raw = "{\"data\":[${msg("assistant", 1000, finish = "tool-calls", completed = 1500)}]}"
        assertTrue(turnAlive(raw) == true)
    }

    @Test
    fun `v1-конверт с info и role`() {
        val raw = "[{\"info\":{\"role\":\"assistant\",\"time\":{\"created\":1000}}}]"
        assertTrue(turnAlive(raw) == true)
    }

    @Test
    fun `битая или пустая лента — незнание, а не конец хода`() {
        assertNull(turnAlive(null))
        assertNull(turnAlive(""))
        assertNull(turnAlive("не json"))
        assertNull(turnAlive("[]"))
    }

    @Test
    fun `пустой assistant-шаг без времени не считается закрытым`() {
        assertEquals(true, turnAlive("[{\"type\":\"assistant\"}]"))
    }
}
