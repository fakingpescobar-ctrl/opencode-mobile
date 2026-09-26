package org.opencode.mobile.account

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * OAuth-клиент Яндекс ID, через который читается библиотека Яндекс Музыки.
 *
 * Здесь только формы запросов и разбор ответов — без сети и без Android, чтобы контракт
 * можно было проверить обычными юнит-тестами. Сеть живёт в [YandexAccountClient],
 * хранение — в [YandexTokenStore].
 *
 * Три решения, которые в доках неочевидны и каждое было проверено вживую:
 *
 * - **PKCE вместо client_secret.** Если в обмен кода на токен передаётся `code_verifier`,
 *   секрет передавать не нужно. Секрет — это «пароль» приложения, и хранить его на
 *   устройстве не хочется; без него в приложении лежит только публичный `client_id`.
 * - **Домен `.ru`, а не `.com`.** Сессия пользователя живёт на `.ru`: запрос на
 *   `oauth.yandex.com` уводит на `passport.yandex.com` и заставляет логиниться заново.
 * - **Scope только `login:info`.** Отдельного «музыкального» scope в рабочем виде нет:
 *   `music:api-public` отвечает `invalid_scope`, а `api.music.yandex.net` прекрасно
 *   отдаёт библиотеку на токене с `login:info`. Лишние права ещё и уменьшают TTL токена.
 */
// Много мелких функций - здесь они не «инфраструктура вперемешку с логикой», а разбор
// одного контракта по частям: форма запроса, ответ токена, ответ ошибки. Держать это в
// одном методе значило бы получить функцию на сто строк с четырьмя разными формами ответа.
@Suppress("TooManyFunctions")
object YandexOAuth {
    /** Публичный идентификатор приложения. Секрет намеренно не хранится — см. KDoc. */
    const val CLIENT_ID = "70e7fc7e75144b2badc68ba8d0293882"

    /** Схема, на которую Яндекс отдаёт код. Проверено: кастомные схемы Яндекс принимает. */
    const val REDIRECT_URI = "org.opencode.mobile://oauth"

    /** Схема для intent-filter. В URI `org.opencode.mobile://oauth` именно она, а `oauth` — host. */
    const val SCHEME = "org.opencode.mobile"

    /**
     * Хост deep link. Проверяется вместе со схемой, потому что схема сама по себе не
     * уникальна: в intent-filter попадёт любой сторонний url вида
     * `org.opencode.mobile://что-угодно`, и ждать от него код авторизации незачем.
     */
    const val HOST = "oauth"

    const val SCOPE = "login:info"
    const val AUTHORIZE_URL = "https://oauth.yandex.ru/authorize"
    const val TOKEN_URL = "https://oauth.yandex.ru/token"
    const val INFO_URL = "https://login.yandex.ru/info?format=json"

    /** Грань приемлемого: символы из RFC 7636 unreserved, 43..128 по спецификации. */
    private const val VERIFIER_LENGTH = 64
    private const val VERIFIER_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val STATE_BYTES = 24
    private const val GRANT_AUTHORIZATION_CODE = "authorization_code"
    private const val GRANT_REFRESH_TOKEN = "refresh_token"
    private const val SHA_256 = "SHA-256"
    private const val ERROR_TEXT_LIMIT = 200

    // Имена полей ответа token-эндпоинта. Вынесены, потому что `parseToken` и тесты
    // должны читать ровно те же строки, что и живой ответ Яндекса.
    private const val JSON_ACCESS = "access_token"
    private const val JSON_REFRESH = "refresh_token"
    private const val JSON_EXPIRES = "expires_in"
    private const val JSON_SCOPE = "scope"

    /** Один заход авторизации: чем подписан запрос и чем он проверяется на возврате. */
    data class Pkce(
        val verifier: String,
        val challenge: String,
        val state: String,
    )

    /**
     * Что пришло на [REDIRECT_URI]. Один и тот же обработчик принимает и успех, и отказ:
     * Яндекс кладёт ошибку в ту же строку запроса, что и код.
     */
    data class Callback(
        val code: String?,
        val state: String?,
        val error: String?,
        val errorDescription: String?,
    ) {
        val failed: Boolean get() = !error.isNullOrBlank()
    }

    /** Ответ `/token`. [refreshToken] может прийти пустым — тогда refresh невозможен. */
    data class TokenResponse(
        val accessToken: String,
        val refreshToken: String,
        val expiresInSeconds: Long,
        val scope: String,
    )

