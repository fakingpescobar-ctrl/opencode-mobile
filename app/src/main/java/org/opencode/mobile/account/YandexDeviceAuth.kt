package org.opencode.mobile.account

import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64

/**
 * OAuth Device Flow Яндекс ID — вход, который выдаёт токен, годный для Ynison.
 *
 * Зачем он вместо PKCE: токен PKCE умеет только REST (`login:info`), а Ynison такой
 * токен отвергает. Проверено вживью — с device-flow токеном Ynison handshake проходит,
 * с PKCE нет. Значит управление музыкой возможно только здесь.
 *
 * **Кто мы для Яндекса.** Ни здесь, ни где-либо ещё креды не зашиты: идентификатор и
 * секрет лежат в [YandexOAuth], потому что это наша собственная регистрация из консоли
 * Яндекса, общая для обоих входов. Раньше здесь стояли публичные креды официального
 * клиента Яндекс Музыки для Android, и это было неверно: свой `client_id` у приложения
 * уже был, просто device-flow его не использовал. Формально запросы проходили, и ошибка
 * была не видна — вход шёл не от того приложения, и Яндекс показывал чужую регистрацию
 * среди активных.
 *
 * **Что проверено вживую, а не взято из документации:**
 *
 * - `/device/code` секрета не требует: `client_id` в теле формы достаточно, заголовок
 *   `Authorization: Basic` можно не слать. Логи в документации и в сторонних библиотеках
 *   требуют его, и это стоило живого запроса, чтобы не тащить в код лишнее.
 * - **`/token` секрета требует**, в отличие от `/device/code`. Без него ответ —
 *   `invalid_client: Wrong client secret`; с ним на заведомо неверном коде приходит
 *   `invalid_grant`. Разница ошибок и есть доказательство: с верным секретом запрос
 *   проходит проверку клиента и доходит до проверки гранта. Отсюда и [CLIENT_ID], и
 *   [YandexOAuth.CLIENT_SECRET] в теле каждой из форм ниже.
 * - `verification_url` приходит как `https://ya.ru/device` — не `yandex.ru/auth/device`,
 *   как учат примеры, — поэтому ссылку берём из ответа, а не конструируем сами.
 * - `user_code` — 8 символов без дефиса (`jq7ivm4b`), `interval` = 5, `expires_in` = 300.
 *   Срок жизни кода — пять минут, а не привычные десять: агент, который ушёл показывать
 *   код юзеру, обязан уложиться в это окно.
 *
 * Как и [YandexOAuth], объект не ходит в сеть — только собирает формы и разбирает ответы.
 * Сеть живёт в [YandexAccountClient], хранение — в [YandexTokenStore].
 */
object YandexDeviceAuth {
    /** Наша регистрация, общая с PKCE-входом. См. [YandexOAuth]. */
    const val CLIENT_ID: String = YandexOAuth.CLIENT_ID

    /**
     * Секрет достаётся из [YandexOAuth] на месте, а не хранится своим полем.
     *
     * Приватный — в отличие от [CLIENT_ID], о котором можно сказать наружу, потому что он
     * публичный по смыслу. Дублировать же его константой здесь значило бы завести ровно
     * то же расхождение, ради устранения которого секрет и вынесен в одно место.
     */
    private const val CLIENT_SECRET: String = YandexOAuth.CLIENT_SECRET

    const val DEVICE_CODE_URL = "https://oauth.yandex.ru/device/code"

    /**
     * Страница, где юзер вводит код.
     *
     * Не `yandex.ru/auth/device`, как в примерах. Обычно её отдаёт сам `/device/code`, и
     * тогда сюда мы не попадаем; но если сервер забудет про `verification_url`, запасная
     * ссылка должна вести на страницу ввода, а не на API-эндпоинт, где вводить нечего.
     */
    const val VERIFICATION_URL = "https://ya.ru/device"

    /** Имя устройства в списке сессий Яндекса: «этим устройством вошли мы», а не кто-то ещё. */
    const val DEVICE_NAME = "OpenCodeMobile"

