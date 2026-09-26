package org.opencode.mobile.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.opencode.mobile.account.HeadTrack
import org.opencode.mobile.account.YandexPlaylistPlayer

/**
 * Проверка того, чем подтверждается запуск плейлиста.
 *
 * Тонкость, ради которой всё и писалось: сравнивать надо не со строго первым треком, а с
 * началом плейлиста. Яндекс молча выкидывает недоступные треки и начинает со следующего, так
 * что требование «играет именно первый» врёт на любом плейлисте с заблокированным head.
 */
class PlaylistStartMatchTest {
    private val head =
        listOf(
            HeadTrack("YOUR LOVE", 1),
            HeadTrack("F.I.T.S.", 2),
            HeadTrack("Believe", 3),
            HeadTrack("Mercurial", 4),
            HeadTrack("ONE", 5),
        )

    @Test
    fun `the very first track of the head is position one`() {
        assertEquals(1, YandexPlaylistPlayer.startedTrack("YOUR LOVE", head)?.position)
    }

    @Test
    fun `a later head track reports its real position instead of failing`() {
        assertEquals(2, YandexPlaylistPlayer.startedTrack("F.I.T.S.", head)?.position)
        assertEquals(4, YandexPlaylistPlayer.startedTrack("Mercurial", head)?.position)
    }

    @Test
    fun `case and punctuation do not decide the match`() {
        assertEquals(4, YandexPlaylistPlayer.startedTrack("mercurial!", head)?.position)
    }

    @Test
    fun `a track from outside the playlist is not a successful start`() {
        assertNull(YandexPlaylistPlayer.startedTrack("Bullet Tooth", head))
    }

    /**
     * Регрессия на ложное подтверждение: короткое название из головы плейлиста не должно
     * совпадать с чужим треком, который просто содержит это слово внутри своего.
     */
    @Test
    fun `a foreign track containing a head title is not a successful start`() {
        assertNull(YandexPlaylistPlayer.startedTrack("Someone Like You", head))
        assertNull(YandexPlaylistPlayer.startedTrack("Stone", head))
        assertNull(YandexPlaylistPlayer.startedTrack("Money", head))
        assertNull(YandexPlaylistPlayer.startedTrack("Unbelievable", head))
        assertNull(YandexPlaylistPlayer.startedTrack("Benefits", head))
    }

    @Test
    fun `a silent session never counts as started`() {
        assertNull(YandexPlaylistPlayer.startedTrack(null, head))
        assertNull(YandexPlaylistPlayer.startedTrack("   ", head))
        assertNull(YandexPlaylistPlayer.startedTrack("", head))
    }

    @Test
    fun `an unresolvable head cannot confirm a start`() {
        // Если метаданные не пришли, сверять не с чем: подтверждать тут нечего.
        assertNull(YandexPlaylistPlayer.startedTrack("Believe", emptyList()))
    }
}
