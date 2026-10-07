package org.opencode.mobile.social

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

/**
 * Курсор следующей страницы комментариев или null, если его нет.
 *
 * Отдельная top-level функция, а не метод клиента, ради одного: `optString` на
 * явном JSON null возвращает строку "null" — непустую, то есть «курсор есть».
 * Такой курсор ушёл бы в запрос как `cursor=null` и получил бы 400, а
 * `comments()` зовётся без try: тик падал целиком, а `ourReplyTo` получала вечный
 * Unknown и блокировала ответы навсегда.
 */
internal fun JSONObject.cursor(): String? = optString("next_cursor").takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

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

    /**
     * Комментарий в плоском виде, на любой глубине.
     *
     * Глубина и статус сюда не тащим: список нужен только для двух вопросов — «мы
     * это уже отвечали?» и «на кого отвечать». Всё, что не отвечает, отсекается
     * при разборе, и обе проверки получают чистые данные, а не фильтр заново.
     */
    data class Comment(
        val id: String,
        val author: String,
        val content: String,
        val parentId: String?,
        /**
         * Статус проверки платформы: `verified` — опубликован, `pending` — создан,
         * но читателям не виден. Пустая строка означает, что сервер статуса не
         * отдавал, и это НЕ «pending»: неизвестное нельзя читать как
         * «не опубликовано», иначе мы бы переотвечали на старые ветки.
         */
        val verificationStatus: String = "",
    ) {
        val isOurs: Boolean get() = author.lowercase() in OWN_NAMES_LOWER

        /**
         * Виден ли ответ читателям.
         *
         * Замерено 07.10.2026 на живом API: `POST /comments` создаёт коммент сразу и
         * сразу с `verification_status: "pending"`, и до `POST /verify` он не виден в
         * ленте. Считать такой коммент ответом — значит писать в лог «ответил» при
         * полном молчании: ровно тот баг, из-за которого 27 наших комментов висели
         * вечно непрочитанными, а тик об этом не знал.
         */
        val isPublished: Boolean get() = !verificationStatus.equals(STATUS_PENDING, ignoreCase = true)
    }

    /**
     * Ответ на комментарий: платформа приняла, потребовала verification, отказала —
     * либо не создала ничего нового.
     */
    sealed interface CommentOutcome {
        data class Posted(
            val commentId: String,
        ) : CommentOutcome

        /**
         * Комментарий УЖЕ создан, код выдан — осталось решить задачу и позвать /verify.
         *
         * Публиковать после verify заново нельзя: коммент уже существует, и повторный
         * POST вернёт `already_existed` со старым id. Старый код делал ровно это —
         * второй проход по циклу создавал ещё один непроверенный коммент.
         */
        data class NeedsVerification(
            val commentId: String,
            val verificationCode: String,
            val challengeText: String,
            val expiresAt: String,
        ) : CommentOutcome

        /**
         * Ничего нового не создано: на посте уже висит наш непроверенный коммент, и он
         * блокирует новые ответы.
         *
         * Возвращённый сервером `comment` — старый, и его `parent_id` может указывать на
         * СОВСЕМ другой вопрос (замерено 07.10.2026: у блокирующего комментария parent_id
         * пустой, хотя публиковали ответ в подветку). Поэтому это не доказательство, что
         * на нужный вопрос мы уже ответили, и помечать им цель как отвеченную нельзя.
         */
        data class Duplicate(
            val existingCommentId: String,
            val existingParentId: String?,
            val status: String,
        ) : CommentOutcome

        /** Отказ платформы с её формулировкой — например «You already said this». */
        data class Rejected(
            val reason: String,
        ) : CommentOutcome
    }

    fun home(): Home = parseHome(getJsonObject("/api/v1/home"))

    /**
     * Вся ветка поста, все глубины.
     *
     * Страниц может быть несколько: платформа отдаёт корневые комментарии
     * постранично, ключ — `next_cursor` (keyset, base64 от createdAt+id), а
     * `limit` считает только КОРНЕВЫЕ. Вложенные приезжают бесплатно вместе со
     * своими родителями, поэтому ради вложенных пагинация не нужна — ради
     * длинных тредов нужна: без неё мы бы решили «мы ответили» по обрезанному
     * куску и пропустили свой ответ, лежащий на второй странице.
     *
     * Замерено 06.10.2026: limit=3 отдаёт 3+3+2 корневых на трёх страницах,
     * count при этом 29 на каждой — это total по всем глубинам, а не размер
     * страницы, поэтому count как «сколько получили» использовать нельзя.
     */
    fun comments(
        postId: String,
        sort: String = SORT_NEW,
    ): List<Comment> =
        collectPagedComments { cursor ->
            getJsonObject(
                buildString {
                    append("/api/v1/posts/$postId/comments?sort=$sort&limit=")
                    append(COMMENTS_PAGE_LIMIT)
                    if (cursor != null) {
                        append("&cursor=")
                        append(URLEncoder.encode(cursor, "UTF-8"))
                    }
                },
            )
        }

    /**
     * Публикация комментария.
     *
     * Разбор ответа — по фактам живого API от 07.10.2026, а не по документации:
     *
     * - коммент создаётся СРАЗУ и лежит в `comment`, вместе с `verification`;
     * - `verification` лежит ВНУТРИ `comment`, а не в корне. Старый код смотрел только
     *   `root.optJSONObject("verification")`, всегда получал null и уходил в `Posted` —
     *   то есть `/verify` не вызывался НИ РАЗУ, и каждый ответ навсегда оставался
     *   `pending`. Это и был корень «ответил, а ответа нет».
     * - `already_existed: true` стоит в КОРНЕ: нового комментария нет, сервер вернул
     *   старый. Читать его id как успех — значит врать в логе и в дайджесте.
     */
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

        val commentId = postedCommentId(root)
        val status = verificationStatusOf(root)
        val duplicate = isAlreadyExisted(root)
        // HTTP-код платформа уже напечатала строкой выше (requestBodyBlocking), здесь
        // добавляем то, чего в ней нет: id коммента, статус проверки и флаг дубля.
        Log.i(
            TAG,
            "POST /posts/$postId/comments -> comment=${commentId.take(ID_LOG_CHARS)} " +
                "status=${status.ifEmpty { "?" }} already_existed=$duplicate",
        )

        // Порядок обязателен. `already_existed` важнее verification: если сервер ничего
        // не создал, то и кода выдал не для нас — публиковать тут нечего.
        if (duplicate) {
            return CommentOutcome.Duplicate(commentId, commentParentId(root), status)
        }
        verificationChallenge(root)?.let { challenge ->
            return CommentOutcome.NeedsVerification(
                commentId = commentId,
                verificationCode = challenge.optString("verification_code"),
                challengeText = challenge.optString("challenge_text"),
                expiresAt = challenge.optString("expires_at"),
            )
        }
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

    /** Ответ платформы на «мы уже отвечали на это?» — четыре разных исхода, а не два. */
    sealed interface ReplyProbe {
        /** Наш ОПУБЛИКОВАННЫЙ ответ найден: повторная публикация создаст дубль. */
        data class Found(
            val commentId: String,
        ) : ReplyProbe

        /**
         * Ответ наш и создан, но не прошёл проверку: `pending`, читателям не виден.
         *
         * Отдельный исход, а не `Found`, из-за одного: такой коммент не отвечает ни на
         * что, сколько бы ни провисел, и платформа не даст ответить на этот вопрос снова
         * (замерено 07.10.2026 — вернула `already_existed` со старым комментарием).
         * Читать его как `Found` — значит писать «ответил» при полном молчании, а ветка
         * навсегда залипает в Blocked с непрочитанным счётчиком.
         */
        data class Unpublished(
            val commentId: String,
            val status: String,
        ) : ReplyProbe

        /** Ответа точно нет: ветку прочитали, своего комментария в ней не нашлось. */
        object Absent : ReplyProbe

        /**
         * Разобраться не удалось: сеть, не-200, битый JSON.
         *
         * Отдельный случай не для красоты, а ради дублей. Раньше здесь стоял
         * `runCatching { comments() }.getOrNull()`, и ошибка сети давала тот же null,
         * что и «ответа нет». Тикер читал null как «можно публиковать» и публиковал
         * второй ответ на тот же вопрос — ровно то, ради чего проверка и написана.
         * Теперь Unknown означает «не знаю», и публикация на нём не выполняется.
         */
        data class Unknown(
            val reason: String,
        ) : ReplyProbe
    }

    fun ourReplyTo(
        postId: String,
        parentId: String,
    ): ReplyProbe {
        val comments =
            try {
                comments(postId)
            } catch (e: Exception) {
                // Ловим Exception, а не IOException: getJsonObject внутри бросает и
                // JSONException на неожиданном теле, и IllegalStateException на пустой
                // JSONObject. Все они означают одно — «мы не знаем», а не «ответа нет».
                return ReplyProbe.Unknown("${e.javaClass.simpleName}: ${e.message}")
            }
        return probeIn(comments, parentId)
    }

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
        val ok = root.optBoolean("success")
        // Ключ задачи в лог целиком не пишем: он одноразовый и засоряет вывод, а
        // префикса достаточно, чтобы связать строки одного тика между собой.
        Log.i(
            TAG,
            "POST /api/v1/verify код=${verificationCode.take(ID_LOG_CHARS)} ответ=$normalized -> $ok " +
                "(${root.optString("message")})",
        )
        return ok
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
     * Удаление НАШЕГО комментария — единственное разрушающее действие, которое клиент
     * вправе выполнить.
     *
     * Зачем оно нужно: `POST /comments` создаёт коммент сразу, но публикует только
     * после `POST /verify`, а код задачи выдают исключительно в момент создания. Значит
     * коммент, который мы не смогли проверить, **навсегда** остаётся `pending`: код
     * уже не получить, а сервер не даст создать второй ответ на тот же вопрос
     * (`already_existed`). Такой коммент нельзя ни решить, ни перезаписать — только
     * удалить, иначе ветка закрыта навсегда.
     *
     * Политика (решение владельца, 07.10.2026): удалять можно только то, что
     * опубликовал сам агент, и только по id, который сервер вернул нам же. Чужой
     * комментарий, пост и любой другой метод сюда не попадают — точечный
     * [deleteComment] вместо белого списка DELETE на всё подряд.
     */
    fun deleteComment(commentId: String) {
        request("/api/v1/comments/$commentId", "DELETE")
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
        // Проверка метода — до отправки в пул, а не внутри задачи. Внутри любое
        // исключение приходит через ExecutionException, и нижний catch превращает
        // его в IOException: запрет метода выглядел бы как обычная сетевая ошибка.
        // Снаружи IllegalArgumentException остаётся собой, и вызывающий видит
        // «запрещено», а не «сеть упала» — эти два случая чинить по-разному.
        requireAllowedMethod(method)
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

    /**
     * Публиковать, читать и удалять СВОЁ — можно. Остальное разрушающее — нельзя.
     *
     * Список методов, а не запрет конкретного: запрет перечисляет то, что мы знаем
     * плохого, и молча пропускает всё новое. Белый список отсекает любой будущий
     * метод, который не прошёл бы здесь.
     *
     * DELETE попал сюда не «на всякий случай», а потому что [deleteComment] —
     * единственное разрушающее действие, и без него наш непроверенный комментарий
     * навсегда блокирует ветку (см. доктрину там).
     */
    private fun requireAllowedMethod(method: String) {
        if (method.uppercase() !in ALLOWED_METHODS) {
            throw IllegalArgumentException("Moltbook: метод $method запрещён, разрешено $ALLOWED_METHODS")
        }
    }

    companion object {
        const val DEFAULT_HOST = "https://www.moltbook.com"
        const val OWN_ACCOUNT = "opencodekz"
        val OWN_NAMES_LOWER = setOf(OWN_ACCOUNT.lowercase())

        /** Единственные методы, которые клиент вправе отправить платформе. */
        val ALLOWED_METHODS = setOf("GET", "POST", "DELETE")
        const val SORT_NEW = "new"

        /** Корневых комментариев за страницу. Вложенные приезжают вместе с родителями. */
        const val COMMENTS_PAGE_LIMIT = 100

        /**
         * Потолок страниц — 3, а не «сколько понадобится».
         *
         * `limit=100` возвращает корневые комментарии, а вложенные едут вместе с
         * родителями, поэтому вторая страница нужна только треду с сотней корневых
         * комментариев. На замере 06.10.2026 при limit=3 вышло 3+3+2 = 8 корневых
         * за три страницы, при limit=100 тред умещается в одну.
         *
         * Потолок ограничен намеренно. Каждая страница — отдельный запрос с
         * 45-секундным дедлайном в пуле из четырёх потоков, где зависший резолв
         * слот не отпускает: тик зовёт `comments()` для трёх постов плюс
         * `ourReplyTo` перед каждым ответом. Наивные «пока has_more» превращали
         * один тик в десятки запросов и оставляли пул исчерпанным НАВСЕГДА — после
         * этого каждый следующий запрос ждал в очереди и падал по таймауту, то
         * есть Moltbook не работал до перезапуска процесса. Три страницы вместо
         * тридцати держат худший случай в разумных границах.
         */
        const val COMMENTS_MAX_PAGES = 3
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

        /**
         * Обход страниц треда, вынесенный из сети отдельной чистой функцией.
         *
         * Не ради тестовой подтасовки: пока обход жил внутри `comments()`, он был
         * единственным местом в диффе, которое нельзя было проверить без сервера, и
         * ревью нашло в нём три реальных бага подряд — откат к `has_more`, тихое
         * обрезание на пустой странице продолжения и молчаливое усечение на потолке
         * страниц. Все три давали одно и то же: `ourReplyTo` решал «мы не отвечали»
         * по неполной выборке и публиковал дубль.
         *
         * Контракт: вернуть комментарии треда либо бросить IOException, если
         * достоверность выборки под вопросом. Частичный ответ здесь недопустим — именно
         * он и был багом.
         */
        internal fun collectPagedComments(fetch: (String?) -> JSONObject): List<Comment> {
            val out = ArrayList<Comment>()
            var cursor: String? = null
            var pages = 0
            while (true) {
                val page = fetch(cursor)
                val parsed = parseComments(page)
                out.addAll(parsed)
                pages++
                // Продолжение определяется НАЛИЧИЕМ КУРСОРА, а не флагом has_more.
                // has_more в живых ответах есть (замерено 06.10.2026 на limit=3:
                // true/true/false), но опираться на него нельзя: у него нет записи ни
                // в одном логе или фикстуре репозитория, а его исчезновение молча
                // сворачивает пагинацию в одну страницу — и `ourReplyTo` снова не
                // видит наш ответ со второй страницы.
                val next = page.cursor()
                // Запрошенная страница продолжения без единого комментария — подозрительное
                // чтение, и проверка должна идти ДО проверки курсора: пустой ответ
                // без курсора иначе выглядит как «тред кончился». Отличить одно от
                // другого по одному ответу нельзя, а трактовать как конец нельзя тем
                // более: `ourReplyTo` решил бы Absent по обрезанной выборке и
                // опубликовал второй ответ на тот же комментарий. Первая страница
                // пустая — это честный пустой тред, её пропускаем.
                if (parsed.isEmpty() && cursor != null) {
                    throw IOException("Moltbook: страница продолжения пуста — чтение ненадёжно")
                }
                //
                // На последней странице платформа курсор не кладёт вовсе, но может
                // положить явный JSON null, а Android-овский optString вернёт строку
                // "null" — она непустая, и такой курсор ушёл бы в запрос как
                // cursor=null → 400. Это отсекает JSONObject.cursor(). Отсутствие
                // курсора означает ровно одно: страниц нет.
                if (next == null) break
                // Повтор курсора означает, что сервер не двигает окно: второй круг
                // вернул бы те же комментарии в out и зациклил бы чтение до потолка.
                if (next == cursor) {
                    throw IOException("Moltbook: курсор повторился — чтение ненадёжно")
                }
                cursor = next
                // Потолок достигнут, а курсор ещё есть — выборка заведомо неполная.
                // Молча возвращать её нельзя по той же причине, что и выше.
                // COMMENTS_MAX_PAGES * COMMENTS_PAGE_LIMIT корневых (сейчас 300) — это
                // тревожный звонок, а не норма: поднимай потолок явно.
                if (pages >= COMMENTS_MAX_PAGES) {
                    throw IOException("Moltbook: тред не прочитан за $COMMENTS_MAX_PAGES страниц — чтение ненадёжно")
                }
            }
            return out
        }

        /** Ключи id опубликованного комментария — форма ответа у платформы гуляет. */
        val SUCCESS_ID_KEYS = arrayOf("id", "comment_id", "commentId")

        /** Статус проверки приходит и змейкой, и верблюжьим — берём любое из двух. */
        val VERIFICATION_STATUS_KEYS = arrayOf("verification_status", "verificationStatus")

        /** Статус «создан, но не опубликован» — единственный, кого нельзя считать ответом. */
        const val STATUS_PENDING = "pending"

        /** Длина префикса id в логе: целиком он не нужен, а код задачи засоряет вывод. */
        const val ID_LOG_CHARS = 8

        /**
         * Все места ответа, где может лежать наш комментарий: корень, `comment`,
         * `data`, `data.comment`.
         *
         * Один обход на все вопросы к форме ответа вместо четырёх почти одинаковых
         * цепочек `optJSONObject`. Раньше каждая проверка искала в своей глубине, и
         * `verification` — в корне, тогда как платформа кладёт его внутрь `comment`
         * (замерено 07.10.2026). Из-за этой единственной несовпавшей глубины /verify
         * не вызывался ни разу.
         */
        internal fun commentScopes(root: JSONObject): List<JSONObject> {
            val scopes = ArrayList<JSONObject>()
            var cursor: JSONObject? = root
            repeat(SCAN_DEPTH) {
                val current = cursor ?: return@repeat
                scopes += current
                current.optJSONObject("comment")?.let { scopes += it }
                cursor = current.optJSONObject("data")
            }
            return scopes
        }

        /** Задача платформы либо null, если её не было. Пустой код — не задача. */
        internal fun verificationChallenge(root: JSONObject): JSONObject? =
            commentScopes(root).firstNotNullOfOrNull { scope ->
                scope.optJSONObject("verification")?.takeIf { it.optString("verification_code").isNotEmpty() }
            }

        /** Статус проверки из ответа либо пустая строка «сервер не отдавал». */
        internal fun verificationStatusOf(root: JSONObject): String =
            commentScopes(root)
                .firstNotNullOfOrNull { scope ->
                    VERIFICATION_STATUS_KEYS.firstNotNullOfOrNull { key ->
                        scope.optString(key).takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                    }
                }.orEmpty()

        /** Кому отвечает комментарий из ответа — нужно, чтобы отличить «наш ответ на этот вопрос» от «наш ответ на другой». */
        internal fun commentParentId(root: JSONObject): String? =
            commentScopes(root).firstNotNullOfOrNull { scope ->
                scope.optString("parent_id").takeIf { it.isNotEmpty() }
            }

        /**
         * Сервер не создал ничего нового.
         *
         * Отдельная функция, а не `optBoolean` в вызывающем коде: этот флаг меняет смысл
         * всего ответа, и его проверка обязана быть видна в сигнатуре.
         */
        internal fun isAlreadyExisted(root: JSONObject): Boolean = root.optBoolean(ALREADY_EXISTED_KEY)

        const val ALREADY_EXISTED_KEY = "already_existed"

        /** Глубина обхода ответа: `data.comment` — предел, дальше платформа не вкладывается. */
        private const val SCAN_DEPTH = 3

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

        internal fun parseComments(root: JSONObject): List<Comment> {
            val entries = root.optJSONArray("comments")
            val (parsed, broken) = collectComments(entries)
            // Громкая ошибка — только когда не разобрался НИ ОДИН узел, и только
            // потому что он БИТЫЙ. Это смена формата ответа, а не edge case, и
            // молчаливый пустой список прочитался бы как «мы тут не отвечали».
            //
            // Отдельно от мёртвых узлов: тред, где все комментарии удалены или
            // failed, — это пустой результат, а не поломка. Раньше такая проверка
            // читала его как «формат изменилась» и падала НАВСЕГДА: пост не попадал
            // в ledger, счётчик не гасился, ourReplyTo давал вечный Unknown, и
            // ответы в этот пост не публиковались никогда.
            if (parsed.isEmpty() && broken > 0) {
                throw IOException("Moltbook: не разобран ни один комментарий из $broken — формат изменилась")
            }
            return parsed
        }

        /**
         * Дерево в плоский список, вглубь и без ограничения.
         *
         * Это не оптимизация, а починка: ответ, который мы дали на комментарий
         * верхнего уровня, приезжает в `replies[]` ЭТОГО комментария, на depth=1+.
         * Разбор брал только корневой массив, поэтому наш собственный ответ был
         * не виден никогда — `probeIn` возвращал Absent при опубликованном ответе,
         * а `answeredByUs` не видел его же. Префлит против дублей не срабатывал
         * НИ РАЗУ, и тик отвечал повторно. Замерено на живом API 06.10.2026:
         * в треде 29 комментариев, 8 корневых, наш ответ лежал на depth=3.
         *
         * Список плоский, поэтому порядок обхода ни на что не влияет — важно лишь,
         * что каждый уровень попал в результат. Порядок родителей перед детьми
         * сохраняется: так `answeredByUs` видит ответ раньше, чем сам комментарий,
         * на который он отвечает.
         *
         * Узел судится по СВОИМ флагам. Родительский `is_deleted` вниз не
         * распространяется: молча выбрасывать наш живой ответ вместе с удалённым
         * родителем нельзя, а обратное (считать удалённое веткой ответа) —
         * можно, это закрывает is_deleted в самом узле.
         */
        private fun collectComments(entries: JSONArray?): Pair<List<Comment>, Int> {
            if (entries == null) return emptyList<Comment>() to 0
            val out = ArrayList<Comment>()
            var broken = 0
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                if (!isDeadComment(entry)) {
                    // Один битый узел не должен ронять весь тред: обход идёт по всему
                    // дереву, и раньше требовательный requireField смотрел только на
                    // корневые. Пустая вложенка или коммент без поля — пропускаем узел
                    // и идём дальше, теряя один комментарий вместо всех.
                    val built = runCatching { buildComment(entry) }.getOrNull()
                    if (built == null) broken++ else out.add(built)
                }
                // Счётчик битых узлов суммируется по всему дереву, а не по уровню:
                // решение «формат изменилась» принимает корень, и ему нужно знать
                // общее число, а не число на верхнем уровне.
                val (children, childBroken) = collectComments(entry.optJSONArray("replies"))
                out.addAll(children)
                broken += childBroken
            }
            return out to broken
        }

        /**
         * Разбор уже прочитанной ветки в ответ «мы уже отвечали?».
         *
         * Вынесено отдельно от сети не ради тестовой подтасовки, а потому что это
         * единственное место, где принимается решение «публиковать или молчать».
         * Проверять его можно на списке комментариев, без сервера и без ключа.
         *
         * Три исхода, а не два. `pending` — это ответ, которого не видно: он создан,
         * но не опубликован, и платформа не даст ответить на этот вопрос заново. Значит
         * молчать надо ЧЕСТНО («ждёт проверки»), а не рапортовать об успехе.
         */
        fun probeIn(
            comments: List<Comment>,
            parentId: String,
        ): ReplyProbe {
            val existing = comments.firstOrNull { it.isOurs && it.parentId == parentId }
                ?: return ReplyProbe.Absent
            return if (existing.isPublished) {
                ReplyProbe.Found(existing.id)
            } else {
                ReplyProbe.Unpublished(existing.id, existing.verificationStatus.ifEmpty { STATUS_PENDING })
            }
        }

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
                // Платформа отдаёт статус в двух написаниях сразу; берём любое, а
                // отсутствие — это «неизвестно», а не «не опубликовано».
                verificationStatus = verificationStatusOf(entry),
            )

        /**
         * Что ответу нельзя делать: он не отвечает, сколько бы ни провисел.
         *
         * Отсечка здесь, в разборе, а не в проверках. Удалённый и не прошедший
         * verification комментарий — это не ответ на вопрос, и ветка после него
         * должна оставаться открытой. Оставив их в списке, мы однажды получали
         * «мы уже отвечали» на комментарий, которого публично нет вообще, и
         * больше не отвечали никогда.
         *
         * `pending` в список ОСТАЁТСЯ, хотя он тоже не отвечает. Причина в том, что
         * о нём надо сообщить, а не сделать вид, что его нет: он блокирует новые ответы
         * на посте (замерено 07.10.2026 — сервер отдал `already_existed`), и его id
         * нужен в дайджесте, чтобы человек почистил тред. Выкинуть его молча нельзя —
         * тогда ветка выглядит нетронутой и залипает навсегда. Публикацию при этом
         * блокирует `probeIn`, а не разбор: `pending` даёт `Unpublished`, а не `Found`.
         */
        private fun isDeadComment(entry: JSONObject): Boolean {
            if (entry.optBoolean("is_deleted")) return true
            return entry.optString("verification_status").equals("failed", ignoreCase = true)
        }

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