    private const val DEVICE_ID_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val DEVICE_ID_LENGTH = 10
    private const val GRANT_DEVICE_CODE = "device_code"
    private const val GRANT_REFRESH = "refresh_token"
    private const val FALLBACK_INTERVAL_SECONDS = 5
    private const val FALLBACK_EXPIRES_SECONDS = 300
    private const val MIN_INTERVAL_SECONDS = 1
    private const val ERROR_PENDING = "authorization_pending"
    private const val ERROR_SLOW_DOWN = "slow_down"
    private const val POLL_HTTP_BAD_REQUEST = 400
    private const val MILLIS_PER_SECOND = 1000L
    private const val ERROR_TEXT_LIMIT = 200
    private const val NON_JSON_BODY = "Yandex returned a body that is not JSON"
    private const val JSON_ACCESS = "access_token"
    private const val JSON_DEVICE_CODE = "device_code"
    private const val JSON_USER_CODE = "user_code"
    private const val JSON_VERIFICATION_URL = "verification_url"
    private const val JSON_EXPIRES = "expires_in"
    private const val JSON_INTERVAL = "interval"
    private const val JSON_ERROR = "error"

    /**
     * Пара кодов, выданная на `/device/code`.
     *
     * [deviceCode] — наш, его поллингом меняем на токен; [userCode] — человеческий, его
     * вводит юзер. Смешивать их нельзя, и путаница тут не гипотетика: обе строки
     * выглядят одинаково, а ошибка выглядит как «юзер подтвердил, а токен не пришёл».
     */
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int,
    ) {
        /** Граница, после которой код больше не опросится. Считается один раз, при выдаче. */
        fun expiresAtMillis(startedAtMillis: Long): Long = startedAtMillis + expiresInSeconds * MILLIS_PER_SECOND
    }

    /**
     * Ответ на один опрос `/token`.
     *
     * Четыре состояния, а не два, потому что «юзер ещё не подтвердил» — это не ошибка:
     * на неё приходится весь нормальный ход входа, и отбросив её как отказ, мы бы отменили
     * успешный вход, просто спросив слишком рано. От «пока жди» отделён [SlowDown] — это
     * не то же самое ожидание, а требование отступить.
     */
    sealed interface Poll {
        /** Токен получен. */
        data class Granted(
            val token: YandexOAuth.TokenResponse,
        ) : Poll

        /** Пользователь ещё не подтвердил вход — опросить позже, как договаривались. */
        data object Pending : Poll

        /**
         * Яндекс просит опрашивать реже.
         *
         * Отделен от [Pending] не для красоты: «юзер ещё вводит код» и «вы стучите слишком
         * часто» требуют от агента противоположного — ждать по плану или отступить. Если оба
         * превратятся в одно ожидание, честный интервал из кода приведёт к бесконечным
         * `slow_down`, и вход закончится истечением кода, хотя юзер подтвердил минуту назад.
         */
        data object SlowDown : Poll

        /** Яндекс отказал: код истёк, отозван или введён неверно. */
        data class Rejected(
            val reason: String,
        ) : Poll
    }

    /**
     * Идентификатор устройства: 10 символов, ровно как ждёт Яндекс.
     *
     * Постоянный, а не случайный на каждый заход: Яндекс показывает этот id в списке
     * активных сессий, и новый на каждом входе превращался бы в россыпь «незнакомых
     * устройств», которые юзеру пришлось бы узнавать и отзывать по одной.
     */
    fun newDeviceId(random: SecureRandom = SecureRandom()): String =
        (1..DEVICE_ID_LENGTH)
            .map { DEVICE_ID_ALPHABET[random.nextInt(DEVICE_ID_ALPHABET.length)] }
            .joinToString("")

    /** Тело `/device/code`. Секрета нет: этому эндпоинту он не нужен — см. KDoc объекта. */
    fun deviceCodeForm(deviceId: String): String =
        "client_id=$CLIENT_ID" +
            "&device_id=${encode(deviceId)}" +
            "&device_name=${encode(DEVICE_NAME)}"

    /** Тело опроса: `grant_type=device_code`. */
    fun deviceTokenForm(deviceCode: String): String =
        "grant_type=$GRANT_DEVICE_CODE" +
            "&code=${encode(deviceCode)}" +
            "&client_id=$CLIENT_ID" +
            "&client_secret=$CLIENT_SECRET"

    /**
     * Тело обновления токена, выданного device-flow.
     *
     * Отдельная форма, а не переиспользование [YandexOAuth.refreshForm]: секрет в теле
     * обязателен, а у PKCE-формы его там быть не должно. Один метод с флагом «а слать ли
     * секрет» дал бы вызов, где забытый флаг тихо ломает обновление на год.
     */
    fun deviceRefreshForm(refreshToken: String): String =
        "grant_type=$GRANT_REFRESH" +
            "&refresh_token=${encode(refreshToken)}" +
            "&client_id=$CLIENT_ID" +
            "&client_secret=$CLIENT_SECRET"

    /**
     * `Authorization: Basic` для `/token`.
     *
     * Секрет уходит и сюда, и в тело формы — намеренно дублируется: `/token` проверяет
     * клиента по обоим и при несовпадении отвечает `invalid_client`, не называя, какой
     * именно из них не сошёлся. Одного канала хватает, но диагностировать отказ в
     * годовой токен дороже, чем отправить два.
     *
     * Значение не кэшируется в константу: base64 от секрета в дампе кучи выглядел бы как
     * настоящий пароль, и искать его потом пришлось бы по всему логу.
     */
    fun basicAuthorization(): String =
        "Basic " + Base64.getEncoder().encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(StandardCharsets.UTF_8))

    /**
     * Разбор ответа `/device/code`.
     *
     * Значения с полями подстраховываются запасными: сервер их не присылает только при
     * ошибке, но если пришлёт ответ без них, молчаливое значение уведёт вход в бесконечный
     * опрос, который юзер видит как «приложение зависло».
     */
    fun parseDeviceCode(body: String): DeviceCode {
        val json = JSONObject(body)
        val deviceCode = json.optString(JSON_DEVICE_CODE).trim()
        val userCode = json.optString(JSON_USER_CODE).trim()
        require(deviceCode.isNotEmpty()) { "device code response has no device_code" }
        require(userCode.isNotEmpty()) { "device code response has no user_code" }
        return DeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            // Ссылку берём из ответа, а не собираем: она уже с нужным кодом внутри.
            verificationUrl = json.optString(JSON_VERIFICATION_URL).trim().ifEmpty { VERIFICATION_URL },
            intervalSeconds = json.optInt(JSON_INTERVAL, FALLBACK_INTERVAL_SECONDS).coerceAtLeast(MIN_INTERVAL_SECONDS),
            expiresInSeconds = json.optInt(JSON_EXPIRES, FALLBACK_EXPIRES_SECONDS).coerceAtLeast(MIN_INTERVAL_SECONDS),
        )
    }

    /**
     * Разбор одного ответа на опрос `/token`.
     *
     * Порядок проверок — сначала токен, потом имя ошибки: успешный ответ Яндекс тоже
     * сопровождает HTTP 200 с полем `error`, и наоборот, отказ приходит кодом 400. Если
     * смотреть только на код ответа, «пользователь ещё не подтвердил» и «вход отменён»
     * стали бы неразличимы.
     *
     * Разбор разложен по [readToken] и [waitOrReject], а не свален сюда, чтобы остаться
     * с двумя возвратами: дальше по коду всё равно читается «есть токен — иначе причина».
     */
    fun poll(body: String): Poll {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val granted = json?.let { readToken(it, body) }
        return when {
            json == null -> Poll.Rejected(body.take(ERROR_TEXT_LIMIT).ifBlank { NON_JSON_BODY })
            granted != null -> Poll.Granted(granted)
            else -> waitOrReject(json, body)
        }
    }

    /** Токен, если ответ его содержит, иначе null — чтобы [poll] решал без второго разбора. */
    private fun readToken(
        json: JSONObject,
        body: String,
    ): YandexOAuth.TokenResponse? = if (json.optString(JSON_ACCESS).isNotBlank()) YandexOAuth.parseToken(body) else null

    /**
     * Имя ошибки решает всё: пока это `authorization_pending` или `slow_down`, вход жив.
     *
     * Живым он остаётся, но по-разному: `pending` означает «жди по своему интервалу», а
     * `slow_down` — «ты опрашиваешь слишком часто, отступи». Маркер без числа сознательно:
     * интервал знает только хранилище кода, и политика «на сколько отступить» живёт там же,
     * где дата истечения. Вынесено из [poll], чтобы тот оставался с двумя возвратами и его
     * порядок проверок читался целиком: сперва токен, потом причина, и только потом отказ.
     */
    private fun waitOrReject(
        json: JSONObject,
        body: String,
    ): Poll =
        when (json.optString(JSON_ERROR).trim()) {
            ERROR_PENDING -> Poll.Pending
            ERROR_SLOW_DOWN -> Poll.SlowDown
            else -> Poll.Rejected(YandexOAuth.describeError(POLL_HTTP_BAD_REQUEST, body))
        }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
