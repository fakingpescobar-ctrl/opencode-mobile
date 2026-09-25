package org.opencode.mobile.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Установленное приложение, способное отдать медиасессию или принять media button. */
data class MediaAppSnapshot(
    val packageName: String,
    val label: String,
    val sessionServices: List<String>,
    val mediaButtonReceiver: String?,
    /** Сколько session-сервисов есть, но они не экспортированы — к ним нельзя подключиться. */
    val hiddenSessionServices: Int = 0,
) {
    /**
     * Управляемо ли приложение отсюда. Только экспортированная сессия: media button
     * broadcast платформа от стороннего приложения не маршрутизирует, проверено на
     * устройстве — receiver событие получает, но плеер на него не реагирует.
     */
    val controlable: Boolean
        get() = sessionServices.isNotEmpty()
}

/** Снимок состояния медиасессии в момент чтения. */
data class MediaPlaybackSnapshot(
    val state: String,
    val isPlaying: Boolean,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val positionMs: Long?,
) {
    /** Сессия считается живой, если у неё есть состояние воспроизведения или метаданные. */
    val isUsable: Boolean
        get() = state != STATE_NONE || title != null

    /** Системная сессия без единого трека: state=error и пустые метаданные — не целевой плеер. */
    val isDegenerate: Boolean
        get() = title == null && (state == STATE_ERROR || positionMs == 0L)

    /** Ничего не играет и трека нет: значит плеер спит и команду play придётся будить с нуля. */
    val isIdle: Boolean
        get() = state == STATE_NONE && title == null && !isPlaying

    companion object {
        const val STATE_NONE = "none"
        const val STATE_ERROR = "error"
    }
}

data class MediaControlResult(
    val packageName: String,
    val label: String,
    val transport: String,
    val command: String,
    val message: String,
    val before: MediaPlaybackSnapshot?,
    val after: MediaPlaybackSnapshot?,
    /** true только когда состояние прочитано до и после: media button это подтвердить не может. */
    val verified: Boolean,
)

data class MediaStatusResult(
    val packageName: String,
    val label: String,
    val transport: String,
    val message: String,
    val playback: MediaPlaybackSnapshot,
)

private const val TAG = "MediaControlController"
private const val COMMAND_TIMEOUT_MS = 2_000L
private const val AFTER_POLL_MS = 80L
private const val SEEK_BACKWARD_MS = -1_000L
private const val SEEK_FORWARD_MS = 5_000L

/**
 * Управление чужими медиасессиями Android через публичный MediaBrowserService.
 *
 * Привилегированный MEDIA_CONTENT_CONTROL не нужен: сессия публикуется самим приложением,
 * мы лишь подключаемся к ней как MediaBrowser-клиент и шлём транспортные команды.
 * Запасного пути через ACTION_MEDIA_BUTTON нет намеренно: платформа маршрутизирует
 * media buttons только из системы, поэтому broadcast из стороннего приложения не управляет
 * плеером (проверено на устройстве — receiver событие получает, но сессия не реагирует).
 * Ни shell, ни UI-автоматизация, ни доступ к данным приложений здесь не используются.
 */
// LargeClass: это диспетчер сессий, и его размер давно не про обход дерева -
// сам обход живёт в MediaLibraryBrowser. Дальше дробить имеет смысл только вместе
// с переездом probe/capabilities, а не из-за двух десятков строк входа в библиотеку.
@Suppress("TooManyFunctions", "LargeClass")
object MediaControlController {
    const val TRANSPORT_SESSION = "media_session"

    /** Имя команды для «включить конкретный трек»: не транспортная кнопка, а элемент каталога. */
    const val COMMAND_PLAY_ITEM = "play_item"

    private const val CONNECT_TIMEOUT_MS = 4_000L
    private const val PROBE_CONNECT_TIMEOUT_MS = 1_200L
    private const val STATE_TIMEOUT_MS = 800L
    private const val AFTER_STATE_TIMEOUT_MS = 2_500L
    private const val COLD_START_TIMEOUT_MS = 9_000L
    private const val PROBE_LIMIT = 8
    private const val PROBE_PARALLELISM = 4
    private const val PROBE_TOTAL_TIMEOUT_MS = 3_000L
    private const val CANDIDATE_LIMIT = 5
    private const val RETAIN_MS = 30_000L
    private const val RESOLVE_CACHE_MS = 20_000L
    private const val SCORE_PLAYING = 3
    private const val SCORE_WITH_METADATA = 2
    private const val SCORE_USABLE = 1

    private val SESSION_SERVICE_ACTIONS =
        listOf(
            "android.media.browse.MediaBrowserService",
            "androidx.media3.session.MediaLibraryService",
            "androidx.media3.session.MediaSessionService",
        )

    /** Подмножество action-ов, которые говорят на протоколе media3, а не на платформенном. */
    private val MEDIA3_SERVICE_ACTIONS =
        setOf(
            "androidx.media3.session.MediaLibraryService",
            "androidx.media3.session.MediaSessionService",
        )

    /** Action-ы сервисов, которые умеют отдавать дерево, а не только кнопки. */
    private val LIBRARY_SERVICE_ACTIONS =
        setOf(
            "androidx.media3.session.MediaLibraryService",
            "android.media.browse.MediaBrowserService",
        )

