package org.opencode.mobile.media

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

/** Граница доверия для команд локального MCP к медиа-мосту. */
object MediaControlRequestValidator {
    const val DEFAULT_LIST_LIMIT = 50
    const val MAX_LIST_LIMIT = 200

    private const val MAX_QUERY_LENGTH = 120
    private const val MAX_PACKAGE_LENGTH = 255
    private val PACKAGE_PART = Regex("[A-Za-z][A-Za-z0-9_]*")

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

    fun status(packageName: String?): String? = packageNameOrNull(packageName)

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
