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
        when (action) {
            is MediaUiAction.Click -> clickNode(job, node, window, describeNode(node))
            // Нашли и отпустили: узел не трогаем, но сам факт наличия - уже ответ на вопрос.
            is MediaUiAction.ReadText ->
                finish(job, MediaUiOutcome.Performed(action, describeNode(node), window.isFocused, gestureUsed = false))
            else -> finish(job, writeText(node, window, action))
        }
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
 * Breadth first, and the three kinds of target want different winners.
 *
 * A label target takes the shallowest - visually topmost - match, unless it asked for the
 * largest one: on a playlist screen "Play" names both the mini player and the playlist's own
 * button, and the tap has to land on the big one. A rect target instead takes the smallest node
 * under the point that the app calls clickable, because a point lands on the innermost control
 * and its container would swallow the tap; only when nothing under the point is clickable do
 * we settle for the smallest node of any kind.
 */
private fun findMatch(
    root: AccessibilityNodeInfo,
    job: MediaUiJob,
    seen: MutableList<MediaUiCandidate>,
): AccessibilityNodeInfo? {
    val search = MatchSearch(job.target)
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.addLast(root)
    var budget = NODE_BUDGET
    while (queue.isNotEmpty() && budget-- > 0) {
        val node = queue.removeFirst()
        if ((node.isClickable || node.isEditable) && seen.size < CANDIDATE_LIMIT) seen.add(candidateOf(node))
        if (MediaUiNodeMatcher.matches(node.toView(), job.target, job.action) && search.offer(node)) {
            return node
        }
        enqueueChildren(node, queue)
    }
    return search.best()
}

/**
 * Два ответа на одну подпись: узел, который кликается сам, и его кликабельный предок.
 *
 * Разделены потому, что точный ответ всегда лучше унаследованного: подпись «Нравится» есть и
 * на самой кнопке, и на подписи внутри строки списка, и тап по строке нажал бы не то. Поэтому
 * унаследованный ответ не завершает обход - им пользуются, только если точного не нашлось вовсе.
 */
private class MatchSearch(
    private val target: MediaUiTarget,
) {
    private val exact = MatchRounds(target)
    private val inherited = MatchRounds(target)

    /** `true` — узел и есть ответ, обход можно закончить. */
    fun offer(node: AccessibilityNodeInfo): Boolean =
        if (!target.requireClickable || node.isClickable) {
            exact.accept(node)
        } else {
            // Подпись кнопки у Яндекса лежит на дочернем узле, а кликается родитель: такую
            // кнопку не видно, если требовать кликабельность от самого совпадения.
            node.tappableAncestor()?.let { inherited.accept(it) }
            false
        }

    fun best(): AccessibilityNodeInfo? = exact.best() ?: inherited.best()
}

/**
 * Сколько вверх можно подняться в поисках кликабельного предка.
 *
 * Потолок нужен, чтобы цепочка не ушла в корень окна и не «нажала» на весь экран, но слишком
 * низкий порог хуже отсутствия порога: у строки трека в плейлисте Яндекса кликабельный
 * родитель лежит на четырнадцатом узле вверх, а у кнопки в шапке - на первом. Двадцать четыре
 * с запасом покрывают оба и всё ещё ограничивают обход.
 */
private const val ANCESTOR_HOPS = 24

/**
 * Ближайший кликабельный предок узла с найденной подписью.
 *
 * У play-кнопки плейлиста подпись `Слушать` живёт на узле с `clickable=false` внутри
 * кликабельного View. Без подъёма поиск её не находит вовсе, и единственная подпись «Слушать»
 * остаётся у пункта «Слушать Мою волну» в нижнем меню - он запускает постороннюю волну, и
 * инструмент сообщает о запуске чужого трека. Поднимаемся до родителя, кликаем по нему, и
 * «самая большая» кнопка считается по его рамке, а не по подписи.
 */
