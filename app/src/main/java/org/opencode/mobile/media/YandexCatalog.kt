package org.opencode.mobile.media

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors

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
// Один открытый API и его формы ответа держим в одном объекте: вынос сети и парсинга по
// отдельным объектам разбросает по проекту одно и то же знание о shape каталога.
@Suppress("TooManyFunctions")
object YandexCatalog {
    /** Чем разобран запрос поиска: по названию или по каталожному id. */
    const val RESOLVED_BY_ID = "id"
    const val RESOLVED_BY_TEXT = "text"

    private const val API = "https://api.music.yandex.net"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val USER_AGENT = "opencode-mobile/1.0"
    private const val TRACK_LIMIT = 20
    private const val MAX_SEARCH_LIMIT = 20

    // Сколько треков одной страницы тянем одновременно. 6 - компромисс: 50 треков успевают
    // за 10-секундный бюджет моста, а Яндекс не успевает срезать нас по лимиту запросов.
    private const val RESOLVE_CONCURRENCY = 6
    private const val MAX_ID_LENGTH = 32
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
     *
     * [exactTrackId] отвечает на вопрос, который выдача сама не отвечает: есть ли трек, названный
     * ровно как запрос. Без него «включи Get Low Remix» и «включи Busta Rymes Get Low Remix» выглядят
     * одинаково — а это разные вещи, и второй запрос в каталоге просто не существует.
     */
    data class Result(
        val query: String,
        val artist: CatalogArtist?,
        val tracks: List<CatalogTrack>,
        val exactTrackId: String? = null,
        val resolvedBy: String = RESOLVED_BY_TEXT,
    )

    fun search(
        query: String,
        limit: Int = TRACK_LIMIT,
    ): Result {
        val text = query.trim()
        require(text.isNotEmpty()) { "search query is empty" }
        val id = text.takeIf(::isTrackId)
        // Запрос-сам-id: ответ точный по построению, а не «трек с похожим названием». Не
        // нашлось — честно пусто, а не чужая дорожка из выдачи.
        if (id != null) {
            val found = track(id).firstOrNull()
            return Result(text, null, found?.let(::listOf) ?: emptyList(), found?.id, RESOLVED_BY_ID)
        }
        return searchText(text, limit)
    }

    /**
     * Каталожные id → треки, для библиотеки Яндекса.
     *
     * Отдельный публичный вход нужен избранному: там приходят id пачкой, и держи второе
     * место, где знают про форму каталога, нельзя. Батчем Яндекс не берёт — `/tracks/a,b`
     * отвечает `validate`, `/tracks/a.b` отдаёт 400, — поэтому id запрашиваются по одному.
     * Порядок входа сохраняется, недоступные (удалённые/региональные) пропускаются: лучше
     * короткий список, чем запись с пустым названием, по которой потом нельзя ничего играть.
     */
    fun resolveTracks(ids: List<String>): List<CatalogTrack> {
        val wanted = ids.map { it.trim() }.filter(::isTrackId).distinct()
        if (wanted.isEmpty()) return emptyList()
        val pool = resolveInParallel(wanted)
        return wanted.mapNotNull(pool::get)
    }

    /**
     * Разбор пачки треков ограниченным числом потоков.
     *
     * Запросы независимы, а бюджет жёсткий: мост отдаёт ответ клиенту за 10 секунд, и
     * по одному запросу на трек 50 треков в него не укладываются - страница падала бы по
     * таймауту. Ширина фиксированная, потому что неконтролируемая пачка запросов к
     * Яндексу заканчивается либо 429, либо баном аккаунта.
     *
     * Пул закрывается вручную, а не через `use`: `ExecutorService` стал `AutoCloseable`
     * только в Java 19, и на старых Android `use` здесь не собрался бы.
     */
    private fun resolveInParallel(ids: List<String>): Map<String, CatalogTrack?> {
        val executor = Executors.newFixedThreadPool(RESOLVE_CONCURRENCY)
        try {
            val futures =
                ids.map { id ->
                    id to executor.submit<CatalogTrack?> { runCatching { track(id) }.getOrNull()?.firstOrNull() }
                }
            return futures.associate { (id, future) -> id to future.get() }
        } finally {
            executor.shutdown()
        }
    }

