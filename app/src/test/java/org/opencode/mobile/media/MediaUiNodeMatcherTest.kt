package org.opencode.mobile.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matcher is the part that decides whether we touch the right thing in a screen we do not own,
 * so it gets tested against the exact strings and rectangles Yandex renders.
 */
class MediaUiNodeMatcherTest {
    private fun node(
        text: String = "",
        contentDescription: String = "",
        clickable: Boolean = true,
        editable: Boolean = false,
    ) = MediaUiNodeView(
        className = "android.widget.TextView",
        text = text,
        contentDescription = contentDescription,
        resourceId = "",
        clickable = clickable,
        editable = editable,
        bounds = null,
    )

    private fun view(
        clickable: Boolean = true,
        editable: Boolean = false,
        bounds: MediaUiBounds? = null,
    ) = MediaUiNodeView(
        className = "android.view.View",
        text = "",
        contentDescription = "",
        resourceId = "",
        clickable = clickable,
        editable = editable,
        bounds = bounds,
    )

    private fun matches(
        node: MediaUiNodeView,
        target: MediaUiTarget,
        action: MediaUiAction = MediaUiAction.Click,
    ): Boolean = MediaUiNodeMatcher.matches(node, target, action)

    @Test
    fun `catalog title matches the row yandex renders with a featured credit`() {
        val row = node(text = "11,My Temper (feat. M. Vegas),Не подходит для детей")
        val target = MediaUiTarget("ru.yandex.music", textContains = listOf("My Temper"))
        assertTrue(matches(row, target))
    }

    @Test
    fun `a shorter catalog title does not match a longer word`() {
        val row = node(text = "My Temperature")
        val target = MediaUiTarget("ru.yandex.music", textContains = listOf("My Temper"))
        assertFalse(matches(row, target))
    }

    @Test
    fun `matching ignores case and punctuation`() {
        val row = node(contentDescription = "НРАВИТСЯ")
        val target = MediaUiTarget("ru.yandex.music", contentDescriptions = listOf("нравится"))
        assertTrue(matches(row, target))
    }

    @Test
    fun `a label on a non clickable node is kept for the caller to resolve`() {
        val row = node(text = "My Temper", clickable = false)
        val target = MediaUiTarget("ru.yandex.music", textContains = listOf("My Temper"))
        // The service walks up to the clickable ancestor, so the label must survive the matcher
        // even though the node it sits on cannot be tapped on its own.
        assertTrue(matches(row, target))
    }

    @Test
    fun `a field is never a tap target however well it is labelled`() {
        val search = node(text = "My Temper", clickable = false, editable = true)
        val target = MediaUiTarget("ru.yandex.music", textContains = listOf("My Temper"))
        assertFalse(matches(search, target))
    }

    @Test
    fun `every named selector family has to agree`() {
        val row = node(text = "My Temper", contentDescription = "Слушать")
        val target =
            MediaUiTarget(
                "ru.yandex.music",
                textContains = listOf("My Temper"),
                contentDescriptions = listOf("Играть"),
            )
        assertFalse(matches(row, target))
    }

    @Test
    fun `resource ids are matched on the tail after the package`() {
        val row =
            MediaUiNodeView(
                className = "android.widget.ImageView",
                text = "",
                contentDescription = "",
                resourceId = "ru.yandex.music:id/play_button",
                clickable = true,
                editable = false,
                bounds = null,
            )
        val target = MediaUiTarget("ru.yandex.music", resourceIds = listOf("id/play_button"))
        assertTrue(matches(row, target))
    }

    @Test
    fun `a node with no package visible never matches a text target`() {
        val row = node(text = "Воспроизвести")
        val target = MediaUiTarget("ru.yandex.music", contentDescriptions = listOf("Воспроизвести"))
        assertFalse(matches(row, target))
    }

