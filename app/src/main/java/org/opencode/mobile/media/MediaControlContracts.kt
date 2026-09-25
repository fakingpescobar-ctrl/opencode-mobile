package org.opencode.mobile.media

import java.util.Locale

/**
 * Команда транспортного контроля медиа-сессии.
 *
 * Коды аппаратных мультимедиа-кнопок здесь больше не нужны: запасной путь через
 * ACTION_MEDIA_BUTTON удалён, потому что платформа не маршрутизирует такой broadcast
 * в стороннем приложении (проверено на устройстве с Яндекс Музыкой).
 */
enum class MediaCommand(
    val wireName: String,
) {
    PLAY("play"),
    PAUSE("pause"),
    PLAY_PAUSE("play_pause"),
    NEXT("next"),
    PREVIOUS("previous"),
    STOP("stop"),
    ;

    companion object {
        private val ALIASES =
            mapOf(
                "play_pause" to PLAY_PAUSE,
                "playpause" to PLAY_PAUSE,
                "play-pause" to PLAY_PAUSE,
                "toggle" to PLAY_PAUSE,
                "play" to PLAY,
                "pause" to PAUSE,
                "resume" to PLAY,
                "next" to NEXT,
                "next_track" to NEXT,
                "skip" to NEXT,
                "previous" to PREVIOUS,
                "prev" to PREVIOUS,
                "back" to PREVIOUS,
                "stop" to STOP,
            )

        fun fromWireName(value: String?): MediaCommand? {
            val key = value?.trim()?.lowercase() ?: return null
            return ALIASES[key]
        }
    }
}

/** Оценка текущего трека: лайк/анлайк и dislike/undislike — кастомные команды сессии. */
data class MediaLikeSpec(
    val packageName: String,
    val action: String,
    val sessionAction: String,
)

/** Каноническая ссылка на трек каталога: её и проверяем на входе, и отдаём сессии. */
const val TRACK_DEEP_LINK_PREFIX = "yandexmusic://track/"

/** Запрос обхода дерева библиотеки плеера. */
data class MediaLibrarySpec(
    val packageName: String,
    val node: String?,
    val query: String?,
    val limit: Int,
)

/** Запрос поиска по публичному каталогу Яндекс Музыки. */
data class MediaSearchSpec(
    val query: String,
    val limit: Int,
)

/** Итог оценки трека: что и с каким треком мы отправили сессии. */
data class MediaLikeResult(
    val packageName: String,
    val label: String,
    val action: String,
    val ok: Boolean,
    val detail: String?,
    val playback: MediaPlaybackSnapshot,
)

/** Ответ поиска по каталогу: артист (если запрос был про него) и треки с их ссылками. */
data class MediaSearchResult(
    val query: String,
    val artist: CatalogArtist?,
    val tracks: List<CatalogTrack>,
)

/** Узел дерева библиотеки плеера: его же mediaId сессия потом принимает в setMediaItem. */
data class MediaLibraryEntry(
    val mediaId: String,
    val title: String,
    val subtitle: String,
    val browsable: Boolean,
    val playable: Boolean,
    val uri: String?,
)

/** Ответ обхода дерева: корень плюс содержимое узла, с честным кодом результата сессии. */
data class MediaLibraryResult(
    val root: MediaLibraryEntry?,
    val entries: List<MediaLibraryEntry>,
    val resultCode: Int?,
    val message: String,
)

/** Ответ сессии на кастомную команду: сессия могла её не знать, отказать или принять. */
data class MediaCustomCommandResult(
    val action: String,
    val ok: Boolean,
    val detail: String?,
)

/**
 * Возможности чужой сессии. Без этого списка нельзя отличить «приложение не умеет играть
 * трек по id» от «мы отправили команду не туда»: у Яндекс Музыки, например, сессия
 * транспортная и setMediaItem может быть недоступен целиком.
 */
data class MediaCapabilities(
    val playerCommands: List<String>,
    val sessionCommands: List<String>,
    val supportsSetMediaItem: Boolean,
    /** Код ошибки последней команды плеера: без него «трек не заиграл» нечем объяснить. */
    val playerError: String?,
)

/** Запрос на список приложений, способных управлять медиасессией. */
data class MediaAppListSpec(
    val query: String?,
    val limit: Int,
)

