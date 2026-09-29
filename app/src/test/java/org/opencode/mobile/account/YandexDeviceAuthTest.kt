package org.opencode.mobile.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Контракт device-flow: формы запросов, разбор кода и разбор ответа на опрос.
 *
 * Тесты повторяют тела, которые прислал живой Яндекс, а не выдуманные примеры: формат
 * `/device/code` и `/token` в документации описан двумя разными способами, и проверять
 * придётся именно то, что сервер принимает на самом деле.
 */
class YandexDeviceAuthTest {
    private val codeBody =
        """{"device_code":"dc-32-chars","user_code":"jq7ivm4b",""" +
            """"verification_url":"https://ya.ru/device","interval":5,"expires_in":300}"""

    @Test
    fun `device code form carries no client secret`() {
        val body = YandexDeviceAuth.deviceCodeForm("opencode1")

        assertTrue(body.contains("client_id=${YandexOAuth.CLIENT_ID}"))
        assertTrue(body.contains("device_id=opencode1"))
        assertTrue(body.contains("device_name=${YandexDeviceAuth.DEVICE_NAME}"))
        // Живой запросом подтверждено: `/device/code` секрета не требует. Секрет здесь
        // был бы не защитой, а лишним следом в дампе запроса.
        assertFalse(body.contains("client_secret"))
    }

    /**
     * Сторож против повторения расхождения: device-flow обязан идти от той же регистрации,
     * что и PKCE. Раньше у него был свой литерал, и он молча уехал на чужой `client_id` —
     * тесты были зелёные, формы собирались, а вход шёл не от того приложения.
     */
    @Test
    fun `device flow identifies as the same app as pkce`() {
        assertEquals(YandexOAuth.CLIENT_ID, YandexDeviceAuth.clientId)
    }

