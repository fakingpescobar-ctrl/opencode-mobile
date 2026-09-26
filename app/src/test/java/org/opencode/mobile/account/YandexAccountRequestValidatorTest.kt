package org.opencode.mobile.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Валидация параметров чтения библиотеки.
 *
 * `offset` и `limit` приходят от агента, то есть из чужого текста, поэтому границы
 * проверяются здесь, а не «на всякий случай» обрезаются в контроллере: тихо срезанный
 * лимит вернул бы агенту неполную страницу, и тот решил бы, что больше ничего нет.
 */
class YandexAccountRequestValidatorTest {
    @Test
    fun `missing parameters fall back to the first page`() {
        val (offset, limit) = YandexAccountRequestValidator.page(null, null)

        assertEquals(0, offset)
        assertEquals(YandexAccountController.DEFAULT_LIMIT, limit)
    }

    @Test
    fun `offset and limit are read from the query`() {
        val (offset, limit) = YandexAccountRequestValidator.page("40", "5")

        assertEquals(40, offset)
        assertEquals(5, limit)
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        val (offset, limit) = YandexAccountRequestValidator.page(" 10 ", " 3 ")

        assertEquals(10, offset)
        assertEquals(3, limit)
    }

    @Test
    fun `a non-numeric offset is refused instead of silently becoming zero`() {
        val failure = runCatching { YandexAccountRequestValidator.page("later", "5") }

        assertTrue(failure.isFailure)
    }

    @Test
    fun `a negative offset is refused`() {
        val failure = runCatching { YandexAccountRequestValidator.page("-1", "5") }

        assertTrue(failure.isFailure)
    }

    @Test
    fun `a zero limit is refused`() {
        val failure = runCatching { YandexAccountRequestValidator.page("0", "0") }

        assertTrue(failure.isFailure)
    }

    @Test
    fun `a limit beyond the maximum is refused`() {
        val failure = runCatching { YandexAccountRequestValidator.page("0", "51") }

        assertTrue(failure.isFailure)
    }

    @Test
    fun `the maximum limit itself is allowed`() {
        val (_, limit) = YandexAccountRequestValidator.page("0", "50")

        assertEquals(YandexAccountController.MAX_LIMIT, limit)
    }
}
