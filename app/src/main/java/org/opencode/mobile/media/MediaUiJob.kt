package org.opencode.mobile.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A screen rectangle in physical pixels, taken from the accessibility tree.
 *
 * Yandex Music labels its track rows and its bottom navigation, but its search magnifier and its
 * player buttons carry no text, no content description and no resource id at all. A rect is the only
 * honest way to name them, so the diagnostic of a failed job hands these back and the caller can aim
 * at one on the next attempt.
 */
data class MediaUiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right > left && bottom > top) {
            "bounds must have a positive area, got [$left,$top,$right,$bottom]"
        }
    }

    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    /** Wider than an Int can hold on a 1440p screen, so the comparison never overflows. */
    val area: Long get() = (right - left).toLong() * (bottom - top).toLong()

    fun contains(
        x: Int,
        y: Int,
    ): Boolean = x in left until right && y in top until bottom

    companion object {
        const val LIMIT = 20_000
    }
}

/**
 * What a job wants to find in another app's window.
 *
 * Parsed once at the boundary so the rest of the pipeline can trust it: every list is non-null and
 * every string is already trimmed and normalized.
 *
 * A target either names a control by label or aims at a rect, never both. Mixing them would be two
 * different answers to "which node", and a caller that gets one of them wrong would silently act on
 * the other.
 */
data class MediaUiTarget(
    val packageName: String,
    val textContains: List<String> = emptyList(),
    val contentDescriptions: List<String> = emptyList(),
    val resourceIds: List<String> = emptyList(),
    val bounds: MediaUiBounds? = null,
    val requireClickable: Boolean = true,
    val preferLargest: Boolean = false,
    val preferTopmost: Boolean = false,
) {
    init {
        require(packageName.isNotBlank()) { "packageName is required" }
        require(packageName.length <= MAX_TARGET_PACKAGE_LENGTH) { "packageName is too long" }
        require(!hasLabelSelector || bounds == null) {
            "a bounds target already says where to touch; do not mix it with label selectors"
        }
        // «Самая большая» — это способ выбрать между несколькими одноимёнными кнопками, то
        // есть между узлами одной метки. С rect-таргетом выбирать не из чего: там узел и так
        // единственный, самый тугой под точкой.
        require(!preferLargest || hasLabelSelector) {
            "preferLargest only means something together with a label selector"
        }
        require(!preferTopmost || hasLabelSelector) {
            "preferTopmost only means something together with a label selector"
        }
        require(!(preferLargest && preferTopmost)) {
            "preferLargest and preferTopmost pick opposite ends of the screen; pick one"
        }
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

    val hasLabelSelector: Boolean
        get() = textContains.isNotEmpty() || contentDescriptions.isNotEmpty() || resourceIds.isNotEmpty()

    val hasSelector: Boolean
        get() = hasLabelSelector || bounds != null

    companion object {
        const val MAX_TARGET_PACKAGE_LENGTH = 255
        const val MAX_SELECTOR_LENGTH = 200
    }
}

/** What to do with the node once it is found. */
sealed interface MediaUiAction {
    val kind: String

    /** Text actions only ever write into a field, so naming no selector is still safe. */
    val requiresSelector: Boolean
        get() = this is Click

    val writesText: Boolean
        get() = this is SetText || this is ClearText

    /** The string a text action types into the field, or null when nothing is typed. */
    val textToType: String?
        get() =
            when (this) {
                is SetText -> text
                ClearText -> ""
                Click, ReadText -> null
            }

    data object Click : MediaUiAction {
        override val kind: String = CLICK
    }

    /**
     * Найти узел и ничего с ним не сделать.
     *
     * Нужно, чтобы спросить «а тот ли это экран?» до того, как нажимать. Без этого действия
     * единственный способ убедиться - нажать и посмотреть на последствия, а последствия здесь
     * это чужой трек в чужом плейлисте.
     */
    data object ReadText : MediaUiAction {
        override val kind: String = READ_TEXT
    }

    data class SetText(
        val text: String,
    ) : MediaUiAction {
        init {
            require(text.isNotBlank()) {
                "set_text needs a non blank query; use clear_text to empty a field"
            }
        }

        override val kind: String = SET_TEXT
    }

    data object ClearText : MediaUiAction {
        override val kind: String = CLEAR_TEXT
    }

    companion object {
        const val CLICK = "click"
        const val SET_TEXT = "set_text"
        const val CLEAR_TEXT = "clear_text"
        const val READ_TEXT = "read_text"

        fun parse(
            kind: String,
            text: String?,
        ): MediaUiAction =
            when (kind) {
                CLICK -> Click
                SET_TEXT -> SetText(text ?: throw IllegalArgumentException("set_text needs \"text\""))
                CLEAR_TEXT -> ClearText
                READ_TEXT -> ReadText
                else ->
                    throw IllegalArgumentException(
                        "unknown action \"$kind\"; use $CLICK, $SET_TEXT, $CLEAR_TEXT or $READ_TEXT",
                    )
            }
    }
}

/** One node a failed job laid eyes on, so the caller can aim at it on the next attempt. */
data class MediaUiCandidate(
    val label: String?,
    val bounds: MediaUiBounds?,
    val clickable: Boolean,
    val editable: Boolean,
)

/** How a job ended. Every success says whether the window it touched was the focused one. */
sealed interface MediaUiOutcome {
    /** The node matched and took the action. */
    data class Performed(
        val action: MediaUiAction,
        val label: String,
        val windowFocused: Boolean,
        val gestureUsed: Boolean,
    ) : MediaUiOutcome

    /** A matching node was there but the action did not stick. */
    data class Rejected(
        val label: String,
        val reason: String,
    ) : MediaUiOutcome

    /** No node ever matched inside the timeout. */
    data class NotFound(
        val candidates: List<MediaUiCandidate>,
    ) : MediaUiOutcome

    /** Something outside the action went wrong, so we stopped instead of pretending. */
    data class Failed(
        val reason: String,
    ) : MediaUiOutcome
}

/**
 * A single pending job.
 *
 * The result is settled exactly once, so the accessibility service and the waiting caller can race
 * freely without locking: whoever gets there first wins and the other one is a no-op.
 */
class MediaUiJob(
    val target: MediaUiTarget,
    val action: MediaUiAction,
    val timeoutMs: Long,
) {
    init {
        require(timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) { "timeout out of range" }
        require(!action.requiresSelector || target.hasSelector) {
            "a ${action.kind} job needs a selector, a tap has to name the control it touches"
        }
    }

    private var outcome: MediaUiOutcome? = null
    private val settledSignal = CountDownLatch(1)
    private val inFlight = AtomicBoolean(false)

    @Synchronized
    fun settle(result: MediaUiOutcome): Boolean {
        if (outcome != null) return false
        outcome = result
        settledSignal.countDown()
        return true
    }

    @Synchronized
    fun settled(): MediaUiOutcome? = outcome

    /** Blocks the calling thread until the job settles or the budget runs out. */
    fun await(timeoutMs: Long): MediaUiOutcome? {
        settledSignal.await(timeoutMs + AWAIT_GRACE_MS, TimeUnit.MILLISECONDS)
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
