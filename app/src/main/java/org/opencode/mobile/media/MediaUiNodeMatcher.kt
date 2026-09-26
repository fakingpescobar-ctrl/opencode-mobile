package org.opencode.mobile.media

/**
 * A flattened accessibility node, so matching stays a pure function and can be unit tested without
 * an Android runtime.
 */
data class MediaUiNodeView(
    val className: String,
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val clickable: Boolean,
    val editable: Boolean,
    val bounds: MediaUiBounds?,
)

/**
 * Matches a node against a [MediaUiTarget] for a given [MediaUiAction].
 *
 * A node matches when it points at the right control and can take that action: only a field takes
 * text, and a tap wants a control rather than a field. Families are checked against text, then
 * content description, then resource id, so a target that only knows the text keeps working when a
 * build also happens to expose a resource id.
 */
object MediaUiNodeMatcher {
    fun matches(
        node: MediaUiNodeView,
        target: MediaUiTarget,
        action: MediaUiAction,
    ): Boolean = aimsAtNode(node, target) && suitsTheAction(node, target, action)

    /**
     * A rect target means "the control under this point"; a label target means "the control that
     * calls itself this". One target uses exactly one of the two, so there is no precedence to
     * reason about.
     */
    private fun aimsAtNode(
        node: MediaUiNodeView,
        target: MediaUiTarget,
    ): Boolean {
        val aim = target.bounds
        return if (aim == null) {
            matchesLabels(node, target)
        } else {
            node.bounds?.contains(aim.centerX, aim.centerY) == true
        }
    }

    private fun suitsTheAction(
        node: MediaUiNodeView,
        target: MediaUiTarget,
        action: MediaUiAction,
    ): Boolean =
        when {
            // Text only ever goes into a field, and a field is found without help from a clickable flag.
            action.writesText -> node.editable
            // A rect names its control by the place it occupies, so the tightest node there will do.
            target.bounds != null -> true
            // A label tap wants a control and never a field: right after set_text the search field
            // holds exactly the words the caller is looking for, so it would always win the match
            // and the tap would land in the search box instead of the row it named.
            //
            // Clickability is deliberately not judged here. The label of a Yandex playlist button
            // or track row sits on a node with clickable=false, and the service answers such a
            // node with its clickable ancestor. Refusing it at this level would make that walk
            // unreachable, and the caller still ends up rejecting a label that has no clickable
            // ancestor to climb to.
            else -> !node.editable
        }

    private fun matchesLabels(
        node: MediaUiNodeView,
        target: MediaUiTarget,
    ): Boolean {
        val textOk = matchesAny(node.text, target.textContains)
        val descOk = matchesAny(node.contentDescription, target.contentDescriptions)
        val idOk = matchesAny(node.resourceId, target.resourceIds)
        return textOk && descOk && idOk
    }

    private fun matchesAny(
        haystack: String,
        wanted: List<String>,
    ): Boolean = wanted.isEmpty() || wanted.any { containsTerm(haystack, it) }

    /**
     * Folds a label the way a human compares two track names: case, punctuation and spacing are
     * noise, the words are the signal. Yandex writes "My Temper (feat. M. Vegas)" where the catalog
     * says "My Temper", so equality would be the wrong comparison.
     */
    fun normalize(value: String): String =
        value
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /**
     * Whole-word containment: the needle's tokens must sit next to each other inside the haystack's
     * tokens. That is what keeps "my temper" from matching "my temperature" while still matching
     * "11,My Temper (feat. M. Vegas),Не подходит для детей".
     */
    internal fun containsTerm(
        haystack: String,
        needle: String,
    ): Boolean {
        val hay = tokenize(haystack)
        val ned = tokenize(needle)
        if (ned.isEmpty() || hay.size < ned.size) return false
        return (0..(hay.size - ned.size)).any { start ->
            ned.withIndex().all { (offset, word) -> hay[start + offset] == word }
        }
    }

    private fun tokenize(value: String): List<String> =
        normalize(value)
            .split(' ')
            .filter { it.isNotEmpty() }
}
