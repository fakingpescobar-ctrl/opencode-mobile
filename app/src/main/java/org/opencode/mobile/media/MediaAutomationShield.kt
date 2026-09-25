package org.opencode.mobile.media

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.opencode.mobile.ui.ChatMirrorSurface
import org.opencode.mobile.ui.theme.OpencodeMobileTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hides another app behind a full screen window that keeps drawing our own chat.
 *
 * Accessibility only hands out windows the system still counts as visible, so the honest way to keep
 * Yandex off the screen is to put our own window over it and take that window away the moment the tap
 * is done. The window is not a blank plate: it renders the very same chat the user is looking at (see
 * ChatMirrorSurface), so raising it is visually indistinguishable from leaving the app — Yandex never
 * gets a frame on screen at all.
 *
 * The window is deliberately short lived and retracts itself: whatever goes wrong, the user gets
 * their screen back within [MAX_LIFETIME_MS] instead of staring at a stuck overlay. It is also
 * deliberately see-through for touches - see [shieldFlags] - so the tap that drives the app below
 * still lands on that app.
 */
object MediaAutomationShield {
    const val REASON_OVERLAYS_NOT_ALLOWED = "opencode mobile may not draw over other apps"
    const val REASON_NOT_INITIALIZED = "shield was not initialized"

    private const val MAX_LIFETIME_MS = 8_000L
    private const val MIN_LIFETIME_MS = 200L
    private const val INSTALL_WAIT_MS = 1_500L

    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var window: ComposeView? = null
    private var retract: Runnable? = null
    private var lastReason: String? = null

    fun initialize(appContext: Context) {
        if (context != null) return
        context = appContext.applicationContext
    }

    fun canDraw(): Boolean {
        val app = context ?: return false
        return Settings.canDrawOverlays(app)
    }

    fun isShowing(): Boolean = window != null

    fun lastReason(): String? = lastReason

    fun show(lifetimeMs: Long = MAX_LIFETIME_MS): Boolean {
        val app = context
        if (app == null || !Settings.canDrawOverlays(app)) {
            lastReason = if (app == null) REASON_NOT_INITIALIZED else REASON_OVERLAYS_NOT_ALLOWED
            return false
        }
        val budget = lifetimeMs.coerceIn(MIN_LIFETIME_MS, MAX_LIFETIME_MS)
        val installed = CountDownLatch(1)
        var placed = false
        main.post {
            placed = install(app, budget)
            installed.countDown()
        }
        installed.await(INSTALL_WAIT_MS, TimeUnit.MILLISECONDS)
        return placed
    }

    fun hide() {
        main.post { remove() }
    }

    private fun install(
        app: Context,
        lifetimeMs: Long,
    ): Boolean =
        when {
            window != null -> {
                scheduleRetract(lifetimeMs)
                true
            }

            else -> addWindow(app, lifetimeMs)
        }

    private fun addWindow(
        app: Context,
        lifetimeMs: Long,
    ): Boolean {
        val manager = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        val view = ComposeView(app).apply {
            // Окно сервиса не принадлежит Activity: CompositionLocal, которые Android
            // раздаёт View-дереву, здесь пустые. Без собственного владельца ComposeView
            // не сможет ни скомпоновать контент, ни пережить attach/detach.
            setViewTreeLifecycleOwner(ShieldWindowOwner)
            setViewTreeSavedStateRegistryOwner(ShieldWindowOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                OpencodeMobileTheme {
                    ChatMirrorSurface()
                }
            }
        }
        val params =
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
                LayoutParams.TYPE_APPLICATION_OVERLAY,
                shieldFlags(),
                PixelFormat.OPAQUE,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
        return runCatching { manager.addView(view, params) }
            .onSuccess {
                window = view
                lastReason = null
                ShieldWindowOwner.resume()
                scheduleRetract(lifetimeMs)
            }.isSuccess
    }

    private fun scheduleRetract(lifetimeMs: Long) {
        retract?.let { main.removeCallbacks(it) }
        retract = Runnable { remove() }.also { main.postDelayed(it, lifetimeMs) }
    }

    private fun remove() {
        val view = window ?: return
        retract?.let { main.removeCallbacks(it) }
        retract = null
        window = null
        ShieldWindowOwner.pause()
        val manager = view.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        runCatching { manager?.removeView(view) }
    }
}

/**
 * Окно щита только показывает - оно не должно ни получать, ни перехватывать касания.
 *
 * Иначе получается худший вид вранья: жест, который accessibility-сервис отправляет по координатам
 * узла, уходит в наше же окно, Яндекс ничего не делает, а вызывающий всё равно получает `Clicked`.
 * С `FLAG_NOT_TOUCHABLE` касание проходит насквозь, и тап доезжает туда, куда aims. Побочный эффект
 * честный и крошечный: пока щит жив (по умолчанию 2с), касание самого человека уходит в Яндекс -
 * но ровно туда же, куда он и смотрит, только в другую картинку.
 */
private fun shieldFlags(): Int = LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE

/**
 * Минимальный владелец жизненного цикла для окна щита.
 *
 * ComposeView ожидает в дереве View LifecycleOwner (иначе не подписывается на recomposition),
 * а Activity, которая дала бы его, под щитом недоступна. Один объект на всё приложение:
 * щит в любой момент может быть один, а лишние подписки ему не нужны.
 */
private object ShieldWindowOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)

    init {
        controller.performRestore(null)
    }

    override val lifecycle: Lifecycle get() = registry

    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun pause() {
        registry.currentState = Lifecycle.State.CREATED
    }
}
