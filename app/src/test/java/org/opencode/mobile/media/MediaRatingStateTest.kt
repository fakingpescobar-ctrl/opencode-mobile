package org.opencode.mobile.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Состояние оценки читается из набора команд сессии, поэтому ошибка здесь молчаливая и опасная:
 * `false` вместо `null` — это выдуманное «не лайкнут» у сессии, которая вообще не отвечает.
 *
 * Снимки команд сняты с реальной сессии Яндекс Музыки на устройстве: до оценки трека в наборе
 * есть `actionLike`, после неё вместо него `actionUndoLike`.
 */
class MediaRatingStateTest {
    @Test
    fun `undo command means the track is rated`() {
        val afterLike = listOf(ACTION_LIKE, ACTION_UNDO_LIKE, ACTION_DISLIKE)

        assertEquals(true, afterLike.ratingState(ACTION_LIKE, ACTION_UNDO_LIKE))
    }

    @Test
    fun `rate command alone means the track is not rated`() {
        val beforeLike = listOf(ACTION_LIKE, ACTION_DISLIKE)

        assertEquals(false, beforeLike.ratingState(ACTION_LIKE, ACTION_UNDO_LIKE))
    }

    @Test
    fun `a session that offers neither command does not know the rating`() {
        // Например, обычный плеер без оценок: null, а не false.
        val noRating = listOf("code_0:some.other.action", "set_rating")

        assertNull(noRating.ratingState(ACTION_LIKE, ACTION_UNDO_LIKE))
    }

    @Test
    fun `dislike is read from its own command pair`() {
        val disliked = listOf(ACTION_UNDO_DISLIKE, ACTION_LIKE)

        assertEquals(true, disliked.ratingState(ACTION_DISLIKE, ACTION_UNDO_DISLIKE))
        assertEquals(false, disliked.ratingState(ACTION_LIKE, ACTION_UNDO_LIKE))
    }

    @Test
    fun `an empty command list stays unknown`() {
        assertNull(emptyList<String>().ratingState(ACTION_LIKE, ACTION_UNDO_LIKE))
    }
}
