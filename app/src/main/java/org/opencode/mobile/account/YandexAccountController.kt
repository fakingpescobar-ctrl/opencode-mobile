package org.opencode.mobile.account

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.opencode.mobile.media.YandexCatalog
import java.io.IOException

/**
 * Сценарий «подключить Яндекс Музыку» целиком: заход, приём кода, статус, отключение.
 *
 * Поток специально разорван на две части, и это не декоративно. Часть, которая открывает
 * браузер, и часть, которая получает код, — это разные процессы и разные кадры: между
 * ними юзер уходит из приложения в Chrome, соглашается и возвращается по схеме. Поэтому
 * состояние захода лежит в хранилище, а не в полях объекта, а [handleCallback] обязан быть
 * идемпотентным: повторный вызов с тем же кодом ничего не должен ломать.
 *
 * Ни один метод не бросает наружу «сырое» исключение: мост отдаёт их в JSON агенту, и там
 * текст ошибки важнее класса исключения. Всё, что может упасть, превращается в [Outcome].
 */
// Фасад над аккаунтом: подключение, статус, выход и всё чтение библиотеки. Методов много
// намеренно - каждый отвечает за один вызов агента, и разносить их по классам ради числа
// значит прятать от моста то, как аккаунт устроен.
@Suppress("TooManyFunctions")
object YandexAccountController {
    /** Код Яндекса живёт 10 минут; столько же ждём его в хранилище. */
    const val DEFAULT_LIMIT = 20
    const val MAX_LIMIT = 50

    /** Сколько треков от начала плейлиста сверяем при запуске. */
    const val PLAYLIST_HEAD = 5

    private const val CODE_LIFETIME_MILLIS = 600_000L
    private const val NO_TOKEN_EXPIRY_AT = 0L
    private const val NOT_CONNECTED = "yandex account is not connected: run the connect tool first"

    /**
     * Надбавка к интервалу после `slow_down`.
     *
     * RFC 8628 велит увеличивать паузу после каждой «реже»; мы добавляем один фиксированный
     * шаг к интервалу из кода (5→10 с). Держать бегущий счётчик означало бы хранить ещё одно
     * число между опросами, а шага уже достаточно, чтобы не упереться в лимит: сервер просил
     * «реже» при нашем темпе, и удвоение с запасом эту жалобу закрывает.
     */
    private const val SLOW_DOWN_EXTRA_SECONDS = 5

    /** Итог приёма кода: подключились или нет, и если нет — почему. */
    sealed interface Outcome {
        data class Connected(
            val identity: YandexIdentity,
        ) : Outcome

        data class Rejected(
            val reason: String,
        ) : Outcome

        /**
         * Пользователь ещё не подтвердил вход.
         *
         * Отдельный случай, а не [Rejected], потому что это нормальная середина device-flow,
         * а не отказ: пришлось бы отличить «подтвердите код» от «код не подошёл» одним
         * boolean'ом, и агент на «ещё не подтвердил» начал бы заново выдавать код юзеру.
         *
         * [retryAfterSeconds] — через сколько опрашивать снова: обычный интервал из кода,
         * или, если Яндекс ответил `slow_down`, интервал с надбавкой, чтобы не стучать
         * в `/token` в темпе, который сервер уже счёл слишком частым.
         */
        data class Waiting(
            val retryAfterSeconds: Int,
        ) : Outcome
    }

    /**
     * Что показать юзеру, чтобы он подтвердил вход.
     *
     * [userCode] обязателен, а не украшение: на `ya.ru/device` код вводится руками, и
     * без него юзеру пришлось бы угадывать. [intervalSeconds] — через сколько секунд
     * опрашивать, чтобы агент не стучал в `/token` чаще, чем Яндекс готов отвечать.
     */
    data class DevicePrompt(
        val userCode: String,
        val verificationUrl: String,
        val intervalSeconds: Int,
        val expiresAtMillis: Long,
    )

