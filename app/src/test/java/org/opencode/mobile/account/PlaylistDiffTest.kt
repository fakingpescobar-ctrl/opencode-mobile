package org.opencode.mobile.account

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Формат `diff` для `/playlists/{kind}/change`.
 *
 * Тест на то, что нельзя увидеть глазами: собранный JSON выглядит осмысленным при любой
 * опечатке в именах полей, а Яндекс отвечает на опечатку 400-кой, а не тихим «ничего не
 * произошло». Имена полей взяты из `Difference.add_insert` / `add_delete` клиента
 * `yandex-music-api`; `albumId` в camelCase - тоже оттуда, путать с `album_id` легко.
 */
class PlaylistDiffTest {
    @Test
    fun `insert diff is one insert op with the track at the given position`() {
        val diff = JSONArray(insertDiff(trackId = "1710808", albumId = "932", at = 3))

        assertEquals(1, diff.length())
        val op = diff.getJSONObject(0)
        assertEquals("insert", op.getString("op"))
        assertEquals(3, op.getInt("at"))
        val tracks = op.getJSONArray("tracks")
        assertEquals(1, tracks.length())
        assertEquals("1710808", tracks.getJSONObject(0).getString("id"))
        assertEquals("932", tracks.getJSONObject(0).getString("albumId"))
    }

    @Test
    fun `insert diff puts a negative position at the top instead of failing the request`() {
        val op = JSONArray(insertDiff(trackId = "1", albumId = "2", at = -5)).getJSONObject(0)

        assertEquals(0, op.getInt("at"))
    }

    @Test
    fun `delete diff is a half-open range so one track is from and from plus one`() {
        val op = JSONArray(deleteDiff(from = 7, to = 8)).getJSONObject(0)

        assertEquals("delete", op.getString("op"))
        assertEquals(7, op.getInt("from"))
        assertEquals(8, op.getInt("to"))
    }

    @Test
    fun `delete diff can span a range of tracks`() {
        val op = JSONArray(deleteDiff(from = 2, to = 5)).getJSONObject(0)

        assertEquals(2, op.getInt("from"))
        assertEquals(5, op.getInt("to"))
    }

    /**
     * Пустой диапазон - это не «удалить ничего», а заявка удалить следующий за `from` трек,
     * потому что `to` включается в границу с другой стороны. Ловим это на входе, а не по
     * жалобе пользователя, что из плейлиста пропала лишняя песня.
     */
    @Test
    fun `delete diff refuses an empty or inverted range`() {
        assertThrows(IllegalArgumentException::class.java) { deleteDiff(from = 3, to = 3) }
        assertThrows(IllegalArgumentException::class.java) { deleteDiff(from = 3, to = 1) }
        assertThrows(IllegalArgumentException::class.java) { deleteDiff(from = -1, to = 2) }
    }

    @Test
    fun `diff is a single line so it survives being put into a form field`() {
        val insert = insertDiff(trackId = "1", albumId = "2", at = 0)
        val delete = deleteDiff(from = 0, to = 1)

        assertTrue("insert diff must not contain a newline", !insert.contains("\n"))
        assertTrue("delete diff must not contain a newline", !delete.contains("\n"))
    }
}
