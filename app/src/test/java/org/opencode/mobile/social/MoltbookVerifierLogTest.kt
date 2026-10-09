package org.opencode.mobile.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Формат строки журнала проверяющего — единственное, что тут проверяемо без телефона.
 *
 * Всё, что важно в журнале, проверяется через [MoltbookVerifierLog.line]: имя файла,
 * режим дозаписи и `fsync` живут в Android и в unit-тест не попадают, поэтому тест
 * бьёт по строке, а не по файловой системе.
 */
class MoltbookVerifierLogTest {
    @Test
    fun `запись это одна строка с временем исходом и постом`() {
        val line = MoltbookVerifierLog.line(MoltbookVerifierLog.Kind.CONFIRMED, 1_700_000_000_000L, "p-1", "id=c-1")
        assertEquals("1700000000000s confirmed post=p-1 id=c-1", line)
    }

    @Test
    fun `перевод строки в деталях не рвёт запись на две`() {
        val line = MoltbookVerifierLog.line(MoltbookVerifierLog.Kind.REFUSED, 7L, "p-2", "первая\nвторая\r\nтретья")
        assertEquals(1, line.lines().size)
        assertEquals("7s refused post=p-2 первая вторая третья", line)
    }

    @Test
    fun `отказ и подтверждение это разные исходы в одной строке`() {
        val confirmed = MoltbookVerifierLog.line(MoltbookVerifierLog.Kind.CONFIRMED, 1L, "p", "x")
        val refused = MoltbookVerifierLog.line(MoltbookVerifierLog.Kind.REFUSED, 1L, "p", "x")
        val mismatch = MoltbookVerifierLog.line(MoltbookVerifierLog.Kind.MISMATCH, 1L, "p", "x")
        val divergence = MoltbookVerifierLog.line(MoltbookVerifierLog.Kind.DIVERGENCE, 1L, "p", "x")
        assertEquals("1s confirmed post=p x", confirmed)
        assertEquals("1s refused post=p x", refused)
        assertEquals("1s mismatch post=p x", mismatch)
        assertEquals("1s divergence post=p x", divergence)
    }

    @Test
    fun `имя файла журнала не совпадает с архивом сырых ответов`() {
        // Журнал лежит рядом со witness.log, а не в moltbook/raw: иначе он стал бы
        // частью того, что проверяет, и потерялся бы вместе с ротацией сырых тел.
        assertTrue(MoltbookVerifierLog.FILE_NAME.endsWith(".log"))
        assertTrue(!MoltbookVerifierLog.FILE_NAME.contains("/"))
    }
}
