package org.opencode.mobile.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matcher is the part that decides whether we tap the right thing in a screen we do not own, so
 * it gets tested against the exact strings Yandex renders.
 */
class MediaUiNodeMatcherTest {
    private fun node(
        text: String = "",
        contentDescription: String = "",
        resourceId: String = "",
        clickable: Boolean = true,
    ) = MediaUiNodeView(
        className = "android.widget.TextView",
        text = text,
        contentDescription = contentDescription,
        resourceId = resourceId,
        clickable = clickable,
    )

    @Test
    fun `catalog title matches the row yandex renders with a featured credit`() {
        val row = node(text = "11,My Temper (feat. M. Vegas),Не подходит для детей")
        val target = MediaUiClickTarget("ru.yandex.music", textContains = listOf("My Temper"))
        assertTrue(MediaUiNodeMatcher.matches(row, target))
    }

    @Test
    fun `a shorter catalog title does not match a longer word`() {
        val row = node(text = "My Temperature")
        val target = MediaUiClickTarget("ru.yandex.music", textContains = listOf("My Temper"))
        assertFalse(MediaUiNodeMatcher.matches(row, target))
    }

    @Test
    fun `matching ignores case and punctuation`() {
        val row = node(contentDescription = "НРАВИТСЯ")
        val target = MediaUiClickTarget("ru.yandex.music", contentDescriptions = listOf("нравится"))
        assertTrue(MediaUiNodeMatcher.matches(row, target))
    }

    @Test
    fun `a non clickable node is refused when the job needs a real tap`() {
        val row = node(text = "My Temper", clickable = false)
        val strict = MediaUiClickTarget("ru.yandex.music", textContains = listOf("My Temper"))
        val loose = MediaUiClickTarget(
            "ru.yandex.music",
            textContains = listOf("My Temper"),
            requireClickable = false,
        )
        assertFalse(MediaUiNodeMatcher.matches(row, strict))
        assertTrue(MediaUiNodeMatcher.matches(row, loose))
    }

    @Test
    fun `every named selector family has to agree`() {
        val row = node(text = "My Temper", contentDescription = "Слушать")
        val target = MediaUiClickTarget(
            "ru.yandex.music",
            textContains = listOf("My Temper"),
            contentDescriptions = listOf("Играть"),
        )
        assertFalse(MediaUiNodeMatcher.matches(row, target))
    }

    @Test
    fun `resource ids are matched on the tail after the package`() {
        val row = node(resourceId = "ru.yandex.music:id/play_button")
        val target = MediaUiClickTarget("ru.yandex.music", resourceIds = listOf("id/play_button"))
        assertTrue(MediaUiNodeMatcher.matches(row, target))
    }

    @Test
    fun `a node with no package visible never matches a text target`() {
        val row = node(text = "Воспроизвести")
        val target = MediaUiClickTarget("ru.yandex.music", contentDescriptions = listOf("Воспроизвести"))
        assertFalse(MediaUiNodeMatcher.matches(row, target))
    }

    @Test
    fun `a target without any selector is rejected up front`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaUiClickTarget("ru.yandex.music")
        }
    }

    @Test
    fun `a target without a package is rejected up front`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaUiClickTarget("", textContains = listOf("My Temper"))
        }
    }
}
