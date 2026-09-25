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
    fun `play request accepts deep link and track page of the same id`() {
        val deepLink = MediaControlRequestValidator.play("ru.yandex.music", "37066063", null, "Rampage")
        val page =
            MediaControlRequestValidator.play(
                "ru.yandex.music",
                "37066063",
                "https://music.yandex.ru/track/37066063",
                "Rampage",
            )
        val plus =
            MediaControlRequestValidator.play(
                "ru.yandex.music",
                "37066063",
                "yandexmusicplus://track/37066063",
                null,
            )

        assertEquals("37066063", deepLink.mediaId)
        assertEquals(TRACK_DEEP_LINK_PREFIX + "37066063", deepLink.uri)
        assertEquals("Rampage", deepLink.title)
        // Канонизируем: что бы агент ни прислал, сессия получает нашу ссылку на тот же id.
        // Форма URI, которую дал вызывающий, сохраняется: плеер сам решает, что умеет.
        // Раньше тут была безусловная подмена на deep link, и https-вариант было не проверить.
        assertEquals("https://music.yandex.ru/track/37066063", page.uri)
        assertEquals("yandexmusicplus://track/37066063", plus.uri)
    }

    @Test
    fun `play request rejects a uri that points at another track`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaControlRequestValidator.play("ru.yandex.music", "37066063", "yandexmusic://track/1", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaControlRequestValidator.play("ru.yandex.music", "37066063", "https://music.yandex.ru/track/1", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaControlRequestValidator.play(
                "ru.yandex.music",
                "37066063",
                "https://music.yandex.uz/track/37066063",
                null,
            )
        }
    }

    @Test
    fun `play request rejects foreign ids and unsafe titles`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaControlRequestValidator.play("ru.yandex.music", "../1", null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaControlRequestValidator.play("ru.yandex.music", "1;rm -rf", null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaControlRequestValidator.play(null, "37066063", null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaControlRequestValidator.play("ru.yandex.music", "37066063", null, "Rampage\nnow")
        }
    }

    @Test
    fun `like request only maps closed rating set`() {
        val pkg = "ru.yandex.music"

        assertEquals("ru.yandex.music.action.ADD_LIKE", MediaControlRequestValidator.like("like", pkg).sessionAction)
        assertEquals(
            "ru.yandex.music.action.REMOVE_LIKE",
            MediaControlRequestValidator.like(" UNLIKE ", pkg).sessionAction,
        )
        assertEquals(
            "ru.yandex.music.action.ADD_DISLIKE",
            MediaControlRequestValidator.like("dislike", pkg).sessionAction,
        )
        assertEquals(
            "ru.yandex.music.action.REMOVE_DISLIKE",
            MediaControlRequestValidator.like("undislike", pkg).sessionAction,
        )
        // Оценка всегда адресная: гадать, какое приложение оценивать, мост не будет.
        assertThrows(IllegalArgumentException::class.java) {
            MediaControlRequestValidator.like("like", null)
        }
        listOf(null, "", "download", "ru.yandex.music.action.REMOVE_ALL").forEach { action ->
            assertThrows(IllegalArgumentException::class.java) {
                MediaControlRequestValidator.like(action, pkg)
            }
        }
    }

    @Test
    fun `search request trims query and bounds the limit`() {
        assertEquals("Twotonetheartist", MediaControlRequestValidator.search("  Twotonetheartist  ", null).query)
        assertEquals(
            MediaControlRequestValidator.DEFAULT_SEARCH_LIMIT,
            MediaControlRequestValidator.search("Rampage", null).limit,
        )
        assertEquals(20, MediaControlRequestValidator.search("Rampage", 999).limit)
        assertEquals(1, MediaControlRequestValidator.search("Rampage", 0).limit)
        listOf(null, "", "   ", "x".repeat(121), "bad\nquery").forEach { query ->
            assertThrows(IllegalArgumentException::class.java) {
                MediaControlRequestValidator.search(query, null)
            }
        }
    }

    @Test
    fun `catalog track exposes the deep link the session understands`() {
        val track =
            CatalogTrack("37066063", "Rampage", "Twotonetheartist", "Album", 120_000, true)

        assertEquals("yandexmusic://track/37066063", track.deepLink)
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

    @Test
    fun `library requires an explicit package`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            MediaControlRequestValidator.library(null, null, null, null)
        }
        assertTrue(error.message.orEmpty().contains("package"))
    }

    @Test
    fun `library keeps the requested node and query`() {
        val spec = MediaControlRequestValidator.library("ru.yandex.music", " playlists ", " Busta ", 5)
        assertEquals("ru.yandex.music", spec.packageName)
        assertEquals("playlists", spec.node)
        assertEquals("Busta", spec.query)
        assertEquals(5, spec.limit)
    }

    @Test
    fun `library defaults the limit and caps it`() {
        assertEquals(
            MediaControlRequestValidator.DEFAULT_LIBRARY_LIMIT,
            MediaControlRequestValidator.library("ru.yandex.music", null, null, null).limit,
        )
        assertEquals(
            MediaControlRequestValidator.MAX_LIBRARY_LIMIT,
            MediaControlRequestValidator.library("ru.yandex.music", null, null, 999).limit,
        )
    }

    @Test
    fun `play keeps the uri form the caller knows`() {
        val deep = MediaControlRequestValidator.play("ru.yandex.music", "42", "yandexmusic://track/42", "Rampage")
        val page =
            MediaControlRequestValidator.play("ru.yandex.music", "42", "https://music.yandex.ru/track/42", "Rampage")
        val bare = MediaControlRequestValidator.play("ru.yandex.music", "42", null, "Rampage")
        assertEquals("yandexmusic://track/42", deep.uri)
        assertEquals("https://music.yandex.ru/track/42", page.uri)
        assertEquals("yandexmusic://track/42", bare.uri)
    }
}
