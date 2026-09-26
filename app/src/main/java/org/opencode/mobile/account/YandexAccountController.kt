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

    /** Итог приёма кода: подключились или нет, и если нет — почему. */
    sealed interface Outcome {
        data class Connected(
            val identity: YandexIdentity,
        ) : Outcome

        data class Rejected(
            val reason: String,
        ) : Outcome
    }

    /** Что приложение знает о подключении на данный момент. */
    data class Status(
        val connected: Boolean,
        val identity: YandexIdentity?,
        val expiresAtMillis: Long,
        val canRefresh: Boolean,
        val awaitingCode: Boolean,
    )

    private lateinit var context: Context

    @Synchronized
    fun initialize(context: Context) {
        if (::context.isInitialized) return
        this.context = context.applicationContext
    }

    /**
     * Начинает заход: готовит PKCE, сохраняет его и открывает браузер на странице согласия.
     *
     * Существующий токен не трогаем: если юзер просто решил переподключиться и ошибся,
     * работающая авторизация должна пережить неудачную попытку.
     */
    fun startConnect(): Status {
        val store = store()
        val pkce = YandexOAuth.newPkce()
        store.savePending(
            YandexTokenStore.PendingAuth(
                verifier = pkce.verifier,
                state = pkce.state,
                startedAtMillis = System.currentTimeMillis(),
            ),
        )
        openBrowser(YandexOAuth.authorizeUrl(pkce))
        return status()
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
            val identity = YandexAccountClient.identity(token.accessToken)
            // Пишем всё разом и только после успешной смены: полуготовый аккаунт
            // хуже, чем аккаунта нет.
            store.saveToken(token)
            store.saveIdentity(identity)
            store.clearPending()
            Outcome.Connected(identity)
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
            awaitingCode = store.pending() != null,
        )
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

    /** Токен, готовый к запросу, и аккаунт, к которому он относится. */
    private data class Session(
        val token: YandexToken,
        val identity: YandexIdentity,
    )

    /**
     * Всё, что нужно любому чтению: непротухший токен и кто перед нами.
     *
     * Собрано в одно место не для красоты: логика «взять токен, при необходимости обновить,
     * а identity добрать и запомнить» была продублирована в каждом читателе, и любая правка
     * в ней разъезжалась бы по копиям. Идентичность заодно кэшируется в хранилище, поэтому
     * платный запрос `/users/me` случается один раз, а не на каждый чих.
     */
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
     */
    private fun freshToken(store: YandexTokenStore): YandexToken {
        val token = store.token() ?: error(NOT_CONNECTED)
        if (System.currentTimeMillis() < token.expiresAtMillis) return token
        if (!token.canRefresh) {
            error("yandex token expired and has no refresh token: run the connect tool again")
        }
        // Яндекс ротирует refresh-токен: прежний живёт ещё около 20 минут, но уже не
        // обновляет доступ. Поэтому новый сохраняем сразу, иначе следующий 401 нечем лечить.
        val (_, refreshed) = YandexAccountClient.refresh(token.refreshToken)
        store.saveToken(refreshed)
        return refreshed
    }

    private fun openBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            contextOrThrow().startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            error("no browser is available to open the Yandex consent page")
        }
    }

    private fun store(): YandexTokenStore = YandexTokenStore(contextOrThrow())

    private fun contextOrThrow(): Context {
        check(::context.isInitialized) { "YandexAccountController is not initialized" }
        return context
    }
}