    /** Что приложение знает о подключении на данный момент. */
    data class Status(
        val connected: Boolean,
        val identity: YandexIdentity?,
        val expiresAtMillis: Long,
        val canRefresh: Boolean,
        val awaitingCode: Boolean,
        val devicePrompt: DevicePrompt? = null,
    )

    private lateinit var context: Context

    @Synchronized
    fun initialize(context: Context) {
        if (::context.isInitialized) return
        this.context = context.applicationContext
    }

    /**
     * Начинает вход через device-flow: просит код, сохраняет его и открывает страницу.
     *
     * Именно device-flow, а не PKCE, потому что токен PKCE Яндекс.Музыка для Ynison не
     * принимает: с ним не проходит handshake, а значит управлять музыкой нечем. Проверено
     * вживую, а не взято из доки, где про scopes ничего внятного.
     *
     * Существующий токен не трогаем: если юзер просто решил переподключиться и ошибся,
     * работающая авторизация должна пережить неудачную попытку.
     */
    fun startConnect(): Status {
        val store = deviceStore()
        val code = YandexDeviceClient.requestDeviceCode(store.deviceId())
        store.savePending(
            YandexDeviceStore.PendingDeviceAuth(
                deviceCode = code.deviceCode,
                userCode = code.userCode,
                verificationUrl = code.verificationUrl,
                intervalSeconds = code.intervalSeconds,
                expiresAtMillis = code.expiresAtMillis(System.currentTimeMillis()),
            ),
        )
        // Ссылка из ответа, а не собранная нами: Яндекс отдаёт `https://ya.ru/device`,
        // и жёстко зашитая версия однажды уехала бы в 404 вместе со всем входом.
        openBrowser(code.verificationUrl)
        return status()
    }

    /**
     * Один опрос device-flow: подтвердил юзер вход или ещё нет.
     *
     * Опросов ровно столько, сколько агент позвал, и ждать внутри нельзя: срок жизни кода
     * пять минут, и блокирующий метод съел бы всё окно на сон, пока юзер вводит код. Зато
     * [Outcome.Waiting] честно отдаёт агенту паузу вместе с интервалом из [Status].
     */
    fun pollConnect(): Outcome {
        val store = deviceStore()
        val pending =
            store.pending()
                ?: return Outcome.Rejected("no device authorization in progress: run the connect tool first")
        return pollGrantedOrReport(store, pending)
    }

    /**
     * Опрос по сохранённому коду.
     *
     * Отдельно от [pollConnect], чтобы там осталось два возврата: «нет кода» и «спросили»,
     * а весь разбор ответа — здесь, где он и живёт.
     */
    private fun pollGrantedOrReport(
        store: YandexDeviceStore,
        pending: YandexDeviceStore.PendingDeviceAuth,
    ): Outcome {
        if (pending.expiredAt(System.currentTimeMillis())) {
            clearPending(store)
            return Outcome.Rejected("device code expired, run the connect tool again")
        }
        val poll = YandexDeviceClient.pollDeviceToken(pending.deviceCode)
        if (poll is YandexDeviceAuth.Poll.Rejected) clearPending(store)
        return when (poll) {
            is YandexDeviceAuth.Poll.Pending -> Outcome.Waiting(pending.intervalSeconds)
            is YandexDeviceAuth.Poll.SlowDown ->
                Outcome.Waiting(pending.intervalSeconds + SLOW_DOWN_EXTRA_SECONDS)
            is YandexDeviceAuth.Poll.Rejected -> Outcome.Rejected(poll.reason)
            is YandexDeviceAuth.Poll.Granted ->
                connectWith(store(), poll.token.toToken(System.currentTimeMillis(), YandexGrant.DEVICE))
        }
    }

    /**
     * Заход закончен — стираем коды обоих входов разом.
     *
     * Одним вызовом не выйдет: коды лежат в двух хранилищах, и начать новый вход можно, не
     * завершив старый. Тогда брошенный PKCE-код пережил бы device-flow и вернулся при
     * следующей попытке, а висящий device-код — наоборот.
     */
    private fun clearPending(deviceStore: YandexDeviceStore) {
        deviceStore.clearPending()
        store().clearPending()
    }

