package org.opencode.mobile.account

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Правило, по которому хранилище решает, что делать с refresh-токеном.
 *
 * Проверяется без Android именно потому, что цена ошибки здесь неочевидна: токен при
 * этом выглядит живым, доступ работает, и юзер узнаёт о потере только через год, когда
 * обновлять будет уже нечем. Молчаливый downgrade заметить трудно.
 */
class YandexTokenRefreshTest {
    @Test
    fun `a rotated refresh token replaces the previous one`() {
        assertEquals("rt-new", keepRefreshToken("rt-old", "rt-new"))
    }

    @Test
    fun `an omitted refresh token keeps the previous one`() {
        // Ровно этот случай ломает доступ, если не сохранить прежнее значение.
        assertEquals("rt-old", keepRefreshToken("rt-old", ""))
    }

    @Test
    fun `a blank refresh token keeps the previous one`() {
        assertEquals("rt-old", keepRefreshToken("rt-old", "   "))
    }

    @Test
    fun `a response with no refresh token at all keeps the previous one`() {
        assertEquals("rt-old", keepRefreshToken("rt-old", "\n\t "))
    }

    @Test
    fun `surrounding whitespace never becomes part of a token`() {
        assertEquals("rt-new", keepRefreshToken(null, "  rt-new  "))
    }

    @Test
    fun `with nothing stored an absent refresh token stays absent`() {
        // Первый вход: сохранять нечего, и выдумывать refresh нельзя - его выдаёт сервер.
        assertEquals("", keepRefreshToken(null, ""))
        assertEquals("", keepRefreshToken("", ""))
    }

    @Test
    fun `a token without a refresh token cannot be refreshed`() {
        val token = YandexToken(accessToken = "at", refreshToken = "", expiresAtMillis = Long.MAX_VALUE)

        assertEquals(false, token.canRefresh)
    }

    @Test
    fun `a token with a refresh token can be refreshed`() {
        val token = YandexToken(accessToken = "at", refreshToken = "rt", expiresAtMillis = Long.MAX_VALUE)

        assertEquals(true, token.canRefresh)
    }
}
