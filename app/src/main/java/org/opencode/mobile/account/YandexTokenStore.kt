package org.opencode.mobile.account

import android.content.Context
import android.content.SharedPreferences

/** Токен доступа с моментом, после которого его нужно обновить. */
data class YandexToken(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
) {
    val canRefresh: Boolean get() = refreshToken.isNotBlank()
}

/** Кто вошёл. Хранится рядом с токеном, чтобы не ходить в сеть за каждым `status`. */
data class YandexIdentity(
    val login: String,
    val uid: String,
)

/**
 * Приватное хранилище токена Яндекса.
 *
 * `MODE_PRIVATE` — это не шифрование, но ровно то, что требует Яндекс: токен не должен
 * попадать в браузер, в открытые настройки и в файлы, доступные другим приложениям.
 * `security-crypto` в проекте нет, а добавлять зависимость ради одного токена смысла мало —
 * доступ к приватным файлам приложения и так есть только у него самого.
 *
 * [PendingAuth] живёт здесь же, а не в памяти процесса: код возвращается в новом процессе
 * (приложение могло быть убито, пока юзер логинился в браузере), и тогда держать verifier
 * в поле класса было бы ровно тем, из-за чего flow ломался бы через раз.
 */
class YandexTokenStore(
    context: Context,
) {
    /** Заход авторизации в полёте. [startedAtMillis] — чтобы протухший код не ждал вечно. */
    data class PendingAuth(
        val verifier: String,
        val state: String,
        val startedAtMillis: Long,
    )

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveToken(token: YandexToken) {
        prefs
            .edit()
            .putString(KEY_ACCESS, token.accessToken)
            .putString(KEY_REFRESH, keepRefreshToken(token()?.refreshToken, token.refreshToken))
            .putLong(KEY_EXPIRES_AT, token.expiresAtMillis)
            .apply()
    }

    /**
     * Инвариант хранилища: сохранённый токен никогда не теряет способность обновиться.
     *
     * Правило вынесено в [keepRefreshToken], потому что проверять его через
     * Android-хранилище пришлось бы Robolectric, которого в проекте нет, - а правило
     * ровно из тех, что дешевле проверить юнит-тестом, чем поймать в проде.
     */
    fun token(): YandexToken? {
        val access = prefs.getString(KEY_ACCESS, null)?.trim().orEmpty()
        if (access.isEmpty()) return null
        return YandexToken(
            accessToken = access,
            refreshToken = prefs.getString(KEY_REFRESH, null)?.trim().orEmpty(),
            expiresAtMillis = prefs.getLong(KEY_EXPIRES_AT, 0L),
        )
    }

    fun saveIdentity(identity: YandexIdentity) {
        prefs
            .edit()
            .putString(KEY_LOGIN, identity.login)
            .putString(KEY_UID, identity.uid)
            .apply()
    }

    fun identity(): YandexIdentity? {
        val login = prefs.getString(KEY_LOGIN, null)?.trim().orEmpty()
        val uid = prefs.getString(KEY_UID, null)?.trim().orEmpty()
        if (login.isEmpty() || uid.isEmpty()) return null
        return YandexIdentity(login = login, uid = uid)
    }

    fun savePending(pending: PendingAuth) {
        prefs
            .edit()
            .putString(KEY_VERIFIER, pending.verifier)
            .putString(KEY_STATE, pending.state)
            .putLong(KEY_PENDING_SINCE, pending.startedAtMillis)
            .apply()
    }

    fun pending(): PendingAuth? {
        val verifier = prefs.getString(KEY_VERIFIER, null)?.trim().orEmpty()
        val state = prefs.getString(KEY_STATE, null)?.trim().orEmpty()
        if (verifier.isEmpty() || state.isEmpty()) return null
        return PendingAuth(
            verifier = verifier,
            state = state,
            startedAtMillis = prefs.getLong(KEY_PENDING_SINCE, 0L),
        )
    }

    /**
     * Заход завершён: убираем только verifier.
     *
     * Именно только. Здесь мы вызываем это уже после того, как новый токен записан, и
     * обычный [clear] снёс бы вместе с verifier'ом и только что полученный доступ.
     */
    fun clearPending() {
        prefs
            .edit()
            .remove(KEY_VERIFIER)
            .remove(KEY_STATE)
            .remove(KEY_PENDING_SINCE)
            .apply()
    }

    /**
     * Отключение от аккаунта. Стирает всё, включая висящий [PendingAuth]: код, который
     * сейчас в браузере, после отключения уже никому не нужен.
     */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        // Имя приватного файла. Не префиксовать именем пакета — так его не спутать с
        // настройками самого приложения при разборе дампа.
        const val PREFS_NAME = "opencode_yandex_auth"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_LOGIN = "login"
        const val KEY_UID = "uid"
        const val KEY_VERIFIER = "code_verifier"
        const val KEY_STATE = "state"
        const val KEY_PENDING_SINCE = "pending_since"
    }
}

/**
 * Инвариант хранилища: сохранённый токен никогда не теряет способность обновиться.
 *
 * По OAuth от refresh-эндпоинта `refresh_token` вовсе не обязан приходить, а Яндекс его
 * ротирует. Если записать пришедшую пустую строку как есть, юзер теряет доступ навсегда:
 * доступ ещё выглядит живым, обновить его уже нечем, и до следующего истечения года ни
 * кто ничего не заметит. Поэтому пустой ответ значит «оставь прежний», а не «refresh
 * теперь нет».
 */
internal fun keepRefreshToken(
    previous: String?,
    incoming: String,
): String = incoming.trim().ifEmpty { previous?.trim().orEmpty() }