    /** Порядок попыток: родной протокол приложения первым, платформенный — запасным. */
    private val MEDIA3_FIRST_PASSES = listOf(true, false)

/** Коды ошибок дерева media3: агенту без имени вообще не разбирает. */

    /**
     * action-ы, по которым найдены сервисы: ключ — flattened-компонент. Нужен, чтобы не
     * гадать протокол при подключении: один и тот же компонент может быть найден и как
     * legacy MediaBrowserService, и как media3-сервис.
     */
    @Volatile
    private var serviceActions: Map<String, String> = emptyMap()

    private val retainLock = Any()
    private var retainedSession: MediaSessionTransport? = null
    private var retainedAt = 0L

    @Volatile
    private var resolvedPackage: String? = null

    @Volatile
    private var resolvedAt = 0L

    private lateinit var context: Context

    private val looper: HandlerThread by lazy { HandlerThread("media-control").apply { start() } }
    private val handler: Handler by lazy { Handler(looper.looper) }

    @Synchronized
    fun initialize(context: Context) {
        if (::context.isInitialized) return
        this.context = context.applicationContext
    }

    fun listApps(spec: MediaAppListSpec): List<MediaAppSnapshot> {
        val normalizedQuery = spec.query?.lowercase(Locale.ROOT)
        return mediaApps()
            .filter { app ->
                normalizedQuery == null ||
                    app.label.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                    app.packageName.lowercase(Locale.ROOT).contains(normalizedQuery)
            }.take(spec.limit)
    }

    fun control(spec: MediaControlSpec): MediaControlResult {
        val apps = mediaApps()
        require(apps.isNotEmpty()) { "no installed app exposes a media session or media button" }
        val target = targetFor(apps, spec.packageName)
        val session = target.session ?: error(unreadableReason(target.app))
        return try {
            controlWithSession(target.app, session, spec.command)
        } finally {
            releaseUnused(session)
        }
    }

    /**
     * Включение конкретного трека каталога. Эффект известен заранее — целевой трек, — поэтому
     * verified требует именно его в метаданных. Любая смена состояния или чужой трек в ответе
     * означают, что сессия элемент не взяла: сообщать об успехе по факту смены заголовка
     * значило бы врать агенту, который потом скажет пользователю «включил».
     */
    fun play(spec: MediaPlaySpec): MediaControlResult {
        val apps = mediaApps()
        require(apps.isNotEmpty()) { "no installed app exposes a media session" }
        val target = targetFor(apps, spec.packageName)
        val session = target.session ?: error(unreadableReason(target.app))
        return try {
            val before = session.awaitPlayback(STATE_TIMEOUT_MS)
            // Сессия отдаёт set_media_item только у уже запущенного плеера: на холодном
            // старте команда молча игнорируется, поэтому сначала поднимаем воспроизведение.
            if (before.isIdle || !session.supportsSetMediaItem()) session.send(MediaCommand.PLAY)
            session.playItem(spec.mediaId, spec.uri, spec.title)
            val after = awaitEffect(session, before, MediaCommand.PLAY, COLD_START_TIMEOUT_MS)
            val expected = spec.title?.lowercase(Locale.ROOT)
            val actual = after.title?.lowercase(Locale.ROOT).orEmpty()
            val playing = after.isPlaying
            val matched = expected == null || actual.contains(expected)
            val verified = playing && matched
            Log.i(
                TAG,
                "media play_item=${spec.mediaId} package=${spec.packageName} " +
                    "state=${before.state}->${after.state} title=${after.title} verified=$verified",
            )
            MediaControlResult(
                packageName = target.app.packageName,
                label = target.app.label,
                transport = TRANSPORT_SESSION,
                command = COMMAND_PLAY_ITEM,
                message =
                    if (verified) {
                        "Android media session started catalog item ${spec.mediaId}"
                    } else {
                        "Android media session did not start catalog item ${spec.mediaId}" +
                            " (reported '${after.title}'${if (expected != null) ", expected '$expected'" else ""})"
                    },
                before = before,
                after = after,
                verified = verified,
            )
        } finally {
            releaseUnused(session)
        }
    }

    /**
     * Лайк/анлайк через кастомную команду сессии: у Яндекс Музыки это
     * `ru.yandex.music.action.ADD_LIKE`, поэтому OAuth и REST не нужны. Действует на текущий
     * трек, поэтому до отправки читаем состояние — иначе «добавь в любимое» без включённого
     * трека выглядело бы как успех.
     */
    fun like(spec: MediaLikeSpec): MediaLikeResult {
        val apps = mediaApps()
        require(apps.isNotEmpty()) { "no installed app exposes a media session" }
        val target = targetFor(apps, spec.packageName)
        val session = target.session ?: error(unreadableReason(target.app))
        return try {
            val before = session.awaitPlayback(STATE_TIMEOUT_MS)
            val result = session.sendCustom(spec.sessionAction)
            if (result == null) {
                error("${target.app.label} session does not support custom actions")
            }
            Log.i(
                TAG,
                "media ${spec.action} package=${spec.packageName} title=${before.title} ok=${result.ok}",
            )
            MediaLikeResult(
                packageName = target.app.packageName,
                label = target.app.label,
                action = spec.action,
                ok = result.ok,
                detail = result.detail,
                playback = before,
            )
        } finally {
            releaseUnused(session)
        }
    }