    /**
     * Разбирает то, что пришло на [YandexOAuth.REDIRECT_URI].
     *
     * Порядок проверок именно такой: сначала «это вообще наш код» (схема), потом
     * «это ответ на наш заход» (`state`), и только потом сам код. Иначе посторонний intent
     * с чужим `state` успевает бы стереть наш verifier, и следующая попытка уже не соберётся.
     *
     * Каскад ранних возвратов здесь и есть суть функции: каждый отказ должен немедленно
     * остановить разбор и сказать, что именно не так. Свёртка этого в один результат
     * «проверил-и-вернул» спрятала бы порядок проверок, а он здесь и есть контракт.
     */
    @Suppress("ReturnCount")
    fun handleCallback(uri: String): Outcome {
        if (Uri.parse(uri).scheme != YandexOAuth.SCHEME) {
            return Outcome.Rejected("unexpected redirect scheme")
        }
        val store = store()
        val pending = store.pending()
            ?: return Outcome.Rejected("no authorization in progress: run the connect tool first")
        val callback = YandexOAuth.parseCallback(uri)
        if (callback.failed) {
            // Отказ согласия — это не поломка, но verifier всё равно сгорел: код, который
            // пользователь не утвердил, повторно не предъявить.
            //
            // Именно clearPending, а не clear: неудачная попытка переподключиться не должна
            // вышибать аккаунт, которым приложение прямо сейчас работает. Сбросить всё -
            // значит превратить «юзер отказал новому логину» в «у юзера теперь вообще
            // нет доступа», и лечить это придётся новым входом.
            store.clearPending()
            val detail = callback.errorDescription?.takeIf(String::isNotBlank) ?: callback.error.orEmpty()
            return Outcome.Rejected(detail.ifEmpty { "authorization was denied" })
        }
        if (callback.state != pending.state) {
            // Чужой редирект ничего не значит и не должен трогать наш pending: иначе любой
            // случайно открытый сторонний url способен сжечь наш код.
            return Outcome.Rejected("state mismatch: this redirect does not belong to our request")
        }
        val age = System.currentTimeMillis() - pending.startedAtMillis
        if (age > CODE_LIFETIME_MILLIS) {
            store.clearPending()
            return Outcome.Rejected("authorization expired, run the connect tool again")
        }
        val code = callback.code?.takeIf(String::isNotBlank)
            ?: return Outcome.Rejected("redirect has no code")
        return try {
            val (_, token) = YandexAccountClient.exchangeCode(code, pending.verifier)
            connectWith(store, token)
        } catch (error: IOException) {
            Outcome.Rejected(error.message ?: "token exchange failed")
        }
    }

    fun status(): Status {
        val store = store()
        val token = store.token()
        return Status(
            connected = token != null,
            identity = store.identity(),
            expiresAtMillis = token?.expiresAtMillis ?: NO_TOKEN_EXPIRY_AT,
            canRefresh = token?.canRefresh == true,
            awaitingCode = store.pending() != null || deviceStore().pending() != null,
            devicePrompt = deviceStore().pending()?.toPrompt(),
        )
    }

    private fun YandexDeviceStore.PendingDeviceAuth.toPrompt(): DevicePrompt =
        DevicePrompt(
            userCode = userCode,
            verificationUrl = verificationUrl,
            intervalSeconds = intervalSeconds,
            expiresAtMillis = expiresAtMillis,
        )

    /**
     * Общий хвост входа: проверить, кто вошёл, и записать всё разом.
     *
     * Вынесен, потому что оба входа — PKCE по коду и device-flow по опросу — заканчиваются
     * одинаково, и различались только получением токена. Пишем всё только после успешной
     * смены: полуготовый аккаунт хуже, чем аккаунта нет.
     */
    private fun connectWith(
        store: YandexTokenStore,
        token: YandexToken,
    ): Outcome =
        try {
            val identity = YandexAccountClient.identity(token.accessToken)
            store.saveToken(token)
            store.saveIdentity(identity)
            clearPending(deviceStore())
            Outcome.Connected(identity)
        } catch (error: IOException) {
            Outcome.Rejected(error.message ?: "token exchange failed")
        }

