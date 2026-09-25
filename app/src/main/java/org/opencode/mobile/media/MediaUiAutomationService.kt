package org.opencode.mobile.media

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.atomic.AtomicReference

/**
 * Finds one node in another app's window and acts on it: a tap, or text typed into a field.
 *
 * The point of the class is to answer one question honestly: can an action land in a window that is
 * not the focused one? Every success therefore carries [MediaUiOutcome.Performed.windowFocused], so a
 * caller never has to guess whether it happened behind the user's back or on screen.
 *
 * The service only ever looks at windows belonging to the package the job named, and it acts on at
 * most one node per job - no loops, no helpful extra taps.
 */
@Suppress("TooManyFunctions")
class MediaUiAutomationService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var pending: MediaUiJob? = null
    private var deadlineMs = 0L
    private var gestureInFlight = false
    private var lastSeenCandidates: List<MediaUiCandidate> = emptyList()

    private val poll = object : Runnable {
        override fun run() = tick()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Without this flag AccessibilityService.windows stays empty, and with it empty there is no
        // background window to act in the first place.
        serviceInfo =
            serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        MediaUiAutomation.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // A window appearing is the earliest sign that our node may be reachable now.
        if (pending == null) return
        main.removeCallbacks(poll)
        main.post(poll)
    }

    override fun onInterrupt() {
        settlePending(MediaUiOutcome.Failed(REASON_INTERRUPTED))
    }

    override fun onDestroy() {
        MediaUiAutomation.detach(this)
        settlePending(MediaUiOutcome.Failed(REASON_SERVICE_STOPPED))
        super.onDestroy()
    }

    /** Called from any thread; hands the job to the main thread, where the accessibility API lives. */
    fun submit(job: MediaUiJob) {
        main.post {
            if (pending != null) {
                job.settle(MediaUiOutcome.Failed(REASON_BUSY))
                return@post
            }
            pending = job
            deadlineMs = SystemClock.uptimeMillis() + job.timeoutMs
            main.removeCallbacks(poll)
            main.post(poll)
        }
    }

    private fun tick() {
        val job = pending ?: return
        if (SystemClock.uptimeMillis() > deadlineMs) {
            finish(job, MediaUiOutcome.NotFound(lastSeenCandidates))
            return
        }
        if (!gestureInFlight && job.tryClaimAttempt()) {
            val seen = mutableListOf<MediaUiCandidate>()
            val hit = locate(job, seen)
            lastSeenCandidates = seen
            if (hit == null) job.releaseAttempt() else act(job, hit.first, hit.second)
        }
        if (pending === job) main.postDelayed(poll, POLL_MS)
    }

    private fun act(
        job: MediaUiJob,
        node: AccessibilityNodeInfo,
        window: AccessibilityWindowInfo,
    ) {
        val action = job.action
        if (action is MediaUiAction.Click) {
            clickNode(job, node, window, describeNode(node))
            return
        }
        finish(job, writeText(node, window, action))
    }

    private fun clickNode(
        job: MediaUiJob,
        node: AccessibilityNodeInfo,
        window: AccessibilityWindowInfo,
        label: String,
    ) {
        when {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK) -> {
                job.releaseAttempt()
                finish(job, MediaUiOutcome.Performed(job.action, label, window.isFocused, gestureUsed = false))
            }

            node.screenBounds().isEmpty -> {
                job.releaseAttempt()
                finish(job, MediaUiOutcome.Rejected(label, "node has no usable bounds"))
            }

            else -> {
                val wasFocused = window.isFocused
                gestureInFlight = true
                gestureTap(node.screenBounds()) { delivered ->
                    gestureInFlight = false
                    job.releaseAttempt()
                    finish(
                        job,
                        if (delivered) {
                            MediaUiOutcome.Performed(job.action, label, wasFocused, gestureUsed = true)
                        } else {
                            MediaUiOutcome.Rejected(label, "tap gesture was cancelled")
                        },
                    )
                }
            }
        }
    }

    /**
     * Text goes in through the field's own action, never through synthetic key events: a hidden field
     * would happily swallow key events and tell nobody. A field that refuses usually wants focus
     * first, so we ask once and then say no out loud.
     */
    private fun writeText(
        node: AccessibilityNodeInfo,
        window: AccessibilityWindowInfo,
        action: MediaUiAction,
    ): MediaUiOutcome {
        val label = describeNode(node)
        val typed =
            action.textToType?.let { text ->
                typeInto(node, text) ||
                    (node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) && typeInto(node, text))
            }
        return when {
            typed == null -> MediaUiOutcome.Failed("a tap is not a text action")
            typed -> MediaUiOutcome.Performed(action, label, window.isFocused, gestureUsed = false)
            else -> MediaUiOutcome.Rejected(label, "field refused text input")
        }
    }

    private fun typeInto(
        node: AccessibilityNodeInfo,
        text: String,
    ): Boolean {
        val arguments =
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun finish(
        job: MediaUiJob,
        result: MediaUiOutcome,
    ) {
        if (pending === job) {
            pending = null
            main.removeCallbacks(poll)
        }
        job.settle(result)
    }

    private fun settlePending(result: MediaUiOutcome) {
        val job = pending ?: return
        pending = null
        main.removeCallbacks(poll)
        job.settle(result)
    }

    /** First matching node, searched across every window the service is allowed to see. */
    private fun locate(
        job: MediaUiJob,
        seen: MutableList<MediaUiCandidate>,
    ): Pair<AccessibilityNodeInfo, AccessibilityWindowInfo>? =
        windows
            .orEmpty()
            .asSequence()
            .filter { window -> window.root?.packageName?.toString() == job.target.packageName }
            .mapNotNull { window ->
                val root = window.root ?: return@mapNotNull null
                findMatch(root, job, seen)?.let { node -> node to window }
            }.firstOrNull()

    private companion object {
        const val POLL_MS = 300L
        const val REASON_BUSY = "another ui job is still running"
        const val REASON_INTERRUPTED = "accessibility service was interrupted"
        const val REASON_SERVICE_STOPPED = "accessibility service was stopped"
    }
}

