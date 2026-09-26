package org.opencode.mobile.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Выбор самой крупной кнопки из одноимённых.
 *
 * Правило появилось из конкретной поломки: на экране плейлиста Яндекс Музыки слово «Слушать»
 * принадлежит и мини-плееру внизу, и самому плейлисту, и тап по умолчанию уходил в первый
 * попавшийся узел. Проверяется не «что вернул сервис», а само правило выбора — с узлом,
 * понятным без устройства.
 */
class WidestMatchTest {
    /** Кнопка плейлиста с проверенного устройства: [692,1352][916,1661]. */
    private val playlistPlay = MediaUiBounds(692, 1352, 916, 1661)

    /** Кнопка мини-плеера: квадрат 84x84 внизу экрана, в десять раз меньше по площади. */
    private val miniPlayerPlay = MediaUiBounds(0, 1000, 84, 1084)

    @Test
    fun `nothing offered means nothing to click`() {
        assertNull(WidestMatch<String>().winner())
    }

    @Test
    fun `the only candidate wins`() {
        val match = WidestMatch<String>()
        match.offer("playlist", playlistPlay)

        assertEquals("playlist", match.winner())
    }

    @Test
    fun `a later but bigger candidate replaces an earlier smaller one`() {
        val match = WidestMatch<String>()
        match.offer("mini", miniPlayerPlay)
        match.offer("playlist", playlistPlay)

        assertEquals("playlist", match.winner())
    }

    /** Обратный случай: пришедший позже мелкий узел перебивать не должен. */
    @Test
    fun `a later but smaller candidate does not replace the current winner`() {
        val match = WidestMatch<String>()
        match.offer("playlist", playlistPlay)
        match.offer("mini", miniPlayerPlay)

        assertEquals("playlist", match.winner())
    }

    /**
     * Площадь, а не длина стороны. Узкая вытянутая кнопка длиннее по краю, но места занимает
     * меньше, и победить её должна широкая низкая.
     */
    @Test
    fun `the widest means the largest area, not the longest edge`() {
        val match = WidestMatch<String>()
        match.offer("tall", MediaUiBounds(0, 0, 10, 500))
        match.offer("wide", MediaUiBounds(0, 0, 300, 20))

        assertEquals("wide", match.winner())
    }

    @Test
    fun `equal areas keep the first candidate seen`() {
        val match = WidestMatch<String>()
        match.offer("first", MediaUiBounds(0, 0, 100, 100))
        match.offer("second", MediaUiBounds(200, 200, 300, 300))

        assertEquals("first", match.winner())
    }
}