    fun disconnect() {
        store().clear()
    }

    /**
     * Страница «Моего плейлиста» с уже разобранными треками.
     *
     * Сервер отдаёт всю библиотеку разом и не умеет постранично, поэтому по сети идёт
     * один большой ответ без метаданных, а в треки превращается только запрошенный кусок.
     * Сортировка Яндекса не меняется от запроса к запросу, так что кэш тут не нужен:
     * он всё равно не спасёт от самого запроса списка id.
     */
    fun readLikes(
        offset: Int = 0,
        limit: Int = DEFAULT_LIMIT,
    ): LikedPage {
        val session = session()
        val library = YandexAccountClient.likedTrackIds(session.token.accessToken, session.identity.login)
        val from = offset.coerceAtLeast(0).coerceAtMost(library.size)
        val size = limit.coerceIn(1, MAX_LIMIT)
        val to = (from + size).coerceAtMost(library.size)
        val ids = library.trackIds.subList(from, to)
        return LikedPage(
            login = session.identity.login,
            uid = library.uid,
            revision = library.revision,
            offset = from,
            total = library.size,
            trackIds = ids,
            tracks = YandexCatalog.resolveTracks(ids),
        )
    }

    /**
     * Список плейлистов аккаунта.
     *
     * Отдельного кэша и `revision` здесь нет намеренно: в ответе есть `kind` у каждой строки,
     * и он же служит адресом для [readPlaylist], так что агенту достаточно одного захода,
     * чтобы показать список и тут же прочитать выбранный. Лайки отделены от этого вызова,
     * потому что у них kind = 0, а не свой плейлист.
     */
    fun readPlaylists(): List<PlaylistSummary> {
        val session = session()
        return YandexAccountClient.playlists(session.token.accessToken, session.identity.uid)
    }

    /**
     * Содержимое одного плейлиста, постранично на стороне приложения.
     *
     * Страница выглядит как у лайков не случайно: тот же список id приезжает одним ответом,
     * и метаданные дотягиваются только на запрошенный кусок. Разница в одном — треки
     * возвращаются в порядке `originalIndex`, а не в порядке выдачи Яндекса: в плейлисте
     * порядок и есть содержание, и терять его нельзя.
     */
    fun readPlaylist(
        kind: Int,
        offset: Int = 0,
        limit: Int = DEFAULT_LIMIT,
    ): PlaylistPage {
        val session = session()
        val library =
            YandexAccountClient.playlist(session.token.accessToken, session.identity.uid, kind)
        val from = offset.coerceAtLeast(0).coerceAtMost(library.size)
        val size = limit.coerceIn(1, MAX_LIMIT)
        val to = (from + size).coerceAtMost(library.size)
        val window = from until to
        val ids = window.map { library.trackIds[it] }
        val positions = window.map { library.originalIndexes[it] }
        return PlaylistPage(
            login = session.identity.login,
            kind = library.kind,
            uuid = library.uuid,
            title = library.title,
            revision = library.revision,
            offset = from,
            total = library.size,
            trackIds = ids,
            originalIndexes = positions,
            tracks = YandexCatalog.resolveTracks(ids),
        )
    }