/**
 * Breadth first, and the two kinds of target want different winners.
 *
 * A label target takes the shallowest - visually topmost - match. A rect target instead takes the
 * smallest node under the point that the app calls clickable, because a point lands on the innermost
 * control and its container would swallow the tap; only when nothing under the point is clickable do
 * we settle for the smallest node of any kind.
 */
private fun findMatch(
    root: AccessibilityNodeInfo,
    job: MediaUiJob,
    seen: MutableList<MediaUiCandidate>,
): AccessibilityNodeInfo? {
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.addLast(root)
    val underThePoint = SmallestUnderThePoint()
    var budget = NODE_BUDGET
    while (queue.isNotEmpty() && budget-- > 0) {
        val node = queue.removeFirst()
        if ((node.isClickable || node.isEditable) && seen.size < CANDIDATE_LIMIT) seen.add(candidateOf(node))
        val matched = MediaUiNodeMatcher.matches(node.toView(), job.target, job.action)
        if (matched && job.target.bounds == null) return node
        if (matched) node.boundsOrNull()?.let { underThePoint.offer(node, it) }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            queue.addLast(child)
        }
    }
    return underThePoint.winner()
}

/** Keeps the tightest control under a point, so a tap lands on the button and not on its container. */
private class SmallestUnderThePoint {
    private var clickableNode: AccessibilityNodeInfo? = null
    private var clickableArea = Long.MAX_VALUE
    private var anyNode: AccessibilityNodeInfo? = null
    private var anyArea = Long.MAX_VALUE

    fun offer(
        node: AccessibilityNodeInfo,
        bounds: MediaUiBounds,
    ) {
        if (bounds.area < anyArea) {
            anyArea = bounds.area
            anyNode = node
        }
        if (node.isClickable && bounds.area < clickableArea) {
            clickableArea = bounds.area
            clickableNode = node
        }
    }

