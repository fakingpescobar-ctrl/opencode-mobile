package org.opencode.mobile.account

import org.opencode.mobile.media.CatalogTrack

/**
 * Контракты чтения библиотеки Яндекса: страница «Моего плейлиста» и разбор её параметров.
 *
 * Страница, а не весь плейлист — потому что в библиотеке юзера тысячи треков, а агент
 * спрашивает про конкретные. Но [LikedPage.total] отдаём всегда: иначе по выдаче нельзя
 * понять, что список обрезан и есть следующая страница.
 */
data class LikedPage(
    val login: String,
    val uid: String,
    val revision: Long,
    val offset: Int,
    val total: Int,
    val trackIds: List<String>,
    val tracks: List<CatalogTrack>,
) {
    val hasMore: Boolean get() = offset + trackIds.size < total
}

/**
 * Валидация входа инструментов.
 *
 * `offset`/`limit` приходят из агента и потому недоверенные: без проверки `offset` минус
 * один уехал бы в подстроку как negative и уронил бы чтение, а `limit` в тысячу заставил
 * бы приложение разом дёргать метаданные. Лимит ограничен ещё и потому, что сервер
 * отдаёт всю библиотеку одним ответом: нарезать можно дёшево, обогащать — нет.
 */
object YandexAccountRequestValidator {
    private const val MAX_LIMIT = YandexAccountController.MAX_LIMIT

    fun page(
        offset: String?,
        limit: String?,
    ): Pair<Int, Int> {
        val start = integerOrDefault(offset, 0, "offset")
        val size = integerOrDefault(limit, YandexAccountController.DEFAULT_LIMIT, "limit")
        require(start >= 0) { "offset must be >= 0" }
        require(size in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
        return start to size
    }

    /**
     * Число из строки, где «нет параметра» и «параметр есть, но мусор» — разные вещи.
     *
     * Именно это различие и не даёт съесть опечатку агента: `toIntOrNull() ?: 0`
     * превратил бы `offset=later` в первую страницу, и агент решил бы, что у юзера
     * ровно 20 треков, хотя он просил не с того места.
     */
    private fun integerOrDefault(
        raw: String?,
        fallback: Int,
        name: String,
    ): Int {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return fallback
        return requireNotNull(text.toIntOrNull()) { "$name must be an integer" }
    }
}
