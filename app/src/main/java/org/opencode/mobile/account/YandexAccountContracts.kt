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
 * Плейлист в списке: ровно то, чем он называется у юзера и по чему его потом можно открыть.
 *
 * [kind] — не украшение, а рабочий ключ: и содержимое, и запуск воспроизведения адресуются
 * именно им, а не [uuid]. [uuid] оставлен рядом потому, что это единственный идентификатор,
 * который не сдвинется при пересоздании плейлиста, — но ни один наш запрос его не ест.
 */
data class PlaylistSummary(
    val kind: Int,
    val uuid: String,
    val title: String,
    val trackCount: Int,
    val durationMs: Long,
)

/**
 * Страница содержимого плейлиста.
 *
 * Устроено как [LikedPage], и не по привычке: обе страницы режут один ответ сервера, у обеих
 * есть [total] и [hasMore], и агент в обоих случаях должен уметь сказать «дальше есть ещё».
 * [originalIndexes] держит позицию трека в плейлисте — по ней видно, что плейлист
 * переупорядочен, и именно она, а не позиция выдачи, отвечает на вопрос «что идёт третьим».
 */
data class PlaylistPage(
    val login: String,
    val kind: Int,
    val uuid: String,
    val title: String,
    val revision: Long,
    val offset: Int,
    val total: Int,
    val trackIds: List<String>,
    val originalIndexes: List<Int>,
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
     * `kind` плейлиста — неотрицательное целое, и это проверяется отдельно от [page].
     *
     * Яндекс различает виды плейлистов одним числом: 0 — «Мой плейлист» с лайками,
     * 1000-1999 — собственные, остальное — чужие подборки. Ноль поэтому пропускаем:
     * агент вправе попросить kind=0 и получить лайки. А вот мусор вроде `kind=latest`
     * обязан упасть здесь, а не уехать в URL и вернуться 404-ом без внятного текста.
     */
    fun playlistKind(raw: String?): Int {
        val text = raw?.trim().orEmpty()
        require(text.isNotEmpty()) { "kind is required" }
        val kind = requireNotNull(text.toIntOrNull()) { "kind must be an integer" }
        require(kind >= 0) { "kind must be >= 0" }
        return kind
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