    /**
     * Поиск по каталогу. Живой сетевой вызов, поэтому делаем его вне внутренних замков и
     * отдаём наружу только необходимое агенту: артист, треки и deep link для проигрывания.
     */
    fun search(spec: MediaSearchSpec): MediaSearchResult {
        val result = YandexCatalog.search(spec.query, spec.limit)
        Log.i(TAG, "media search ${YandexCatalog.describe(result)}")
        return MediaSearchResult(
            query = result.query,
            artist = result.artist,
            tracks = result.tracks,
        )
    }

    /**
     * Обход дерева библиотеки плеера.
     *
     * Смысл не в красоте, а в том, что отсюда берётся mediaId, который сессия признаёт своим.
     * Трек из публичного каталога сессия игнорирует, а трек, на который она сама дала ссылку,
     * принять обязана — иначе ссылка была бы неправильной.
     */
    fun library(spec: MediaLibrarySpec): MediaLibraryResult {
        val apps = mediaApps()
        require(apps.isNotEmpty()) { "no installed app exposes a media session" }
        val app = apps.firstOrNull { it.packageName == spec.packageName }
            ?: error("no media session app for ${spec.packageName}")
        val components = libraryComponents(app)
        require(components.isNotEmpty()) { "${spec.packageName} has no media library service" }
        val result = MediaLibraryBrowser(contextOrThrow(), looper).browse(components, spec)
        Log.i(
            TAG,
            "media library ${spec.packageName} node=${spec.node} query=${spec.query} " +
                "entries=${result.entries.size} state=${result.message}",
        )
        return result
    }

    /**
     * Кандидаты в порядке убывания правды: media3-библиотека умеет дерево по протоколу, обычная
     * сессия — нет, legacy-браузер — последний шанс. Проверяем всех, потому что отказ одного
     * сервиса ничего не говорит о втором, а молчаливый «первый ответ» скрыл бы различие.
     */
    private fun libraryComponents(app: MediaAppSnapshot): List<ComponentName> {
        val refs = sessionRefs(app)
        val library = refs.filter { ref -> ref.action in LIBRARY_SERVICE_ACTIONS }
        val ordered = library.ifEmpty { refs }.sortedBy { ref -> if (ref.isMedia3) 0 else 1 }
        return ordered.map { ref -> ref.component }
    }

    fun capabilities(packageName: String?): MediaCapabilities {
        val apps = mediaApps()
        require(apps.isNotEmpty()) { "no installed app exposes a media session" }
        val target = targetFor(apps, packageName)
        val session = target.session ?: error(unreadableReason(target.app))
        return try {
            session.capabilities() ?: error("${target.app.label} session does not report capabilities")
        } finally {
            releaseUnused(session)
        }
    }

    fun status(packageName: String?): MediaStatusResult {
        val apps = mediaApps()
        require(apps.isNotEmpty()) { "no installed app exposes a media session" }
        val target = targetFor(apps, packageName)
        val session = target.session ?: error(unreadableReason(target.app))
        return try {
            val playback = session.awaitPlayback(STATE_TIMEOUT_MS)
            MediaStatusResult(
                packageName = target.app.packageName,
                label = target.app.label,
                transport = TRANSPORT_SESSION,
                message = "Android media session read",
                playback = playback,
            )
        } finally {
            releaseUnused(session)
        }
    }

    /**
     * Ждём реального эффекта команды, а не фиксированную паузу: плееры отражают паузу с
     * задержкой, и короткое окно превращало бы `verified=true` в ложь. Позиция при игре
     * растёт сама по себе, поэтому сравнение по ней не годится — ловим смену состояния
     * или перемотки, а по истечении таймаута отдаём как есть и оставляем verified=false.
     */
    private fun awaitEffect(
        transport: MediaSessionTransport,
        previous: MediaPlaybackSnapshot,
        command: MediaCommand,
        timeoutMs: Long,
    ): MediaPlaybackSnapshot {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var current = transport.read()
        while (!hasEffect(previous, current, command) && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(AFTER_POLL_MS)
            current = transport.read()
        }
        return current
    }

    /**
     * Считаем ли мы изменение эффектом команды. Смена состояния и смена трека очевидны;
     * для next/previous/догоняющего play засчитываем ещё и перемотку, потому что там
     * состояние может остаться playing. Рост позиции при обычной игре эффектом не считаем.
     */
    private fun hasEffect(
        previous: MediaPlaybackSnapshot,
        current: MediaPlaybackSnapshot,
        command: MediaCommand,
    ): Boolean {
        val trackChanged = current.title != previous.title || current.album != previous.album
        val stateChanged = current.state != previous.state
        val seeked = jumped(previous.positionMs, current.positionMs) && seeks(command)
        return stateChanged || trackChanged || seeked
    }

    private fun seeks(command: MediaCommand): Boolean =
        command == MediaCommand.NEXT ||
            command == MediaCommand.PREVIOUS ||
            command == MediaCommand.PLAY

    /** Перемотка назад или резкий скачок вперёд, в отличие от обычного хода времени. */
    private fun jumped(
        beforeMs: Long?,
        afterMs: Long?,
    ): Boolean {
        val from = beforeMs
        val to = afterMs
        return from != null && to != null && (to - from < SEEK_BACKWARD_MS || to - from > SEEK_FORWARD_MS)
    }

