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
 * - **Домен `.ru`, а не `.com`.** Сессия пользователя живёт на `.ru`: запрос на
 *   `oauth.yandex.com` уводит на `passport.yandex.com` и заставляет логиниться заново.
 * - **Scope надо запрашивать явно, и это [SCOPE], а не `login:info`.** Раньше здесь стоял
 *   вывод «отдельного музыкального scope нет: `music:api-public` отвечает `invalid_scope`,
 *   а `api.music.yandex.net` прекрасно отдаёт библиотеку на `login:info`». Проверка была
 *   верной, но выполнена на другой регистрации — той, что была в коде до `f749d88` и
 *   больше не используется. У клиента, который остался сейчас, `music:api-public`
 *   зарегистрирован: `/device/code` и с ним, и с `login:info music:api-public`
 *   отвечает `200`. Без явного `scope` Яндекс выдаёт токен с урезанным набором, и
 *   `api.music.yandex.net` режет его `403 missing-required-scopes` — при живом токене
 *   и верном `X-Yandex-Music-Client`, то есть отказ не в подписи запроса.
 * - **Секрет нужен только device-flow.** PKCE обходится без него, а [YandexDeviceAuth]
 *   без секрета получает `invalid_client: Wrong client secret` — проверено вживую, и
 *   отказ приходит именно на проверке клиента, до проверки самого гранта.
 *
 * **Секрет здесь — не граница доверия.** Он лежит в APK и извлекается за секунды, но
 * толку от него вне приложения никакого: получить токен можно только если владелец
 * аккаунта сам введёт код на странице Яндекса. Настоящая граница — интерактивное
 * подтверждение, а не эта строка. Хранить её приходится потому, что device-flow без
 * неё не работает, а не потому, что она что-то защищает.
 */
// Много мелких функций - здесь они не «инфраструктура вперемешку с логикой», а разбор
// одного контракта по частям: форма запроса, ответ токена, ответ ошибки. Держать это в
// одном методе значило бы получить функцию на сто строк с четырьмя разными формами ответа.
@Suppress("TooManyFunctions")
object YandexOAuth {
    /**
     * Идентификатор клиента — публичные креды официального приложения Яндекс Музыки для
     * Android; оговорка про «свою регистрацию» разобрана в [YandexDeviceAuth].
     *
     * Единственное место, где он записан: и PKCE, и device-flow обязаны читать его отсюда.
     * Два независимых литерала в двух объектах уже расходились, и такая ошибка не падает:
     * обе формы собираются, обе отправляются, просто вход идёт не от того приложения.
     */
    const val CLIENT_ID = "70e7fc7e75144b2badc68ba8d0293882"

    /**
     * Секрет того же приложения. Нужен device-flow, не нужен PKCE — см. KDoc объекта.
     *
     * Живёт рядом с [CLIENT_ID], а не в [YandexDeviceAuth], по той же причине: пара
     * «идентификатор + секрет» описывает одно приложение, и разносить её по объектам
     * можно только повторить расхождение.
     */
    const val CLIENT_SECRET = "8b8a527bddf14aa8b2b7d1cd90305d2c"

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

    /**
     * Скоупы, зарегистрированные за этим клиентом в консоли Яндекса.
     *
     * Запрашиваются явно и обоими входами одинаково. Набор нельзя расширять: `login:email`
     * Яндекс отклоняет с «Scope from POST does not match the client's one», а `read:music`
     * — с «err_scope not empty». Ровно эти два скоупа и есть всё, что есть у клиента.
     */
    const val SCOPE = "login:info music:api-public"
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
