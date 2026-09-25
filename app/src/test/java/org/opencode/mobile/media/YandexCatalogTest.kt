package org.opencode.mobile.media

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Каталог проверяем без сети: парсинг и правила ответа — единственное, где мы можем ошибиться
 * молча. Сама выдача Яндекса меняется, а вот «трек с таким названием один» должно остаться строгим.
 */
class YandexCatalogTest {
    @Test
    fun `exact title match ignores casing and rejects similar titles`() {
        val tracks = listOf(track("769758", "Get Low"), track("3090596", "Get Low Remix"))

        assertEquals("3090596", YandexCatalog.exactTrackId(tracks, "get low REMIX "))
        assertEquals("769758", YandexCatalog.exactTrackId(tracks, "Get Low"))
    }

    @Test
    fun `a phrase that is not a track title has no exact match`() {
        val tracks = listOf(track("3090596", "Get Low Remix"))

        assertNull(YandexCatalog.exactTrackId(tracks, "Busta Rymes Get Low Remix"))
        assertNull(YandexCatalog.exactTrackId(tracks, "Get Low Remix (Remix)"))
        assertNull(YandexCatalog.exactTrackId(emptyList(), "Get Low Remix"))
        assertNull(YandexCatalog.exactTrackId(tracks, "   "))
    }

    @Test
    fun `a numeric query is a track id and a title never is`() {
        assertTrue(YandexCatalog.isTrackId("3090596"))
        assertTrue(YandexCatalog.isTrackId("7"))
        assertTrue(YandexCatalog.isTrackId("1".repeat(32)))
    }

    @Test
    fun `anything but bare digits is not a track id`() {
        assertFalse(YandexCatalog.isTrackId(""))
        assertFalse(YandexCatalog.isTrackId("42 "))
        assertFalse(YandexCatalog.isTrackId("12.5"))
        assertFalse(YandexCatalog.isTrackId("42a"))
        assertFalse(YandexCatalog.isTrackId("Get Low Remix"))
        assertFalse(YandexCatalog.isTrackId("1".repeat(33)))
    }

    @Test
    fun `track parsing prefers realId and reads artist and album arrays`() {
        val node =
            JSONObject(
                """
                {
                  "realId": "3090596",
                  "title": "Get Low Remix",
                  "durationMs": 223000,
                  "available": true,
                  "artists": [{"name": "Lil Jon"}, {"name": "Busta Rhymes"}],
                  "albums": [{"title": "Part II"}]
                }
                """,
            )

        val parsed = YandexCatalog.readTracks(JSONArray().put(node)).single()

        assertEquals("3090596", parsed.id)
        assertEquals("Get Low Remix", parsed.title)
        assertEquals("Lil Jon, Busta Rhymes", parsed.artist)
        assertEquals("Part II", parsed.album)
        assertEquals(223_000L, parsed.durationMs)
        assertTrue(parsed.available)
    }

    @Test
    fun `track parsing falls back to id, plain artist and albumTitle`() {
        val node =
            JSONObject(
                """
                {
                  "id": "137229506",
                  "title": "Dram and Bass",
                  "artist": "Avcio",
                  "albumTitle": "Плейлист",
                  "available": false
                }
                """,
            )

        val parsed = YandexCatalog.readTracks(JSONArray().put(node)).single()

        assertEquals("137229506", parsed.id)
        assertEquals("Avcio", parsed.artist)
        assertEquals("Плейлист", parsed.album)
        assertEquals(0L, parsed.durationMs)
        assertFalse(parsed.available)
    }

    @Test
    fun `a track node without any id is dropped instead of becoming an empty target`() {
        val array =
            JSONArray()
                .put(JSONObject().put("title", "Без id"))
                .put(JSONObject().put("realId", "42").put("title", "С id"))

        val parsed = YandexCatalog.readTracks(array)

        assertEquals(1, parsed.size)
        assertEquals("С id", parsed.single().title)
    }

    @Test
    fun `an id without a title is dropped so it never becomes an exact match`() {
        // Каталог так отвечает на несуществующий id: узел есть, названия нет.
        val array = JSONArray().put(JSONObject().put("id", "999999999"))

        assertTrue(YandexCatalog.readTracks(array).isEmpty())
        assertNull(YandexCatalog.exactTrackId(YandexCatalog.readTracks(array), "999999999"))
    }

    @Test
    fun `artist parsing needs both id and name`() {
        assertNull(YandexCatalog.readArtist(JSONObject().put("id", "1")))
        assertNull(YandexCatalog.readArtist(JSONObject().put("name", "Скриптонит")))
        assertEquals(0, YandexCatalog.readArtist(JSONObject().put("id", "1").put("name", "X"))?.tracksCount)
    }

    @Test
    fun `artist parsing reads the track count the catalog reports`() {
        val node =
            JSONObject()
                .put("id", "201259")
                .put("name", "Скриптонит")
                .put("counts", JSONObject().put("tracks", 120))

        assertEquals(CatalogArtist("201259", "Скриптонит", 120), YandexCatalog.readArtist(node))
    }

    @Test
    fun `a search result defaults to a text query with no exact track`() {
        val result = MediaSearchResult("Get Low", null, emptyList())

        assertEquals(YandexCatalog.RESOLVED_BY_TEXT, result.resolvedBy)
        assertNull(result.exactTrackId)
    }

    private fun track(
        id: String,
        title: String,
    ) = CatalogTrack(id = id, title = title, artist = "artist", album = "album", durationMs = 0L, available = true)
}
