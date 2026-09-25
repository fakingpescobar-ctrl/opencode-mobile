package org.opencode.mobile.media

import android.content.ComponentName
import android.content.Context
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.TimeUnit

private const val TAG = "MediaLibraryBrowser"
private const val COMMAND_TIMEOUT_MS = 2_000L
private const val CONNECT_TIMEOUT_MS = 3_000L
private const val RESULT_TIMEOUT_MS = 4_000L
private const val NO_SESSION = "no_session"

/** Коды дерева media3: агенту и в лог без имён бесполезны, а ветвление ради цикломатики — нет. */
private val LIBRARY_RESULT_NAMES =
    mapOf(
        LibraryResult.RESULT_ERROR_NOT_SUPPORTED to "not_supported",
        LibraryResult.RESULT_ERROR_PERMISSION_DENIED to "permission_denied",
        LibraryResult.RESULT_ERROR_BAD_VALUE to "bad_value",
        LibraryResult.RESULT_ERROR_INVALID_STATE to "invalid_state",
        LibraryResult.RESULT_ERROR_IO to "io",
        LibraryResult.RESULT_ERROR_SESSION_DISCONNECTED to "disconnected",
        LibraryResult.RESULT_ERROR_SESSION_AUTHENTICATION_EXPIRED to "auth_expired",
        LibraryResult.RESULT_ERROR_UNKNOWN to "unknown",
    )

/**
 * Обход дерева библиотеки медиасессии.
 *
 * Нужен не для красоты: отсюда берётся mediaId, который сессия признаёт своим, потому что
 * выдала его сама. Трек из публичного каталога сессия принимает молча и не играет, а трек
 * из собственной библиотеки обязан узнать — иначе ссылка была бы неправильной.
 *
 * Отдельный класс, а не метод контроллера: у обхода своя жизнь (bind, чтение, release) и свой
 * протокол ошибок, где «запрещено» и «не умеет» — разные ответы.
 */
