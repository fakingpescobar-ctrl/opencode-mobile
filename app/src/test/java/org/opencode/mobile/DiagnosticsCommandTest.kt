package org.opencode.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила состояния диагностики: «открой экран» и «покажи секцию».
 *
 * Зачем тест на такую мелочь: мост отвечает агенту тем, что лежит в этом
 * состоянии, и если `seq` не растёт, повторная просьба про ту же секцию будет
 * выглядеть принятой, а в кадре ничего не изменится — то есть агент запишет
 * «проверено» по эху. Три свойства ниже — ровно то, от чего зависит этот ответ.
 */
class DiagnosticsCommandTest {
    @Test
    fun `состояние экрана возвращается после вызова`() {
        assertTrue(DiagnosticsCommand.setOpen(true))
        assertTrue(DiagnosticsCommand.visible.value)
        assertFalse(DiagnosticsCommand.setOpen(false))
        assertFalse(DiagnosticsCommand.visible.value)
    }

    @Test
    fun `просьба хранит секцию без пробелов и флаг раскрытия`() {
        val request = DiagnosticsCommand.focusSection("  модели  ", expand = false)
        assertEquals("модели", request.section)
        assertFalse(request.expand)
        assertEquals(request, DiagnosticsCommand.focusRequest.value)
    }

    @Test
    fun `номер растёт на каждой просьбе, иначе повтор это эхо`() {
        val first = DiagnosticsCommand.focusSection("модели", expand = true)
        val second = DiagnosticsCommand.focusSection("модели", expand = true)
        assertTrue(second.seq > first.seq)
        assertEquals(second, DiagnosticsCommand.focusRequest.value)
    }

    @Test
    fun `просьба применяется один раз, повторно - нет`() {
        val request = DiagnosticsCommand.focusSection("журнал", expand = true)
        assertTrue(DiagnosticsCommand.claimFocus(request))
        // Вторая композиция экрана перезапускает LaunchedEffect с тем же seq:
        // без отказа она раскрыла бы секцию снова (17.11).
        assertFalse(DiagnosticsCommand.claimFocus(request))
    }

    @Test
    fun `новая просьба применяется после старой`() {
        val first = DiagnosticsCommand.focusSection("музыка", expand = true)
        assertTrue(DiagnosticsCommand.claimFocus(first))
        val second = DiagnosticsCommand.focusSection("музыка", expand = true)
        assertTrue(DiagnosticsCommand.claimFocus(second))
    }
}
