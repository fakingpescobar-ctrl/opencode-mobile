package org.opencode.mobile.account

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Разбор `result` из `/playlists/list`.
 *
 * Отдельная функция без сети, чтобы правила проверялись тестом: главное из них - строки без
 * названия выбрасываются. Название здесь не украшение, а единственное, по чему агент вообще
 * может показать плейлист человеку, и пустая строка в списке бесполезна вдвойме.
 *
 * `kind` берётся как есть, без проверки диапазона: Яндекс отдаёт и 0 (лайки), и собственные
 * 1000-1999, и чужие подборки, и отбраковка не наша задача - мы показываем то, что есть.
 */

/**
 * `diff` для вставки: массив с одной операцией `insert`.
 *
 * Вынесено отдельно от сети намеренно. Формат diff - это единственное место, где ошибка не
 * видна в коде: `albumId` вместо `album_id`, `op: "add"` вместо `insert`, `at` вместо `from`
 * собираются в осмысленный JSON и уезжают на сервер как есть. Яндекс отвечает 400 с текстом
 * про неизвестное поле, но написать такой тест дешевле, чем потом ловить 400 на живом аккаунте.
 *
 * `at` - позиция вставки в текущем плейлисте, не id трека.
 */
internal fun insertDiff(
    trackId: String,
    albumId: String,
    at: Int,
): String =
    JSONArray()
        .put(
            JSONObject()
                .put("op", "insert")
                .put("at", at.coerceAtLeast(0))
                .put("tracks", JSONArray().put(JSONObject().put("id", trackId).put("albumId", albumId))),
        ).toString()

/**
 * `diff` для удаления: полуинтервал `[from, to)`, поэтому один трек - это `from` и `from + 1`.
 *
 * Яндекс применяет операции к плейлисту, а не к id треков, так что границы обязаны быть
 * индексами уже прочитанного плейлиста - иначе `to` уедет на соседа.
 */
internal fun deleteDiff(
    from: Int,
    to: Int,
): String {
    require(from >= 0) { "from must not be negative, got $from" }
    require(to > from) { "delete range must not be empty: from=$from to=$to" }
    return JSONArray()
        .put(JSONObject().put("op", "delete").put("from", from).put("to", to))
        .toString()
}
internal fun readPlaylistSummaries(result: JSONArray): List<PlaylistSummary> =
    (0 until result.length())
        .mapNotNull { index -> result.optJSONObject(index) }
        .filter { it.optString("title").isNotBlank() }
        .map { item ->
            PlaylistSummary(
                kind = item.optInt("kind", -1),
                uuid = item.optString("playlistUuid").trim(),
                title = item.optString("title").trim(),
                trackCount = item.optInt("trackCount", 0),
                durationMs = item.optLong("durationMs", 0L),
            )
        }

/**
 * Разбор `result` из `/playlists/{kind}`.
 *
 * Треки лежат в `tracks[]`, а метаданные - в `tracks[].track`, и единственное, что приходится
 * восстанавливать руками, - это порядок: у каждой записи он свой, в [originalIndexes].
 * Пропуск записи с пустым `track.id` согласован по обеим спискам, иначе [originalIndexes]
 * разъехался бы с [trackIds] и позиция «третьим» указала бы не на тот трек.
 *
 * Отсутствие `originalIndex` подставляется позицией выдачи: лучше правдоподобный порядок,
 * чем дыры в нумерации, из-за которых сортировка агента поехала бы.
 */
internal fun readPlaylistLibrary(
    requestedKind: Int,
    result: JSONObject,
): PlaylistLibrary {
    val entries = result.optJSONArray("tracks")
    // id и позиция собираются одной записью и сортируются вместе - так они не могут разъехаться,
    // и после сортировки это ровно тот случай, где разъезжаются.
    //
    // Порядок берётся из `originalIndex`, а не из порядка выдачи, и это не косметика: head
    // плейлиста - это первые пять `trackIds`, подписанные позициями из `originalIndexes`.
    // При выдаче вперемешку мы бы поставили плейлист с середины, а потом обвинили его в
    // «ранние треки недоступны».
    val numbered =
        (0 until (entries?.length() ?: 0))
            .map { index ->
                val entry = entries?.optJSONObject(index)
                (entry?.playlistTrackId().orEmpty()) to (entry?.optInt("originalIndex", index) ?: index)
            }.filter { (id, _) -> id.isNotEmpty() }
            // sortedBy стабилен: треки с одинаковой позицией сохраняют порядок выдачи.
            .sortedBy { (_, position) -> position }
    return PlaylistLibrary(
        kind = result.optInt("kind", requestedKind),
        uuid = result.optString("playlistUuid").trim(),
        title = result.optString("title").trim(),
        revision = result.optLong("revision", 0L),
        trackIds = numbered.map { (id, _) -> id },
        originalIndexes = numbered.map { (_, position) -> position },
    )
}

