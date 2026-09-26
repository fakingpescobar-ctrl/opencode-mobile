package org.opencode.mobile.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Frames copied off a real Yandex Music playlist screen while the first track was loaded and
 * paused: the same title sits in the playlist list and in the mini-player, and the mini-player
 * is the roomier control. A "largest wins" rule taps it and resumes the previous track.
 */
class TopmostMatchTest {
    @Test
    fun `picks the list row over the roomier mini-player`() {
        val match = TopmostMatch<String>()
        match.offer("mini-player", MediaUiBounds(left = 0, top = 2289, right = 1272, bottom = 2566))
        match.offer("list row", MediaUiBounds(left = 0, top = 1815, right = 1272, bottom = 2081))

        assertEquals("list row", match.winner())
    }

    @Test
    fun `keeps the upper control regardless of the order offers arrive in`() {
        val match = TopmostMatch<String>()
        match.offer("list row", MediaUiBounds(left = 0, top = 1815, right = 1272, bottom = 2081))
        match.offer("mini-player", MediaUiBounds(left = 0, top = 2289, right = 1272, bottom = 2566))

        assertEquals("list row", match.winner())
    }

    @Test
    fun `falls back to the roomier control when both sit on the same line`() {
        val match = TopmostMatch<String>()
        match.offer("small", MediaUiBounds(left = 0, top = 1000, right = 100, bottom = 1060))
        match.offer("wide", MediaUiBounds(left = 0, top = 1000, right = 900, bottom = 1100))

        assertEquals("wide", match.winner())
    }

    @Test
    fun `answers nothing until something is offered`() {
        assertNull(TopmostMatch<String>().winner())
    }
}