    /**
     * Честная причина отказа. Media button broadcast из стороннего приложения платформа
     * не маршрутизирует: проверено на устройстве — broadcast с KEYCODE_MEDIA_PLAY доходит
     * до receiver Яндекс Музыки, но сессия не стартует, тогда как системный
     * `input keyevent 85` переключает её. Значит управлять можно только через сессию,
     * которую приложение отдаёт само.
     */
    private fun unreadableReason(app: MediaAppSnapshot): String =
        when {
            app.sessionServices.isEmpty() && app.hiddenSessionServices > 0 ->
                "${app.packageName} keeps its media session private; without MEDIA_CONTENT_CONTROL it can " +
                    "only be driven through the system media keys, not from this app"
            app.sessionServices.isNotEmpty() ->
                "media session of ${app.packageName} published ${app.sessionServices.first()} " +
                    "but did not answer; the player may be stopped"
            else ->
                "no exported media session for ${app.packageName}; its media button receiver is reached by " +
                    "the system only, so this app cannot drive it"
        }

    private fun controlWithSession(
        app: MediaAppSnapshot,
        session: MediaSessionTransport,
        command: MediaCommand,
    ): MediaControlResult {
        val before = session.awaitPlayback(STATE_TIMEOUT_MS)
        val effective =
            when (command) {
                MediaCommand.PLAY_PAUSE -> if (before.isPlaying) MediaCommand.PAUSE else MediaCommand.PLAY
                else -> command
            }
        session.send(effective)
        // Остановленный плеер на play должен сначала проснуться: поднять процесс, построить
        // сессию, загрузить библиотеку и только потом начать звук. Короткого окна на это не
        // хватает, и команда выглядит как «не сработала», хотя music уже играет. Поэтому
        // холодный старт ждём дольше, а не отдаём verified=false по нетерпению.
        val effectTimeoutMs =
            if (before.isIdle && effective == MediaCommand.PLAY) COLD_START_TIMEOUT_MS else AFTER_STATE_TIMEOUT_MS
        val after = awaitEffect(session, before, effective, effectTimeoutMs)
        val verified = after.state != before.state || after.title != before.title
        Log.i(
            TAG,
            "media command=${effective.wireName} package=${app.packageName} " +
                "state=${before.state}->${after.state} verified=$verified",
        )
        return MediaControlResult(
            packageName = app.packageName,
            label = app.label,
            transport = TRANSPORT_SESSION,
            command = effective.wireName,
            message =
                if (verified) {
                    "Android media session accepted the command"
                } else {
                    "Android media session took the command but reported no state change within " +
                        "${effectTimeoutMs}ms; the player may have ignored it"
                },
            before = before,
            after = after,
            verified = verified,
        )
    }

    /**
     * Явный package: подключаемся к его сессии, без неё управление невозможно.
     * Usable здесь не требуется: «включи Яндекс Музыку» должно работать и когда
     * ничего не играет — сессия остановленного плеера тоже валидная цель.
     */
    private fun targetFor(
        apps: List<MediaAppSnapshot>,
        packageName: String?,
    ): MediaTarget {
        val explicit = packageName?.let { required -> requiredApp(apps, required) }
        return if (explicit != null) {
            MediaTarget(explicit, sessionFor(explicit, requireUsable = false))
        } else {
            liveSessionTarget(apps)
        }
    }

    /**
     * Без явного package работаем только с той сессией, что отвечает прямо сейчас.
     * Кэш переиспользуем лишь пока сессия живая: иначе протухший target уводил бы
     * команду в приложение, которое уже ничего не играет.
     */
    private fun liveSessionTarget(apps: List<MediaAppSnapshot>): MediaTarget {
        val cached = cachedPackage()?.let { name -> apps.firstOrNull { it.packageName == name } }
        if (cached != null) {
            val session = sessionFor(cached, requireUsable = true)
            if (session != null) {
                val playback = session.awaitPlayback(STATE_TIMEOUT_MS)
                if (playback.isUsable && !playback.isDegenerate) {
                    return MediaTarget(cached, session)
                }
                releaseUnused(session)
            }
        }
        val probed = probeForLiveSession(apps)
        if (probed != null) {
            rememberResolved(probed.app.packageName)
            return probed
        }
        error("no live media session found; pass package, candidates: ${candidateNames(apps)}")
    }

    private fun candidateNames(apps: List<MediaAppSnapshot>): String {
        val names = apps.take(CANDIDATE_LIMIT).map { it.packageName }
        return names.joinToString()
    }

    private fun requiredApp(
        apps: List<MediaAppSnapshot>,
        packageName: String,
    ): MediaAppSnapshot =
        apps.firstOrNull { it.packageName == packageName }
            ?: throw IllegalArgumentException(
                "no media session service or media button receiver for $packageName",
            )

    /**
     * Опрашивает кандидатов параллельно: последовательный обход с таймаутом на каждого
     * упирается в сокетный таймаут моста и всегда смотрит только на первые N пакетов
     * по алфавиту. Здесь важно увидеть настоящий плеер, а не bluetooth-сессию.
     */
    private fun probeForLiveSession(apps: List<MediaAppSnapshot>): MediaTarget? {
        val candidates = apps.filter { it.sessionServices.isNotEmpty() }.take(PROBE_LIMIT)
        if (candidates.isEmpty()) return null
        return probeAll(candidates)
    }