    /** Поиск по названию: единственный путь, где нужен разбор выдачи. */
    private fun searchText(
        text: String,
        limit: Int,
    ): Result {
        // `page` обязателен: без него каталог отвечает 400 и пустым result.
        val url = "/search?text=${URLEncoder.encode(text, StandardCharsets.UTF_8.name())}" +
            "&type=all&page=0&sortBy=relevance"
        val result = getJson(url).optJSONObject("result") ?: JSONObject()
        // `best` — это конверт: {type, result}. Если запрос про артиста, то треки берём из его
        // карточки, а не из выдачи: у выдачи они перемешаны с чужими.
        val artist = result
            .optJSONObject("best")
            ?.takeIf { it.optString("type") == "artist" }
            ?.optJSONObject("result")
            ?.let { readArtist(it) }
        if (artist != null) return Result(text, artist, artistTracks(artist.id, limit))
        val found = readTracks(result.optJSONObject("tracks")?.optJSONArray("results") ?: JSONArray())
            .take(limit.coerceIn(1, MAX_SEARCH_LIMIT))
        return Result(text, null, found, exactTrackId(found, text))
    }

    /**
     * Трек по его каталожному id. Тот же открытый каталог, только адресной запрос: агент получает
     * id из ссылки, из сообщения пользователя или из прошлого поиска и хочет узнать, что это.
     */
    private fun track(trackId: String): List<CatalogTrack> {
        val id = trackId.trim()
        require(isTrackId(id)) { "track id must be numeric" }
        return readTracks(getJson("/tracks/$id").optJSONArray("result") ?: JSONArray())
    }

    /**
     * Трек с ровно таким названием, как запрос. Сравнение строгое, без угадывания: разница между
     * «есть такой трек» и «нашлось что-то похожее» слишком дорогая, чтобы её размывать.
     */
    fun exactTrackId(
        tracks: List<CatalogTrack>,
        query: String,
    ): String? {
        val wanted = query.trim()
        if (wanted.isEmpty()) return null
        return tracks.firstOrNull { it.title.trim().equals(wanted, ignoreCase = true) }?.id
    }

    /** Каталожные id — только цифры; такой запрос не может быть названием. */
    internal fun isTrackId(value: String): Boolean {
        if (value.isEmpty() || value.length > MAX_ID_LENGTH) return false
        return value.all(Char::isDigit)
    }

    /** Треки артиста: `/artists/{id}/tracks` отдаёт их без всякой авторизации. */
    fun artistTracks(
        artistId: String,
        limit: Int = TRACK_LIMIT,
    ): List<CatalogTrack> {
        // Каталог сам зажимает страницу на 20, а limit агента — ещё ниже: режем у себя.
        val page = limit.coerceIn(1, MAX_SEARCH_LIMIT)
        val root = getJson("/artists/$artistId/tracks?page=0&perPage=$page")
        val result = root.optJSONObject("result") ?: return emptyList()
        val raw = result.optJSONArray("tracks") ?: result.optJSONArray("collection")
        return readTracks(raw ?: JSONArray()).take(page)
    }

    internal fun readArtist(node: JSONObject): CatalogArtist? {
        val id = node.optString(JSON_ID).takeIf(String::isNotEmpty)
        val name = node.optString(JSON_NAME).takeIf(String::isNotEmpty)
        if (id == null || name == null) return null
        val counts = node.optJSONObject("counts")
        return CatalogArtist(id, name, counts?.optInt(TRACKS_COUNT) ?: 0)
    }

    internal fun readTracks(array: JSONArray): List<CatalogTrack> =
        (0 until array.length()).mapNotNull { index ->
            val node = array.optJSONObject(index) ?: return@mapNotNull null
            val id = node.optString("realId").takeIf(String::isNotEmpty) ?: node.optString("id")
            val title = node.optString(JSON_TITLE).trim()
            // На несуществующий id каталог отвечает узлом, где id есть, а остального нет. Такой
            // трек нечего ни показать, ни сверить, и «точное совпадение» из него делать нельзя —
            // поэтому он отбрасывается, а не попадает в выдачу пустым рядом.
            if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
            val durationMs = node.optLong("durationMs", 0L)
            CatalogTrack(
                id = id,
                title = title,
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
            append(" [")
            append(result.resolvedBy)
            append("]")
            append(" -> ")
            append(result.artist?.let { "artist ${it.name} [${it.id}]; " } ?: "")
            append("${result.tracks.size} tracks")
            append(result.exactTrackId?.let { "; exact=$it" } ?: "; exact=none")
            append(": ")
            append(head)
        }.lowercase(Locale.ROOT)
    }
}
