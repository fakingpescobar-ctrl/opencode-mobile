package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Исход проверки реестра — значение, а не Boolean.
 *
 * Повод: 16.11 кнопка «проверить обновление ядра» на телефоне ответила
 * «ничего не заменил: версия не новее, либо сеть тарифицируется, либо
 * сверка/пробный запуск не прошли». Три причины одной фразой — то есть ноль
 * информации; а в логе самый частый исход (`в реестре ровно наша версия`) не
 * оставлял вообще ничего. Проверить, какая из трёх сработала, было нечем.
 *
 * Здесь проверяется решение «качать или нет» — оно чистое, и именно оно раньше
 * было веткой с четырьмя `return false`.
 */
class CoreCheckOutcomeTest {
    @Test
    fun `в реестре ровно наша версия — не новее`() {
        assertEquals(
            CoreUpdater.Outcome.NOT_NEWER,
            CoreUpdater.refusal("2.3.0", "2.3.0", null, metered = false),
        )
    }

    @Test
    fun `новее в том же мажоре и сеть бесплатная — причин не качать нет`() {
        assertNull(CoreUpdater.refusal("2.4.0", "2.3.0", blocked = null, metered = false))
    }

    @Test
    fun `отбракованная падением версия не предлагается снова`() {
        assertEquals(
            CoreUpdater.Outcome.BLOCKED,
            CoreUpdater.refusal("2.4.0", "2.3.0", blocked = "2.4.0", metered = false),
        )
    }

    @Test
    fun `другой мажор сам не накатывается`() {
        assertEquals(
            CoreUpdater.Outcome.MAJOR,
            CoreUpdater.refusal("3.0.0", "2.3.0", blocked = null, metered = false),
        )
    }

    @Test
    fun `тарифицируемая сеть откладывает обновление`() {
        assertEquals(
            CoreUpdater.Outcome.METERED,
            CoreUpdater.refusal("2.4.0", "2.3.0", blocked = null, metered = true),
        )
    }

    /** Мажор важнее сети: версия, которую не накатываем вообще, не зависит от трафика. */
    @Test
    fun `мажор проверяется раньше тарификации`() {
        assertEquals(
            CoreUpdater.Outcome.MAJOR,
            CoreUpdater.refusal("3.0.0", "2.3.0", blocked = null, metered = true),
        )
    }

    /** «Не новее» важнее отбраковки: реестр отдаёт нашу же версию — это не инцидент. */
    @Test
    fun `не новее проверяется раньше отбраковки`() {
        assertEquals(
            CoreUpdater.Outcome.NOT_NEWER,
            CoreUpdater.refusal("2.3.0", "2.3.0", blocked = "2.3.0", metered = false),
        )
    }

    @Test
    fun `текст исхода называет версии и не склеивает причины через либо`() {
        val result =
            CoreUpdater.Result(
                outcome = CoreUpdater.Outcome.NOT_NEWER,
                at = 0L,
                latest = "2.3.0",
                current = "2.3.0",
                apk = "2.3.0",
            )
        val text = result.text()
        assertTrue(text.contains("2.3.0"))
        assertFalse(text.contains("либо"))
        assertFalse(result.applied)
    }

    @Test
    fun `поставленное ядро — это applied и новая действующая версия`() {
        val result =
            CoreUpdater.Result(
                outcome = CoreUpdater.Outcome.APPLIED,
                at = 0L,
                latest = "2.4.0",
                current = "2.4.0",
                apk = "2.3.0",
            )
        assertTrue(result.applied)
        assertTrue(result.text().contains("2.4.0"))
    }
}