// Мелкие хелперы внизу (code/item/entries/state/codeName/toEntry) — это разбор одного и того же
// LibraryResult, и держать их вперемешку с bind-логикой значит читать не то место.
@Suppress("TooManyFunctions")
internal class MediaLibraryBrowser(
    private val context: Context,
    private val looper: HandlerThread,
) {
    /**
     * Кандидаты проверяются по очереди: отказ одного сервиса ничего не говорит о следующем,
     * а один молчаливый ответ скрыл бы различие между «запрещено» и «не умеет».
     */
    fun browse(
        components: List<ComponentName>,
        spec: MediaLibrarySpec,
    ): MediaLibraryResult {
        val attempts = components.map { component -> component to browseComponent(component, spec) }
        val answered = attempts.firstOrNull { (_, result) -> result.entries.isNotEmpty() }?.second
        return answered ?: attempts.last().second.copy(message = describe(attempts))
    }

    private fun browseComponent(
        component: ComponentName,
        spec: MediaLibrarySpec,
    ): MediaLibraryResult {
        val browser = connect(component) ?: return MediaLibraryResult(
            root = null,
            entries = emptyList(),
            resultCode = null,
            message = NO_SESSION,
        )
        return try {
            read(browser, spec)
        } finally {
            runCatching { onMediaLooper(looper, COMMAND_TIMEOUT_MS) { browser.release() } }
        }
    }

    private fun connect(component: ComponentName): MediaBrowser? {
        val future =
            runCatching {
                onMediaLooper(looper, COMMAND_TIMEOUT_MS) {
                    MediaBrowser
                        .Builder(context, SessionToken(context, component))
                        .setApplicationLooper(looper.looper)
                        .buildAsync()
                }
            }.onFailure { error -> Log.w(TAG, "cannot start media3 browser for $component", error) }
                .getOrNull() ?: return null
        return runCatching { future.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .onFailure { error ->
                Log.w(TAG, "media3 library did not answer for $component", error)
                releaseLate(future)
            }.getOrNull()
    }

    /**
     * Браузер, ответивший после того, как мы перестали ждать, всё равно держит привязанную сессию:
     * если его не отпустить, плеер продолжает считать нас подключёнными, а следующая попытка
     * упирается в занятую сессию. Отпускаем ровно один раз - из колбэка самого future.
     */
    private fun releaseLate(future: ListenableFuture<MediaBrowser>) {
        future.addListener(
            { runCatching { onMediaLooper(looper, COMMAND_TIMEOUT_MS) { future.get().release() } } },
            Runnable::run,
        )
    }

    private fun read(
        browser: MediaBrowser,
        spec: MediaLibrarySpec,
    ): MediaLibraryResult =
        if (!spec.query.isNullOrEmpty()) {
            search(browser, spec.query, spec.limit)
        } else {
            tree(browser, spec)
        }

    private fun tree(
        browser: MediaBrowser,
        spec: MediaLibrarySpec,
    ): MediaLibraryResult {
        val root = call("library root") { browser.getLibraryRoot(null) }
        val parent = spec.node ?: root?.value?.mediaId
        if (parent == null) {
            return MediaLibraryResult(null, emptyList(), root.code(), root.state(emptyList()))
        }
        val children = call("children of $parent") { browser.getChildren(parent, 0, spec.limit, null) }
        val entries = children.libraryEntries(spec.limit)
        return MediaLibraryResult(root.libraryItem(), entries, children.code(), children.state(entries))
    }

    private fun search(
        browser: MediaBrowser,
        query: String,
        limit: Int,
    ): MediaLibraryResult {
        val found = call("search '$query'") { browser.getSearchResult(query, 0, limit, null) }
        val entries = found.libraryEntries(limit)
        return MediaLibraryResult(null, entries, found.code(), found.state(entries))
    }

    /**
     * Вызов media3 возвращает future, который завершается на media-looper, поэтому ждать его
     * там же нельзя: сначала отправляем вызов, затем ждём уже на вызывающем потоке.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> call(
        what: String,
        block: () -> ListenableFuture<T>,
    ): T? {
        val future =
            runCatching { onMediaLooper(looper, COMMAND_TIMEOUT_MS, block) }
                .onFailure { error -> Log.w(TAG, "media library $what call rejected", error) }
                .getOrNull() ?: return null
        return try {
            future.get(RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            Log.w(TAG, "media library $what failed", error)
            null
        }
    }

    private fun describe(attempts: List<Pair<ComponentName, MediaLibraryResult>>): String =
        attempts.joinToString("; ") { (component, result) ->
            "${component.className.substringAfterLast('.')}=${result.message}"
        }

    private fun <T> LibraryResult<T>?.code(): Int? = this?.resultCode

    private fun LibraryResult<MediaItem>?.libraryItem(): MediaLibraryEntry? = this?.value?.let(::toEntry)

    private fun LibraryResult<ImmutableList<MediaItem>>?.libraryEntries(limit: Int): List<MediaLibraryEntry> =
        this
            ?.value
            ?.map(::toEntry)
            ?.take(limit)
            .orEmpty()

    /** Пустой результат и «сервис не умеет» — разные вещи, и агент должен их различать. */
    private fun <T> LibraryResult<T>?.state(entries: List<MediaLibraryEntry>): String =
        when {
            this == null -> "no answer"
            resultCode == LibraryResult.RESULT_SUCCESS && entries.isEmpty() -> "empty"
            resultCode == LibraryResult.RESULT_SUCCESS -> "ok"
            resultCode == LibraryResult.RESULT_ERROR_NOT_SUPPORTED -> "not_supported"
            else -> "${codeName(resultCode)}_$resultCode"
        }

    private fun codeName(resultCode: Int): String = LIBRARY_RESULT_NAMES[resultCode] ?: "error"

    private fun toEntry(item: MediaItem): MediaLibraryEntry {
        val metadata = item.mediaMetadata
        return MediaLibraryEntry(
            mediaId = item.mediaId,
            title = metadata.title?.toString().orEmpty(),
            subtitle = metadata.artist?.toString().orEmpty(),
            browsable = metadata.isBrowsable == true,
            playable = metadata.isPlayable == true,
            uri = item.localConfiguration?.uri?.toString(),
        )
    }
}