/** Проверенная команда управления медиа: точный package либо null для автоопределения. */
data class MediaControlSpec(
    val command: MediaCommand,
    val packageName: String?,
)

/**
 * Запрос «включи вот этот трек каталога». Пакет обязателен: id трека бессмыслен без
 * приложения-владельца сессии, а автоопределение тут выбрало бы не тот плеер.
 */
data class MediaPlaySpec(
    val packageName: String,
    val mediaId: String,
    val uri: String,
    val title: String?,
)

/** Граница доверия для команд локального MCP к медиа-мосту. */
object MediaControlRequestValidator {
    const val DEFAULT_LIST_LIMIT = 50
    const val MAX_LIST_LIMIT = 200
    const val DEFAULT_SEARCH_LIMIT = 10
    const val MAX_SEARCH_LIMIT = 20
    const val DEFAULT_LIBRARY_LIMIT = 20
    const val MAX_LIBRARY_LIMIT = 50

    private const val MAX_QUERY_LENGTH = 120
    private const val MAX_PACKAGE_LENGTH = 255
    private const val MAX_MEDIA_ID_LENGTH = 32
    private const val MAX_TITLE_LENGTH = 200
    private const val MAX_NODE_LENGTH = 512
    private val PACKAGE_PART = Regex("[A-Za-z][A-Za-z0-9_]*")
    private val TRACK_DEEP_LINK = Regex("yandexmusic(?:plus)?://track/(\\d+)")
    private val TRACK_PAGE = Regex("https://music\\.yandex\\.(?:ru|com)/track/(\\d+)")

    private val LIKE_ACTIONS =
        mapOf(
            "like" to "ADD_LIKE",
            "unlike" to "REMOVE_LIKE",
            "add_like" to "ADD_LIKE",
            "remove_like" to "REMOVE_LIKE",
            "dislike" to "ADD_DISLIKE",
            "undislike" to "REMOVE_DISLIKE",
            "add_dislike" to "ADD_DISLIKE",
            "remove_dislike" to "REMOVE_DISLIKE",
        )

    fun list(
        query: String?,
        limit: Int?,
    ): MediaAppListSpec {
        val cleanQuery = query?.trim()?.takeIf(String::isNotEmpty)
        require(cleanQuery == null || cleanQuery.length <= MAX_QUERY_LENGTH) {
            "media app search query is too long"
        }
        require(cleanQuery == null || cleanQuery.none { it.isISOControl() }) {
            "media app search query contains control characters"
        }
        val cleanLimit = limit ?: DEFAULT_LIST_LIMIT
        require(cleanLimit in 1..MAX_LIST_LIMIT) {
            "limit must be between 1 and $MAX_LIST_LIMIT"
        }
        return MediaAppListSpec(cleanQuery, cleanLimit)
    }

    fun control(
        action: String?,
        packageName: String?,
    ): MediaControlSpec {
        val command =
            MediaCommand.fromWireName(action)
                ?: throw IllegalArgumentException(
                    "action must be one of: ${MediaCommand.values().joinToString { it.wireName }}",
                )
        return MediaControlSpec(command, packageNameOrNull(packageName))
    }

    fun play(
        packageName: String?,
        mediaId: String?,
        uri: String?,
        title: String?,
    ): MediaPlaySpec {
        val pkg =
            packageNameOrNull(packageName)
                ?: throw IllegalArgumentException("package is required to play a specific item")
        val cleanId =
            mediaId?.trim()?.takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException("media_id is required to play a specific item")
        require(cleanId.length <= MAX_MEDIA_ID_LENGTH && cleanId.all(Char::isDigit)) {
            "media_id must be a numeric catalog id"
        }
        // Мост принимает только две собственные ссылки на трек: произвольный uri здесь был бы
        // способом заставить чужое приложение открыть любой контент. Если ссылки нет, строим
        // свою из уже проверенного числового id — агент часто знает только его.
        val rawUri = uri?.trim().orEmpty()
        if (rawUri.isNotEmpty()) {
            val match = TRACK_DEEP_LINK.matchEntire(rawUri) ?: TRACK_PAGE.matchEntire(rawUri)
            require(match != null && match.groupValues[1] == cleanId) {
                "uri must be yandexmusic://track/<media_id> or https://music.yandex.ru/track/<media_id>"
            }
        }
        // Форму URI не подменяем: плеер сам решает, что умеет — yandexmusic:// или страницу.
        // Раньше тут была безусловная подмена на deep link, и https-вариант нельзя было
        // даже проверить, хотя валидатор его разрешал.
        val cleanUri = rawUri.ifEmpty { TRACK_DEEP_LINK_PREFIX + cleanId }
        val cleanTitle = title?.trim()?.takeIf(String::isNotEmpty)
        require(cleanTitle == null || cleanTitle.length <= MAX_TITLE_LENGTH) { "title is too long" }
        require(cleanTitle == null || cleanTitle.none { it.isISOControl() }) {
            "title contains control characters"
        }
        return MediaPlaySpec(pkg, cleanId, cleanUri, cleanTitle)
    }

