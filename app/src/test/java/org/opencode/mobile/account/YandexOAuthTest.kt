package org.opencode.mobile.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт OAuth-формул Яндекса: PKCE, тело обмена, разбор ответа и разбор ошибок.
 *
 * Проверка [pkce challenge matches the RFC 7636 test vector] не украшение: если наш
 * `code_challenge` не совпадёт с эталоном из спецификации, Яндекс отклонит код на
 * обмене, и это выглядело бы как «Яндекс сломался», хотя сломались мы.
 */
class YandexOAuthTest {
    @Test
    fun `pkce challenge matches the RFC 7636 test vector`() {
        // Приложение B RFC 7636 - официальный вектор, а не пример из документации Яндекса.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", YandexOAuth.challengeFor(verifier))
    }

    @Test
    fun `pkce verifier stays inside the RFC 7636 allowed range and alphabet`() {
        val pkce = YandexOAuth.newPkce()

        assertEquals(64, pkce.verifier.length)
        assertTrue(pkce.verifier.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~" })
        // Длина verifier попадает в требуемые 43..128, иначе Яндекс отвергнет запрос.
        assertTrue(pkce.verifier.length in 43..128)
    }

    @Test
    fun `each authorization gets a fresh state`() {
        val states = (1..25).map { YandexOAuth.newPkce().state }.toSet()

        assertEquals(25, states.size)
        assertTrue(states.all { it.isNotBlank() })
    }

    @Test
    fun `authorize url carries every required oauth parameter`() {
        val url = YandexOAuth.authorizeUrl(YandexOAuth.newPkce())

        assertTrue(url.startsWith(YandexOAuth.AUTHORIZE_URL))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=${YandexOAuth.CLIENT_ID}"))
        assertTrue(url.contains("scope=login%3Ainfo"))
        assertTrue(url.contains("code_challenge_method=S256"))
        // Схема redirect уходит в запрос закодированной: иначе Яндекс не примет заявку.
        assertTrue(url.contains("redirect_uri=org.opencode.mobile%3A%2F%2Foauth"))
    }

    @Test
    fun `token exchange sends the verifier and never a client secret`() {
        val body = YandexOAuth.tokenForm("code-1", "verifier-1")

        assertTrue(body.contains("grant_type=authorization_code"))
        assertTrue(body.contains("code=code-1"))
        assertTrue(body.contains("code_verifier=verifier-1"))
        assertTrue(body.contains("client_id=${YandexOAuth.CLIENT_ID}"))
        // Секрет в приложении отсутствует намеренно, и его не должно даже появляться в теле.
        assertFalse(body.contains("client_secret"))
    }

    @Test
    fun `refresh body carries the refresh token and no verifier`() {
        val body = YandexOAuth.refreshForm("refresh-1")

        assertTrue(body.contains("grant_type=refresh_token"))
        assertTrue(body.contains("refresh_token=refresh-1"))
        assertFalse(body.contains("code_verifier"))
        assertFalse(body.contains("client_secret"))
    }

    @Test
    fun `callback parsing reads code and state`() {
        val callback = YandexOAuth.parseCallback("org.opencode.mobile://oauth?code=abc&state=xyz")

        assertEquals("abc", callback.code)
        assertEquals("xyz", callback.state)
        assertFalse(callback.failed)
    }

    @Test
    fun `callback parsing keeps the extra cid parameter out of the way`() {
        val callback = YandexOAuth.parseCallback("org.opencode.mobile://oauth?cid=1700000000&code=abc&state=xyz")

        assertEquals("abc", callback.code)
        assertEquals("xyz", callback.state)
    }

    @Test
    fun `callback parsing reports a denied consent as a failure`() {
        val callback =
            YandexOAuth.parseCallback("org.opencode.mobile://oauth?error=access_denied&error_description=user+said+no")

        assertTrue(callback.failed)
        assertEquals("access_denied", callback.error)
        assertEquals("user said no", callback.errorDescription)
        assertNull(callback.code)
    }

    @Test
    fun `token response is read from the documented fields`() {
        val token =
            YandexOAuth.parseToken(
                """{"access_token":"at","refresh_token":"rt","expires_in":31536000,"scope":"login:info,music:api"}""",
            )

        assertEquals("at", token.accessToken)
        assertEquals("rt", token.refreshToken)
        assertEquals(31536000L, token.expiresInSeconds)
    }

    @Test
    fun `token response without a refresh token is still usable`() {
        val token = YandexOAuth.parseToken("""{"access_token":"at","expires_in":3600}""")

        assertEquals("at", token.accessToken)
        // Refresh может не прийти - тогда приложение обязано честно сказать, что обновлять нечем.
        assertEquals("", token.refreshToken)
    }

    @Test
    fun `token response without an access token is rejected`() {
        val failure = runCatching { YandexOAuth.parseToken("""{"error":"invalid_grant"}""") }

        assertTrue(failure.isFailure)
    }

    @Test
    fun `error text is read from each yandex host shape`() {
        // oauth.yandex.ru/token
        assertEquals(
            "HTTP 400: refresh token expired",
            YandexOAuth.describeError(400, """{"error":"invalid_grant","error_description":"refresh token expired"}"""),
        )
        // login.yandex.ru/info
        assertEquals(
            "HTTP 401: NOT_AUTHORIZED",
            YandexOAuth.describeError(401, """{"code":"NOT_AUTHORIZED","message":"no token"}"""),
        )
        // api.music.yandex.net
        assertEquals(
            "HTTP 403: This is not authorized",
            YandexOAuth.describeError(403, """{"status":403,"error":"This is not authorized","requestId":"abc"}"""),
        )
    }

    @Test
    fun `error text falls back to the status when the body says nothing`() {
        assertEquals("HTTP 500", YandexOAuth.describeError(500, ""))
        assertEquals("HTTP 502", YandexOAuth.describeError(502, "<html>bad gateway</html>"))
    }
}