    /**
     * Включает плейлист целиком.
     *
     * Первый трек читается здесь же, тем же запросом, что и страница, и уходит в плеер как
     * ожидание: без него воспроизведение нечем подтвердить, а «мы нажали кнопку» — не
     * подтверждение. Заодно это даёт агенту ответ на вопрос «что зазвучит», не дожидаясь
     * первого бара.
     */
    fun playPlaylist(kind: Int): PlaylistPlayback {
        val session = session()
        val library = YandexAccountClient.playlist(session.token.accessToken, session.identity.uid, kind)
        // Берём начало плейлиста, а не только первый трек: недоступные треки Яндекс выкидывает
        // молча, и запуск начинается со следующего играбельного. Сверка по всей голове
        // отличает «плейлист пошёл» от «тапнули и продолжился чужой трек».
        val window = library.trackIds.take(PLAYLIST_HEAD).indices
        val head =
            window
                .map { index ->
                    val track = YandexCatalog.resolveTracks(listOf(library.trackIds[index])).firstOrNull()
                    track?.let { HeadTrack(it.title, library.originalIndexes[index] + 1) }
                }.filterNotNull()
        return YandexPlaylistPlayer.play(
            login = session.identity.login,
            kind = kind,
            title = library.title.ifEmpty { "kind $kind" },
            headTracks = head,
        )
    }

    /**
     * Кладёт трек в плейлист.
     *
     * Агенту не нужен ни `albumId`, ни `revision`, ни позиция: всё это либо обязательное, но
     * выводимое только из каталога ([albumId]), либо обязательное, но meaningless без
     * свежего чтения ([revision] — плейлист могли поменять с прошлого запроса). Поэтому
     * плейлист читается здесь же, и цена лишнего захода честная: зато агент не может
     * собрать запрос, который Яндекс отвергнет.
     *
     * [at] — индекс вставки, по умолчанию в начало: так «добавь в плейлист» попадает туда,
     * куда человек положил бы трек руками, а не в конец длинного списка.
     */
    fun addTrackToPlaylist(
        kind: Int,
        trackId: String,
        at: Int? = null,
    ): PlaylistEdit {
        val session = session()
        val library = YandexAccountClient.playlist(session.token.accessToken, session.identity.uid, kind)
        val track =
            YandexCatalog.resolveTracks(listOf(trackId)).firstOrNull()
                ?: throw IllegalArgumentException("Yandex catalog has no track $trackId")
        // Раньше здесь был бы 400 от Яндекса с текстом про albumId: отвечать пользователю
        // «трек без альбома» он не может проверить, а трек без альбома в каталоге — это
        // синхронизированная штука, которой нет почти никогда.
        require(track.albumId.isNotEmpty()) { "Yandex catalog returned no album id for track $trackId" }
        val position = (at ?: 0).coerceIn(0, library.size)
        val change =
            YandexAccountClient.applyDiff(
                accessToken = session.token.accessToken,
                target = PlaylistTarget(uid = session.identity.uid, kind = kind, revision = library.revision),
                diff = insertDiff(trackId = trackId, albumId = track.albumId, at = position),
            )
        return PlaylistEdit(
            action = "added",
            kind = change.kind,
            title = change.title.ifEmpty { library.title },
            trackId = trackId,
            trackTitle = track.title,
            position = position,
            trackCount = change.trackCount,
        )
    }

    /**
     * Убирает трек из плейлиста.
     *
     * Яндекс удаляет не по id, а по диапазону индексов, поэтому индекс ищется здесь, по
     * прочитанному плейлисту, и это единственное место, где такая подмена возможна: если
     * трека в плейлисте нет, удалять нечего, и это ошибка, а не успех.
     */
    fun removeTrackFromPlaylist(
        kind: Int,
        trackId: String,
    ): PlaylistEdit {
        val session = session()
        val library = YandexAccountClient.playlist(session.token.accessToken, session.identity.uid, kind)
        val position = library.trackIds.indexOf(trackId)
        require(position >= 0) { "track $trackId is not in playlist $kind (${library.title})" }
        // `to` не включительный: это индекс уже за последним удаляемым треком.
        val change =
            YandexAccountClient.applyDiff(
                accessToken = session.token.accessToken,
                target = PlaylistTarget(uid = session.identity.uid, kind = kind, revision = library.revision),
                diff = deleteDiff(from = position, to = position + 1),
            )
        return PlaylistEdit(
            action = "removed",
            kind = change.kind,
            title = change.title.ifEmpty { library.title },
            trackId = trackId,
            trackTitle = null,
            position = position,
            trackCount = change.trackCount,
        )
    }