    private fun probeAll(candidates: List<MediaAppSnapshot>): MediaTarget? {
        val pool = Executors.newFixedThreadPool(minOf(candidates.size, PROBE_PARALLELISM))
        val probes =
            try {
                pool.invokeAll(
                    candidates.map { app -> Callable { probeOne(app) } },
                    PROBE_TOTAL_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS,
                )
            } finally {
                pool.shutdownNow()
            }
        val alive = probes.filter { it.isDone && !it.isCancelled }
        // null = сессия не ответила. Такой пакет не цель: иначе останавливаемся на первом
        // приложении с сервисом, а не на том, что реально играет.
        val winner =
            alive
                .mapNotNull { runCatching { it.get() }.getOrNull() }
                .filter { it.playback?.isUsable == true && !it.playback.isDegenerate }
                .maxByOrNull { it.score }
        alive
            .mapNotNull { runCatching { it.get() }.getOrNull() }
            .filter { winner == null || it.session !== winner.session }
            .forEach { probe -> releaseUnused(probe.session) }
        if (winner == null) {
            Log.i(TAG, "auto session probe found no live player")
            return null
        }
        Log.i(TAG, "auto session target=${winner.app.packageName} score=${winner.score}")
        return MediaTarget(winner.app, winner.session)
    }

    private data class Probe(
        val app: MediaAppSnapshot,
        val session: MediaSessionTransport?,
        val playback: MediaPlaybackSnapshot?,
        val score: Int,
    )

    private fun probeOne(app: MediaAppSnapshot): Probe {
        val session = openSession(app, PROBE_CONNECT_TIMEOUT_MS, requireUsable = true)
        val playback = session?.awaitPlayback(STATE_TIMEOUT_MS)
        return Probe(app, session, playback, playback?.let(::scoreOf) ?: SCORE_USABLE)
    }

    /**
     * Bluetooth и прочие системные сессии отвечают и держат state=error без метаданных.
     * Для автоопределения это шум: настоящий плеер выдаёт playing либо хотя бы метаданные.
     */
    private fun scoreOf(playback: MediaPlaybackSnapshot): Int =
        when {
            playback.isPlaying -> SCORE_PLAYING
            playback.title != null -> SCORE_WITH_METADATA
            else -> SCORE_USABLE
        }

    private fun sessionFor(
        app: MediaAppSnapshot,
        requireUsable: Boolean,
    ): MediaSessionTransport? {
        val retained = freshRetainedSession()
        return if (retained != null) {
            retained
        } else {
            openSession(app, CONNECT_TIMEOUT_MS, requireUsable)?.also(::retainSession)
        }
    }

    /**
     * Отдаёт живую сессию из кэша, а протухшую закрывает: иначе MediaController продолжал бы
     * держатьbind чужого сервиса после простоя. Закрытие вне synchronize, потому что оно
     * ждёт ответа с media-looper и держать под ним замок нельзя.
     */
    private fun freshRetainedSession(): MediaSessionTransport? {
        var fresh: MediaSessionTransport? = null
        var stale: MediaSessionTransport? = null
        synchronized(retainLock) {
            val handle = retainedSession
            if (handle != null) {
                fresh = handle.takeIf { SystemClock.elapsedRealtime() - retainedAt <= RETAIN_MS }
                stale = handle.takeIf { fresh !== it }
                if (stale != null) retainedSession = null
            }
        }
        stale?.closeQuietly()
        return fresh
    }

    private fun retainSession(session: MediaSessionTransport) {
        synchronized(retainLock) {
            retainedSession?.closeQuietly()
            retainedSession = session
            retainedAt = SystemClock.elapsedRealtime()
        }
    }

    /** Закрывает сессию, если она больше не нужна как кэш для следующего вызова. */
    private fun releaseUnused(session: MediaSessionTransport?) {
        if (session == null) return
        val keep =
            synchronized(retainLock) {
                val handle = retainedSession
                handle === session && SystemClock.elapsedRealtime() - retainedAt <= RETAIN_MS
            }
        if (!keep) session.closeQuietly()
    }

    /** Компонент сессии вместе с action-ом, по которому он найден. */
    private data class SessionRef(
        val flattened: String,
        val component: ComponentName,
        val action: String?,
    ) {
        val isMedia3: Boolean
            get() = action in MEDIA3_SERVICE_ACTIONS
    }

    /**
     * Подключается к сессии приложения, перебирая сервисы в порядке актуальности протокола.
     *
     * Сначала идут media3-сервисы: это родной протокол современных плееров, и именно на них
     * держится Яндекс Музыка. Платформенный MediaBrowserService — запасной путь для старых
     * плееров. Попытка не выбирается наугад: action известен из запроса PackageManager,
     * поэтому приложение без media3-сервисов не тратит таймаут на заведомо бесполезный bind.
     *
     * requireUsable отличает две задачи: автоопределение живьевой сессии должно отбросить
     * молчащий сервис, а явный package наоборот принимает и остановленного плеера — иначе
     * «включи Яндекс Музыку» не сработало бы ровно тогда, когда оно нужнее всего.
     */
    private fun openSession(
        app: MediaAppSnapshot,
        connectTimeoutMs: Long,
        requireUsable: Boolean,
    ): MediaSessionTransport? {
        val refs = sessionRefs(app)
        val ordered = MEDIA3_FIRST_PASSES.flatMap { pass -> refs.filter { it.isMedia3 == pass } }
        return ordered
            .asSequence()
            .mapNotNull { ref -> connectTransport(ref, connectTimeoutMs) }
            .firstNotNullOfOrNull { transport -> acceptTransport(transport, requireUsable) }
    }

