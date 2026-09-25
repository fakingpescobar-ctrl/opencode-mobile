package org.opencode.mobile.installer

/** Отфильтрованный запрос на список приложений, доступных для запуска. */
data class AppListSpec(
    val query: String?,
    val limit: Int,
)

/** Проверенный package ID приложения для запуска. */
data class AppLaunchSpec(
    val packageName: String,
)

/** Граница доверия для команд локального MCP к Android-мосту. */
object AppControlRequestValidator {
    const val DEFAULT_LIST_LIMIT = 100
    const val MAX_LIST_LIMIT = 200

    private const val MAX_QUERY_LENGTH = 120
    private const val MAX_PACKAGE_LENGTH = 255
    private val PACKAGE_PART = Regex("[A-Za-z][A-Za-z0-9_]*")

    fun list(
        query: String?,
        limit: Int?,
    ): AppListSpec {
        val cleanQuery = query?.trim()?.takeIf(String::isNotEmpty)
        require(cleanQuery == null || cleanQuery.length <= MAX_QUERY_LENGTH) {
            "app search query is too long"
        }
        require(cleanQuery == null || cleanQuery.none { it.isISOControl() }) {
            "app search query contains control characters"
        }
        val cleanLimit = limit ?: DEFAULT_LIST_LIMIT
        require(cleanLimit in 1..MAX_LIST_LIMIT) {
            "limit must be between 1 and $MAX_LIST_LIMIT"
        }
        return AppListSpec(cleanQuery, cleanLimit)
    }

    fun launch(packageName: String): AppLaunchSpec {
        val cleanPackage = packageName.trim()
        require(cleanPackage.length <= MAX_PACKAGE_LENGTH) { "package name is too long" }
        val parts = cleanPackage.split('.')
        require(parts.size >= 2 && parts.all { PACKAGE_PART.matches(it) }) {
            "invalid Android package name"
        }
        return AppLaunchSpec(cleanPackage)
    }
}
