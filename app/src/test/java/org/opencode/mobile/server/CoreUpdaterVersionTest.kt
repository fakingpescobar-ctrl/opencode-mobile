package org.opencode.mobile.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Логика «накатывать или нет» — единственное, что решает, заменит ли приложение
 * своё ядро. Ошибка здесь дороже, чем в сетевом коде: слишком мягкое сравнение
 * откатило бы ядро назад, слишком строгое — не дало бы обновиться вообще.
 *
 * Сравнение обязано быть числовым: строкой `2.10.0` меньше, чем `2.9.0`, и
 * именно на этом ломаются наивные реализации.
 */
class CoreUpdaterVersionTest {
    @Test
    fun `2_10 новее 2_9 — сравниваем числа, а не строки`() {
        assertTrue(CoreUpdater.isNewer("2.10.0", "2.9.0"))
    }

    @Test
    fun `равная версия не считается обновлением`() {
        assertFalse(CoreUpdater.isNewer("2.3.0", "2.3.0"))
    }

    @Test
    fun `минор и патч тоже обновления`() {
        assertTrue(CoreUpdater.isNewer("2.3.1", "2.3.0"))
        assertTrue(CoreUpdater.isNewer("2.4.0", "2.3.9"))
    }

    @Test
    fun `старое не накатывается поверх нового`() {
        assertFalse(CoreUpdater.isNewer("2.2.9", "2.3.0"))
        assertFalse(CoreUpdater.isNewer("1.9.9", "2.0.0"))
    }

    @Test
    fun `суффикс сборки не делает версию новее`() {
        assertFalse(CoreUpdater.isNewer("2.3.0-dev", "2.3.0"))
        assertFalse(CoreUpdater.isNewer("2.3", "2.3.0"))
    }

    @Test
    fun `мажор сам не накатывается, а минор накатывается`() {
        assertFalse(CoreUpdater.sameMajor("3.0.0", "2.3.0"))
        assertTrue(CoreUpdater.sameMajor("2.4.1", "2.3.0"))
    }

    /** Скачанное новее APK и не отбраковано — запускается именно оно. */
    @Test
    fun `новее и не отбраковано — скачанное действует`() {
        assertTrue(CoreUpdater.isUsable("2.4.0", "2.3.0", null))
    }

    /** Отбракованное падением сервера ядро не действует, даже если оно новее. */
    @Test
    fun `отбракованное ядро не запускается`() {
        assertFalse(CoreUpdater.isUsable("2.4.0", "2.3.0", "2.4.0"))
    }

    /** Отбракована другая версия — на эту запрет не распространяется. */
    @Test
    fun `запрет привязан к версии, а не к факту отката`() {
        assertTrue(CoreUpdater.isUsable("2.5.0", "2.3.0", "2.4.0"))
    }

    /** Равное или старое скачанное не должно перебивать ядро из APK. */
    @Test
    fun `равное и старое не действуют`() {
        assertFalse(CoreUpdater.isUsable("2.3.0", "2.3.0", null))
        assertFalse(CoreUpdater.isUsable("2.2.9", "2.3.0", null))
    }
}