    fun newPkce(random: SecureRandom = SecureRandom()): Pkce {
        val verifier = buildString(VERIFIER_LENGTH) {
            repeat(VERIFIER_LENGTH) { append(VERIFIER_ALPHABET[random.nextInt(VERIFIER_ALPHABET.length)]) }
        }
        return Pkce(
            verifier = verifier,
            challenge = challengeFor(verifier),
            state = randomToken(random, STATE_BYTES),
        )
    }

    /** `code_challenge` = base64url(SHA-256(verifier)) без padding — так требует RFC 7636. */
    fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance(SHA_256).digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun authorizeUrl(pkce: Pkce): String =
        "$AUTHORIZE_URL?response_type=code" +
            "&client_id=${encode(CLIENT_ID)}" +
            "&redirect_uri=${encode(REDIRECT_URI)}" +
            "&scope=${encode(SCOPE)}" +
            "&state=${encode(pkce.state)}" +
            "&code_challenge=${encode(pkce.challenge)}" +
            "&code_challenge_method=S256"

    /** Тело запроса за кодом. Секрета нет намеренно — его заменяет `code_verifier`. */
    fun tokenForm(
        code: String,
        verifier: String,
    ): String =
        "grant_type=$GRANT_AUTHORIZATION_CODE" +
            "&code=${encode(code)}" +
            "&client_id=${encode(CLIENT_ID)}" +
            "&code_verifier=${encode(verifier)}"

    fun refreshForm(refreshToken: String): String =
        "grant_type=$GRANT_REFRESH_TOKEN" +
            "&refresh_token=${encode(refreshToken)}" +
            "&client_id=${encode(CLIENT_ID)}"

    /**
     * Разбирает строку, пришедшую на redirect. Разбор ручной, а не через `android.net.Uri`:
     * в юнит-тестах `Uri` — заглушка без реализации, и такой парсер там просто упал бы.
     */
    fun parseCallback(uri: String): Callback {
        val params = queryParams(uri)
        return Callback(
            code = params["code"],
            state = params["state"],
            error = params["error"],
            errorDescription = params["error_description"],
        )
    }

    fun parseToken(body: String): TokenResponse {
        val json = JSONObject(body)
        val access = json.optString(JSON_ACCESS).trim()
        require(access.isNotEmpty()) { "token response has no access_token" }
        return TokenResponse(
            accessToken = access,
            refreshToken = json.optString(JSON_REFRESH).trim(),
            expiresInSeconds = json.optLong(JSON_EXPIRES, 0L),
            scope = json.optString(JSON_SCOPE).trim(),
        )
    }

    /**
     * Текст ошибки из тела ответа.
     *
     * Единого формата нет и быть не может: `oauth.yandex.ru/token` отдаёт
     * `{error, error_description}`, `login.yandex.ru/info` — `{code, message}`,
     * `api.music.yandex.net` — `{status, error, message, requestId}`. Поэтому перебираем
     * поля в порядке убывания полезности, а не разбираем один «канонический» вид.
     *
     * Порядок неочевиден и важен: `message` стоит последним не из вежливости, а потому
     * что у Яндекса это проза поверх настоящего имени ошибки. У `login.yandex.ru` тело
     * это `{code: "NOT_AUTHORIZED", message: "no token"}`, и полезно отдать агенту
     * `NOT_AUTHORIZED` - по нему видно, что именно не так, - а не `no token`.
     */
    fun describeError(
        status: Int,
        body: String,
    ): String {
        val detail = runCatching { JSONObject(body) }.getOrNull()?.let { json ->
            ERROR_FIELDS.firstNotNullOfOrNull { field -> json.optString(field).trim().ifEmpty { null } }
        }
        val text = detail?.take(ERROR_TEXT_LIMIT)
        return if (text == null) "HTTP $status" else "HTTP $status: $text"
    }

    // error_description - самый конкретный текст; error/code - устойчивое имя ошибки;
    // message - проза, которой почти всегда достаточно только если имени нет.
    private val ERROR_FIELDS = listOf("error_description", "error", "code", "message")

    private fun queryParams(uri: String): Map<String, String> =
        uri
            .substringAfter('?', "")
            .substringBefore('#')
            .split('&')
            .filter { it.isNotBlank() }
            .associate { pair ->
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                decode(name) to decode(value)
            }

    private fun randomToken(
        random: SecureRandom,
        bytes: Int,
    ): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
