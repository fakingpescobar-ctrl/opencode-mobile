package org.opencode.mobile.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The job is the boundary type that couples a target to an action, so these tests are about what the
 * bridge is allowed to accept: a tap has to name something, text may aim at the one field a window
 * offers, and a result is only ever settled once.
 */
class MediaUiJobTest {
    private val labelled = MediaUiTarget("ru.yandex.music", textContains = listOf("Paranoia"))

    @Test
    fun `a tap without a selector is refused before it reaches the service`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaUiJob(MediaUiTarget("ru.yandex.music"), MediaUiAction.Click, timeoutMs = 5_000)
        }
    }

    @Test
    fun `text may go into the one field a window offers`() {
        val job = MediaUiJob(MediaUiTarget("ru.yandex.music"), MediaUiAction.SetText("busta"), timeoutMs = 5_000)
        assertEquals("set_text", job.action.kind)
    }

    @Test
    fun `a click needs a selector but a rect counts as one`() {
        val aimed = MediaUiTarget("ru.yandex.music", bounds = MediaUiBounds(10, 20, 30, 40))
        val job = MediaUiJob(aimed, MediaUiAction.Click, timeoutMs = 5_000)
        assertTrue(job.target.hasSelector)
    }

    @Test
    fun `a timeout outside the budget is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaUiJob(labelled, MediaUiAction.Click, timeoutMs = 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaUiJob(labelled, MediaUiAction.Click, timeoutMs = 60_000)
        }
    }

    @Test
    fun `a blank query has to be spelled as clear_text`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaUiAction.parse("set_text", "   ")
        }
        assertEquals("clear_text", MediaUiAction.parse("clear_text", null).kind)
    }

    @Test
    fun `an unknown action is refused with the list of real ones`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                MediaUiAction.parse("type", "busta")
            }
        assertTrue(error.message?.contains("set_text") == true)
    }

    @Test
    fun `set_text without text is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaUiAction.parse("set_text", null)
        }
    }

    @Test
    fun `the first result wins and later ones are dropped`() {
        val job = MediaUiJob(labelled, MediaUiAction.Click, timeoutMs = 5_000)
        assertTrue(job.settle(MediaUiOutcome.Performed(job.action, "Paranoia", true, gestureUsed = false)))
        assertFalse(job.settle(MediaUiOutcome.Failed("late")))
        assertTrue(job.settled() is MediaUiOutcome.Performed)
    }

    @Test
    fun `an unsettled job reports nothing rather than guessing`() {
        val job = MediaUiJob(labelled, MediaUiAction.Click, timeoutMs = 500)
        assertNull(job.await(timeoutMs = 0))
    }

    /**
     * `preferLargest` — про выбор между одноимёнными кнопками, поэтому в одиночку он
     * бессмыслен и обязан падать. Иначе агент отправил бы флаг в никуда и получил бы
     * первый попавшийся узел, то есть мини-плеер вместо кнопки плейлиста.
     */
    @Test
    fun `preferLargest without a label selector is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaUiTarget("ru.yandex.music", preferLargest = true)
        }
    }

    @Test
    fun `preferLargest cannot be combined with a bounds target`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaUiTarget(
                packageName = "ru.yandex.music",
                bounds = MediaUiBounds(0, 0, 10, 10),
                contentDescriptions = listOf("Play"),
                preferLargest = true,
            )
        }
    }

    @Test
    fun `preferLargest with a content desc is allowed`() {
        val target =
            MediaUiTarget(
                packageName = "ru.yandex.music",
                contentDescriptions = listOf("Play"),
                preferLargest = true,
            )

        assertTrue(target.preferLargest)
    }

    @Test
    fun `the first match stays the default so existing callers are unaffected`() {
        assertFalse(labelled.preferLargest)
    }
}
