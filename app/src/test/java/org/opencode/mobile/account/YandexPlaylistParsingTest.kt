package org.opencode.mobile.account

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Разбор ответов Яндекса про плейлисты.
 *
 * Формат взят из живого аккаунта, а не придуман: два места стоит зафиксировать тестом,
 * потому что оба молча ломают результат. Первое - треки лежат в `tracks[].track`, и если
 * взять верхний уровень, id превратятся в пустые строки. Второе - порядок приходит в
 * `originalIndex` и не обязан совпадать с порядком выдачи: плейлист, в котором треки
 * переставлены, обязан прочитаться в том порядке, который выбрал человек.
 */
class YandexPlaylistParsingTest {
    @Test
    fun `a playlist row keeps the kind that addresses it`() {
        val rows =
            readPlaylistSummaries(
                JSONArray().put(
                    JSONObject()
                        .put("kind", 1001)
                        .put("playlistUuid", "7cf3f7f3-2390-9428-bfe8-ee9f8269860a")
                        .put("title", "D.N.B")
                        .put("trackCount", 264)
                        .put("durationMs", 79_800_000L),
                ),
            )

        val playlist = rows.single()
        assertEquals(1001, playlist.kind)
        assertEquals("7cf3f7f3-2390-9428-bfe8-ee9f8269860a", playlist.uuid)
        assertEquals("D.N.B", playlist.title)
        assertEquals(264, playlist.trackCount)
        assertEquals(79_800_000L, playlist.durationMs)
    }

    /**
     * Список без названия бесполезен вдвойне: показать его нечем и открыть не по чему, поэтому
     * такая строка выбрасывается, а не едет в ответ пустым заголовком.
     */
    @Test
    fun `a row without a title is dropped`() {
        val rows =
            readPlaylistSummaries(
                JSONArray()
                    .put(JSONObject().put("kind", 1001).put("title", "   ").put("trackCount", 3))
                    .put(JSONObject().put("kind", 1002).put("title", "Room_313")),
            )

        assertEquals(listOf(1002), rows.map { it.kind })
    }

    @Test
    fun `the likes playlist is a row like any other`() {
        val rows =
            readPlaylistSummaries(
                JSONArray().put(JSONObject().put("kind", 0).put("title", "Мой плейлист")),
            )

        assertEquals(0, rows.single().kind)
    }

    @Test
    fun `tracks are read from the nested track object`() {
        val library =
            readPlaylistLibrary(
                1001,
                playlistJson(
                    "5159246" to 0,
                    "769758" to 1,
                ),
            )

        assertEquals(listOf("5159246", "769758"), library.trackIds)
        assertEquals("D.N.B", library.title)
        assertEquals(1001, library.kind)
        assertEquals(2, library.size)
    }

    /**
     * Главный случай: выдача переставлена, а порядок плейлиста — нет.
     *
     * Проверяется не только параллельный список позиций, но и сам порядок id: head плейлиста
     * берётся первыми пятью `trackIds`, поэтому в выдаче вперемешку мы бы поставили его с
     * середины, а позиции из `originalIndexes` подписали бы чужой трек его настоящим номером.
     */
    @Test
    fun `the playlist order comes from original index, not from delivery order`() {
        val library =
            readPlaylistLibrary(
                1001,
                playlistJson(
                    "third" to 2,
                    "first" to 0,
                    "second" to 1,
                ),
            )

        assertEquals(listOf("first", "second", "third"), library.trackIds)
        assertEquals(listOf(0, 1, 2), library.originalIndexes)
    }

    @Test
    fun `positions stay aligned with ids when a broken track is skipped`() {
        val json =
            playlistJson("first" to 0, "second" to 1).put(
                "tracks",
                JSONArray()
                    .put(trackEntry("first", 0))
                    .put(trackEntry("", 1))
                    .put(trackEntry("third", 2)),
            )

        val library = readPlaylistLibrary(1001, json)

        assertEquals(listOf("first", "third"), library.trackIds)
        assertEquals(listOf(0, 2), library.originalIndexes)
        assertEquals(library.trackIds.size, library.originalIndexes.size)
    }

    /** Без `originalIndex` лучше правдоподобный порядок, чем дыры в нумерации. */
    @Test
    fun `a track without an original index falls back to its delivery position`() {
        val json =
            playlistJson("first" to 0)
                .put(
                    "tracks",
                    JSONArray()
                        .put(JSONObject().put("track", JSONObject().put("id", "a")))
                        .put(JSONObject().put("track", JSONObject().put("id", "b"))),
                )

        assertEquals(listOf(0, 1), readPlaylistLibrary(1001, json).originalIndexes)
    }

    @Test
    fun `a playlist without tracks reads as empty rather than failing`() {
        val library = readPlaylistLibrary(1012, JSONObject().put("title", "MINUSA"))

        assertEquals(0, library.size)
        assertEquals("MINUSA", library.title)
    }

    @Test
    fun `the requested kind is kept when the response omits it`() {
        val library = readPlaylistLibrary(1012, JSONObject().put("title", "MINUSA"))

        assertEquals(1012, library.kind)
    }

    private fun playlistJson(vararg entries: Pair<String, Int>): JSONObject =
        JSONObject()
            .put("kind", 1001)
            .put("title", "D.N.B")
            .put(
                "tracks",
                JSONArray().apply { entries.forEach { put(trackEntry(it.first, it.second)) } },
            )

    private fun trackEntry(
        id: String,
        originalIndex: Int,
    ): JSONObject =
        JSONObject()
            .put("originalIndex", originalIndex)
            .put("track", JSONObject().put("id", id).put("title", "track $id"))
}
