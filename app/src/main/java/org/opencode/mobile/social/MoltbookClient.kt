package org.opencode.mobile.social

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP-клиент Moltbook. Сеть и разбор — здесь, проза — у модели.
 *
 * Почему Kotlin, а не скрипт на bun: musl-Bun в песочнице приложения не резолвит
 * www.moltbook.com (getaddrinfo ETIMEOUT), и обход через IP + заголовок Host упирается
 * в SNI — сертификат CloudFront выдан для имени, а не для адреса. Резолвер Android
 * работает штатно, поэтому при обычном HttpURLConnection ни DNS-костыля, ни рукопожатия
 * по IP не нужно вовсе.
 *
 * Разбор — строго на границе ([JSONObject] в типы внутри), дальше по коду ходят уже
 * доверенные типы: проверять JSONObject в цикле разбора комментариев — значит
 * размазать проверки по всей логике тика.
 */
internal class MoltbookClient(
    private val apiKey: String,
    private val host: String = DEFAULT_HOST,
) {
    /** Один раз за процесс печатаем реальные ключи ленты — вместо догадок о схеме. */
    @Volatile
    private var loggedFeedKeys = false


    data class Home(
        val karma: Int,
        val unreadNotifications: Int,
        val posts: List<Activity>,
    ) {
        /** Посты, где нас ждёт ответа. */
        val awaitingReply: List<Activity> get() = posts.filter { it.newNotifications > 0 }
    }

    data class Activity(
        val postId: String,
        val title: String,
        val newNotifications: Int,
        val commenters: List<String>,
    )

    data class Comment(
        val id: String,
        val author: String,
        val content: String,
        val parentId: String?,
    ) {
        val isOurs: Boolean get() = author.lowercase() in OWN_NAMES_LOWER
    }

    /** Ответ на комментарий: платформа либо приняла, либо потребовала verification. */
    sealed interface CommentOutcome {
        data class Posted(val commentId: String) : CommentOutcome

        data class NeedsVerification(
            val verificationCode: String,
            val challengeText: String,
        ) : CommentOutcome

        /** Отказ платформы с её формулировкой — например «You already said this». */
        data class Rejected(val reason: String) : CommentOutcome
    }

    fun home(): Home = parseHome(getJsonObject("/api/v1/home"))

    fun comments(
        postId: String,
        sort: String = SORT_NEW,
    ): List<Comment> = parseComments(getJsonObject("/api/v1/posts/$postId/comments?sort=$sort"))

    fun postComment(
        postId: String,
        content: String,
        parentId: String?,
    ): CommentOutcome {
        val payload = JSONObject().apply {
            put("content", content)
            parentId?.let { put("parent_id", it) }
        }
        val root = postJsonObject("/api/v1/posts/$postId/comments", payload)

        root.optJSONObject("verification")?.let { challenge ->
            return CommentOutcome.NeedsVerification(
                verificationCode = challenge.optString("verification_code"),
                challengeText = challenge.optString("challenge_text"),
            )
        }
val commentId = postedCommentId(root)
        if (commentId.isEmpty()) {
            return CommentOutcome.Rejected(root.optString("message", "без comment и без verification"))
        }
        return CommentOutcome.Posted(commentId)
    }

    /**
     * Наш ли ответ на [parentId] уже висит в посте — и какой у него id.
     *
     * Стоит перед каждой публикацией и единственный способ не задублировать чужой
     * пост: запись в сокет уходит раньше, чем приходит ответ, поэтому таймаут на
     * чтении означает «сервер, возможно, уже создал комментарий». Формально мы в
     * этот момент ничего не знаем, а практически — перечитываем ветку и видим
     * свой комментарий. Без этой проверки ретрай через 15 минут публиковал бы
     * второй ответ на тот же вопрос, а отозвать его уже нельзя.
     */
    fun ourReplyTo(
        postId: String,
        parentId: String,
    ): String? =
        runCatching { comments(postId) }
            .getOrNull()
            ?.firstOrNull { it.isOurs && it.parentId == parentId }
            ?.id

    /**
     * @param answer ровно `число.00` — формат задаёт платформа, и ответ с другим
     *   форматом молча тратит verification-код, который потом нельзя переиспользовать.
     */
    fun verify(
        verificationCode: String,
        answer: String,
    ): Boolean {
val normalized = answer.trim()
        // IOException, а НЕ check(): check бросает IllegalStateException, который
        // нигде в тике не ловится и улетает в общий catch приёмника — вместе с ним
        // терялись остальные ответы, апвоуты и снятие счётчиков. Один ответ вида
        // «48.0» вместо «48.00» молча заканчивал весь визит. Здесь формат — обычная
        // сетевая ошибка: тикер её ловит и берёт следующую попытку со свежим кодом.
        if (!VERIFICATION_ANSWER.matches(normalized)) {
            throw IOException("verification-ответ должен быть числом с двумя знаками, получено: $normalized")
        }
        val root = postJsonObject(
            "/api/v1/verify",
            JSONObject().apply {
                put("verification_code", verificationCode)
                put("answer", normalized)
            },
        )
        return root.optBoolean("success")
    }

    /**
     * Снятие счётчика непрочитанного для поста. Эндпоинт взят из рабочего
     * `moltwatch.js`, а не придуман: `POST /api/v1/posts/<id>/read-marker` отдаёт 404.
     *
     * Вызывающий ловит ошибку сам: ответы к этому моменту уже опубликованы, и терять
     * из-за счётчика весь итог тика (дайджест, метку времени) хуже, чем оставить

     * один просроченный бейдж.
     */
    fun markPostRead(postId: String) {
        request("/api/v1/notifications/read-by-post/$postId", "POST")
    }

    /** Апвоут поста. Даунвота в API нет — поэтому у нас только «плюс» и «пропустить». */
    fun upvote(postId: String) {
        request("/api/v1/posts/$postId/upvote", "POST")
    }

    /**
     * Пост из общей ленты. [repostOf] — id поста, который этот пост пересказывает,
     * если сервер отдаёт его явно.
     */
    data class FeedPost(
        val postId: String,
        val title: String,
        val body: String,
        val author: String,
        val commentsTotal: Int,
        val upvotes: Int,
        val repostOf: String?,
    )

    /**
     * Лента: `sort=top|new|following`.
     *
     * Схема ответа разведана на живом API, но названия полей у сервера гуляют
     * (`comment_count` / `comments_count`), поэтому берём первый подходящий ключ из
     * списка. Неизвестные поля печатаем ОДИН раз за процесс: это дешёвый способ
     * узнать правду о сервере вместо догадок, после чего лишние ключи можно убрать.
     */
    fun feed(sort: String): List<FeedPost> {
        val body = requestBody("/api/v1/feed?sort=$sort", "GET")
        val items =
            when {
                body.trimStart().startsWith("[") -> org.json.JSONArray(body)
                else ->
                    JSONObject(body).let { root ->
                        // Сначала известные ключи, и только потом «первый массив»:
                        // при ответе вида {"related":[…],"posts":[…]} старая эвристика
                        // брала related, ни один элемент не распарсивался, и лента
                        // молча становилась пустой — вместе с репостами и апвоутами.
                        feedArray(root) ?: run {
                            Log.i(TAG, "feed: массив постов не найден, ключи ответа: ${root.keys().asSequence().toList()}")
                            JSONArray()
                        }
                    }
            }
        logUnknownKeysOnce(items)
        return (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.let { parseFeedPost(it) }
        }
    }

    private fun parseFeedPost(item: JSONObject): FeedPost? {
        val postId = item.firstString("id", "post_id", "_id") ?: return null
        return FeedPost(
            postId = postId,
            title = item.firstString("title", "post_title") ?: "",
            body = item.firstString("content", "body", "text") ?: "",
            author = item.authorName(),
            commentsTotal = item.firstInt("comment_count", "comments_count", "num_comments"),
            upvotes = item.firstInt("upvote_count", "upvotes", "score"),
            repostOf = item.firstString("repost_of", "repost_of_post_id", "quote_of", "original_post_id"),
        )
    }

    private fun JSONObject.firstInt(vararg keys: String): Int {
        for (key in keys) {
            val raw = opt(key)
            if (raw is Number) return raw.toInt()
            if (raw is String && raw.toIntOrNull() != null) return raw.toInt()
        }
        return 0
    }

    private fun logUnknownKeysOnce(items: org.json.JSONArray) {
        if (loggedFeedKeys || items.length() == 0) return
        loggedFeedKeys = true
        val first = items.optJSONObject(0) ?: return
        Log.i(TAG, "feed: реальные ключи поста = ${first.keys().asSequence().toList()}")
    }



    private fun getJsonObject(path: String): JSONObject = request(path, "GET")

    private fun postJsonObject(
        path: String,
        payload: JSONObject,
    ): JSONObject = request(path, "POST", payload.toString())

    private fun request(
        path: String,
        method: String,
        body: String? = null,
    ): JSONObject = bounded(path, method) { requestBlocking(path, method, body) }

    /**
     * Общий дедлайн на запрос. connectTimeout/readTimeout НЕ ограничивают резолв имени:
     * если DNS (особенно за VPN) завис, поток виснет навсегда, а следующий тик — уже не
     * встанет. Поэтому весь запрос едет в отдельном потоке под общим таймаутом,
     * который выкидывает и поток, а не только возвращает ошибку.
     */
private fun <T> bounded(
        path: String,
        method: String,
        block: () -> T,
    ): T {
        return try {
            HTTP_POOL.submit(Callable { block() }).get(TOTAL_DEADLINE_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // Пул общий на весь процесс, и в этом весь смысл: зависший резолв не
            // реагирует на interrupt, поэтому поток НЕ умирает. Раньше executor
            // создавался на каждый вызов, и каждый зависший запрос навсегда занимал
            // свой поток и свой сокет — утечка была безграничной и накапливалась от
            // тика к тику. Теперь зависший забирает один слот из четырёх, а остальные
            // запросы продолжают работать.
            throw IOException("Moltbook $method $path не ответил за ${TOTAL_DEADLINE_MS / 1000} с (завис резолв или TLS)")
        } catch (e: ExecutionException) {
            throw (e.cause ?: e) as? IOException ?: IOException("Moltbook $method $path: ${e.cause?.message}", e)
        }
    }

    private fun requestBlocking(
        path: String,
        method: String,
        body: String?,
    ): JSONObject {
        val text = requestBodyBlocking(path, method, body)
        if (text.isBlank()) return JSONObject()
        return try {
            JSONObject(text)
        } catch (e: org.json.JSONException) {
            throw IOException("Moltbook $method $path вернул не JSON: ${text.take(ERROR_BODY_CHARS)}", e)
        }
    }

    /**
     * Сырое тело ответа строкой. Отдельный метод нужен там, где форма JSON ещё не
     * известна (разведка ленты): [request] обязан вернуть JSONObject и падает на
     * не-объекте, а для разведки нужен именно как есть.
     */
    fun requestBody(
        path: String,
        method: String,
        body: String? = null,
    ): String = bounded(path, method) { requestBodyBlocking(path, method, body) }

    private fun requestBodyBlocking(
        path: String,
        method: String,
        body: String?,
    ): String {
        Log.i(TAG, "$method $path: начинаю")
        val conn =
            (URL(host + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
        try {
            body?.let { payload ->
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            Log.i(TAG, "$method $path -> $status")
            val text =
                (if (status in HTTP_OK_MIN..HTTP_OK_MAX) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
            if (status == HTTP_UNAUTHORIZED) {
                throw IOException("Moltbook отклонил ключ (401) — проверить moltkey")
            }
            if (status == HTTP_FORBIDDEN) {
                throw IOException("Moltbook зарезал запрос WAF (403) — сократить текст комментария")
            }
            if (status !in HTTP_OK_MIN..HTTP_OK_MAX) {
                throw IOException("Moltbook $method $path → $status: ${text.take(ERROR_BODY_CHARS)}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }


    companion object {
        const val DEFAULT_HOST = "https://www.moltbook.com"
        const val OWN_ACCOUNT = "opencodekz"
        val OWN_NAMES_LOWER = setOf(OWN_ACCOUNT.lowercase())
        const val SORT_NEW = "new"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val TOTAL_DEADLINE_MS = 45_000L
        const val TAG = "MoltbookApi"
        const val ERROR_BODY_CHARS = 200
        const val HTTP_OK_MIN = 200
        const val HTTP_OK_MAX = 299
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403

/** Платформа требует ровно два знака после запятой: `48.00`, не `48` и не `48,00`. */
        val VERIFICATION_ANSWER = Regex("""^\d{1,6}\.\d{2}$""")

        /** Ключи массива постов: пробуем их до эвристики «первый массив в ответе». */
        val FEED_ARRAY_KEYS = arrayOf("posts", "items", "data", "results")

        /**
         * Массив постов из объекта ответа либо null, если массива нет.
         *
         * Порядок именно такой: известные ключи, и только если их нет — первый
         * попавшийся массив. Обратный порядок — тихая потеря ленты: ответ вида
         * `{"related":[…],"posts":[…]}` давал related, ноль распарсенных постов и
         * пустую ленту без единой ошибки, а вместе с ней молча пропадали репосты и
         * апвоуты.
         */
        internal fun feedArray(root: JSONObject): JSONArray? {
            for (key in FEED_ARRAY_KEYS) {
                root.optJSONArray(key)?.let { return it }
            }
            val fallbackKey = root.keys().asSequence().firstOrNull { root.opt(it) is JSONArray } ?: return null
            return root.optJSONArray(fallbackKey)
        }

        /** Ключи id опубликованного комментария — форма ответа у платформы гуляет. */
        val SUCCESS_ID_KEYS = arrayOf("id", "comment_id", "commentId")

        /**
         * Пул HTTP на процесс, а не на вызов. Четыре потока: зависший резолв занимает
         * один слот и не мешает остальным запросам, но и не копится с тиками.
         */
        private const val HTTP_POOL_THREADS = 4

        private val HTTP_POOL: java.util.concurrent.ExecutorService =
            Executors.newFixedThreadPool(HTTP_POOL_THREADS) { runnable ->
                Thread(runnable, "moltbook-http").apply { isDaemon = true }
            }

        /**
         * ID только что опубликованного комментария либо пустая строка.
         *
         * Раньше читалась ровно одна форма — `{"comment":{"id"}}`. Любая другая
         * молча превращалась в «комментарий не создан»: `our_reply_id` не писался,
         * и следующий тик видел вопрос неотвеченным и публиковал второй ответ на
         * него. А отозвать уже опубликованное нельзя, поэтому цена ошибки здесь
         * не «логическое уведомление», а дубль в чужой ленте.
         */
        internal fun postedCommentId(root: JSONObject): String {
            var cursor: JSONObject? = root
            repeat(3) {
                val current = cursor ?: return ""
                current.optJSONObject("comment")?.let { nested ->
                    for (key in SUCCESS_ID_KEYS) {
                        nested.optString(key).takeIf { it.isNotEmpty() }?.let { return it }
                    }
                }
                for (key in SUCCESS_ID_KEYS) {
                    current.optString(key).takeIf { it.isNotEmpty() }?.let { return it }
                }
                cursor = current.optJSONObject("data")
            }
            return ""
        }

/**
     * Первый непустой строковый ключ из списка: названия полей у платформы гуляют,
     * и вместо одной догадки перебираем известные варианты.
     */
    internal fun JSONObject.firstString(vararg keys: String): String? =
        keys
            .firstOrNull { opt(it) is String && (opt(it) as String).isNotBlank() }
            ?.let { optString(it) }

    /** Автор приходит объектом (`author.name`) или строкой — Moltbook отдаёт оба вида. */
    internal fun JSONObject.authorName(): String {
        optJSONObject("author")?.let { nested ->
            nested.firstString("name", "username", "display_name")?.let { return it }
        }
        return firstString("author_name", "agent_name", "username", "author") ?: ""
    }

    /**
     * Разбор живёт в компаньоне, а не в HTTP-методах, чтобы юнит-тесты гоняли
     * настоящий JSON без сокета: формат ответа меняет платформа, а не сеть.
     */
    internal fun parseHome(root: JSONObject): Home {
            val account = root.optJSONObject("your_account")
                ?: throw IOException("Moltbook /home без your_account — формат ответа изменилась")
            val posts = root.optJSONArray("activity_on_your_posts").mapObjects { entry ->
                Activity(
                    postId = entry.optString("post_id").ifEmpty { entry.optString("id") },
                    title = entry.optString("post_title"),
                    newNotifications = entry.optInt("new_notification_count"),
                    commenters = entry.optJSONArray("latest_commenters").mapStrings(),
                )
            }
            return Home(
                karma = account.optInt("karma"),
                unreadNotifications = account.optInt("unread_notification_count"),
                posts = posts,
            )
        }

        internal fun parseComments(root: JSONObject): List<Comment> =
            root.optJSONArray("comments").mapObjects(::buildComment)

private fun buildComment(entry: JSONObject) =
            Comment(
                id = entry.requireField("id", "comment"),
                // authorName(), а не optJSONObject("author")?.optString("name"):
                // платформа отдаёт автора и объектом, и строкой. Строковый вариант
                // молчно давал пустого автора, а пустой автор — это «не мы», то есть
                // наш собственный ответ выглядел чужим и получал ответ ещё раз.
                author = entry.authorName(),
                content = entry.requireField("content", "comment"),
                parentId = entry.optString("parent_id").takeIf { it.isNotEmpty() },
            )

        /**
         * Ключ лежит файлом в приватном хранилище: в коде и в репозитории его быть
         * не должно. Бросаем явно — иначе тик молча уедет на «нет ключа» и агент
         * будет выглядеть как «молчащий, потому что нечего сказать».
         */
        fun readKey(file: File): String {
            if (!file.isFile) throw IOException("Moltbook: нет файла ключа ${file.path}")
            val key = file.readText().trim()
            if (key.isEmpty()) throw IOException("Moltbook: файл ключа ${file.path} пуст")
            return key
        }

        fun JSONArray?.mapStrings(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotEmpty() } }
        }

        inline fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { index -> optJSONObject(index)?.let(transform) }
        }

        fun JSONObject.requireField(
            name: String,
            entity: String,
        ): String = optString(name).ifEmpty { throw IOException("Moltbook: $entity без поля \"$name\"") }
    }
}
