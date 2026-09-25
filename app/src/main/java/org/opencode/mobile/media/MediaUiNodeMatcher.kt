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
)

/**
 * Matches a node against a [MediaUiClickTarget].
 *
 * A node matches when it satisfies every selector family the target actually names, and at least one
 * family at all - an unconstrained "match anything" is never a legal target. Families are checked
 * against text, then content description, then resource id, so a target that only knows the text
 * keeps working when a build also happens to expose a resource id.
 */
object MediaUiNodeMatcher {
    fun matches(
        node: MediaUiNodeView,
        target: MediaUiClickTarget,
    ): Boolean {
        val clickableOk = !target.requireClickable || node.clickable
        val textOk = matchesAny(node.text, target.textContains)
        val descOk = matchesAny(node.contentDescription, target.contentDescriptions)
        val idOk = matchesAny(node.resourceId, target.resourceIds)
        return clickableOk && textOk && descOk && idOk
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
    private fun containsTerm(
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
