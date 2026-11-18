package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogRotatorTest {
    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    private fun rotator(
        maxBytes: Long = MAX_BYTES,
        maxFiles: Int = MAX_FILES,
        budget: Long = BUDGET,
    ) = LogRotator(maxBytes, maxFiles, budget)

    private fun file(
        name: String,
        content: String,
    ): File = tmp.newFile(name).apply { writeText(content) }

    @Test
    fun `ниже порога цепочка не двигается`() {
        val log = file("opencode.log", "short")
        val first = file("opencode.log.1", "old")
        rotator().rotate(log)
        assertEquals("short", log.readText())
        assertEquals("old", first.readText())
        assertFalse(File(tmp.root, "opencode.log.2").exists())
    }

    @Test
    fun `сдвиг затирает самый старый хвост`() {
        val log = file("opencode.log", "current-log")
        file("opencode.log.1", "one")
        file("opencode.log.2", "two")
        rotator().rotate(log)
        assertFalse(log.exists())
        assertEquals("current-log", File(tmp.root, "opencode.log.1").readText())
        assertEquals("one", File(tmp.root, "opencode.log.2").readText())
        assertEquals("two", File(tmp.root, "opencode.log.3").readText())
    }

    @Test
    fun `прунинг удаляет самые старые хвосты первыми`() {
        val log = file("opencode.log", "abcdef")
        val one = file("opencode.log.1", "12345")
        val two = file("opencode.log.2", "12345")
        val three = file("opencode.log.3", "12345")
        // 6 + 3×5 = 21 байт при бюджете 12: удаляются .3 и .2, .1 остаётся.
        rotator(budget = SMALL_BUDGET).prune(log)
        assertTrue(log.exists())
        assertTrue(one.exists())
        assertFalse(two.exists())
        assertFalse(three.exists())
        assertEquals(EXPECTED_TOTAL, log.length() + one.length())
    }

    @Test
    fun `прунинг работает и без сдвига`() {
        val log = file("opencode.log", "short")
        file("opencode.log.1", "x".repeat(BIG_TAIL))
        rotator(budget = TINY_BUDGET).rotate(log)
        assertEquals("short", log.readText())
        assertFalse(File(tmp.root, "opencode.log.1").exists())
    }

    @Test
    fun `отсутствующий лог не создаёт цепочку`() {
        rotator().rotate(File(tmp.root, "opencode.log"))
        assertFalse(File(tmp.root, "opencode.log.1").exists())
    }

    private companion object {
        const val MAX_BYTES = 10L
        const val MAX_FILES = 3
        const val BUDGET = 1_000L
        const val SMALL_BUDGET = 12L
        const val TINY_BUDGET = 100L
        const val BIG_TAIL = 600
        const val EXPECTED_TOTAL = 11L
    }
}