    @Test
    fun `a rect reaches the unnamed magnifier yandex gives no label at all`() {
        val magnifier = view(bounds = MediaUiBounds(1090, 155, 1258, 323))
        val target = MediaUiTarget("ru.yandex.music", bounds = MediaUiBounds(1090, 155, 1258, 323))
        assertTrue(matches(magnifier, target))
    }

    @Test
    fun `a rect only reaches the node under its point`() {
        val searchField = view(editable = true, bounds = MediaUiBounds(175, 169, 1216, 337))
        val magnifier = view(bounds = MediaUiBounds(1090, 155, 1258, 323))
        val aimedAtMagnifier = MediaUiTarget("ru.yandex.music", bounds = MediaUiBounds(1090, 155, 1258, 323))
        // The field is wide enough to sit under the magnifier point, so both nodes match here and
        // the service is the one that keeps the tighter control.
        assertTrue(matches(searchField, aimedAtMagnifier))
        assertTrue(matches(magnifier, aimedAtMagnifier))
        val aimedAtField = MediaUiTarget("ru.yandex.music", bounds = MediaUiBounds(200, 180, 400, 300))
        assertTrue(matches(searchField, aimedAtField, MediaUiAction.SetText("busta")))
        assertFalse(matches(magnifier, aimedAtField))
    }

    @Test
    fun `a rect aims at a node the app never called clickable`() {
        val panel = view(clickable = false, bounds = MediaUiBounds(0, 148, 1272, 337))
        val target = MediaUiTarget("ru.yandex.music", bounds = MediaUiBounds(1100, 200, 1200, 260))
        assertTrue(matches(panel, target))
    }

    @Test
    fun `a node with no drawn area cannot be aimed at`() {
        val offscreenRow = view(bounds = null)
        val target = MediaUiTarget("ru.yandex.music", bounds = MediaUiBounds(0, 0, 10, 10))
        assertFalse(matches(offscreenRow, target))
    }

    @Test
    fun `text only goes into a field`() {
        val label = node(text = "Paranoia")
        val field = view(editable = true)
        val action = MediaUiAction.SetText("busta rhymes")
        assertFalse(matches(label, MediaUiTarget("ru.yandex.music"), action))
        assertTrue(matches(field, MediaUiTarget("ru.yandex.music"), action))
    }

    @Test
    fun `a tap on a field is still a tap`() {
        val field = view(editable = true, clickable = false)
        val target = MediaUiTarget("ru.yandex.music")
        assertFalse(matches(field, target, MediaUiAction.Click))
    }

    @Test
    fun `a label tap never lands in the field that holds the words`() {
        // The search field is clickable and carries the query, so a label match on it would always
        // beat the result row the caller actually named.
        val typed = view(clickable = true, editable = true).copy(text = "Get Low Remix")
        val target = MediaUiTarget("ru.yandex.music", textContains = listOf("Get Low Remix"))
        assertFalse(matches(typed, target, MediaUiAction.Click))
        assertTrue(matches(typed, target, MediaUiAction.SetText("Get Low Remix")))
        val row = node(text = "Get Low Remix")
        assertTrue(matches(row, target, MediaUiAction.Click))
    }

    @Test
    fun `a rect may still aim at a field`() {
        val field = view(clickable = true, editable = true, bounds = MediaUiBounds(175, 169, 1216, 337))
        val target = MediaUiTarget("ru.yandex.music", bounds = MediaUiBounds(175, 169, 1216, 337))
        assertTrue(matches(field, target, MediaUiAction.Click))
    }

    @Test
    fun `mixing a rect with a label is rejected up front`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaUiTarget(
                "ru.yandex.music",
                textContains = listOf("Paranoia"),
                bounds = MediaUiBounds(0, 0, 10, 10),
            )
        }
    }

    @Test
    fun `a target without a package is rejected up front`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaUiTarget("", textContains = listOf("My Temper"))
        }
    }

    @Test
    fun `an empty rect is rejected up front`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaUiBounds(10, 10, 10, 40)
        }
    }
}
