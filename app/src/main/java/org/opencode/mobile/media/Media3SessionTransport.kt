package org.opencode.mobile.media

import android.content.ComponentName
import android.content.Context
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "Media3Session"
private const val COMMAND_TIMEOUT_MS = 2_000L

/**
 * Транспорт до сессии, опубликованной по протоколу media3
 * (androidx.media3.session.MediaSessionService / MediaLibraryService).
 *
 * Отдельная реализация нужна потому, что media3-сервис не отвечает на платформенный bind
 * MediaBrowser: это другой протокол и другой Binder, поэтому legacy-путь на таких
 * приложениях молча не срабатывает. Именно так была потеряна управляемость Яндекс Музыки.
 *
 * Ни shell, ни UI-автоматизация, ни права доступа: мы обычный media3-клиент, который
 * подключается к сервису, опубликованному самим приложением.
 */
internal class Media3SessionTransport(
    private val controller: MediaController,
    private val looper: HandlerThread,
) : MediaSessionTransport {
    private val ready = CountDownLatch(1)
    private val listener: Player.Listener =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                ready.countDown()
            }
        }

    init {
        // Конструктор обязан выполняться на looper контроллера: addListener туда и доставляет
        // коллбэки, с другого потока media3 бросает исключение о неверном потоке.
        controller.addListener(listener)
    }

    override fun awaitReady(timeoutMs: Long) {
        ready.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Чтение состояния — тоже вызов MediaController, поэтому только на его потоке. */
    override fun read(): MediaPlaybackSnapshot = onMediaLooper(looper, COMMAND_TIMEOUT_MS) { readMedia3(controller) }

    override fun send(command: MediaCommand) {
        onMediaLooper(looper, COMMAND_TIMEOUT_MS) {
            when (command) {
                MediaCommand.PLAY -> controller.play()
                MediaCommand.PAUSE -> controller.pause()
                MediaCommand.PLAY_PAUSE -> Unit
                MediaCommand.NEXT -> controller.seekToNext()
                MediaCommand.PREVIOUS -> controller.seekToPrevious()
                MediaCommand.STOP -> controller.stop()
            }
        }
    }

    override fun closeQuietly() {
        runCatching {
            onMediaLooper(looper, COMMAND_TIMEOUT_MS) {
                controller.removeListener(listener)
                controller.release()
            }
        }.onFailure { error -> Log.w(TAG, "closing media3 session failed", error) }
    }

    companion object {
        /**
         * Соединение строится на media-looper, а ответ сессии ждётся на вызывающем потоке:
         * future завершается колбэком, который media3 доставляет на looper контроллера, то
         * есть на тот же самый. Ожидание на нём же было бы взаимоблокировкой.
         */
        fun connect(
            context: Context,
            looper: HandlerThread,
            component: ComponentName,
            timeoutMs: Long,
        ): Media3SessionTransport? {
            val future = onMediaLooper(looper, COMMAND_TIMEOUT_MS) {
                MediaController.Builder(context, SessionToken(context, component)).buildAsync()
            }
            val controller =
                runCatching { future.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrElse { error ->
                    Log.w(TAG, "media3 session did not answer for $component", error)
                    null
                } ?: return null
            return runCatching {
                onMediaLooper(looper, COMMAND_TIMEOUT_MS) { Media3SessionTransport(controller, looper) }
            }.getOrElse { error ->
                Log.w(TAG, "cannot wrap media3 controller for $component", error)
                runCatching { onMediaLooper(looper, COMMAND_TIMEOUT_MS) { controller.release() } }
                null
            }
        }
    }
}

/**
 * Состояние media3 в терминах платформенного снимка. У media3 четыре состояния вместо
 * платформенных восьми, поэтому playing/paused различаются флагом isPlaying, а idle
 * (сессия подключена, но ничего не играет) намеренно отдаётся как none: для явного
 * package это всё равно валидная цель, а для автоопределения — честный отказ.
 */
private fun readMedia3(controller: MediaController): MediaPlaybackSnapshot {
    val metadata = controller.mediaMetadata
    return MediaPlaybackSnapshot(
        state = media3State(controller),
        isPlaying = controller.isPlaying,
        title = metadata.title.cleanMedia3Text(),
        artist = metadata.artist.cleanMedia3Text(),
        album = metadata.albumTitle.cleanMedia3Text(),
        durationMs = controller.duration.takeIf { it > 0L },
        positionMs = controller.currentPosition,
    )
}

private fun media3State(controller: MediaController): String =
    when {
        controller.playerError != null -> MediaPlaybackSnapshot.STATE_ERROR
        controller.playbackState == Player.STATE_BUFFERING -> "buffering"
        controller.playbackState == Player.STATE_READY -> if (controller.isPlaying) "playing" else "paused"
        controller.playbackState == Player.STATE_ENDED -> "stopped"
        else -> MediaPlaybackSnapshot.STATE_NONE
    }

/** media3 отдаёт CharSequence, поэтому текст приводится к строке и режется по краям. */
private fun CharSequence?.cleanMedia3Text(): String? = this?.toString()?.trim()?.takeIf(String::isNotEmpty)