    private fun sessionRefs(app: MediaAppSnapshot): List<SessionRef> =
        app.sessionServices
            .mapNotNull { flattened ->
                val component = ComponentName.unflattenFromString(flattened) ?: return@mapNotNull null
                SessionRef(flattened, component, serviceActions[flattened])
            }

    /**
     * Транспорт годится, если для автоопределения он живой. Негодный закрываем: удерживать
     * bind к молчащему сервису незачем, иначе следующий вызов поедет уже в него.
     */
    private fun acceptTransport(
        transport: MediaSessionTransport,
        requireUsable: Boolean,
    ): MediaSessionTransport? {
        val playback = transport.awaitPlayback(STATE_TIMEOUT_MS)
        if (!requireUsable || playback.isUsable) return transport
        transport.closeQuietly()
        return null
    }

    private fun connectTransport(
        ref: SessionRef,
        timeoutMs: Long,
    ): MediaSessionTransport? {
        if (!ref.isMedia3) return connectPlatform(ref.component, timeoutMs)
        return runCatching { Media3SessionTransport.connect(contextOrThrow(), looper, ref.component, timeoutMs) }
            .onFailure { error -> Log.w(TAG, "cannot bind media3 session ${ref.flattened}", error) }
            .getOrNull()
    }

    private fun connectPlatform(
        component: ComponentName,
        timeoutMs: Long,
    ): MediaSessionTransport? {
        val browser =
            runCatching { connectBrowser(component, timeoutMs) }
                .onFailure { error -> Log.w(TAG, "cannot bind $component", error) }
                .getOrNull()
                ?: return null
        return runCatching { openHandle(browser, browser.sessionToken) }.getOrElse { error ->
            Log.w(TAG, "cannot open session for $component", error)
            browser.disconnect()
            null
        }
    }

    /**
     * Публичный SDK-конструктор MediaBrowser не принимает Handler, поэтому объект обязан быть
     * создан на потоке с Looper, иначе коллбэки соединения некуда доставить.
     */
    private fun connectBrowser(
        component: ComponentName,
        timeoutMs: Long,
    ): MediaBrowser? {
        val connected = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val browser =
            onMediaLooper(looper, COMMAND_TIMEOUT_MS) {
                MediaBrowser(
                    contextOrThrow(),
                    component,
                    object : MediaBrowser.ConnectionCallback() {
                        override fun onConnected() {
                            connected.set(true)
                            latch.countDown()
                        }

                        override fun onConnectionFailed() {
                            latch.countDown()
                        }

                        override fun onConnectionSuspended() {
                            latch.countDown()
                        }
                    },
                    null,
                ).also { created -> created.connect() }
            }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        // getSessionToken() бросает IllegalStateException до connect, поэтому читать его можно
        // только после успешного коллбэка, а не «на всякий случай».
        val usable = connected.get() && runCatching { browser.sessionToken }.isSuccess
        if (!usable) runCatching { browser.disconnect() }
        return browser.takeIf { usable }
    }

