package org.opencode.mobile.account

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * Сеть device-flow: выдача кода, опрос и обновление токена.
 *
 * Отдельный объект от [YandexAccountClient] не из-за количества, а из-за контракта
 * ошибок. Там не-2xx — всегда исключение: там нет состояния «ещё подтверди не
 * подтвердил». Здесь 400 на опросе — это нормальный ход входа, и метод, который на него
 * бросает, отменил бы вход, идущий к успеху. Смешав два контракта в одном объекте,
 * пришлось бы тащить в каждое чтение библиотеки знание про device-flow, которое им
 * ничего не говорит.
 */
object YandexDeviceClient {
    private const val USER_AGENT = "opencode-mobile/1.0"
    private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=UTF-8"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val HTTP_OK = 200
    private const val HTTP_MAX = 299

    /**
     * Просит у Яндекса пару кодов и возвращает её в виде, пригодном для показа юзеру.
     *
     * Без заголовка `Authorization: Basic`: этот эндпоинт секрета не требует, и проверено
     * живым запросом. Заголовок «на всякий случай» в коде авторизации обходится дороже,
     * чем сам секрет, — он оставил бы в дампе запроса base64 чужой учётной пары.
     */
    fun requestDeviceCode(deviceId: String): YandexDeviceAuth.DeviceCode {
        val response = formPost(YandexDeviceAuth.DEVICE_CODE_URL, YandexDeviceAuth.deviceCodeForm(deviceId))
        if (!response.ok) throw IOException(failureText("device code request", response))
        return YandexDeviceAuth.parseDeviceCode(response.body)
    }

    /**
     * Один опрос device-flow.
     *
     * Код ответа HTTP здесь намеренно не проверяется: и успех, и «юзер ещё не
     * подтвердил» приходят телом, которое разбирает [YandexDeviceAuth.poll], а проверка по
     * коду ответа спутала бы «подтвердите вход» с «вход отменён».
     */
    fun pollDeviceToken(deviceCode: String): YandexDeviceAuth.Poll =
        YandexDeviceAuth.poll(
            formPost(
                YandexOAuth.TOKEN_URL,
                YandexDeviceAuth.deviceTokenForm(deviceCode),
                authorization = YandexDeviceAuth.basicAuthorization(),
            ).body,
        )

    /**
     * Обновление токена, выданного device-flow.
     *
     * Отдельный метод, а не вариант PKCE-refresh с флагом: у входов несовместимые тела
     * запроса, и вызов с перепутанным флагом тихо испортил бы обновление до следующего
     * истечения токена — то есть через год, когда юзер уже не помнит, что подключал.
     */
    fun refreshDeviceToken(
        refreshToken: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Pair<YandexOAuth.TokenResponse, YandexToken> {
        val poll =
            YandexDeviceAuth.poll(
                formPost(
                    YandexOAuth.TOKEN_URL,
                    YandexDeviceAuth.deviceRefreshForm(refreshToken),
                    authorization = YandexDeviceAuth.basicAuthorization(),
                ).body,
            )
        val granted = poll as? YandexDeviceAuth.Poll.Granted
            ?: throw IOException("Yandex device token refresh failed: ${describe(poll)}")
        return granted.token to granted.token.toToken(nowMillis, YandexGrant.DEVICE)
    }

    private fun describe(poll: YandexDeviceAuth.Poll): String =
        when (poll) {
            is YandexDeviceAuth.Poll.Rejected -> poll.reason
            YandexDeviceAuth.Poll.SlowDown -> "yandex asked to poll less often"
            YandexDeviceAuth.Poll.Pending, is YandexDeviceAuth.Poll.Granted -> "authorization is not granted yet"
        }

    private fun failureText(
        what: String,
        response: FormResponse,
    ): String = "Yandex $what failed: ${YandexOAuth.describeError(response.code, response.body)}"

    /** Ответ формы: код нужен вызывающему, потому что 400 здесь — не всегда отказ. */
    private data class FormResponse(
        val code: Int,
        val body: String,
    ) {
        val ok: Boolean get() = code in HTTP_OK..HTTP_MAX
    }

    /**
     * POST формы с телом; ответ не разбирается и не бросается.
     *
     * Возвращать сырой ответ и решать вызывающему — единственный способ не тащить флаг
     * «разрешить ошибку» в сигнатуру: [YandexAccountClient.postForm] на не-2xx обязан
     * бросать, а здесь 400 означает «жди дальше».
     */
    private fun formPost(
        url: String,
        form: String,
        authorization: String? = null,
    ): FormResponse {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Content-Type", FORM_CONTENT_TYPE)
            connection.setRequestProperty("Accept", "application/json")
            authorization?.let { connection.setRequestProperty("Authorization", it) }
            connection.outputStream.use { it.write(form.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val body = (if (code in HTTP_OK..HTTP_MAX) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            return FormResponse(code, body)
        } finally {
            connection.disconnect()
        }
    }
}
