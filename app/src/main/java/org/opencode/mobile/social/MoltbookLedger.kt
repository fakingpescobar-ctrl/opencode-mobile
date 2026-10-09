package org.opencode.mobile.social

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

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

    /**
     * Чем закончился ответ: не «какой статус», а ЧТО мы умеем утверждать.
     *
     * Статус [CommentStatus.POSTED] одинаков у ответа, который мы создали и прочитали
     * обратно, и у ответа, который сервер вернул как уже существующий. Считать по
     * статусу — значит отчитаться «ответили» там, где мы ничего не создали (дефект 2).
     * Разводить их отдельной колонкой, а счётчик получать пересчётом по журналу —
     * ровно то, чего требует сообщество (MOLTBOOK_ADVICE 3.13: «счётчик должен быть
     * проекцией журнала, не отдельным хранилищем») и 3.2 (`REUSED_OBJECT_ID` — ноль
     * новых подтверждённых фактов).
     *
     * Пустое значение в базе (строки, записанные до v5) = «не знаем» и в счётчик не
     * попадает: пересчёт должен быть консервативным, иначе он врёт на старой базе.
     */
    enum class ReplyOutcome(val wire: String) {
        /** Создали новый комментарий, id получили. Ждёт verification или уже verified. */
        CREATED("created"),

        /** Создали и подтвердили проверкой. */
        VERIFIED("verified"),

        /** Ничего не создано: наш прошлый ответ вернулся как уже существующий. */
        REUSED("reused"),
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
                replied_at INTEGER NOT NULL DEFAULT 0,
                reply_outcome TEXT NOT NULL DEFAULT ''
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
        db.execSQL(CREATE_SEEN_TABLE)
        db.execSQL("CREATE INDEX idx_comments_status ON comments(status)")
        db.execSQL("CREATE INDEX idx_comments_post ON comments(post_id)")
        db.execSQL(CREATE_SEEN_INDEX_POST)
        db.execSQL(CREATE_SEEN_INDEX_AT)
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
        // Дальше — по одному шагу на версию.
        //
        // v3: журнал прочитанного. Таблица новая и пустая по смыслу (что мы уже
        // видели — известно только из ленты, восстановить нечего), поэтому
        // наполнять её нечем и не нужно: первый же скан запишет текущее состояние
        // как «новое», и это честно — прошлого наблюдения у нас не было.
        if (oldVersion < SCHEMA_V3) {
            db.execSQL(CREATE_SEEN_TABLE_V3)
            db.execSQL(CREATE_SEEN_INDEX_POST)
            db.execSQL(CREATE_SEEN_INDEX_AT)
        }
        // v4: счётчик наблюдений. Без него «пережил скан» приходилось выводить
        // из времени последнего прочтения, а порог в часах меньше интервала
        // тика (2-12 ч) срабатывал на первом же пропуске, то есть мигание
        // пагинации объявлялось исчезновением. Накопленное наблюдение — прямой
        // признак «пережил», и он не зависит от того, как часто тикает тикер.
        //
        // Колонка добавляется ОТДЕЛЬНОЙ веткой по той же причине, по какой
        // ветка v3 создаёт таблицу БЕЗ `hits`: обе строки проходят подряд при
        // обновлении с версии 2, и создание уже с колонкой сделало бы следующий
        // ALTER дубликатом — `SQLiteException: duplicate column name` на первом
        // же обращении к базе, то есть у всех, кто обновляется с текущей версии.
        if (oldVersion < SCHEMA_V4) {
            db.execSQL(ADD_HITS_COLUMN_V4)
        }
        // v5: чем закончился ответ. Статус POSTED одинаков у созданного нами
        // комментария и у уже существующего, который вернул сервер, — а тик обязан
        // различать их хотя бы в счётчике. Пустое значение у старых строк означает
        // «не знаем» и в пересчёт не попадает: консервативно.
        if (oldVersion < SCHEMA_V5) {
            db.execSQL(ADD_REPLY_OUTCOME_COLUMN_V5)
        }
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
        outcome: ReplyOutcome? = null,
    ) {
        val values =
            ContentValues().apply {
                put("status", status.name)
                put("replied_at", now)
                // FAILED означает «не вышло сейчас», а не «не нужно никогда»: без
                // сдвига метки времени невозможно отличить свежую неудачу от той,
                // что пять часов назад. Именно по seen_at считается кулдаун до
                // следующей попытки. Для остальных статусов seen_at не трогаем —
                // это время попадания комментария в очередь, и сдвигать его нельзя.
                if (status == CommentStatus.FAILED) put("seen_at", now)
                if (ourReplyId != null) put("our_reply_id", ourReplyId)
                // Исход пишется ТОЛЬКО когда его утверждаем (Created/Verified/Reused).
                // Для остальных статусов колонка не трогается: пустое значит «не знаем»,
                // и пересчёт по журналу такие строки пропускает.
                if (outcome != null) put("reply_outcome", outcome.wire)
            }
        writableDatabase.update("comments", values, "id = ?", arrayOf(commentId))
    }

    /**
     * Сколько ответов тик [since] реально создал и записал как подтверждённые.
     *
     * Не счётчик в памяти, а пересчёт по журналу — по требованию MOLTBOOK_ADVICE 3.13
     * («счётчик должен быть проекцией журнала, не отдельным хранилищем») и 3.2:
     * [ReplyOutcome.REUSED] в подсчёт не входит, потому что это ноль новых фактов.
     * Ветку, где исход потерян, пересчёт исправить не может — она и не должна его
     * чинить, она обязана быть заметной в тике.
     *
     * Окно — по `replied_at` тика, а не по времени записи строки: два прохода
     * с одним и тем же `now` не должны разъезжаться.
     */
    fun createdRepliesSince(since: Long): Int =
        readableDatabase.rawQuery(
            CREATED_SQL,
            arrayOf(
                CommentStatus.POSTED.name,
                ReplyOutcome.CREATED.wire,
                ReplyOutcome.VERIFIED.wire,
                since.toString(),
            ),
        ).use { rows -> if (rows.moveToFirst()) rows.getInt(0) else 0 }

    /** Ответов, прошедших проверку. Считается так же, как [createdRepliesSince]. */
    fun verifiedRepliesSince(since: Long): Int =
        readableDatabase.rawQuery(
            VERIFIED_SQL,
            arrayOf(CommentStatus.POSTED.name, ReplyOutcome.VERIFIED.wire, since.toString()),
        ).use { rows -> if (rows.moveToFirst()) rows.getInt(0) else 0 }

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

    /**
     * Убирает ключ совсем, а не пишет пустую строку: панель проверяет
     * `lastError != null`, и пустая строка нарисовала бы «последняя ошибка: » с
     * ничего не значащим хвостом.
     */
    fun clearState(key: String) {
        writableDatabase.delete("ticker_state", "key = ?", arrayOf(key))
    }

    fun state(key: String): String? =
        readableDatabase
            .query("ticker_state", arrayOf("value"), "key = ?", arrayOf(key), null, null, null)
            .use { c ->
                if (!c.moveToFirst()) null else c.getString(0)
            }

    /** Наши посты — ими же ищем репосты в ленте. */

    /** Репост засчитан один раз: иначе один и тот же пересказ попадёт в дайджест каждый тик. */
    fun hasSeenRepost(postId: String): Boolean = state("$KEY_REPOST_SEEN$postId") != null

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
     * Комментарий, который опубликовали МЫ, и пост, под которым он живёт.
     *
     * Это id ответа, а не id чужого комментария, на который мы отвечали: колонка
     * `our_reply_id` хранит то, что вернул сервер в момент нашей публикации. Без
     * этого уточнения легко снести чужой вопрос вместо своего ответа.
     */
    data class OurReply(
        val commentId: String,
        val postId: String,
    )

    /**
     * Наши ответы — кандидаты в свип: записи, где сервер отдал id, а в ленте его
     * может не оказаться.
     *
     * Сверка с сервером обязательна, а не «снести всё, что в `our_reply_id`»: старый
     * код писал туда id КАЖДОГО своего ответа, включая успешно проверенные (замерено
     * 07.10.2026 — он считал `pending` успехом и уходил дальше). Снести выборку целиком
     * значило бы удалить живые ответы, которые в публичной ленте читают люди, и
     * которые платформа пересоздать не даст. Поэтому колонка — только адрес
     * кандидата, а решение принимается после чтения ветки.
     *
     * Порядок — от самых старых ответов: непроверенный коммент старше всех и держит
     * ветку дольше всех, а `[limit]` не даёт тику превратиться в двадцать запросов
     * подряд. Остаток доедет следующим.
     *
     * `GROUP BY our_reply_id` — не украшение, а производительность с правильностью:
     * один и тот же ответ лежит в `comments` несколькими строками (замерено 08.10.2026
     * — id `009eb106` встречался трижды), и без свёрки свип отправлял бы по одному
     * `DELETE` на каждую копию. Лимит при этом съедался дублями: из двадцати строк на
     * треть постов доезжало семь уникальных комментов вместо двадцати.
     */
    fun ourReplies(limit: Int): List<OurReply> =
        readableDatabase
            .rawQuery(
                """
                SELECT our_reply_id, MIN(post_id) FROM comments
                WHERE our_reply_id IS NOT NULL AND our_reply_id != ''
                GROUP BY our_reply_id
                ORDER BY MIN(replied_at) ASC, our_reply_id ASC
                LIMIT ?
                """.trimIndent(),
                arrayOf(limit.toString()),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(OurReply(commentId = c.getString(0), postId = c.getString(1)))
                    }
                }
            }

    /**
     * Снимает адрес из выборки свипа, НЕ трогая статус комментария.
     *
     * Вызывается в двух случаях, и разница между ними принципиальна. Ответ видим
     * читателям — он живой, забываем только адрес, чтобы не проверять его снова
     * каждый тик: `repliedTotal` и «уже ответили» при этом остаются в силе, а
     * повторный скан ленты не восстановит `our_reply_id` (см. `upsertComment`).
     *
     * Ответ удалён как непроверенный — целевой коммент в NEW мы НЕ возвращаем, и это
     * решение, а не недосмотр. Удалённый коммент всё равно виден в ленте со знаком
     * удаления, а вернуть его в NEW значит заставить агента заново ответить на тот же
     * вопрос и завести вторую ветку под ним. Тишина здесь честнее: неизвестно, ответил
     * ли кто-то ещё, а второй комментарий под тем же вопросом читается хуже, чем
     * отсутствие ответа от удалённого.
     */
    fun forgetOurReply(commentId: String) {
        writableDatabase.update(
            "comments",
            ContentValues().apply { putNull("our_reply_id") },
            "our_reply_id = ?",
            arrayOf(commentId),
        )
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

    /**
     * @param retryBefore снимает кулдаун с FAILED: в очередь вернутся те неудачи,
     *   что старше этого момента. По умолчанию `0` — то есть FAILED не берутся
     *   никогда, и это режим панели: показывать то, за что агент возьмётся сейчас.
     *
     * FAILED в обычной выборке НЕ участвует, и это была тихая потеря работы:
     * `upsertComment` при повторном скане сохраняет прежний статус, `scanPosts`
     * переводит в SKIPPED только NULL и NEW, поэтому помеченный FAILED комментарий
     * не попадал в очередь больше НИКОГДА. Один ответ модели `null` — и вопрос
     * человека исчезал навсегда, хотя комментарий в коде обещал «дождётся
     * следующего тика».
     */
    fun pendingReplies(
        limit: Int,
        retryBefore: Long = 0L,
    ): List<PendingReply> =
        readableDatabase
            .rawQuery(
                """
                SELECT c.id, c.post_id, p.title, c.author, c.body, c.summary_ru
                FROM comments c JOIN posts p ON p.id = c.post_id
                WHERE (c.status = ? OR (c.status = ? AND c.seen_at <= ?)) AND LOWER(c.author) != ?
                ORDER BY c.seen_at ASC, c.created_at ASC
                LIMIT ?
                """.trimIndent(),
                arrayOf(
                    CommentStatus.NEW.name,
                    CommentStatus.FAILED.name,
                    retryBefore.toString(),
                    OUR_AGENT.lowercase(),
                    limit.toString(),
                ),
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
                db
                    .rawQuery(
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

    /**
     * Комментарий, который мы уже прочитали в ленте — «слепой зоне памяти» между
     * сканами, у которой теперь есть доказательство прочтения.
     *
     * До 08.10.2026 (разбор therecordkeeper, ответ `a2708b49`) скан писал только
     * текущее состояние: между двумя проходами лента могла и порасти, и потерять
     * комментарии, и мы об этом не знали — а в журнал шёл только снимок «сейчас».
     * Эта строка и есть то, чем «сейчас» можно сверить с «тогда».
     *
     * @param status статус комментария на момент прочтения: смена статуса между
     *   сканами — самостоятельный факт для дайджеста, а не шум.
     * @param ours наш ли это комментарий: свои в очередь не берутся, и их появление
     *   тоже стоит показать («агент заговорил»).
     * @param at когда прочитали в ЭТОМ проходе. Время первого появления хранится
     *   отдельно, в `comments.seen_at`; здесь нужно время последнего прочтения,
     *   потому что по нему идёт усечение таблицы.
     */
    data class SeenComment(
        val postId: String,
        val commentId: String,
        val status: String,
        val ours: Boolean,
        val at: Long,
        /**
         * Сколько сканов подряд наблюдение было живым. Первое прочтение — 1.
         *
         * Существует ради одного вопроса, на который время отвечает неверно:
         * «комментарий пережил скан или мигнул и пропал». Порог в часах здесь
         * бесполезен — интервал тика (2-12 ч) больше любого разумного часового
         * окна, и исчезновение объявлялось на первом же пропуске. Накопленное
         * наблюдение отвечает прямо: видели дважды — пережил, видели один раз —
         * нет.
         */
        val hits: Int = 1,
    )

    /**
     * Что изменилось между прошлым сканом и этим.
     *
     * Отдельная структура, а не три счётчика, потому что дайджесту нужно не «сколько
     * изменилось», а ЧТО: «новых=3, один из них — наш ответ, один пропал» —
     * это факт, а «счётчик дельты равен 4» — нет.
     */
    data class ScanDelta(
        /** Комментариев не было в `seen`, а теперь есть. */
        val added: List<SeenComment> = emptyList(),
        /** Комментарий и наш, и прежний статус: `(было, стало)`. */
        val statusChanged: List<Pair<SeenComment, String>> = emptyList(),
        /** Были в `seen`, в этом проходе их не пришло. Только по полностью просканированным постам. */
        val vanished: List<SeenComment> = emptyList(),
    ) {
        /** Ничего не изменилось — это нормальный тик, а не повод для строки в дайджесте. */
        val isEmpty: Boolean get() = added.isEmpty() && statusChanged.isEmpty() && vanished.isEmpty()

        /**
         * Сводка для фактов дайджеста, на русском: «новых=3 сменило статус=1 исчезло=2».
         *
         * Формат «=» выбран не случайно: значения должны читаться как числа и
         * сразу после слова-подписи, иначе в тексте агента они становятся частью
         * прозы и модель начинает их пересказывать своими словами.
         */
        val summary: String
            get() = "новых=${added.size} сменило статус=${statusChanged.size} исчезло=${vanished.size}"
    }

    /**
     * Сверить прочитанное с журналом прочтений, ЗАТЕМ записать новое состояние.
     *
     * Одна операция, а не две, потому что разорванные «посчитал / записали» дают
     * ложную дельту при любом сбое между ними: процесс убили после чтения — и на
     * следующем тике весь пост выглядит «новым» целиком. Запись и усечение идут в
     * одной транзакции, чтобы усечение не видело промежуточного состояния.
     *
     * Исчезнувшие записи удаляются — см. разбор в [recordSeen]: пока строка лежала,
     * «исчезло» повторялось в каждой дельте и не могло потухнуть. Отличать
     * «пережил скан» от «мигнул» теперь не по часам, а по [SURVIVED_SCANS] —
     * часовой порог короче интервала тика и срабатывал на первом же пропуске.
     *
     * @param batch что тикер только что прочитал по одному или нескольким постам.
     *   ВАЖНО для исчезновений: пост считается просканированным только если в [batch]
     *   есть хоть бы один его комментарий, поэтому частичный обход (лимит страниц)
     *   не может объявить «исчезло» то, что просто не влезло в выборку.
     * @param now время прохода, единое для всей пачки: разные `at` внутри одного
     *   вызова означали бы, что на усечение влияет порядок обхода, и одинаковое
     *   состояние давало бы разный результат.
     */
    fun recordSeen(
        batch: List<SeenComment>,
        now: Long,
    ): ScanDelta {
        val scannedPosts = batch.map { it.postId }.toSet()
        if (scannedPosts.isEmpty()) return ScanDelta()

        val previous = seenOfPosts(scannedPosts)
        val delta = computeDelta(previous, batch, now)

        writableDatabase.beginTransaction()
        try {
            batch.forEach { upsertSeen(it, now) }
            // Исчезнувшие сносятся из журнала, и это ровно то, что делает
            // исчезновение ОДНОВРЕМЕННЫМ фактом. Пока запись лежала, она
            // попадала в `vanished` дельты КАЖДОГО следующего тика: «исчезло=3»
            // в дайджесте не тухло, а `ScanDelta.isEmpty` не мог стать истинным
            // никогда — то есть агент получал «что-то изменилось» без единого
            // изменения. Цена сноса — вернувшийся комментарий придёт как новый,
            // и агент ответит на него заново; зато «исчезло» означает ровно то,
            // что написано, и повторный проход такого комментария — уже не
            // повтор, а новая работа.
            delta.vanished.forEach { row ->
                writableDatabase.delete("seen", "comment_id = ?", arrayOf(row.commentId))
            }
            pruneSeen(now)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return delta
    }

    /** Всё, что журнал помнит по указанным постам: одна выборка, а не запрос на пост. */
    private fun seenOfPosts(postIds: Set<String>): List<SeenComment> =
        readableDatabase
            .rawQuery(
                "SELECT post_id, comment_id, status, ours, at, hits FROM seen WHERE post_id IN (${placeholders(postIds.size)})",
                postIds.toList().toTypedArray(),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            SeenComment(
                                postId = c.getString(0),
                                commentId = c.getString(1),
                                status = c.getString(2),
                                ours = c.getInt(3) != 0,
                                at = c.getLong(4),
                                hits = c.getInt(5),
                            ),
                        )
                    }
                }
            }

    private fun upsertSeen(
        row: SeenComment,
        now: Long,
    ) {
        // Счётчик наблюдений растёт, а не заменяется: `CONFLICT_REPLACE` обнулял
        // его при каждом прочтении, и «пережил скан» не наступало НИКОГДА.
        // Поэтому сначала UPDATE (он же признак «запись уже была»), и только
        // если он не задел ни строки — INSERT новой.
        //
        // `hits = hits + 1` считает БАЗУ, а не поле батча. Поле `hits` в
        // [SeenComment] приходит из скана, который эту запись не читал, и там
        // оно всегда 1: прибавлять его — значит застрять на 2 навсегда и тихо
        // сломать любой будущий порог больше двух сканов.
        val updated =
            writableDatabase.run {
                execSQL(
                    "UPDATE seen SET post_id = ?, status = ?, ours = ?, at = ?, hits = hits + 1 WHERE comment_id = ? AND post_id = ?",
                    arrayOf<Any>(
                        row.postId,
                        row.status,
                        if (row.ours) 1 else 0,
                        now,
                        row.commentId,
                        row.postId,
                    ),
                )
                compileStatement("SELECT changes()").use { st ->
                    st.simpleQueryForLong()
                }
            }
        if (updated > 0L) return
        writableDatabase.insertWithOnConflict(
            "seen",
            null,
            ContentValues().apply {
                put("post_id", row.postId)
                put("comment_id", row.commentId)
                put("status", row.status)
                put("ours", if (row.ours) 1 else 0)
                put("at", now)
                put("hits", 1)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /**
     * Усечение базы `seen` после записи батча.
     *
     * Два шага, а не один `DELETE` по объединённому условию: сначала срок жизни,
     * потом потолок по числу. Порядок значим — при одном запросе по объединённому
     * условию нельзя объяснить, КАКОЕ из двух правил сработало, а в дампе на
     * телефоне причина усечения читается только через неё.
     *
     * Порог по числу считается запросом, а не `LIMIT` со смещением: смещение между
     * двумя вызовами на одной и той же базе разные вещи (одна запись успела
     * прийти), а порог обязан быть воспроизводимым. Режется по `at <= edge` —
     * вместе с записью на границе, иначе таблица осталась бы на одну строну выше
     * потолка навсегда.
     */
    private fun pruneSeen(now: Long) {
        val cutoff = now - SEEN_KEEP_MS
        writableDatabase.delete("seen", "at < ?", arrayOf(cutoff.toString()))
        val total =
            readableDatabase.rawQuery("SELECT COUNT(*) FROM seen", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        if (total <= MAX_SEEN) return
        // Граница — ПАРА (время, id), а не одно время: весь батч пишется одним
        // `now`, поэтому `at <= edge` на равных временах вычищал за раз весь тик,
        // а не одну лишнюю строку. Продовый `pruneSeen` обязан оставлять ровно
        // столько, сколько оставляет тестируемый `prunePlan`, иначе проверка
        // чинит не тот код, который работает на телефоне.
        //
        // Знак по `comment_id` — `>`: граница берётся сортировкой ПО УБЫВАНИЮ
        // времени, то есть выживает «не меньше границы» в этом порядке, и при
        // равных временах выживает всё, что ПОЗЖЕ границы по id.
        var edgeAt = cutoff
        var edgeId = ""
        readableDatabase
            .rawQuery(
                "SELECT at, comment_id FROM seen ORDER BY at DESC, comment_id ASC LIMIT 1 OFFSET ?",
                arrayOf((MAX_SEEN - 1).toString()),
            ).use { c ->
                if (c.moveToFirst()) {
                    edgeAt = c.getLong(0)
                    edgeId = c.getString(1)
                }
            }
        writableDatabase.delete(
            "seen",
            "(at < ? OR (at = ? AND comment_id > ?))",
            arrayOf(edgeAt.toString(), edgeAt.toString(), edgeId),
        )
    }

    /**
     * Отпечаток состояния базы: доказательство «что было записано в конце тика».
     *
     * Тикер пишет его в журнал-свидетель в конце тика и в начале СЛЕДУЮЩЕГО
     * сверяет. Расхождение означает, что между тиками кто-то писал в базу мимо
     * тикера — руками, откатом, другим кодом. Объявить это порчей обязан сам
     * тикер: журнал, который молчит о расхождении, хуже отсутствия журнала,
     * потому что выглядит доказательством («всё сходится») при отсутствии
     * доказательства. Формулировка oomjo3 от 08.10.2026: «startup-diff,
     * доказывающий лог против диска — lest the liar become the memory».
     *
     * Детерминированность здесь не косметика, а условие работоспособности: в
     * preimage нет ни времени, ни unordered-обходов, иначе один и тот же проход
     * дал бы два разных хэша и каждое расхождение было бы ложной тревогой —
     * а ложная тревога обучает тикера игнорировать настоящую.
     *
     * Исключение НЕ пробрасывается: `"err:" + класс` позволяет тикеру записать в
     * журнал «порча» и продолжить работу, тогда как упавший тик не пишет ничего и
     * следующий тик увидит расхождение уже без причины.
     */
    fun ledgerDigest(): String =
        try {
            digestOf(snapshot())
        } catch (e: Exception) {
            // Ловим Exception, а не Throwable: OOM из хэширования — это падение
            // процесса, а не «база в непонятном состоянии», и подменять его строкой
            // нельзя: упавший тик не напишет ничего, и следующий тик увидит
            // расхождение уже без причины.
            "err:" + e.javaClass.simpleName
        }

    /**
     * Снимок состояния базы для [digestOf] — единственное место, где отпечаток
     * соприкасается с SQL.
     *
     * ЧТО НЕ ПОПАДАЕТ и почему. [KEY_LAST_TICK], [KEY_NEXT_VISIT_AT], [KEY_KARMA]
     * и [KEY_UNREAD] — это РЕЗУЛЬТАТ работы, а не состояние: их значения обязаны
     * отличаться от того, что было в конце прошлого тика, иначе тик не сделал бы
     * свою работу. Включать их в отпечаток нельзя — тогда расхождение стало бы
     * нормой, а не признаком порчи, и сверка в следующем тике превратилась бы в
     * гарантированно ложное «база менялась мимо нас».
     *
     * `ticker_state` читается выборочно: только ключи `repost_seen:*` (см.
     * [markRepostSeen]) и только счётчиком — они уже отражают решение
     * («репост учтён»), а не сырьё.
     *
     * Порядок строк задаётся запросами (`ORDER BY`), а не порядком обхода
     * результата: план SQLite для одного и того же текста может отличаться между
     * версиями движка, и тогда один и тот же проход дал бы два разных хэша.
     */
    private fun snapshot(): Map<String, String> {
        val db = readableDatabase
        val parts = linkedMapOf<String, String>()

        // Счётчики по статусам: без сортировки SQLite не гарантирует порядок
        // строк, а порядок в preimage обязан быть один и тот же при любом плане
        // запроса. Ключ приводится к строке явно — он идёт в подпись префикса.
        db.rawQuery("SELECT status, COUNT(*) FROM comments GROUP BY status ORDER BY status ASC", null).use { c ->
            while (c.moveToNext()) {
                parts["comments[" + c.getString(0) + "]"] = c.getInt(1).toString()
            }
        }

        fun count(
            sql: String,
            args: Array<String> = emptyArray(),
        ): Int = db.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

        parts["ourPosts"] = count("SELECT COUNT(*) FROM posts WHERE ours = 1").toString()
        parts["reposts"] = count("SELECT COUNT(*) FROM posts WHERE repost_of IS NOT NULL").toString()
        parts["upvotes"] = count("SELECT COUNT(*) FROM upvotes").toString()
        parts["seen"] = count("SELECT COUNT(*) FROM seen").toString()
        parts["repostSeen"] =
            count(
                "SELECT COUNT(*) FROM ticker_state WHERE key LIKE ?",
                arrayOf("$KEY_REPOST_SEEN%"),
            ).toString()

        // Наши ответы — ключ `our_reply_id`, по которому свип чинит публичные
        // ветки. Забытая строка здесь означала бы «считать, что ответа не было»,
        // то есть потенциальный дубль ответа в живой ленте, а дубль на Moltbook
        // удалить нельзя. Поэтому ответы входят в отпечаток целиком и поштучно, а не
        // счётчиком: два наших ответа и три дают разные базы, и по счётчику это
        // не различить.
        db
            .rawQuery(
                "SELECT our_reply_id FROM comments WHERE our_reply_id IS NOT NULL AND our_reply_id != '' ORDER BY our_reply_id ASC",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    parts["ourReply:" + c.getString(0)] = "1"
                }
            }
        return parts
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
        /** Счётчик наблюдений: отдельная ветка миграции, см. [onUpgrade]. */
    private const val ADD_HITS_COLUMN_V4 =
        "ALTER TABLE seen ADD COLUMN hits INTEGER NOT NULL DEFAULT 1"

    /** Чем закончился ответ: created/verified/reused, см. [ReplyOutcome]. */
    private const val ADD_REPLY_OUTCOME_COLUMN_V5 =
        "ALTER TABLE comments ADD COLUMN reply_outcome TEXT NOT NULL DEFAULT ''"

    /** Схема v3: журнал прочитанного, [CREATE_SEEN_TABLE_V3]. */
    private const val SCHEMA_V3 = 3

    /** Схема v4: счётчик наблюдений, [ADD_HITS_COLUMN_V4]. */
    private const val SCHEMA_V4 = 4

    /**
     * Схема v5: чем закончился ответ, [ADD_REPLY_OUTCOME_COLUMN_V5].
     *
     * Ровно то же число, что [DB_VERSION]: последняя ветка миграции обязана
     * срабатывать при обновлении на текущую версию, иначе новая колонка
     * появится только у тех, кто перескочит через две версии сразу.
     */
    private const val SCHEMA_V5 = 5

    private const val DB_VERSION = 5

        /**
         * Ключ под отпечаток последнего тика. Пишет и сверяет его ИНТЕГРАЦИЯ
         * (тикер), сам ledger только выдаёт значение — иначе доказательство
         * оказывается в том же файле, который оно охраняет, и теряет смысл.
         */
        const val KEY_LAST_DIGEST = "last_digest"

    /**
     * Время тика, который начал работу и не дописал её до конца.
     *
     * Нужен, чтобы отличать чужую правку базы от своего же оборванного тика.
     * Отпечаток [KEY_LAST_DIGEST] писался только на успешном пути, а база менялась
     * намного раньше (карма, скан, `markComment`, `forgetOurReply`), поэтому любой
     * сбой после первой записи гарантировал ложное «отпечаток базы не совпал» на
     * следующем тике — и запись `KIND_TAMPER` в журнал свидетеля о порче, которой
     * не было. Маркер ставится ДО первой записи и снимается в конце: оставшийся
     * маркер означает «тик не дописал сам», а не «прав был кто-то ещё».
     */
    const val KEY_TICK_OPENED = "tick_opened"

        /**
         * Схема таблицы прочитанного. `IF NOT EXISTS` — не формальность: `onCreate`
         * и `onUpgrade` вызывают один и тот же SQL, а миграция обязана переживать
         * повторный запуск (установка поверх, откат версии, частичный апгрейд).
         *
         * Ключ — `comment_id`, а не пара `(post_id, comment_id)`: идентификатор
         * комментария на Moltbook сквозной, и пара была бы второй истиной о том же
         * самом. Первичный ключ здесь не «украшение», а условие идемпотентности
         * `CONFLICT_REPLACE`: без него двойной прогон скана оставил бы две строки
         * об одном комментарии, и вторая с бо́льшим `at` жила бы в таблице неделю.
         *
         * `at` — время ПОСЛЕДНЕГО прочтения, а не первого появления: по нему идёт
         * и усечение, и решение «исчезновение настоящее или это мигание пагинации».
         * Время попадания в очередь хранится отдельно, в `comments.seen_at`.
         */
        const val CREATE_SEEN_TABLE =
            """
            CREATE TABLE IF NOT EXISTS seen (
                comment_id TEXT PRIMARY KEY,
                post_id TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT '',
                ours INTEGER NOT NULL DEFAULT 0,
                at INTEGER NOT NULL,
                hits INTEGER NOT NULL DEFAULT 1
            )
            """

        /**
         * Индекс по посту обязателен: `recordSeen` читает журнал по постам батчем,
         * и без него каждый скан делал полный проход по всей таблице прочитанного.
         */
        const val CREATE_SEEN_INDEX_POST =
            "CREATE INDEX IF NOT EXISTS idx_seen_post ON seen(post_id)"

        /** Индекс по времени — им же усекается таблица (см. [pruneSeen]). */
        const val CREATE_SEEN_INDEX_AT =
            "CREATE INDEX IF NOT EXISTS idx_seen_at ON seen(at)"

        /**
         * Таблица прочитанного в РОВНО том виде, в котором её завела версия 3:
         * без `hits`.
         *
         * Существует только для ветки миграции `oldVersion < 3`. Обновление с
         * версии 2 проходит подряд две ветки — создание таблицы и добавление
         * колонки, — поэтому таблица обязана создаваться БЕЗ колонки, иначе
         * `ALTER TABLE ... ADD COLUMN hits` падает с «duplicate column name» на
         * первом же обращении к базе. Чистая установка (onCreate) колонку
         * получает сразу, через [CREATE_SEEN_TABLE].
         */
        const val CREATE_SEEN_TABLE_V3 =
            """
            CREATE TABLE IF NOT EXISTS seen (
                comment_id TEXT PRIMARY KEY,
                post_id TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT '',
                ours INTEGER NOT NULL DEFAULT 0,
                at INTEGER NOT NULL
            )
            """

        /**
         * Пересчёт счётчика ответов по журналу — [createdRepliesSince].
         *
         * Пишется SQL, а не собирается из куска в функции: `count` в классе живёт
         * внутри [stats] и в companion, и ни один из них не виден снаружи так, как
         * нужно здесь. Константа честнее, чем третий вариант подсчёта в том же файле.
         *
         * Порядок аргументов: статус POSTED, исходы CREATED и VERIFIED, метка времени.
         */
        const val CREATED_SQL =
            "SELECT COUNT(*) FROM comments " +
                "WHERE status = ? AND reply_outcome IN (?, ?) AND replied_at >= ?"

        /** Пересчёт проверок по журналу — [verifiedRepliesSince]. Аргументы те же, кроме исхода. */
        const val VERIFIED_SQL =
            "SELECT COUNT(*) FROM comments " +
                "WHERE status = ? AND reply_outcome = ? AND replied_at >= ?"

        /**
         * Потолок таблицы `seen`.
         *
         * 5000 записей — это не «сколько поместится», а сколько нужно: тик ходит
         * примерно раз в 30 минут и читает до [pendingLimit] комментов, а лента
         * молтбука на обход даёт сотни строк. 5000 покрывает несколько суток
         * работы тикера с запасом.
         *
         * Почему не «сколько угодно» и не «помнить всё»: таблица `seen` — ПАМЯТЬ
         * О НАБЛЮДЕНИИ, а не архив ленты. Её единственная работа — сравнить соседние
         * сканы. Данные старше [SEEN_KEEP_MS] физически не могут участвовать в
         * сравнении «прошлый скан против текущего», поэтому хранить их — платить
         * размером базы за информацию, которую никто не прочитает.
         *
         * 5000, а не 1000: обрезание слишком частое означает, что коммент успевает
         * выпасть из памяти между соседними сканами и вернувшись будет назван
         * «новым». Дешевле дер��жать лишнее, чем скармливать агенту ложную дельту.
         */
        const val MAX_SEEN = 5000

        /**
         * Сколько живёт запись прочитанного, независимо от [MAX_SEEN].
         *
         * 7 суток — длиннее максимального интервала между тиками (панель даёт до
         * 6 часов) с запасом на выключенный телефон и пропущенные визиты. Если
         * запись пережила неделю, она уже не «прошлый скан», а часть истории,
         * для которой дельта всё равно не вычисляется.
         *
         * Истечение по времени, а не только по числу: иначе таблица на посте с
         * тысячей комментов вытесняла бы записи других постов, и слепая зона
         * появлялась бы не там, где лента реально менялась.
         */
        const val SEEN_KEEP_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * Пережил ли пропавший комментарий один скан — иначе «вернулся» будет
         * неотличимо от «нового».
         *
         * Запись не удаляется сразу: [recordSeen] её только ПЕРЕЧИТЫВАет. Снос
         * моментально означал бы, что комментарий, мигнувший на секунду из-за
         * пагинации ленты, на следующем скане вернулся бы как `added` и агент
         * ответил бы на него второй раз. Одно переживание — минимум, при котором
         * возврат читается как `vanished + снова тот же статус`, а не как новая
         * работа.
         *
         * Почему порог «пережил скан», а не «пережил N часов»: срок в часах лёг бы
         * на тот же `at`, что и время прочтения, и две разные политики
         * (срок жизни и защита от повторного появления) делили бы одну метку.
         */
        const val VANISHED_GRACE_MS = 60L * 60 * 1000

        /**
         * Сколько раз комментарий должен быть замечен, чтобы его пропажу можно
         * было назвать исчезновением, а не миганием пагинации.
         *
         * Порог в часах ([VANISHED_GRACE_MS]) для этого НЕ годится: он короче
         * интервала тика, и `at` обновляется при каждом прочтении, поэтому запись,
         * добавленная сканом N, на скане N+1 уже считалась «старой» — мигание
         * объявлялось исчезновением, а настоящее исчезновение ждало следующего
         * тика наравне с миганием. Накопленное наблюдение отвечает прямо: видели
         * дважды — пережил скан, видели один раз — нет.
         */
        const val SURVIVED_SCANS = 2

        /**
         * Чистая дельта между прошлым журналом и только что прочитанным.
         *
         * Порядок результата — по `commentId`, чтобы два одинаковых по смыслу скана
         * дали одинаковый список: иначе агент увидит «те же три новых комментария» в
         * другом порядке, и это уже выглядит как другой тик.
         */
        fun computeDelta(
            previous: List<SeenComment>,
            batch: List<SeenComment>,
            now: Long,
        ): ScanDelta {
            val previousByComment = previous.associateBy { it.commentId }
            // Посты, которые скан реально прошёл: без этого фильтра частичный
            // обход (лимит страниц) объявил бы исчезнувшим всё, что не влезло в
            // первую страницу, — тревога без причины, после которой настоящим
            // исчезновениям уже не верят.
            val scannedPosts = batch.map { it.postId }.toSet()
            val added = mutableListOf<SeenComment>()
            val statusChanged = mutableListOf<Pair<SeenComment, String>>()
            val freshIds = mutableSetOf<String>()

            for (row in batch) {
                // Двойной прогон по одному посту (обрыв сети на середине скана,
                // повтор тика) не должен двоить `added`: идемпотентность записи
                // гарантирует только одна строка в базе, значит и в дельте одна.
                if (!freshIds.add(row.commentId)) continue
                val before = previousByComment[row.commentId]
                when {
                    before == null -> added += row
                    before.status != row.status -> statusChanged += before to row.status
                    else -> Unit
                }
            }

            val vanished =
                previous.filter {
                    it.commentId !in freshIds &&
                        it.postId in scannedPosts &&
                        it.hits >= SURVIVED_SCANS &&
                        now - it.at >= VANISHED_GRACE_MS
                }
            return ScanDelta(
                added = added.sortedBy { it.commentId },
                statusChanged = statusChanged.sortedBy { (before, _) -> before.commentId },
                vanished = vanished.sortedBy { it.commentId },
            )
        }

        /**
         * Какие записи `seen` убрать на этом проходе. Чистая функция, чтобы решение
         * об усечении проверялось тестом без БД. Возвращает то, что надо УДАЛИТЬ.
         *
         * Порог выводится из [now] и [SEEN_KEEP_MS], а не из «последних N по времени
         * сортировки»: сортировка по `at` зависит от того, какие посты сканировали в
         * этом тике, и при частичном обходе выкинула бы записи сканящихся постов, то
         * есть сломала бы ровно ту дельту, ради которой таблица и нужна.
         *
         * Известная и принятая цена усечения: запись, вытесненная по [MAX_SEEN],
         * при возврате будет названа новой (`added`). Это ложь в ту сторону, где
         * ошибка безопасна — «новый вопрос» стоит лишнего прочтения, а «пропавший
         * ответ на свой же вопрос» стоил бы повторной публикации.
         */
        fun prunePlan(
            rows: List<SeenComment>,
            now: Long,
        ): List<SeenComment> {
            if (rows.isEmpty()) return emptyList()
            val cutoff = now - SEEN_KEEP_MS
            val expired = rows.filter { it.at < cutoff }
            val survivors = rows.filter { it.at >= cutoff }
            if (survivors.size <= MAX_SEEN) return expired
            val overflow =
                survivors
                    .sortedWith(compareByDescending<SeenComment> { it.at }.thenBy { it.commentId })
                    .drop(MAX_SEEN)
            return expired + overflow
        }

        /**
         * SHA-256 от канонического снимка, hex в нижнем регистре.
         *
         * Ключи сортируются здесь, а не полагаются на порядок вставки [snapshot]:
         * сортировка в preimage — единственное место, где гарантируется, что
         * одинаковое состояние даёт одинаковый хэш. Нарушение здесь не «другое
         * значение», а ложное расхождение на каждом тике, после которого сверка
         * перестаёт означать что бы то ни было.
         *
         * Разделитель `\n` и формат `ключ=значение` выбраны так, чтобы перенос
         * разделителя из значения в соседний ключ был невозможен: ключи — имена
         * таблиц, статусы и id, `=` и перевод строки в них не встречаются.
         *
         * Отдельная приватная функция, а не вызов `MoltbookWitness.hashOf`: тот
         * хэширует запись журнала-свидетеля, у него своё разбиение preimage
         * (`seq|at|kind|payload|prevHash`), и подстановка сюда сделала бы отпечаток
         * базы зависящим от формата чужого журнала. Связь «порча базы ↔ порча
         * журнала» должна быть логической, а не через общий вызов.
         */
        fun digestOf(parts: Map<String, String>): String {
            val canonical =
                parts.entries
                    .sortedBy { it.key }
                    .joinToString("\n") { (key, value) -> "$key=$value" }
            return MessageDigest
                .getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        }

        /** Заполнитель `IN (...)`: n вопросительных знаков для n постов. */
        private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")
    }
}