    @Test
    fun `device id is ten characters from the alnum alphabet`() {
        val id = YandexDeviceAuth.newDeviceId()

        assertEquals(10, id.length)
        assertTrue(id.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" })
    }

    @Test
    fun `each device id is fresh`() {
        val ids = (1..25).map { YandexDeviceAuth.newDeviceId() }.toSet()

        // Постоянство хранилища проверяется в YandexTokenStore, здесь — что генератор
        // вообще не выдаёт заведомо одинаковые значения.
        assertEquals(25, ids.size)
    }

    @Test
    fun `device token form asks for the device_code grant`() {
        val body = YandexDeviceAuth.deviceTokenForm("dc-32-chars")

        assertTrue(body.contains("grant_type=device_code"))
        assertTrue(body.contains("code=dc-32-chars"))
        assertTrue(body.contains("client_id=${YandexOAuth.CLIENT_ID}"))
        // Секрет на `/token` обязателен, в отличие от `/device/code`. Проверено вживью:
        // без него Яндекс отвечает `invalid_client: Wrong client secret` и до гранта не доходит.
        assertTrue(body.contains("client_secret=${YandexOAuth.CLIENT_SECRET}"))
    }

    @Test
    fun `device refresh form is not the pkce one`() {
        val device = YandexDeviceAuth.deviceRefreshForm("rt-1")
        val pkce = YandexOAuth.refreshForm("rt-1")

        assertTrue(device.contains("grant_type=refresh_token"))
        assertTrue(device.contains("refresh_token=rt-1"))
        assertTrue(device.contains("client_secret"))
        // Ключевое различие: у PKCE секрета быть не должно, иначе refresh упадёт через год.
        assertFalse(pkce.contains("client_secret"))
        assertNotEquals(device, pkce)
    }

    @Test
    fun `basic authorization is base64 of the registered client pair`() {
        val header = YandexDeviceAuth.basicAuthorization()

        assertTrue(header.startsWith("Basic "))
        val decoded =
            String(
                Base64.getDecoder().decode(header.removePrefix("Basic ")),
                StandardCharsets.UTF_8,
            )
        assertEquals("${YandexOAuth.CLIENT_ID}:${YandexOAuth.CLIENT_SECRET}", decoded)
    }

    @Test
    fun `device code parsing reads the live response fields`() {
        val code = YandexDeviceAuth.parseDeviceCode(codeBody)

        assertEquals("dc-32-chars", code.deviceCode)
        assertEquals("jq7ivm4b", code.userCode)
        assertEquals(5, code.intervalSeconds)
        assertEquals(300, code.expiresInSeconds)
    }

    @Test
    fun `verification url is taken from the response rather than assumed`() {
        // Живой Яндекс отдаёт `https://ya.ru/device`, а не `yandex.ru/auth/device`,
        // как учат примеры. Собранная нами ссылка уехала бы в 404 вместе со всем входом.
        assertEquals("https://ya.ru/device", YandexDeviceAuth.parseDeviceCode(codeBody).verificationUrl)
    }

    @Test
    fun `empty verification url falls back to the human page not the api endpoint`() {
        // Код вводится на `ya.ru/device`. Запасная ссылка на `oauth.yandex.ru/device/code`
        // привела бы юзера на API-эндпоинт, где нечего вводить, — и вход умер бы молча,
        // хотя всё, что от него нужно, происходило.
        val code = YandexDeviceAuth.parseDeviceCode("""{"device_code":"dc","user_code":"uc"}""")

        assertEquals("https://ya.ru/device", code.verificationUrl)
    }

    @Test
    fun `missing polling hints fall back to the values yandex sends today`() {
        val code = YandexDeviceAuth.parseDeviceCode("""{"device_code":"dc","user_code":"uc"}""")

        assertEquals(5, code.intervalSeconds)
        assertEquals(300, code.expiresInSeconds)
    }

    @Test
    fun `a zero interval is raised to one second`() {
        // Нулевой интервал превратил бы опрос в плотный цикл по `/token` и гарантированно
        // привёл бы к отказу `slow_down` — то есть к наказанию за честный ответ сервера.
        val code = YandexDeviceAuth.parseDeviceCode("""{"device_code":"dc","user_code":"uc","interval":0}""")

        assertEquals(1, code.intervalSeconds)
    }

    @Test
    fun `device code without a code is rejected`() {
        assertTrue(runCatching { YandexDeviceAuth.parseDeviceCode("""{"user_code":"uc"}""") }.isFailure)
        assertTrue(runCatching { YandexDeviceAuth.parseDeviceCode("""{"device_code":"dc"}""") }.isFailure)
    }

    @Test
    fun `expiry is counted from the moment the code was issued`() {
        val code = YandexDeviceAuth.parseDeviceCode(codeBody)

        assertEquals(1_000L + 300_000L, code.expiresAtMillis(1_000L))
    }

    @Test
    fun `a token body means the user confirmed the login`() {
        val poll = YandexDeviceAuth.poll("""{"access_token":"at","refresh_token":"rt","expires_in":31536000}""")

        val granted = poll as YandexDeviceAuth.Poll.Granted
        assertEquals("at", granted.token.accessToken)
        // Refresh приходит с device-токеном: без него год спустя пришлось бы входить заново.
        assertEquals("rt", granted.token.refreshToken)
    }

    @Test
    fun `authorization_pending and slow_down are both waiting but distinct`() {
        // Различать их нужно: «пользователь ещё не подтвердил» и «вы слишком часто
        // спрашиваете» требуют от агента противоположного — ждать по плану или отступить.
        // Если оба станут отказом, успешный вход отменится сам собой на середине; если оба
        // станут одним ожиданием — темп не изменится и slow_down будет повторяться вечно.
        val pending = YandexDeviceAuth.poll("""{"error":"authorization_pending"}""")
        val slow = YandexDeviceAuth.poll("""{"error":"slow_down"}""")

        assertEquals(YandexDeviceAuth.Poll.Pending, pending)
        assertEquals(YandexDeviceAuth.Poll.SlowDown, slow)
    }

    @Test
    fun `a real rejection keeps the yandex error name`() {
        val poll = YandexDeviceAuth.poll("""{"error":"invalid_grant","error_description":"code expired"}""")

        val rejected = poll as YandexDeviceAuth.Poll.Rejected
        assertTrue(rejected.reason.contains("code expired"))
    }

    @Test
    fun `a non-json body is a rejection instead of a crash`() {
        // 502 от Яндекса приходит html'ом. Упавший здесь JSONObject унёс бы весь вход
        // вместо того, чтобы отдать агенту понятный отказ и попросить повторить.
        val poll = YandexDeviceAuth.poll("<html>bad gateway</html>")

        val rejected = poll as YandexDeviceAuth.Poll.Rejected
        assertTrue(rejected.reason.isNotBlank())
    }
}
