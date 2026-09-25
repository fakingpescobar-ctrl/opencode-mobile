package org.opencode.mobile.media

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

    override fun playItem(
        mediaId: String,
        uri: String,
        title: String?,
    ) {
        onMediaLooper(looper, COMMAND_TIMEOUT_MS) {
            val item =
                MediaItem
                    .Builder()
                    .setMediaId(mediaId)
                    .setUri(uri)
                    .apply { title?.let { setMediaMetadata(MediaMetadata.Builder().setTitle(it).build()) } }
                    .build()
            // setMediaItem сбрасывает очередь и сразу стартует трек, поэтому play() не нужен.
            controller.setMediaItem(item)
        }
    }

    /**
     * Кастомная команда отправляется на media-looper, а её результат ждётся на вызывающем
     * потоке: media3 отдаёт future через колбэк на application looper, и ожидание внутри
     * media-looper было бы риском взаимоблокировки. Неизвестное действие — это отказ, а не
     * успех: иначе «добавил в любимое» сообщало бы об успехе, ничего не сделав.
     */
    override fun sendCustom(action: String): MediaCustomCommandResult? {
        val future =
            onMediaLooper(looper, COMMAND_TIMEOUT_MS) {
                controller.availableSessionCommands.commands
                    .firstOrNull { it.customAction == action }
                    ?.let { controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), Bundle.EMPTY) }
            }
                ?: return MediaCustomCommandResult(action, false, "session does not expose $action")
        return try {
            val result = future.get(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            MediaCustomCommandResult(
                action = action,
                ok = result.resultCode == SessionResult.RESULT_SUCCESS,
                detail = "result_code=${result.resultCode}",
            )
        } catch (error: TimeoutException) {
            Log.w(TAG, "custom command $action timed out", error)
            MediaCustomCommandResult(action, false, "timeout")
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            MediaCustomCommandResult(action, false, "interrupted")
        } catch (error: ExecutionException) {
            Log.w(TAG, "custom command $action failed", error)
            MediaCustomCommandResult(action, false, error.cause?.javaClass?.simpleName ?: "failed")
        }
    }

    override fun supportsSetMediaItem(): Boolean =
        onMediaLooper(looper, COMMAND_TIMEOUT_MS) {
            controller.availableCommands.contains(Player.COMMAND_SET_MEDIA_ITEM)
        }

    override fun capabilities(): MediaCapabilities =
        onMediaLooper(looper, COMMAND_TIMEOUT_MS) {
            val player = controller.availableCommands
            val names = (0 until player.size()).map { index -> commandName(player.get(index)) }
            MediaCapabilities(
                playerCommands = names,
                sessionCommands =
                    controller.availableSessionCommands.commands.map { command ->
                        val name = SESSION_COMMAND_NAMES[command.commandCode] ?: "code_${command.commandCode}"
                        val action: String? = command.customAction
                        if (action.isNullOrEmpty()) name else "$name:$action"
                    },
                supportsSetMediaItem = player.contains(Player.COMMAND_SET_MEDIA_ITEM),
                playerError = controller.playerError?.errorCodeName,
            )
        }

    /**
     * Имена нужны не для красоты: у media3 набор команд различается между плеерами, и без
     * точного названия в ответе нельзя понять, поддерживает ли сессия, например,
     * setMediaItem. Неизвестный бит отдаём числом, а не молча выкидываем.
     */
    private fun commandName(command: Int): String = COMMAND_NAMES[command] ?: "command_$command"

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
         * Коды session-команд media3 → имена. Именно здесь прячется путь к избранному:
         * set_rating сессия Яндекса принимает без всякого OAuth.
         */
        private val SESSION_COMMAND_NAMES: Map<Int, String> =
            mapOf(
                SessionCommand.COMMAND_CODE_SESSION_SET_RATING to "set_rating",
                SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT to "library_get_root",
                SessionCommand.COMMAND_CODE_LIBRARY_SUBSCRIBE to "library_subscribe",
                SessionCommand.COMMAND_CODE_LIBRARY_UNSUBSCRIBE to "library_unsubscribe",
                SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN to "library_get_children",
                SessionCommand.COMMAND_CODE_LIBRARY_GET_ITEM to "library_get_item",
                SessionCommand.COMMAND_CODE_LIBRARY_SEARCH to "library_search",
                SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT to "library_get_search_result",
            )

        /** Разряды media3 → человекочитаемое имя; таблица, а не ветвление, ради cyclomatic. */
        private val COMMAND_NAMES: Map<Int, String> =
            mapOf(
                Player.COMMAND_PLAY_PAUSE to "play_pause",
                Player.COMMAND_PREPARE to "prepare",
                Player.COMMAND_STOP to "stop",
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION to "seek_to_default_position",
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM to "seek_in_current_item",
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM to "seek_to_previous_item",
                Player.COMMAND_SEEK_TO_PREVIOUS to "seek_to_previous",
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM to "seek_to_next_item",
                Player.COMMAND_SEEK_TO_NEXT to "seek_to_next",
                Player.COMMAND_SEEK_TO_MEDIA_ITEM to "seek_to_item",
                Player.COMMAND_SEEK_BACK to "seek_back",
                Player.COMMAND_SEEK_FORWARD to "seek_forward",
                Player.COMMAND_SET_SPEED_AND_PITCH to "set_speed_and_pitch",
                Player.COMMAND_SET_SHUFFLE_MODE to "set_shuffle_mode",
                Player.COMMAND_SET_REPEAT_MODE to "set_repeat_mode",
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM to "get_current_item",
                Player.COMMAND_GET_TIMELINE to "get_timeline",
                Player.COMMAND_GET_METADATA to "get_metadata",
                Player.COMMAND_SET_PLAYLIST_METADATA to "set_playlist_metadata",
                Player.COMMAND_SET_MEDIA_ITEM to "set_media_item",
                Player.COMMAND_CHANGE_MEDIA_ITEMS to "change_media_items",
                Player.COMMAND_GET_AUDIO_ATTRIBUTES to "get_audio_attributes",
                Player.COMMAND_GET_VOLUME to "get_volume",
                Player.COMMAND_GET_DEVICE_VOLUME to "get_device_volume",
                Player.COMMAND_SET_VOLUME to "set_volume",
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS to "adjust_device_volume_with_flags",
                Player.COMMAND_SET_AUDIO_ATTRIBUTES to "set_audio_attributes",
                Player.COMMAND_GET_TEXT to "get_text",
                Player.COMMAND_GET_TRACKS to "get_tracks",
                Player.COMMAND_RELEASE to "release",
            )

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