    fun status(packageName: String?): String? = packageNameOrNull(packageName)

    /**
     * Поиск по каталогу: без токена, только публичные данные. limit ограничен сверху, чтобы
     * агент не утащил в контекст весь каталог.
     */
    fun search(
        query: String?,
        limit: Int?,
    ): MediaSearchSpec {
        val cleanQuery = query?.trim()?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("query is required to search the catalog")
        require(cleanQuery.length <= MAX_QUERY_LENGTH) { "search query is too long" }
        require(cleanQuery.none { it.isISOControl() }) { "search query contains control characters" }
        val cleanLimit = (limit ?: DEFAULT_SEARCH_LIMIT).coerceIn(1, MAX_SEARCH_LIMIT)
        return MediaSearchSpec(cleanQuery, cleanLimit)
    }

    /**
     * Обход дерева библиотеки. node указывает, что развернуть (null — корень), query ищет
     * внутри плеера. Здесь limit задан жёстко: ответы плеера не должны съесть контекст агента.
     */
    fun library(
        packageName: String?,
        node: String?,
        query: String?,
        limit: Int?,
    ): MediaLibrarySpec {
        val pkg =
            packageNameOrNull(packageName)
                ?: throw IllegalArgumentException("package is required to browse a media library")
        val cleanNode = node?.trim()?.takeIf(String::isNotEmpty)
        require(cleanNode == null || cleanNode.length <= MAX_NODE_LENGTH) { "node id is too long" }
        val cleanQuery = query?.trim()?.takeIf(String::isNotEmpty)
        require(cleanQuery == null || cleanQuery.length <= MAX_QUERY_LENGTH) { "search query is too long" }
        val cleanLimit = (limit ?: DEFAULT_LIBRARY_LIMIT).coerceIn(1, MAX_LIBRARY_LIMIT)
        return MediaLibrarySpec(pkg, cleanNode, cleanQuery, cleanLimit)
    }

    /**
     * Избранное и дизлайк — кастомные команды сессии Яндекса, а не REST: OAuth для них не
     * нужен. Список закрыт намеренно, чтобы мост не стал способом слать в чужую сессию
     * произвольные строки.
     */
    fun like(
        action: String?,
        packageName: String?,
    ): MediaLikeSpec {
        val pkg =
            packageNameOrNull(packageName)
                ?: throw IllegalArgumentException("package is required to rate a track")
        // Действие обязано быть явным: молчаливый default «like» дал бы оценку трека тому,
        // кто просто забыл поле.
        val cleanAction = action?.trim()?.lowercase(Locale.ROOT)
        require(!cleanAction.isNullOrEmpty()) { "action is required to rate a track" }
        val wireName =
            LIKE_ACTIONS[cleanAction]
                ?: throw IllegalArgumentException("action must be one of: ${LIKE_ACTIONS.keys.joinToString()}")
        return MediaLikeSpec(pkg, wireName, "ru.yandex.music.action.$wireName")
    }

    fun packageNameOrNull(value: String?): String? {
        val clean = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        require(clean.length <= MAX_PACKAGE_LENGTH) { "package name is too long" }
        val parts = clean.split('.')
        require(parts.size >= 2 && parts.all { PACKAGE_PART.matches(it) }) {
            "invalid Android package name"
        }
        return clean
    }
}
