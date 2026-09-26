package org.opencode.mobile.account

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Библиотека «Моего плейлиста»: id треков в порядке Яндекса плюс ревизия для снятия кэша. */
data class LikedLibrary(
    val uid: String,
    val revision: Long,
    val trackIds: List<String>,
) {
    val size: Int get() = trackIds.size
}

/**
 * Сеть до Яндекса: обмен кода на токен, refresh, профиль и «Мой плейлист».
 *
 * Обмен кода и refresh ходят на `oauth.yandex.ru` и требуют тела `application/x-www-form-urlencoded`;
 * профиль и библиотека — на других хостах и требуют заголовка `Authorization`. Требования
 * разные, поэтому здесь один общий `request`, но две разные сборки заголовков — смешивать
 * их в одну сущность нельзя, а дублировать соединение целиком тоже незачем.
 *
 * [likedTrackIds] отдаёт **все** треки разом: `page`/`perPage` у метода нет, сервер их
 * игнорирует, а ответ на 2500+ треков большой. Поэтому страницы нарезает вызывающий код,
 * уже сжав ответ до списка id — метаданные запрашиваются отдельно и только для нужной страницы.
 */
object YandexAccountClient {
    private const val MUSIC_API = "https://api.music.yandex.net"
    private const val USER_AGENT = "opencode-mobile/1.0"
    private const val MUSIC_CLIENT = "YandexMusicAndroid/24023621"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=UTF-8"
    private const val HTTP_OK = 200
    private const val HTTP_MAX = 299
    private const val TOKEN_BODY = "token"
    private const val INFO_BODY = "info"
    private const val LIKES_BODY = "likes"
    private const val REFRESH_BODY = "refresh"
    private const val EXPIRY_SLACK_MILLIS = 60_000L

    fun exchangeCode(
        code: String,
        verifier: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Pair<YandexOAuth.TokenResponse, YandexToken> {
        val response = YandexOAuth.parseToken(postForm(TOKEN_BODY, YandexOAuth.tokenForm(code, verifier)))
        return response to response.toToken(nowMillis)
    }

    fun refresh(
        refreshToken: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Pair<YandexOAuth.TokenResponse, YandexToken> {
        val response =
            YandexOAuth.parseToken(postForm(REFRESH_BODY, YandexOAuth.refreshForm(refreshToken)))
        return response to response.toToken(nowMillis)
    }

    /**
     * Кто вошёл. У Яндекса два равных идентификатора — `id` здесь и `library.uid` в
     * плейлисте — и живьём мы оба проверили совпадение; берём профиль как источник истины.
     */
    fun identity(accessToken: String): YandexIdentity {
        val json = get(YandexOAuth.INFO_URL, accessToken, INFO_BODY)
        val login = json.optString("login").trim()
        val id = json.optString("id").trim()
        if (login.isEmpty() || id.isEmpty()) {
            throw IOException("Yandex profile has no login/id: ${json.optString("error").ifEmpty { "empty" }}")
        }
        return YandexIdentity(login = login, uid = id)
    }

    fun likedTrackIds(
        accessToken: String,
        login: String,
    ): LikedLibrary {
        val encoded = URLEncoder.encode(login, StandardCharsets.UTF_8.name())
        val json = get("$MUSIC_API/users/$encoded/likes/tracks", accessToken, LIKES_BODY)
        val library = json.optJSONObject("result")?.optJSONObject("library")
            ?: throw IOException("Yandex liked tracks response has no result.library")
        val tracks = library.optJSONArray("tracks")
        val ids = (0 until (tracks?.length() ?: 0))
            .mapNotNull { tracks?.optJSONObject(it)?.optString("id")?.trim() }
            .filter(String::isNotEmpty)
        return LikedLibrary(
            uid = library.optString("uid").trim().ifEmpty { login },
            revision = library.optLong("revision", 0L),
            trackIds = ids,
        )
    }

    private fun YandexOAuth.TokenResponse.toToken(nowMillis: Long): YandexToken =
        YandexToken(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMillis = nowMillis + expiresInSeconds * 1000L - EXPIRY_SLACK_MILLIS,
        )

    private fun postForm(
        what: String,
        form: String,
    ): String {
        val connection = URI.create(YandexOAuth.TOKEN_URL).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Content-Type", FORM_CONTENT_TYPE)
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(form.toByteArray(StandardCharsets.UTF_8)) }
            return readBody(connection, what)
        } finally {
            connection.disconnect()
        }
    }

    private fun get(
        url: String,
        accessToken: String,
        what: String,
    ): JSONObject {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            // Именно так Яндекс ждёт доступ: префикс `OAuth` в заголовке, не «Bearer».
            connection.setRequestProperty("Authorization", "OAuth $accessToken")
            connection.setRequestProperty("X-Yandex-Music-Client", MUSIC_CLIENT)
            return JSONObject(readBody(connection, what))
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(
        connection: HttpURLConnection,
        what: String,
    ): String {
        val code = connection.responseCode
        val body = (if (code in HTTP_OK..HTTP_MAX) connection.inputStream else connection.errorStream)
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
        if (code !in HTTP_OK..HTTP_MAX) {
            // Текст ошибки отдаём разобранным: у хостов Яндекса три разных формата тела,
            // и без их разбора в лог уходит голый «HTTP 401».
            throw IOException("Yandex $what failed: ${YandexOAuth.describeError(code, body)}")
        }
        return body
    }
}