    private fun openHandle(
        browser: MediaBrowser,
        token: MediaSession.Token?,
    ): MediaSessionHandle =
        onMediaLooper(looper, COMMAND_TIMEOUT_MS) {
            requireNotNull(token) { "media session token is missing" }
            val controller = MediaController(contextOrThrow(), token)
            val ready = CountDownLatch(1)
            val callback =
                object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        ready.countDown()
                    }

                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        ready.countDown()
                    }
                }
            controller.registerCallback(callback, handler)
            MediaSessionHandle(browser, controller, callback, ready, handler, looper)
        }

    private data class ServiceIndex(
        val connectable: Map<String, List<String>>,
        val blocked: Map<String, Int>,
        val actions: Map<String, String>,
    )

    private fun mediaApps(): List<MediaAppSnapshot> {
        val services = collectSessionServices()
        serviceActions = services.actions
        val buttons = collectMediaButtons()
        return (services.connectable.keys + buttons.keys)
            .sorted()
            .map { packageName ->
                MediaAppSnapshot(
                    packageName = packageName,
                    label = labelOf(packageName),
                    sessionServices = services.connectable[packageName].orEmpty(),
                    mediaButtonReceiver = buttons[packageName],
                    hiddenSessionServices = services.blocked[packageName] ?: 0,
                )
            }
    }

    /**
     * Собирает session-сервисы по трём action-ам. Не подключиться можно к выключенному
     * или неэкспортированному компоненту, поэтому такие считаем отдельно: по ним видно,
     * что приложение прячет сессию, а не что её нет.
     */
    private fun collectSessionServices(): ServiceIndex {
        val connectable = mutableMapOf<String, MutableList<String>>()
        val blocked = mutableMapOf<String, MutableSet<String>>()
        val actions = mutableMapOf<String, String>()
        SESSION_SERVICE_ACTIONS.forEach { action ->
            queryServices(action).forEach { resolveInfo ->
                val service = resolveInfo.serviceInfo ?: return@forEach
                val flattened = ComponentName(service.packageName, service.name).flattenToString()
                // Сервис отвечает на несколько media-действий сразу: Media3LibraryService висит
                // ещё и на legacy-экшене. Классифицировать надо по самому «родному» протоколу,
                // иначе media3-сервис уедет в платформенный bind и не ответит.
                if (actions[flattened] !in MEDIA3_SERVICE_ACTIONS) actions[flattened] = action
                Log.d(
                    TAG,
                    "media service ${service.packageName}/${service.name} action=$action " +
                        "exported=${service.exported} enabled=${service.enabled}",
                )
                // Гейт — только экспортированность: bind к чужому компоненту иначе запрещён.
                // Включённость из ServiceInfo брать нельзя, PackageManager отдаёт манифестное
                // значение, а Яндекс Музыка переключает Media3LibraryService в рантайме и
                // манифест там android:enabled="false". Решает bind, и его промах мы логируем.
                if (service.exported) {
                    connectable.getOrPut(service.packageName) { mutableListOf() }.addIfAbsent(flattened)
                } else {
                    blocked.getOrPut(service.packageName) { mutableSetOf() }.add(flattened)
                }
            }
        }
        // Показываем и пробуем родной протокол первым: для Яндекс Музыки это Media3LibraryService.
        val media3Actions = actions.filterValues { action -> action in MEDIA3_SERVICE_ACTIONS }.keys
        val ordered = connectable.mapValues { (_, services) ->
            services.sortedByDescending { flattened -> flattened in media3Actions }
        }
        return ServiceIndex(ordered, blocked.mapValues { it.value.size }, actions)
    }

    private fun collectMediaButtons(): Map<String, String> {
        val buttons = mutableMapOf<String, String>()
        queryReceivers(Intent.ACTION_MEDIA_BUTTON).forEach { resolveInfo ->
            val receiver = resolveInfo.activityInfo ?: return@forEach
            if (receiver.enabled && receiver.exported) {
                buttons.putIfAbsent(
                    receiver.packageName,
                    ComponentName(receiver.packageName, receiver.name).flattenToString(),
                )
            }
        }
        return buttons
    }

    private fun <T> MutableList<T>.addIfAbsent(value: T) {
        if (!contains(value)) add(value)
    }

    private fun labelOf(packageName: String): String =
        runCatching {
            val packageManager = contextOrThrow().packageManager
            val application = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(application).toString().trim()
        }.getOrNull()
            ?.takeIf(String::isNotEmpty)
            ?: packageName

    /**
     * Компоненты чужих плееров переключают в рантайме: Яндекс Музыка держит Media3LibraryService
     * выключенным в манифесте и включает его экспериментом, а legacy-сервис наоборот гасит.
     * Без MATCH_DISABLED_COMPONENTS PackageManager отдаёт только манифестную картину, и мы бы
     * заключили «сессии нет», хотя она есть. Поэтому спрашиваем и выключенные компоненты, а
     * актуальное состояние берём из ServiceInfo.
     */
    @Suppress("DEPRECATION")
    private fun queryServices(action: String): List<ResolveInfo> {
        val packageManager = contextOrThrow().packageManager
        val intent = Intent(action)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()),
            )
        } else {
            packageManager.queryIntentServices(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
        }
    }

    @Suppress("DEPRECATION")
    private fun queryReceivers(action: String): List<ResolveInfo> {
        val packageManager = contextOrThrow().packageManager
        val intent = Intent(action)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryBroadcastReceivers(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            packageManager.queryBroadcastReceivers(intent, 0)
        }
    }

    private fun cachedPackage(): String? {
        val name = resolvedPackage ?: return null
        return if (SystemClock.elapsedRealtime() - resolvedAt <= RESOLVE_CACHE_MS) name else null
    }

    private fun rememberResolved(packageName: String) {
        resolvedPackage = packageName
        resolvedAt = SystemClock.elapsedRealtime()
    }

    private fun contextOrThrow(): Context =
        if (::context.isInitialized) {
            context
        } else {
            error("MediaControlController is not initialized")
        }
}

/**
 * Живое подключение к чужой медиасессии.
 *
 * Контракт минимален, потому что протоколов два: платформенный MediaBrowser и media3.
 * Общее у них только чтение состояния и транспортная команда; проверка эффекта команды
 * живёт в вызывающем коде, чтобы не расходилась между реализациями.
 */
internal interface MediaSessionTransport {
    /** Дождаться первого ответа сессии: метаданных либо состояния воспроизведения. */
    fun awaitReady(timeoutMs: Long)

    fun read(): MediaPlaybackSnapshot

    fun send(command: MediaCommand)

    /**
     * Включить конкретный трек каталога вместо транспортной кнопки. uri — deep link, который
     * сессия умеет резолвить сама (`yandexmusic://track/<id>`); mediaId идёт идентификатором
     * элемента, чтобы плеер показал нормальные метаданные, а не пустую очередь.
     */
    fun playItem(
        mediaId: String,
        uri: String,
        title: String?,
    )

    /**
     * Возможности сессии: player-команды, custom session-команды, умение принимать трек по id.
     * null, если протокол их не сообщает (устаревшая MediaBrowser-сессия).
     */
    fun capabilities(): MediaCapabilities?

