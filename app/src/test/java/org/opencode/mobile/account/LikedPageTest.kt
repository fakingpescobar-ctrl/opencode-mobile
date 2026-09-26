package org.opencode.mobile.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencode.mobile.media.CatalogTrack

/**
 * Границы страницы библиотеки.
 *
 * Сервер отдаёт всю библиотеку разом, поэтому «есть ещё что читать» вычисляет приложение.
 * Ошибка на границе страшнее простого «не показали»: агент увидит `has_more=false` и
 * объявит, что у юзера всего 20 треков, хотя их 2594.
 */
class LikedPageTest {
    @Test
    fun `a partial page reports more to read`() {
        val page = page(offset = 0, total = 2594, ids = 20)

        assertTrue(page.hasMore)
        assertEquals(0, page.offset)
        assertEquals(2594, page.total)
    }

    @Test
    fun `the last full page reports nothing left`() {
        val page = page(offset = 2580, total = 2594, ids = 14)

        assertFalse(page.hasMore)
    }

    @Test
    fun `an exact boundary is the end, not another page`() {
        val page = page(offset = 20, total = 40, ids = 20)

        assertFalse(page.hasMore)
    }

    @Test
    fun `an empty library is not an error and has no more`() {
        val page = page(offset = 0, total = 0, ids = 0)

        assertFalse(page.hasMore)
        assertTrue(page.trackIds.isEmpty())
        assertTrue(page.tracks.isEmpty())
    }

    @Test
    fun `dropped tracks do not turn the page into the last one`() {
        val page =
            LikedPage(
                login = "user",
                uid = "1",
                revision = 2,
                offset = 0,
                total = 2594,
                trackIds = listOf("1", "2", "3"),
                tracks = listOf(track("1")),
            )

        // has_more считается по запрошенным id, а не по разобранным трекам: иначе страница
        // с недоступными треками выглядела бы как конец библиотеки.
        assertTrue(page.hasMore)
    }

    private fun page(
        offset: Int,
        total: Int,
        ids: Int,
    ): LikedPage =
        LikedPage(
            login = "user",
            uid = "1",
            revision = 1,
            offset = offset,
            total = total,
            trackIds = (1..ids).map { it.toString() },
            tracks = emptyList(),
        )

    private fun track(id: String): CatalogTrack = CatalogTrack(id, "t", "a", "al", 1L, true)
}