private fun JSONObject.playlistTrackId(): String? = optJSONObject("track")?.optString("id")?.trim()?.ifEmpty { null }

/**
 * Запас до момента истечения.
 *
 * Токен, который формально ещё жив, но через секунду протухнет посреди нашего запроса,
 * оборачивается ошибкой 401 в самый неподходящий момент. Минута запаса означает, что мы
 * считаем токен протухшим чуть раньше Яндекса и обновляем его, не дожидаясь отказа.
 */
private const val EXPIRY_SLACK_MILLIS = 60_000L

/**
 * Ответ `/token` превращается в хранимый токен.
 *
 * Верхнеуровневая, а не метод [YandexAccountClient], потому что ею пользуется и контроллер:
 * member-расширение видно только внутри своего объекта, и контроллер, получив токен из
 * device-flow, не смог бы сохранить его вообще.
 *
 * [grant] едет вместе с токеном намеренно: обновлять его потом надо тем же способом, каким
 * он получен, а способ этот живёт не в настройках, а рядом с секретом.
 */
internal fun YandexOAuth.TokenResponse.toToken(
    nowMillis: Long,
    grant: YandexGrant,
): YandexToken =
    YandexToken(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtMillis = nowMillis + expiresInSeconds * 1000L - EXPIRY_SLACK_MILLIS,
        grant = grant,
    )

/** Библиотека «Моего плейлиста»: id треков в порядке Яндекса плюс ревизия для снятия кэша. */
data class LikedLibrary(
    val uid: String,
    val revision: Long,
    val trackIds: List<String>,
) {
    val size: Int get() = trackIds.size
}

/**
 * Плейлист после изменения.
 *
 * `trackCount` идёт из ответа, а не пересчитывается нами: Яндекс после вставки в середину
 * пересчитывает позиции сам, и число, посчитанное до запроса, было бы враньём на один трек.
 */
data class PlaylistChange(
    val kind: Int,
    val title: String,
    val revision: Long,
    val trackCount: Int,
)

/**
 * Один плейлист целиком: что это, какой у него id для адресации и в каком порядке идут треки.
 *
 * [originalIndexes] хранится рядом с [trackIds] и всегда той же длины: позиция в плейлисте —
 * это независимая от порядка выдачи величина, и потерять её можно только вместе с ней.
 */