    fun winner(): AccessibilityNodeInfo? = clickableNode ?: anyNode
}

private fun AccessibilityService.gestureTap(
    bounds: Rect,
    onSettled: (Boolean) -> Unit,
) {
    val path = Path().apply {
        moveTo(bounds.exactCenterX(), bounds.exactCenterY())
        lineTo(bounds.exactCenterX(), bounds.exactCenterY())
    }
    val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS)).build()
    dispatchGesture(
        gesture,
        object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(description: GestureDescription?) {
                onSettled(true)
            }

            override fun onCancelled(description: GestureDescription?) {
                onSettled(false)
            }
        },
        null,
    )
}

private fun AccessibilityNodeInfo.screenBounds(): Rect = Rect().also { getBoundsInScreen(it) }

/** A node the screen never drew has no area, and an empty rect is not something we can aim at. */
private fun AccessibilityNodeInfo.boundsOrNull(): MediaUiBounds? {
    val rect = screenBounds()
    if (rect.width() <= 0 || rect.height() <= 0) return null
    return MediaUiBounds(rect.left, rect.top, rect.right, rect.bottom)
}

private fun AccessibilityNodeInfo.toView(): MediaUiNodeView =
    MediaUiNodeView(
        className = className?.toString().orEmpty(),
        text = text?.toString().orEmpty(),
        contentDescription = contentDescription?.toString().orEmpty(),
        resourceId = viewIdResourceName?.toString().orEmpty(),
        clickable = isClickable,
        editable = isEditable,
        bounds = boundsOrNull(),
    )

/** A node with nothing to say about itself gets no invented name; its rect is the honest handle. */
private fun describeLabel(node: AccessibilityNodeInfo): String? =
    node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        ?: node.text?.toString()?.takeIf { it.isNotBlank() }
        ?: node.viewIdResourceName?.takeIf { it.isNotBlank() }

private fun describeNode(node: AccessibilityNodeInfo): String =
    (describeLabel(node) ?: node.className?.toString() ?: "unlabelled node")
        .trim()
        .take(LABEL_LIMIT)
        .replace(WHITESPACE, " ")

private fun candidateOf(node: AccessibilityNodeInfo): MediaUiCandidate =
    MediaUiCandidate(
        label = describeLabel(node)?.trim()?.take(LABEL_LIMIT)?.replace(WHITESPACE, " "),
        bounds = node.boundsOrNull(),
        clickable = node.isClickable,
        editable = node.isEditable,
    )

private const val NODE_BUDGET = 4_000
private const val CANDIDATE_LIMIT = 16
private const val TAP_MS = 60L
private const val LABEL_LIMIT = 80
private val WHITESPACE = Regex("\\s+")

/**
 * The one place that knows whether a ui job can run at all.
 *
 * An object because the accessibility service and the bridge request handler share one process, and
 * a job nobody can settle is worse than a clear refusal.
 */
object MediaUiAutomation {
    const val REASON_NOT_ENABLED = "accessibility service is not enabled for opencode mobile"

    private val service = AtomicReference<MediaUiAutomationService?>(null)

    fun isEnabled(): Boolean = service.get() != null

    fun perform(
        target: MediaUiTarget,
        action: MediaUiAction,
        timeoutMs: Long,
    ): MediaUiOutcome {
        val running = service.get() ?: return MediaUiOutcome.Failed(REASON_NOT_ENABLED)
        val job = MediaUiJob(target, action, timeoutMs)
        running.submit(job)
        return job.await(timeoutMs)
            ?: MediaUiOutcome.Failed("ui job did not settle inside its budget")
    }

    internal fun attach(instance: MediaUiAutomationService) {
        service.set(instance)
    }

    internal fun detach(instance: MediaUiAutomationService) {
        service.compareAndSet(instance, null)
    }
}
