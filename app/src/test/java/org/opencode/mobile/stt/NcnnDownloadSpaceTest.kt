package org.opencode.mobile.stt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сторож свободного места решает, продолжать ли скачивание набора на 2.33 ГБ.
 *
 * Цена ошибки несимметрична: пропустить нехватку — значит завалить системный раздел и
 * отдать место еде; сработать на ровном месте — значит заблокировать скачивание у того,
 * у кого оно шло. Поэтому оба края проверяются явно.
 */
class NcnnDownloadSpaceTest {
    private val gib = 1024L * 1024 * 1024
    private val needed = 2L * gib

    @Test
    fun `plenty of room is not a shortfall`() {
        assertFalse(spaceIsShort(freeBytes = 5 * gib, neededBytes = needed))
    }

    @Test
    fun `no room at all is a shortfall`() {
        assertTrue(spaceIsShort(freeBytes = 1L, neededBytes = needed))
    }

    /**
     * Граница с запасом: ровно «нужно + запас» ещё проходит, на байт меньше - уже нет.
     * Запас в 100 МБ нужен, потому что система успевает занять место между проверками.
     */
    @Test
    fun `the margin is what decides the borderline`() {
        val margin = 100L * 1024 * 1024
        assertFalse(spaceIsShort(needed + margin, needed))
        assertTrue(spaceIsShort(needed + margin - 1, needed))
    }

    /**
     * Ноль - это «не удалось определить», а не «мест нет». Если прочитать его как нехватку,
     * скачивание блокируется на любом устройстве, где stat отдаёт ноль.
     */
    @Test
    fun `an unknown free space never blocks the download`() {
        assertFalse(spaceIsShort(freeBytes = 0L, neededBytes = needed))
    }

    @Test
    fun `nothing left to download never blocks the download`() {
        assertFalse(spaceIsShort(freeBytes = 0L, neededBytes = 0L))
        assertFalse(spaceIsShort(freeBytes = 1L, neededBytes = 0L))
    }
}
