package org.opencode.mobile.media

/**
 * What an automation job wants to find in another app's window.
 *
 * Parsed once at the boundary so the rest of the pipeline can trust it: every list is non-null and
 * every string is already trimmed and normalized.
 */
data class MediaUiClickTarget(
    val packageName: String,
    val textContains: List<String> = emptyList(),
    val contentDescriptions: List<String> = emptyList(),
    val resourceIds: List<String> = emptyList(),
    val requireClickable: Boolean = true,
) {
    init {
        require(packageName.isNotBlank()) { "packageName is required" }
        require(
            textContains.isNotEmpty() || contentDescriptions.isNotEmpty() || resourceIds.isNotEmpty(),
        ) { "a click target needs at least one of text, content description or resource id" }
        require(packageName.length <= MAX_TARGET_PACKAGE_LENGTH) { "packageName is too long" }
        textContains.forEach {
            require(it.isNotBlank() && it.length <= MAX_SELECTOR_LENGTH) { "text selector is empty or too long" }
        }
        contentDescriptions.forEach {
            require(it.isNotBlank() && it.length <= MAX_SELECTOR_LENGTH) {
                "content desc selector is empty or too long"
            }
        }
        resourceIds.forEach {
            require(it.isNotBlank() && it.length <= MAX_SELECTOR_LENGTH) {
                "resource id selector is empty or too long"
            }
        }
    }

    companion object {
        const val MAX_TARGET_PACKAGE_LENGTH = 255
        const val MAX_SELECTOR_LENGTH = 200
    }
}

/** How a click job ended. Every outcome says whether the tapped window was the focused one. */
sealed interface MediaUiClickOutcome {
    /** A node matched and got the click. */
    data class Clicked(
        val label: String,
        val windowFocused: Boolean,
        val gestureUsed: Boolean,
    ) : MediaUiClickOutcome

    /** A matching node was visible but the click did not stick. */
    data class ClickRejected(
        val label: String,
        val reason: String,
    ) : MediaUiClickOutcome

    /** No node ever matched inside the timeout. */
    data class NotFound(
        val seenClickableLabels: List<String>,
    ) : MediaUiClickOutcome

    /** Something outside the click went wrong, so we stopped instead of pretending. */
    data class Failed(
        val reason: String,
    ) : MediaUiClickOutcome
}

/**
 * A single pending click.
 *
 * The result is settled exactly once, so the accessibility service and the waiting caller can race
 * freely without locking: whoever gets there first wins and the other one is a no-op.
 */
class MediaUiClickJob(
    val target: MediaUiClickTarget,
    val timeoutMs: Long,
) {
    init {
        require(timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) { "timeout out of range" }
    }

    private var outcome: MediaUiClickOutcome? = null
    private val settledSignal = java.util.concurrent.CountDownLatch(1)
    private val inFlight = java.util.concurrent.atomic
        .AtomicBoolean(false)

    @Synchronized
    fun settle(result: MediaUiClickOutcome): Boolean {
        if (outcome != null) return false
        outcome = result
        settledSignal.countDown()
        return true
    }

    @Synchronized
    fun settled(): MediaUiClickOutcome? = outcome

    /** Blocks the calling thread until the job settles or the budget runs out. */
    fun await(timeoutMs: Long): MediaUiClickOutcome? {
        settledSignal.await(timeoutMs + AWAIT_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        return settled()
    }

    /** Only one attempt may be in flight at a time, no matter how many window events arrive. */
    fun tryClaimAttempt(): Boolean = inFlight.compareAndSet(false, true)

    fun releaseAttempt() {
        inFlight.set(false)
    }

    companion object {
        const val MIN_TIMEOUT_MS = 500L
        const val MAX_TIMEOUT_MS = 30_000L
        private const val AWAIT_GRACE_MS = 1_500L
    }
}
