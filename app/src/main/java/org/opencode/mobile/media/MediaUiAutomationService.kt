package org.opencode.mobile.media

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.atomic.AtomicReference

/**
 * Finds one node in another app's window and clicks it.
 *
 * The point of the class is to answer one question honestly: can a tap land in a window that is not
 * the focused one? Every success therefore carries [MediaUiClickOutcome.Clicked.windowFocused], so a
 * caller never has to guess whether the tap happened behind the user's back or on screen.
 *
 * The service only ever looks at windows belonging to the package the job named, and it clicks at
 * most one node per job - no loops, no helpful extra taps.
 */
@Suppress("TooManyFunctions")
class MediaUiAutomationService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var pending: MediaUiClickJob? = null
    private var deadlineMs = 0L
    private var gestureInFlight = false
    private var lastSeenLabels: List<String> = emptyList()

    private val poll = object : Runnable {
        override fun run() = tick()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Without this flag AccessibilityService.windows stays empty, and with it empty there is no
        // background window to click in the first place.
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
        settlePending(MediaUiClickOutcome.Failed(REASON_INTERRUPTED))
    }

    override fun onDestroy() {
        MediaUiAutomation.detach(this)
        settlePending(MediaUiClickOutcome.Failed(REASON_SERVICE_STOPPED))
        super.onDestroy()
    }

    /** Called from any thread; hands the job to the main thread, where the accessibility API lives. */
    fun submit(job: MediaUiClickJob) {
        main.post {
            if (pending != null) {
                job.settle(MediaUiClickOutcome.Failed(REASON_BUSY))
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
            finish(job, MediaUiClickOutcome.NotFound(lastSeenLabels))
            return
        }
        if (!gestureInFlight && job.tryClaimAttempt()) {
            val seen = mutableListOf<String>()
            val hit = locate(job.target, seen)
            lastSeenLabels = seen
            if (hit == null) job.releaseAttempt() else tapNode(job, hit.first, hit.second)
        }
        if (pending === job) main.postDelayed(poll, POLL_MS)
    }

    private fun tapNode(
        job: MediaUiClickJob,
        node: AccessibilityNodeInfo,
        window: AccessibilityWindowInfo,
    ) {
        val label = describeNode(node)
        when {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK) -> {
                job.releaseAttempt()
                finish(job, MediaUiClickOutcome.Clicked(label, window.isFocused, gestureUsed = false))
            }

            node.screenBounds().isEmpty() -> {
                job.releaseAttempt()
                finish(job, MediaUiClickOutcome.ClickRejected(label, "node has no usable bounds"))
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
                            MediaUiClickOutcome.Clicked(label, wasFocused, gestureUsed = true)
                        } else {
                            MediaUiClickOutcome.ClickRejected(label, "tap gesture was cancelled")
                        },
                    )
                }
            }
        }
    }

    private fun finish(
        job: MediaUiClickJob,
        result: MediaUiClickOutcome,
    ) {
        if (pending === job) {
            pending = null
            main.removeCallbacks(poll)
        }
        job.settle(result)
    }

    private fun settlePending(result: MediaUiClickOutcome) {
        val job = pending ?: return
        pending = null
        main.removeCallbacks(poll)
        job.settle(result)
    }

    /** First matching node, searched across every window the service is allowed to see. */
    private fun locate(
        target: MediaUiClickTarget,
        seen: MutableList<String>,
    ): Pair<AccessibilityNodeInfo, AccessibilityWindowInfo>? =
        windows
            .orEmpty()
            .asSequence()
            .filter { window -> window.root?.packageName?.toString() == target.packageName }
            .mapNotNull { window ->
                val root = window.root ?: return@mapNotNull null
                findMatch(root, target, seen)?.let { node -> node to window }
            }.firstOrNull()

    private companion object {
        const val POLL_MS = 300L
        const val REASON_BUSY = "another ui click job is still running"
        const val REASON_INTERRUPTED = "accessibility service was interrupted"
        const val REASON_SERVICE_STOPPED = "accessibility service was stopped"
    }
}

/** Breadth first, so the shallowest - visually topmost - match wins. */
private fun findMatch(
    root: AccessibilityNodeInfo,
    target: MediaUiClickTarget,
    seen: MutableList<String>,
): AccessibilityNodeInfo? {
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.addLast(root)
    var budget = NODE_BUDGET
    while (queue.isNotEmpty() && budget-- > 0) {
        val node = queue.removeFirst()
        if (node.isClickable && seen.size < SEEN_LIMIT) seen.add(describeNode(node))
        if (MediaUiNodeMatcher.matches(node.toView(), target)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            queue.addLast(child)
        }
    }
    return null
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

private fun AccessibilityNodeInfo.toView(): MediaUiNodeView =
    MediaUiNodeView(
        className = className?.toString().orEmpty(),
        text = text?.toString().orEmpty(),
        contentDescription = contentDescription?.toString().orEmpty(),
        resourceId = viewIdResourceName?.toString().orEmpty(),
        clickable = isClickable,
    )

private fun describeNode(node: AccessibilityNodeInfo): String {
    val label = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        ?: node.text?.toString()?.takeIf { it.isNotBlank() }
        ?: node.viewIdResourceName?.takeIf { it.isNotBlank() }
        ?: node.className?.toString()
        ?: "unlabelled node"
    return label.trim().take(LABEL_LIMIT).replace(WHITESPACE, " ")
}

private const val NODE_BUDGET = 4_000
private const val SEEN_LIMIT = 12
private const val TAP_MS = 60L
private const val LABEL_LIMIT = 80
private val WHITESPACE = Regex("\\s+")

/**
 * The one place that knows whether a click job can run at all.
 *
 * An object because the accessibility service and the bridge request handler share one process, and
 * a job nobody can settle is worse than a clear refusal.
 */
object MediaUiAutomation {
    const val REASON_NOT_ENABLED = "accessibility service is not enabled for opencode mobile"

    private val service = AtomicReference<MediaUiAutomationService?>(null)

    fun isEnabled(): Boolean = service.get() != null

    fun click(
        target: MediaUiClickTarget,
        timeoutMs: Long,
    ): MediaUiClickOutcome {
        val running = service.get() ?: return MediaUiClickOutcome.Failed(REASON_NOT_ENABLED)
        val job = MediaUiClickJob(target, timeoutMs)
        running.submit(job)
        return job.await(timeoutMs)
            ?: MediaUiClickOutcome.Failed("click job did not settle inside its budget")
    }

    internal fun attach(instance: MediaUiAutomationService) {
        service.set(instance)
    }

    internal fun detach(instance: MediaUiAutomationService) {
        service.compareAndSet(instance, null)
    }
}