    /** Есть ли у сессии право принимать новый элемент: у Яндекса это зависит от состояния. */
    fun supportsSetMediaItem(): Boolean

    /**
     * Кастомная команда сессии (лайк, скип подкаста и прочее). null, когда протокол их не
     * поддерживает: устаревшая сессия таких команд не знает в принципе.
     */
    fun sendCustom(action: String): MediaCustomCommandResult?

    fun closeQuietly()
}

private data class MediaTarget(
    val app: MediaAppSnapshot,
    val session: MediaSessionTransport?,
)

/** Ожидание готовности и чтение состояния — так начинается любая работа с транспортом. */
private fun MediaSessionTransport.awaitPlayback(timeoutMs: Long): MediaPlaybackSnapshot {
    awaitReady(timeoutMs)
    return read()
}

/**
 * Живое подключение к чужой MediaSession. Создаётся только на media-looper:
 * MediaController требует Looper, а ожидание callback-ов идёт с другого потока,
 * поэтому этот looper никогда не блокируется.
 */
private class MediaSessionHandle(
    private val browser: MediaBrowser,
    private val controller: MediaController,
    private val callback: MediaController.Callback,
    private val ready: CountDownLatch,
    private val handler: Handler,
    private val looper: HandlerThread,
) : MediaSessionTransport {
    override fun awaitReady(timeoutMs: Long) {
        ready.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun send(command: MediaCommand) {
        onMediaLooper(looper, COMMAND_TIMEOUT_MS) {
            val controls = controller.transportControls
            when (command) {
                MediaCommand.PLAY -> controls.play()
                MediaCommand.PAUSE -> controls.pause()
                MediaCommand.PLAY_PAUSE -> Unit
                MediaCommand.NEXT -> controls.skipToNext()
                MediaCommand.PREVIOUS -> controls.skipToPrevious()
                MediaCommand.STOP -> controls.stop()
            }
        }
    }

    override fun playItem(
        mediaId: String,
        uri: String,
        title: String?,
    ) {
        onMediaLooper(looper, COMMAND_TIMEOUT_MS) {
            // Устаревшая сессия умеет только playFromUri: id трека в неё не передать.
            controller.transportControls.playFromUri(Uri.parse(uri), null)
        }
    }

    override fun capabilities(): MediaCapabilities? = null

    override fun supportsSetMediaItem(): Boolean = false

    override fun sendCustom(action: String): MediaCustomCommandResult? = null

    override fun closeQuietly() {
        runCatching {
            // MediaController.release() скрыт в публичном SDK: сессия отпускается через disconnect.
            onMediaLooper(looper, COMMAND_TIMEOUT_MS) {
                controller.unregisterCallback(callback)
            }
            browser.disconnect()
        }.onFailure { Log.w(TAG, "closing session failed", it) }
    }

    override fun read(): MediaPlaybackSnapshot = readPlayback(controller)
}

private fun readPlayback(controller: MediaController): MediaPlaybackSnapshot {
    val state = controller.playbackState
    val metadata = controller.metadata
    return MediaPlaybackSnapshot(
        state = state.toWireState(),
        isPlaying = state.toIsPlaying(),
        title = metadata.cleanText(MediaMetadata.METADATA_KEY_TITLE),
        artist = metadata.cleanText(MediaMetadata.METADATA_KEY_ARTIST),
        album = metadata.cleanText(MediaMetadata.METADATA_KEY_ALBUM),
        durationMs = metadata.durationMs(),
        positionMs = state?.position,
    )
}

private fun PlaybackState?.toWireState(): String =
    when (this?.state) {
        null, PlaybackState.STATE_NONE -> MediaPlaybackSnapshot.STATE_NONE
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "buffering"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_ERROR -> "error"
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        -> "skipping"
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        -> "seeking"
        else -> "other"
    }

/** PlaybackState.isPlaying() скрыт в публичном SDK, поэтому состояние выводим из state и speed. */
private fun PlaybackState?.toIsPlaying(): Boolean {
    val current = this
    return current != null &&
        (current.state == PlaybackState.STATE_PLAYING || (current.isActive && current.playbackSpeed > 0f))
}

private fun MediaMetadata?.cleanText(key: String): String? = this?.getString(key)?.trim()?.takeIf(String::isNotEmpty)

/** PlaybackState.getDuration() скрыт в публичном SDK, поэтому длительность берём из метаданных. */
private fun MediaMetadata?.durationMs(): Long? {
    val value = runCatching { this?.getLong(MediaMetadata.METADATA_KEY_DURATION) }.getOrNull()
    return value?.takeIf { it > 0L }
}

/**
 * Выполняет короткую задачу на media-looper и возвращает её результат вызывающему потоку.
 * Сама задача ничего не ждёт: блокировка looper-а затормозила бы и MediaBrowser-коллбэки.
 */
internal fun <T> onMediaLooper(
    looper: HandlerThread,
    timeoutMs: Long,
    block: () -> T,
): T {
    val result = AtomicReference<Result<T>>()
    val latch = CountDownLatch(1)
    val posted =
        Handler(looper.looper).post {
            runCatching(block)
                .onSuccess { value -> result.set(Result.success(value)) }
                .onFailure { error -> result.set(Result.failure(error)) }
            latch.countDown()
        }
    check(posted) { "media looper rejected the task" }
    check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) { "media control timed out" }
    return result.get().getOrThrow()
}
