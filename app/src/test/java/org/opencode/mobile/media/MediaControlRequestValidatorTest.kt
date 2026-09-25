package org.opencode.mobile.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaControlRequestValidatorTest {
    @Test
    fun `list request trims query and uses bounded default`() {
        val request = MediaControlRequestValidator.list("  Yandex Music  ", null)

        assertEquals("Yandex Music", request.query)
        assertEquals(MediaControlRequestValidator.DEFAULT_LIST_LIMIT, request.limit)
    }

    @Test
    fun `list request rejects unsafe query and limits`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaControlRequestValidator.list("x".repeat(121), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaControlRequestValidator.list("bad\nquery", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaControlRequestValidator.list(null, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaControlRequestValidator.list(null, 201)
        }
    }

    @Test
    fun `control request maps every supported action`() {
        assertEquals(MediaCommand.PAUSE, MediaControlRequestValidator.control("pause", null).command)
        assertEquals(MediaCommand.NEXT, MediaControlRequestValidator.control("  NEXT ", null).command)
        assertEquals(MediaCommand.PLAY, MediaControlRequestValidator.control("resume", null).command)
        assertEquals(MediaCommand.PREVIOUS, MediaControlRequestValidator.control("prev", null).command)
        assertEquals(MediaCommand.PLAY_PAUSE, MediaControlRequestValidator.control("play-pause", null).command)
        assertEquals(MediaCommand.STOP, MediaControlRequestValidator.control("stop", null).command)
    }

    @Test
    fun `control request keeps an exact package and allows auto detect`() {
        assertEquals("ru.yandex.music", MediaControlRequestValidator.control("pause", " ru.yandex.music ").packageName)
        assertNull(MediaControlRequestValidator.control("pause", null).packageName)
        assertNull(MediaControlRequestValidator.control("pause", "   ").packageName)
    }

    @Test
    fun `control request rejects unknown actions and guessed packages`() {
        listOf(null, "", "launch", "install", "next track").forEach { action ->
            assertThrows(IllegalArgumentException::class.java) {
                MediaControlRequestValidator.control(action, null)
            }
        }
        listOf("ruyandex", "ru.yandex.music;rm", "com.example.app/sub", "x".repeat(256)).forEach { packageName ->
            assertThrows(IllegalArgumentException::class.java) {
                MediaControlRequestValidator.control("pause", packageName)
            }
        }
    }

    @Test
    fun `status request validates the optional package`() {
        assertNull(MediaControlRequestValidator.status(null))
        assertEquals("ru.yandex.music", MediaControlRequestValidator.status("ru.yandex.music"))
        assertThrows(IllegalArgumentException::class.java) {
            MediaControlRequestValidator.status("ruyandex")
        }
    }

    @Test
    fun `every command has a stable wire name`() {
        val wireNames = MediaCommand.values().map { it.wireName }
        assertEquals(
            listOf("play", "pause", "play_pause", "next", "previous", "stop"),
            wireNames,
        )
    }

    @Test
    fun `only an exported session makes an app controlable`() {
        val withSession = MediaAppSnapshot("ru.yandex.music", "Яндекс Музыка", listOf("svc"), null)
        val buttonOnly = MediaAppSnapshot("ru.yandex.music", "Яндекс Музыка", emptyList(), "rcv")
        val hiddenOnly = MediaAppSnapshot("ru.yandex.music", "Яндекс Музыка", emptyList(), "rcv", 1)
        assertTrue(withSession.controlable)
        assertFalse(buttonOnly.controlable)
        assertFalse(hiddenOnly.controlable)
    }
}
