package org.opencode.mobile.media

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Трек каталога Яндекс Музыки в том виде, в котором его можно отдать сессии. */
data class CatalogTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val available: Boolean,
) {
    val deepLink: String get() = TRACK_DEEP_LINK_PREFIX + id
}

/** Артист каталога: id нужен, чтобы забрать его треки отдельным запросом. */
data class CatalogArtist(
    val id: String,
    val name: String,
    val tracksCount: Int,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("tracks_count", tracksCount)
}

/**
 * Публичный каталог Яндекс Музыки.
 *
 * Отдельный поиск по артистам без токена отдаёт только служебный идентификатор запроса, а вот
 * `type=all` возвращает треки и блок `best`, в котором лежит артист, если запрос про артиста.
 * Отсюда и порядок: сначала `best`, и только когда это артист — добираем его треки по id.
 *
 * Здесь нет авторизации намеренно: каталог открыт, а избранное живёт в сессии, а не в REST.
 */
object YandexCatalog {
    private const val API = "https://api.music.yandex.net"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val USER_AGENT = "opencode-mobile/1.0"
    private const val TRACK_LIMIT = 20
    private const val MAX_SEARCH_LIMIT = 20
    private const val HTTP_OK = 200
    private const val HTTP_MAX = 299
    private const val JSON_ID = "id"
    private const val JSON_TITLE = "title"
    private const val JSON_NAME = "name"
    private const val JSON_AVAILABLE = "available"
    private const val TRACKS_COUNT = "tracks"
    private const val LOG_TRACKS = 3

    /**
     * Что нашлось: артист (если запрос был про него) и треки. Треки берём и из выдачи, и из
     * карточки артиста — иначе «включи артиста» на запросе-артисте нечего было бы играть.
     */
    data class Result(
        val query: String,
        val artist: CatalogArtist?,
        val tracks: List<CatalogTrack>,
    )

    fun search(
        query: String,
        limit: Int = TRACK_LIMIT,
    ): Result {
        val text = query.trim()
        require(text.isNotEmpty()) { "search query is empty" }
        val root = getJson("/search?text=${encode(text)}&type=all&page=0&sortBy=relevance")
        val result = root.optJSONObject("result") ?: JSONObject()
        // `best` — это конверт: {type, result}. Если запрос про артиста, то треки берём из его
        // карточки, а не из выдачи: у выдачи они перемешаны с чужими.
        val best = result.optJSONObject("best")?.takeIf { it.optString("type") == "artist" }
        val artist = best?.optJSONObject("result")?.let { readArtist(it) }
        if (artist != null) {
            return Result(text, artist, artistTracks(artist.id, limit))
        }
        val tracks = result.optJSONObject("tracks")?.optJSONArray("results") ?: JSONArray()
        return Result(text, null, readTracks(tracks).take(capped(limit)))
    }

    /** Треки артиста: `/artists/{id}/tracks` отдаёт их без всякой авторизации. */
    fun artistTracks(
        artistId: String,
        limit: Int = TRACK_LIMIT,
    ): List<CatalogTrack> {
        val page = capped(limit)
        val root = getJson("/artists/$artistId/tracks?page=0&perPage=$page")
        val result = root.optJSONObject("result") ?: return emptyList()
        val raw = result.optJSONArray("tracks") ?: result.optJSONArray("collection")
        return readTracks(raw ?: JSONArray()).take(page)
    }

    /** Каталог сам зажимает страницу на 20, а limit агента — ещё ниже: режем у себя. */
    private fun capped(limit: Int): Int = limit.coerceIn(1, MAX_SEARCH_LIMIT)

    private fun readArtist(node: JSONObject): CatalogArtist? {
        val id = node.optString(JSON_ID).takeIf(String::isNotEmpty)
        val name = node.optString(JSON_NAME).takeIf(String::isNotEmpty)
        if (id == null || name == null) return null
        val counts = node.optJSONObject("counts")
        return CatalogArtist(id, name, counts?.optInt(TRACKS_COUNT) ?: 0)
    }

    private fun readTracks(array: JSONArray): List<CatalogTrack> =
        (0 until array.length()).mapNotNull { index ->
            val node = array.optJSONObject(index) ?: return@mapNotNull null
            val id = node.optString("realId").takeIf(String::isNotEmpty) ?: node.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            val durationMs = node.optLong("durationMs", 0L)
            CatalogTrack(
                id = id,
                title = node.optString(JSON_TITLE),
                artist = readArtists(node),
                album = readAlbum(node),
                durationMs = durationMs,
                available = node.optBoolean(JSON_AVAILABLE, true),
            )
        }

    /** Альбом приходит массивом `albums`, а не строкой: у трека их может быть несколько. */
    private fun readAlbum(node: JSONObject): String {
        val albums = node.optJSONArray("albums")
        if (albums != null && albums.length() > 0) {
            val first = albums.optJSONObject(0)?.optString(JSON_TITLE)
            if (!first.isNullOrEmpty()) return first
        }
        return node.optString("albumTitle")
    }

    /** У трека бывает compound-версия с `artists` и обычная с одним `artist`. */
    private fun readArtists(node: JSONObject): String {
        val list = node.optJSONArray("artists")
        if (list != null && list.length() > 0) {
            return (0 until list.length())
                .mapNotNull { list.optJSONObject(it)?.optString("name") }
                .filter(String::isNotEmpty)
                .joinToString(", ")
        }
        return node.optString("artist")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun getJson(path: String): JSONObject {
        val connection = URI.create(API + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val body = (if (code in HTTP_OK..HTTP_MAX) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (code !in HTTP_OK..HTTP_MAX) {
                throw IOException("Yandex Music catalog returned HTTP $code")
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    /** Имя для логов и ошибок: без префикса «ru.yandex» его легко спутать с пакетом. */
    fun describe(result: Result): String {
        val head = result.tracks.take(LOG_TRACKS).joinToString("; ") { "${it.artist} - ${it.title}" }
        return buildString {
            append(result.query)
            append(" -> ")
            append(result.artist?.let { "artist ${it.name} [${it.id}]; " } ?: "")
            append("${result.tracks.size} tracks: ")
            append(head)
        }.lowercase(Locale.ROOT)
    }
}