data class PlaylistLibrary(
    val kind: Int,
    val uuid: String,
    val title: String,
    val revision: Long,
    val trackIds: List<String>,
    val originalIndexes: List<Int>,
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
    private const val PLAYLISTS_BODY = "playlists"
    private const val PLAYLIST_BODY = "playlist"
    private const val REFRESH_BODY = "refresh"
    private const val CHANGE_BODY = "playlist change"

    /**
     * Применяет `diff` к плейлисту: единственный способ изменить плейлист у Яндекса.
     *
     * Отдельного эндпоинта «добавить трек» или «удалить трек» не существует - любое
     * изменение идёт одним `POST .../playlists/{kind}/change`, а что именно менять, задаёт
     * `diff`. Он уходит **form-полем**, внутри которого лежит JSON-строка, то есть JSON
     * внутри form - двойного кодирования тут нет: кодирует только форма, ровно один раз.
     * Формат операций - [insertDiff] и [deleteDiff].
     *
     * `diff` собирают вызывающие: [PlaylistTarget.revision] обязателен и не имеет значения
     * по умолчанию намеренно - плейлист меняют и веб-версия, и другое устройство, а
     * молчаливая запись по чужой ревизии выглядит как «ничего не произошло».
     */
    fun applyDiff(
        accessToken: String,
        target: PlaylistTarget,
        diff: String,
    ): PlaylistChange {
        val form =
            listOf(
                "kind" to target.kind.toString(),
                "revision" to target.revision.toString(),
                "diff" to diff,
            ).joinToString("&") { (name, value) ->
                "${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }
        val response =
            JSONObject(
                postForm(
                    what = CHANGE_BODY,
                    url = "$MUSIC_API/users/${target.uid}/playlists/${target.kind}/change",
                    form = form,
                    accessToken = accessToken,
                ),
            )
        val result = response.optJSONObject("result")
            ?: throw IOException("Yandex playlist change response has no result object")
        return PlaylistChange(
            kind = result.optInt("kind", target.kind),
            title = result.optString("title").trim(),
            revision = result.optLong("revision", 0L),
            trackCount = result.optInt("trackCount", 0),
        )
    }

    /**
     * POST с form-encoded телом: [accessToken] добавляет заголовок авторизации, `null` - нет.
     *
     * Общий на все form-POST, потому что они отличаются только адресом и наличием токена, а
     * раздельные копии этого кода уже разошлись: версия для правки плейлиста забыла про
     * `X-Yandex-Music-Client`, который для music.api обязателен.
     */
    private fun postForm(
        what: String,
        url: String,
        form: String,
        accessToken: String? = null,
    ): String {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Content-Type", FORM_CONTENT_TYPE)
            connection.setRequestProperty("Accept", "application/json")
            if (accessToken != null) {
                connection.setRequestProperty("Authorization", "OAuth $accessToken")
                connection.setRequestProperty("X-Yandex-Music-Client", MUSIC_CLIENT)
            }
            connection.outputStream.use { it.write(form.toByteArray(StandardCharsets.UTF_8)) }
            return readBody(connection, what)
        } finally {
            connection.disconnect()
        }
    }

    fun exchangeCode(
        code: String,
        verifier: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Pair<YandexOAuth.TokenResponse, YandexToken> {
        val response =
            YandexOAuth.parseToken(
                postForm(
                    what = TOKEN_BODY,
                    url = YandexOAuth.TOKEN_URL,
                    form = YandexOAuth.tokenForm(code, verifier),
                ),
            )
        return response to response.toToken(nowMillis, YandexGrant.PKCE)
    }

    fun refresh(
        refreshToken: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Pair<YandexOAuth.TokenResponse, YandexToken> {
        val response =
            YandexOAuth.parseToken(
                postForm(
                    what = REFRESH_BODY,
                    url = YandexOAuth.TOKEN_URL,
                    form = YandexOAuth.refreshForm(refreshToken),
                ),
            )
        return response to response.toToken(nowMillis, YandexGrant.PKCE)
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

    /**
     * Все плейлисты аккаунта одним запросом, без пагинации: сервер отдаёт их списком и
     * `kinds`-фильтр здесь не нужен, потому что фильтровать будем по [PlaylistSummary.kind]
     * уже у нас — так агент получает в ответе ровно те строки, которые может адресовать.
     */
    fun playlists(
        accessToken: String,
        uid: String,
    ): List<PlaylistSummary> {
        val json = get("$MUSIC_API/users/$uid/playlists/list", accessToken, PLAYLISTS_BODY)
        val result = json.optJSONArray("result")
            ?: throw IOException("Yandex playlists response has no result array")
        return readPlaylistSummaries(result)
    }

    /**
     * Содержимое плейлиста.
     *
     * Адресуется [kind], а не [PlaylistSummary.uuid]: путь по uuid у Яндекса не существует
     * и отвечает 404, хотя uuid в списке и выглядит как главный идентификатор. Проверено на
     * живом аккаунте — именно поэтому в [PlaylistSummary] оба поля и помечены, чем является
     * каждое.
     *
     * Треки приходят уже с метаданными, но [trackIds] всё равно собирается отдельно: страницу
     * режет вызывающий код, и метаданные он дозапрашивает через [YandexCatalog] только для
     * нужного куска — большой ответ целиком в память не берём.
     */
    fun playlist(
        accessToken: String,
        uid: String,
        kind: Int,
    ): PlaylistLibrary {
        val json = get("$MUSIC_API/users/$uid/playlists/$kind", accessToken, PLAYLIST_BODY)
        val result = json.optJSONObject("result")
            ?: throw IOException("Yandex playlist $kind response has no result object")
        return readPlaylistLibrary(kind, result)
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
