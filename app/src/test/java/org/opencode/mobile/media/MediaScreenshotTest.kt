package org.opencode.mobile.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Имя файла снимка.
 *
 * Запрошенное имя приходит от агента, то есть текст в нём — недоверенный. Проверяется
 * главное: чем бы имя ни было, файл остаётся внутри Download и оканчивается на `.png`.
 * Отказ от странного имени здесь не нужен — снимок всё равно должен получиться, поэтому
 * непрошедшее имя заменяется меткой времени, а не ошибкой.
 */
class MediaScreenshotTest {
    @Test
    fun `без имени — метка времени`() {
        assertEquals("shot-1700000000000.png", screenshotName(null, 1_700_000_000_000L))
        assertEquals("shot-1700000000000.png", screenshotName("   ", 1_700_000_000_000L))
    }

    @Test
    fun `обычное имя сохраняется`() {
        assertEquals("shot-diag.png", screenshotName("diag", 1L))
    }

    @Test
    fun `расширение не удваивается`() {
        assertEquals("shot-diag.png", screenshotName("diag.png", 1L))
        assertEquals("shot-diag.png", screenshotName("diag.PNG", 1L))
    }

    @Test
    fun `путь в имени не уводит файл из Download`() {
        val name = screenshotName("/data/user/0/org.opencode.mobile.debug/files/moltkey", 1L)
        assertEquals("shot-moltkey.png", name)
        assertFalse(name.contains('/'))
    }

    @Test
    fun `две точки в имени не остаются`() {
        val name = screenshotName("../../secret", 1L)
        assertFalse(name.contains(".."))
    }

    @Test
    fun `служебные символы вычищаются`() {
        val name = screenshotName("a b\nc;rm", 1L)
        assertEquals("shot-abcrm.png", name)
    }

    @Test
    fun `слишком длинное имя обрезается`() {
        val name = screenshotName("x".repeat(300), 1L)
        assertTrue(name.length <= 5 + 40 + 4)
    }

    @Test
    fun `файл всегда внутри Download и всегда png`() {
        val file = screenshotFile(null, 1L)
        assertEquals(SCREENSHOT_DIR, file.parent)
        assertTrue(file.path.startsWith("$SCREENSHOT_DIR/"))
        assertTrue(file.path.endsWith(".png"))
    }
}
