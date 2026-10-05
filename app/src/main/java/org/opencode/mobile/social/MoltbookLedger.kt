package org.opencode.mobile.social

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * Локальный учёт Moltbook. До него «сколько не отвечено» вычислялось пересчётом
 * дерева комментов на каждом тике, и цифра жила ровно до конца тика: упал тик —
 * потеряли счёт, а если забыть `read-by-post`, то на следующем тике тот же коммент
 * снова «ждёт ответа» и мы отвечаем в него дважды.
 *
 * Здесь состояние переживает тик. Один коммент = одна строка со статусом; статус
 * двигается только вперёд (NEW → POSTED/SKIPPED/FAILED) и никогда не откатывается,
 * поэтому повторный проход по той же ленте не может «разбудить» уже закрытый коммент.
 */
internal class MoltbookLedger(
    context: Context,
) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    /** Статус комментария. Хранится строкой — парсится в enum на границе чтения. */
    enum class CommentStatus {
        /** Найден, ответа ещё нет. Единственное состояние, из которого берётся работа. */
        NEW,

        /** Ответили (в т.ч. после verification). */
        POSTED,

        /** Ответ не нужен: наш собственный, репост, спам, автор закрыт. */
        SKIPPED,

        /** Попытались и не вышло: сеть, WAF, verification. Коммент не «сгорает». */
        FAILED,
    }

    data class PendingReply(
        val commentId: String,
        val postId: String,
        val postTitle: String,
        val author: String,
        val body: String,
        /** Русский перевод вопроса одной строкой. Пусто — модель ещё не переводила. */
        val summaryRu: String = "",
    )

    /** Всё, что показывает панель «М». Один снимок — панель рисуется без запросов в сеть. */
    data class Stats(
        val awaitingReply: Int = 0,
        val repliedTotal: Int = 0,
        val failedTotal: Int = 0,
        val ourPosts: Int = 0,
        val repliesToOurPosts: Int = 0,
        val repostsOfOurs: Int = 0,
        val upvotesGiven: Int = 0,
        val unread: Int = 0,
        val karma: Int = 0,
        val lastTickAt: Long = 0L,

        /** Когда агент обещал вернуться (абсолютное время, мс). 0 — ещё не решал. */
        val nextVisitAt: Long = 0L,
        val lastError: String? = null,
        val pending: List<PendingReply> = emptyList(),
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE posts (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL DEFAULT '',
                author TEXT NOT NULL DEFAULT '',
                ours INTEGER NOT NULL DEFAULT 0,
                repost_of TEXT,
                comments_total INTEGER NOT NULL DEFAULT 0,
                upvotes INTEGER NOT NULL DEFAULT 0,
                seen_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE comments (
                id TEXT PRIMARY KEY,
                post_id TEXT NOT NULL,
                author TEXT NOT NULL DEFAULT '',
                body TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                our_reply_id TEXT,
                summary_ru TEXT NOT NULL DEFAULT '',
                seen_at INTEGER NOT NULL,
                replied_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE upvotes (
                post_id TEXT PRIMARY KEY,
                decided_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE ticker_state (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_comments_status ON comments(status)")
        db.execSQL("CREATE INDEX idx_comments_post ON comments(post_id)")
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        // Миграция ТОЛЬКО на добавление колонок, и это не формальность.
        //
        // Раньше здесь стоял DROP всех таблиц с пересозданием («учёт — производные
        // данные»). Для счётчиков это правда, но в этих таблицах лежит не только
        // счётчик: там статусы POSTED и our_reply_id. Снеси их — и следующий тик
        // увидит уже отвеченные комменты как NEW, то есть агент опубликует ДУБЛИ
        // своих ответов в публичной ленте. Дубли на Moltbook удалить нельзя.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE comments ADD COLUMN summary_ru TEXT NOT NULL DEFAULT ''")
        }
        // Дальше — по одному шагу на версию: if (oldVersion < 3) { ... }
    }

    /** Наш пост или чужой — как есть с сервера. [repostOf] заполняется сканом ленты. */
    fun upsertPost(
        postId: String,
        title: String,
        author: String,
        ours: Boolean,
        repostOf: String?,
        commentsTotal: Int,
        upvotes: Int,
        now: Long,
    ) {
        writableDatabase.insertWithOnConflict(
            "posts",
            null,
            ContentValues().apply {
                put("id", postId)
                put("title", title)
                put("author", author)
                put("ours", if (ours) 1 else 0)
                if (repostOf == null) putNull("repost_of") else put("repost_of", repostOf)
                put("comments_total", commentsTotal)
                put("upvotes", upvotes)
                put("seen_at", now)
                put("updated_at", now)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /**
     * Новый коммент в ленте. Статус выставляется только при ПЕРВОМ появлении: повторный
     * проход по той же ленте не имеет права переводить POSTED обратно в NEW, иначе мы
     * отвечаем на один и тот же коммент каждый тик.
     */
    fun upsertComment(
        commentId: String,
        postId: String,
        author: String,
        body: String,
        createdAt: Long,
        now: Long,
    ) {
        val existing = statusOf(commentId)
        if (existing == null) {
            writableDatabase.insert(
                "comments",
                null,
                ContentValues().apply {
                    put("id", commentId)
                    put("post_id", postId)
                    put("author", author)
                    put("body", body)
                    put("created_at", createdAt)
                    put("status", CommentStatus.NEW.name)
                    put("seen_at", now)
                    put("replied_at", 0L)
                },
            )
            return
        }
        // Коммент уже известен: освежаем только текст. Статус, our_reply_id и
        // replied_at — наша же история, и затирать её повторным сканом ленты нельзя,
        // иначе «отвечено» снова превратится в «ждёт ответа».
        writableDatabase.update(
            "comments",
            ContentValues().apply {
                put("post_id", postId)
                put("author", author)
                put("body", body)
                put("created_at", createdAt)
            },
            "id = ?",
            arrayOf(commentId),
        )
    }

    fun statusOf(commentId: String): CommentStatus? {
        readableDatabase
            .query("comments", arrayOf("status"), "id = ?", arrayOf(commentId), null, null, null)
            .use { c ->
                if (!c.moveToFirst()) return null
                return CommentStatus.valueOf(c.getString(0))
            }
    }

    fun markComment(
        commentId: String,
        status: CommentStatus,
        ourReplyId: String? = null,
        now: Long,
    ) {
        val values =
            ContentValues().apply {
                put("status", status.name)
                put("replied_at", now)
                if (ourReplyId != null) put("our_reply_id", ourReplyId)
            }
        writableDatabase.update("comments", values, "id = ?", arrayOf(commentId))
    }

    fun recordUpvote(
        postId: String,
        now: Long,
    ) {
        writableDatabase.insertWithOnConflict(
            "upvotes",
            null,
            ContentValues().apply {
                put("post_id", postId)
                put("decided_at", now)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun hasUpvoted(postId: String): Boolean =
        readableDatabase
            .query("upvotes", arrayOf("post_id"), "post_id = ?", arrayOf(postId), null, null, null)
            .use { it.moveToFirst() }

    fun putState(
        key: String,
        value: String,
    ) {
        writableDatabase.insertWithOnConflict(
            "ticker_state",
            null,
            ContentValues().apply {
                put("key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun state(key: String): String? =
        readableDatabase
            .query("ticker_state", arrayOf("value"), "key = ?", arrayOf(key), null, null, null)
            .use { c ->
                if (!c.moveToFirst()) null else c.getString(0)
            }

    /** Наши посты — ими же ищем репосты в ленте. */
    /** Репост засчитан один раз: иначе один и тот же пересказ попадёт в дайджест каждый тик. */
    fun hasSeenRepost(postId: String): Boolean =
        state("$KEY_REPOST_SEEN$postId") != null

    fun markRepostSeen(postId: String) {
        putState("$KEY_REPOST_SEEN$postId", "1")
    }

    fun ourPostIds(): List<String> =
        readableDatabase
            .query("posts", arrayOf("id"), "ours = 1", null, null, null, null)
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(c.getString(0))
                }
            }

    /**
     * Работа на тик: комменты в статусе NEW, самые старые первыми. [limit] —
     * предохранитель от 9 ответов за один проход; лишнее остаётся в NEW и доедет
     * следующим тиком.
     *
     * Фильтра по автору ПОСТА тут намеренно нет: `activity_on_your_posts` — это
     * активность НА НАШИХ постах, и отвечать на комменты под своим постом как раз
     * нужно (карма с этого и растёт).
     *
     * А вот фильтр по автору КОММЕНТА — обязателен, и он здесь, а не только в
     * `deservesReply`: единственный способ узнать, что очередь чистая, — не
     * опубликовать агентом ответ самому себе. Автономный агент, пишущий в
     * публичную ленту, получает второй эшелон и на уровне выборки.
     */
    fun pendingReplies(limit: Int): List<PendingReply> =
        readableDatabase
            .rawQuery(
                """
                SELECT c.id, c.post_id, p.title, c.author, c.body, c.summary_ru
                FROM comments c JOIN posts p ON p.id = c.post_id
                WHERE c.status = ? AND LOWER(c.author) != ?
                ORDER BY c.seen_at ASC, c.created_at ASC
                LIMIT ?
                """.trimIndent(),
                arrayOf(CommentStatus.NEW.name, OUR_AGENT.lowercase(), limit.toString()),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            PendingReply(
                                commentId = c.getString(0),
                                postId = c.getString(1),
                                postTitle = c.getString(2),
                                author = c.getString(3),
                                body = c.getString(4),
                                summaryRu = c.getString(5).orEmpty(),
                            ),
                        )
                    }
                }
            }

    /**
     * Русский перевод вопроса от той же модели, что и ответы, — отдельным вызовом
     * не ходим: лишний запрос на каждый открытый список это и лишние токены, и
     * задержка отрисовки панели. Пустая строка — перевода ещё нет, панель
     * показывает оригинал.
     */
    fun setGloss(
        commentId: String,
        summaryRu: String,
    ) {
        if (summaryRu.isBlank()) return
        writableDatabase.update(
            "comments",
            ContentValues().apply { put("summary_ru", summaryRu) },
            "id = ?",
            arrayOf(commentId),
        )
    }

    /** Счётчик непрочитанного снимаем только когда у поста не осталось NEW. */
    fun postHasNoPending(postId: String): Boolean =
        readableDatabase
            .rawQuery("SELECT 1 FROM comments WHERE post_id = ? AND status = ? LIMIT 1", arrayOf(postId, CommentStatus.NEW.name))
            .use { !it.moveToFirst() }

    fun stats(pendingLimit: Int = 20): Stats {
        val db = readableDatabase
        fun count(
            table: String,
            where: String,
            args: Array<String> = emptyArray(),
        ): Int =
            db.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", args).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }

        return Stats(
            // Тот же набор, что отдаёт pendingReplies: наши собственные комменты
            // исключены. Раньше «ждут ответа» считал их, и панель показывала 6 при
            // списке из 4 — то есть обещала работу, которой агент не сделает никогда.
            awaitingReply =
                count(
                    "comments",
                    "status = ? AND LOWER(author) != ?",
                    arrayOf(CommentStatus.NEW.name, OUR_AGENT.lowercase()),
                ),
            repliedTotal = count("comments", "status = ?", arrayOf(CommentStatus.POSTED.name)),
            failedTotal = count("comments", "status = ?", arrayOf(CommentStatus.FAILED.name)),
            ourPosts = count("posts", "ours = 1"),
            repliesToOurPosts =
                db.rawQuery(
                    "SELECT COUNT(*) FROM comments c JOIN posts p ON p.id = c.post_id WHERE p.ours = 1 AND c.author != ?",
                    arrayOf(OUR_AGENT),
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 },
            repostsOfOurs = count("posts", "repost_of IS NOT NULL"),
            upvotesGiven = count("upvotes", "1 = 1"),
            unread = state(KEY_UNREAD)?.toIntOrNull() ?: 0,
            karma = state(KEY_KARMA)?.toIntOrNull() ?: 0,
            lastTickAt = state(KEY_LAST_TICK)?.toLongOrNull() ?: 0L,
            nextVisitAt = state(KEY_NEXT_VISIT_AT)?.toLongOrNull() ?: 0L,
            lastError = state(KEY_LAST_ERROR),
            pending = pendingReplies(pendingLimit),
        )
    }

    companion object {
        const val TAG = "MoltbookLedger"
        const val KEY_KARMA = "karma"
        const val KEY_UNREAD = "unread"
        const val KEY_LAST_TICK = "last_tick"

        /** Абсолютное время следующего визита: панель показывает его, а не выдумывает. */
        const val KEY_NEXT_VISIT_AT = "next_visit_at"
        const val KEY_LAST_ERROR = "last_error"
        private const val KEY_REPOST_SEEN = "repost_seen:"
        const val OUR_AGENT = "opencodekz"
        private const val DB_NAME = "moltbook.db"
        private const val DB_VERSION = 2
    }
}
