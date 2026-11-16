package org.opencode.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тексты рубильника будильника.
 *
 * Проверяются не ради формулировок, а ради одного свойства: при выключенном
 * будильнике карточка не имеет права обещать тики. Раньше в ней был только
 * выбор периода, и «выключено» нельзя было выразить вообще; теперь выключенное
 * состояние обязано называть последствие («тиков нет»), а кнопка — действие
 * («включить»), иначе человек читает «выключить» как текущий статус.
 */
class MoltbookAlarmTextTest {
    @Test
    fun `выключенный будильник говорит об отсутствии тиков`() {
        val text = alarmStateText(enabled = false)
        assertTrue(text.contains("выключен"))
        assertTrue(text.contains("тиков нет"))
    }

    @Test
    fun `выключенный будильник не обещает никакого периода`() {
        val text = alarmStateText(enabled = false)
        assertFalse(text.contains("раз в"))
        assertFalse(text.contains("мин"))
        assertFalse(text.contains(" ч"))
    }

    @Test
    fun `включённый не дублирует число периода — его показывает ряд чипов`() {
        val text = alarmStateText(enabled = true)
        assertTrue(text.contains("включён"))
        assertFalse(text.contains("раз в"))
    }

    @Test
    fun `кнопка называет действие, а не состояние`() {
        assertEquals("выключить", alarmActionText(enabled = true))
        assertEquals("включить", alarmActionText(enabled = false))
    }
}