    /** Токен, готовый к запросу, и аккаунт, к которому он относится. */
    private data class Session(
        val token: YandexToken,
        val identity: YandexIdentity,
    )
    /**
     * Токен для внешних потребителей, которых зовёт НЕ UI: сейчас — запускатель Ynison.
     * Отдельный метод, а не переиспользование [session], потому что Ynison не знает юзера
     * и не делает платных запросов — ему нужен ровно один access token, и тянуть за собой
     * лишний сетевой выход `/users/me` при каждом старте процесса незачем.
     *
     * Публичный, но узкий: наружу уходит только строка токена, и только когда вход уже
     * состоялся. Отказ — это null, а не исключение, потому что вызывающий (старт runtime)
     * обязан пережить «юзер не подключил Яндекс» и поднять всё остальное без музыки.
     */
    fun runtimeAccessToken(): String? =
        if (!isInitialized()) {
            null
        } else {
            runCatching { freshToken(store()).accessToken }.getOrNull()
        }

    /**
     * Идентификатор устройства для Ynison — тот же, что и в device-flow.
     *
     * Один на установку, а не новый на каждый запуск процесса: Яндекс показывает его в
     * списке активных сессий, и «анонимные устройства», плодящиеся при каждом рестарте
     * serve, заставили бы юзера по одному отзывать собственную музыку.
     *
     * Публичный, потому что серверный слой не должен знать, где Яндекс хранит свои ключи.
     */
    fun runtimeDeviceId(): String? =
        if (!isInitialized()) {
            null
        } else {
            runCatching { deviceStore().deviceId() }.getOrNull()
        }

    private fun session(): Session {
        val store = store()
        val token = freshToken(store)
        val identity =
            store.identity()
                ?: YandexAccountClient.identity(token.accessToken).also(store::saveIdentity)
        return Session(token, identity)
    }

    /**
     * Токен, готовый к запросу: просроченный обновляется на лету.
     *
     * Refresh обязателен: Яндекс не выдаёт вечный токен, а сменить его без refresh нечем —
     * пользователю пришлось бы переавторизовываться раз в год и терпеть потерянный доступ.
     *
     * Чем обновлять — выбирает [YandexGrant], то есть тот вход, который токен выдал: у
     * PKCE и device-flow несовместимые тела запроса, и обновление «не тем» способом
     * падало бы тихо, через год, когда юзер уже не помнит, что подключал.
     */
    private fun freshToken(store: YandexTokenStore): YandexToken {
        val token = store.token() ?: error(NOT_CONNECTED)
        if (System.currentTimeMillis() < token.expiresAtMillis) return token
        if (!token.canRefresh) {
            error("yandex token expired and has no refresh token: run the connect tool again")
        }
        // Яндекс ротирует refresh-токен: прежний живёт ещё около 20 минут, но уже не
        // обновляет доступ. Поэтому новый сохраняем сразу, иначе следующий 401 нечем лечить.
        val (_, refreshed) =
            when (token.grant) {
                YandexGrant.DEVICE -> YandexDeviceClient.refreshDeviceToken(token.refreshToken)
                YandexGrant.PKCE -> YandexAccountClient.refresh(token.refreshToken)
            }
        store.saveToken(refreshed)
        return refreshed
    }

    /**
     * Открывает страницу подтверждения.
     *
     * Публичная, потому что UI должен уметь вернуть юзера на страницу после возврата из
     * браузера: вход живёт пять минут, и если код не введён с первого раза, кнопка
     * «открыть ещё раз» обязана вести туда же, а не собирать ссылку в своей копии.
     */
    fun openBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            contextOrThrow().startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            error("no browser is available to open the Yandex consent page")
        }
    }

    private fun store(): YandexTokenStore = YandexTokenStore(contextOrThrow())

    private fun deviceStore(): YandexDeviceStore = YandexDeviceStore(contextOrThrow())

    private fun contextOrThrow(): Context {
        check(::context.isInitialized) { "YandexAccountController is not initialized" }
        return context
    }

    private fun isInitialized(): Boolean = ::context.isInitialized
}
