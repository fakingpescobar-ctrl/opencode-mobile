package org.opencode.mobile.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppControlRequestValidatorTest {
    @Test
    fun `list request trims query and uses bounded default`() {
        val request = AppControlRequestValidator.list("  Yandex Music  ", null)

        assertEquals("Yandex Music", request.query)
        assertEquals(AppControlRequestValidator.DEFAULT_LIST_LIMIT, request.limit)
    }

    @Test
    fun `list request rejects unsafe query and limits`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppControlRequestValidator.list("x".repeat(121), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppControlRequestValidator.list("bad\nquery", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppControlRequestValidator.list(null, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppControlRequestValidator.list(null, 201)
        }
    }

    @Test
    fun `launch request accepts an exact package id`() {
        assertEquals("ru.yandex.music", AppControlRequestValidator.launch("  ru.yandex.music  ").packageName)
    }

    @Test
    fun `launch request rejects guessed or malformed package ids`() {
        listOf("", "ruyandex", "ru.yandex.yandex music", "ru.yandex.music;rm").forEach { packageName ->
            assertThrows(IllegalArgumentException::class.java) {
                AppControlRequestValidator.launch(packageName)
            }
        }
    }
}