private fun AccessibilityNodeInfo.tappableAncestor(): AccessibilityNodeInfo? {
    var candidate = parent
    var hops = 0
    while (candidate != null && hops++ < ANCESTOR_HOPS) {
        if (candidate.isClickable) return candidate
        candidate = candidate.parent
    }
    return null
}

private fun enqueueChildren(
    node: AccessibilityNodeInfo,
    queue: ArrayDeque<AccessibilityNodeInfo>,
) {
    for (index in 0 until node.childCount) {
        node.getChild(index)?.let { queue.addLast(it) }
    }
}

/**
 * Кто побеждает среди уже просмотренных узлов и когда поиск можно остановить.
 *
 * Разведено с обходом, потому что у трёх видов таргета правило разное, и в теле цикла оно
 * размазывалось на четыре условия. Здесь оно читается прямо: у метки без `preferLargest`
 * первый же матч и есть ответ, у метки с ним - ждём самый крупный, у rect - самый тугой.
 */
private class MatchRounds(
    private val target: MediaUiTarget,
) {
    private val underThePoint = SmallestUnderThePoint()
    private val widest = WidestMatch<AccessibilityNodeInfo>()
    private val topmost = TopmostMatch<AccessibilityNodeInfo>()
    private var first: AccessibilityNodeInfo? = null

    /** `true` — узел и есть ответ, дальше идти незачем. */
    fun accept(node: AccessibilityNodeInfo): Boolean {
        first = first ?: node
        node.boundsOrNull()?.let { bounds ->
            when {
                target.bounds != null -> underThePoint.offer(node, bounds)
                target.preferTopmost -> topmost.offer(node, bounds)
                else -> widest.offer(node, bounds)
            }
        }
        return target.bounds == null && !target.preferLargest && !target.preferTopmost
    }

    /**
     * Ответ без раннего выхода из обхода: первый замеченный узел, а если правило таргета
     * требует лучший, то победитель этого правила.
     *
     * Нужен вторым проходом в [findMatch], где отвечать раньше времени нельзя: там решение
     * принимает не этот класс, а сравнение точного и унаследованного совпадения.
     */
    fun best(): AccessibilityNodeInfo? =
        first
            ?: when {
                target.bounds == null && target.preferTopmost -> topmost.winner()
                target.bounds == null && target.preferLargest -> widest.winner()
                else -> underThePoint.winner()
            }
}

/**
 * Keeps the roomiest control among equally named ones, so "Play" means the playlist's own button.
 *
 * Generic over the payload purely so the rule can be tested: [node] in production is an
 * AccessibilityNodeInfo, which cannot be built off a device, and an untested "bigger wins" is
 * exactly the kind of rule that quietly keeps picking the wrong control.
 */
internal class WidestMatch<T> {
    private var node: T? = null
    private var area = -1L

    fun offer(
        candidate: T,
        bounds: MediaUiBounds,
    ) {
        if (bounds.area > area) {
            area = bounds.area
            node = candidate
        }
    }

    fun winner(): T? = node
}

/**
 * Keeps the control nearest the top of the screen, so a repeated track name means the first
 * track of a list and not the mini-player that happens to be playing it.
 *
 * On the Yandex playlist screen the first head track appears twice: once as the row of the
 * playlist list and once in the mini-player pinned to the bottom, and when playback is paused
 * the mini-player is the roomier of the two - a "largest wins" rule reliably taps it and
 * resumes whatever was playing before instead of starting the list. The row is always the
 * upper one, so "topmost wins" separates them. Ties go to the larger control, which only
 * happens when both are on the same line.
 *
 * Generic over the payload for the same reason as [WidestMatch]: the rule is worth a unit test
 * and an AccessibilityNodeInfo cannot be built off the device.
 */
internal class TopmostMatch<T> {
    private var node: T? = null
    private var top = Int.MAX_VALUE
    private var area = -1L

    fun offer(
        candidate: T,
        bounds: MediaUiBounds,
    ) {
        if (bounds.top < top || (bounds.top == top && bounds.area > area)) {
            top = bounds.top
            area = bounds.area
            node = candidate
        }
    }

    fun winner(): T? = node
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
