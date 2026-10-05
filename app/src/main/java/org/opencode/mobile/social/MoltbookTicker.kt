package org.opencode.mobile.social

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.opencode.mobile.OpencodeApp
import org.opencode.mobile.server.LocalOpenCodeClient
import java.io.File
import java.io.IOException

/**
 * Один проход по Moltbook: лента → неотвеченные комменты → черновики от модели →
 * публикация. Сеть и состояние ведёт [MoltbookClient], текст пишет модель через
 * собственную сессию opencode, поэтому тик не зависит ни от Termux, ни от того,
 * догадается ли агент выполнить сетевой вызов сам.
 *
 * Разделение намеренное: модель возвращает готовый текст, а не «сделал/не сделал».
 * Иначе сетевой сбой выглядит как молчание агента, а это худший вид отказа —
 * его замечают через сутки.
 */
internal class MoltbookTicker(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val keyFile: File get() = File(OpencodeApp.ServerConfig.appFiles, KEY_PATH)

    /** Ledger держим один на тик: SQLiteOpenHelper уже открыл файл БД и соединение. */
    private var ledgerCache: MoltbookLedger? = null

    /**
 * Кэшированный ledger тика. Открыт наружу, потому что приёмник дописывает в него
 * итог тика: если бы он создавал свой MoltbookLedger, на каждый тик плодился бы
 * лишний SQLiteOpenHelper, который никто не закрывает.
 */
internal fun ledger(): MoltbookLedger = ledgerCache ?: MoltbookLedger(context).also { ledgerCache = it }



    /** Результат прохода — попадает в лог и в дайджест, чтобы «почему молчишь» отвечалось фактами. */
    data class TickReport(
        val postsChecked: Int,
        val repliesPosted: Int,
        val verificationsSolved: Int,
        val karma: Int,
        val unread: Int,
        val facts: List<String>,
        /** Найдено, но не отвечено из-за потолка на тик — доберём следующим проходом. */
        val deferredReplies: Int = 0,
        val upvotesGiven: Int = 0,
        /** Пауза до следующего визита — её выбирает сам агент. */
        val nextVisitMinutes: Int = fallbackMinutes(),
        /** Агент реально ответил, а не сработал fallback. Разница видна только тут. */
        val nextVisitChosenByModel: Boolean = false,
    )

    fun runOnce(): TickReport {
        val client = MoltbookClient(MoltbookClient.readKey(keyFile))
        val ledger = ledger()
        val now = clock()
        val facts = mutableListOf<String>()

        // Проход 1 (дёшево, без модели). Обновляем учёт: карма, непрочитанные и все
        // новые комменты. Только сеть — это секунды, а не минуты.
        val home = client.home()
        ledger.putState(MoltbookLedger.KEY_KARMA, home.karma.toString())
        ledger.putState(MoltbookLedger.KEY_UNREAD, home.unreadNotifications.toString())
        // Скан возвращает id РЕАЛЬНО просмотренных постов: по нему же ниже решается,
        // кому можно гасить счётчик непрочитанного.
        val scannedPosts = scanPostsIntoLedger(ledger, client, home, now)
        // Репосты ищем ПОСЛЕ скана постов: список наших id наполняется именно там, и
        // обратный порядок молча давал пустой результат весь первый тик.
        facts += scanFeedForReposts(ledger, client, now)

        // ПРОХОД 2 (дорого, с моделью). Потолок — предохранитель: один проход без
        // него выдал 9 ответов за 11 минут, и в ленте это выглядит как спам-бот.
        // retryBefore отдаёт в очередь и FAILED, которым не меньше RETRY_COOLDOWN_MS:
// раньше такие комментарии выпадали из выборки навсегда.
        val pending = ledger.pendingReplies(MAX_REPLIES_PER_TICK, retryBefore = now - RETRY_COOLDOWN_MS)
        var replies = 0
        var verifications = 0
        for (target in pending) {
            val draft = askModel(draftPrompt(target))
            if (draft == null) {
                // Модель не ответила: коммент помечаем FAILED, но НЕ SKIPPED — он не
                // «не нужен», на него просто не хватило сил, и он дождётся следующего тика.
                ledger.markComment(target.commentId, MoltbookLedger.CommentStatus.FAILED, now = now)
                continue
            }
            val outcome =
                try {
                    postWithVerification(client, target.postId, target.commentId, draft)
                } catch (e: IOException) {
                    // Сеть отвалилась на ЭТОМ ответе. Без catch IOException улетал из
                    // runOnce в общий catch приёмника и уносил весь остаток визита:
                    // остальные ответы, все апвоуты и снятие счётчиков. Ответ, который
                    // не ушёл, — это FAILED, а не причина списать тик.
                    Log.w(TAG, "сеть упала на ответе ${target.commentId}: ${e.message}")
                    PostResult.Failed(e.message ?: "сеть недоступна")
                }
            when (outcome) {
                is PostResult.Done -> {
                    replies++
                    // our_reply_id — ID нашего комментария, а не чужого: по нему
                    // отличаем «уже ответили» от «ответили, но это другой тред».
                    ledger.markComment(
                        target.commentId,
                        MoltbookLedger.CommentStatus.POSTED,
                        outcome.commentId,
                        now,
                    )
                    facts += "ответил ${target.author} в «${target.postTitle}»"
                }
                is PostResult.Verified -> {
                    replies++
                    verifications++
                    ledger.markComment(
                        target.commentId,
                        MoltbookLedger.CommentStatus.POSTED,
                        outcome.commentId,
                        now,
                    )
                    facts += "ответил ${target.author} в «${target.postTitle}» (с verification)"
                }
                is PostResult.Failed -> {
                    Log.w(TAG, "коммент не опубликован: ${outcome.reason}")
                    // FAILED, а не SKIPPED: ответ не отправлен, коммент не потерян,
                    // он дождётся следующего тика.
                    ledger.markComment(target.commentId, MoltbookLedger.CommentStatus.FAILED, now = now)
                }
            }
        }

// Счётчик непрочитанного снимаем только у постов, которые мы РЕАЛЬНО просканировали
        // в этом тике, и только там, где NEW не осталось.
        //
        // Раньше цикл шёл по ВСЕМ home.awaitingReply, а скан берёт только
        // POSTS_PER_TICK постов. У непросканированного поста в comments нет строк,
        // поэтому postHasNoPending истинно вакуумно — markPostRead гасил серверный
        // счётчик у поста, чьи комменты мы даже не выгрузили. Сервер обнулял
        // new_notification_count, пост навсегда выпадал из awaitingReply
        // (там фильтр newNotifications > 0), и его комменты уже никогда не попадали
        // в ledger и не получали ответа, при том что глобальный unread уже обнулён.
        // Ровно та потеря, ради которой этот блок и написан.
        for (postId in scannedPosts) {
            if (!ledger.postHasNoPending(postId)) continue
            try {
                client.markPostRead(postId)
            } catch (e: IOException) {
                // Ответы к этому моменту уже в ленте: терять из-за бейджа весь итог
                // тика (дайджест + метку времени) — заметно хуже, чем просроченный
                // счётчик непрочитанного. Пишем в лог и идём дальше.
                Log.w(TAG, "не снял счётчик непрочитанного по $postId: ${e.message}")
            }
        }

        val stats = ledger.stats()
        val housekeeping = decideHousekeeping(ledger, client, facts)
        var upvotes = 0
        for (postId in housekeeping.upvotePostIds) {
            try {
                client.upvote(postId)
                ledger.recordUpvote(postId, now)
                upvotes++
            } catch (e: IOException) {
                Log.w(TAG, "апвоут не прошёл: ${e.message}")
            }
        }
        if (upvotes > 0) facts += "поддержал $upvotes пост(ов) в ленте"

        val report =
            TickReport(
                postsChecked = scannedPosts.size,
                repliesPosted = replies,
                verificationsSolved = verifications,
                karma = home.karma,
                unread = home.unreadNotifications,
                facts = facts,
                upvotesGiven = upvotes,
                // stats читается ПОСЛЕ цикла ответов, поэтому repliedTotal уже учитывает свежие
// POSTED. Вычитать replies второй раз нельзя: было 3 NEW, отвечено 2, осталось 1,
// а формула давала 1 - 2 и coerceAtLeast(0) показывала 0 — строка «отложено» из
                // дайджеста исчезала. awaitingReply уже и есть «сколько ждёт сейчас».
                deferredReplies = stats.awaitingReply,
                nextVisitMinutes = housekeeping.nextVisitMinutes,
                nextVisitChosenByModel = housekeeping.nextVisitChosenByModel,
            )
        ledger.putState(MoltbookLedger.KEY_LAST_TICK, now.toString())
        // Абсолютное время, а не минуты: панель показывает «вернётся в 22:10», и это
        // правда даже если телефон с тех пор перезагружали или будильник сдвинулся.
        ledger.putState(
            MoltbookLedger.KEY_NEXT_VISIT_AT,
            (now + housekeeping.nextVisitMinutes * 60_000L).toString(),
        )
        persist(report)
        return report
    }

    /**
     * Ритм и апвоуты решает сам агент, одним запросом: два вызова модели на тик —
     * это две лишние минуты и слив квоты ради двух чисел.
     *
     * Ответ ждём строгим форматом `UPVOTE: 1 3` / `NEXT: 180`. Свободный текст сюда
     * не пускаем: иначе «через пару часов» превратится в `NaN` и расписание встанет.
     */
    private fun decideHousekeeping(
        ledger: MoltbookLedger,
        client: MoltbookClient,
        facts: List<String>,
    ): Housekeeping {
        // pendingLimit не 0: те же комменты нужны для перевода на русский, и брать их
        // из уже прочитанной базы дешевле, чем повторно ходить в сеть.
        val stats = ledger.stats(pendingLimit = MAX_GLOSS_ROWS)
        val candidates = upvoteCandidates(ledger, client)
        val answer = askModel(housekeepingPrompt(stats, facts, candidates))
        val parsed = parseHousekeeping(answer, candidates, stats.pending)
        // Переводы — побочный продукт того же вызова: пишем их в базу, и панель
        // рисует русский текст мгновенно и офлайн, без обращения к сети.
        parsed.glosses.forEach { (index, ru) ->
            stats.pending.getOrNull(index - 1)?.let { ledger.setGloss(it.commentId, ru) }
        }
        return parsed
    }

    /** Кандидаты на апвоут: чужой пост с живым обсуждением, который мы ещё не оценили. */
    private fun upvoteCandidates(
        ledger: MoltbookLedger,
        client: MoltbookClient,
    ): List<MoltbookClient.FeedPost> =
        client
            .feed("top")
            .filter { !it.author.equals(MoltbookLedger.OUR_AGENT, ignoreCase = true) }
            .filter { it.repostOf == null }
            .filter { it.upvotes >= MIN_UPVOTES_FOR_CANDIDATE }
            .filterNot { ledger.hasUpvoted(it.postId) }
            .take(MAX_UPVOTES_PER_TICK)

    private fun parseHousekeeping(
        answer: String?,
        candidates: List<MoltbookClient.FeedPost>,
        pending: List<MoltbookLedger.PendingReply>,
    ): Housekeeping {
        val parsed =
            parseHousekeepingAnswer(
                answer,
                candidates.map { it.postId },
                fallbackMinutes(),
                glossableCount = pending.size,
            )
        // Индексы из ответа — это номера строк в том списке, который мы показали
        // модели. Переводим их в id здесь: парсер остаётся чистой функцией от строки.
        return parsed.copy(
            glosses = parsed.glosses.filterKeys { pending.getOrNull(it - 1) != null },
        )
    }

    data class Housekeeping(
        val upvotePostIds: List<String> = emptyList(),
        val nextVisitMinutes: Int,
        /** Модель ответила разбираемым форматом — пауза её, а не наш fallback. */
        val nextVisitChosenByModel: Boolean = false,
        /** Номер строки списка ожидающих → русский перевод. Номера не id: id знает только база. */
        val glosses: Map<Int, String> = emptyMap(),
    )

    /**
     * Комменты постов активности раскладываем в ledger: NEW — работа, остальное SKIPPED.
     * Возвращает id постов, которые действительно просмотрены, — снятие счётчика
     * непрочитанного разрешено только для них.
     */
    private fun scanPostsIntoLedger(
        ledger: MoltbookLedger,
        client: MoltbookClient,
        home: MoltbookClient.Home,
        now: Long,
    ): Set<String> {
        val scanned = LinkedHashSet<String>()
        for (post in home.awaitingReply.take(POSTS_PER_TICK)) {
            scanned += post.postId
            ledger.upsertPost(
                postId = post.postId,
                title = post.title,
                author = "",
                ours = true,
                repostOf = null,
                commentsTotal = 0,
                upvotes = 0,
                now = now,
            )
            val comments = client.comments(post.postId)
            ledger.upsertPost(post.postId, post.title, "", true, null, comments.size, 0, now)
            val answeredByUs = comments.filter { it.isOurs }.mapNotNull { it.parentId }.toSet()
            for (comment in comments) {
                // Статус читаем ДО upsert: upsertComment вставляет строку, и после него
                // statusOf уже никогда не null. Проверка «known == null» после вставки
                // была всегда false, и ни один коммент не помечался SKIPPED — включая
                // наши собственные, на которые агент в итоге отвечал сам себе.
                val statusBefore = ledger.statusOf(comment.id)
                ledger.upsertComment(comment.id, post.postId, comment.author, comment.content, 0L, now)
                // Повторный скан не переписывает историю: POSTED/FAILED/SKIPPED трогать
                // нельзя, иначе repliedTotal тает на каждом тике. Чистим только NEW —
                // он по определению «ещё не решён», и в нём могут осесть ошибочно
                // оставленные записи (в том числе наши, засевшие до этого фикса).
                if (!deservesReply(comment, answeredByUs) &&
                    (statusBefore == null || statusBefore == MoltbookLedger.CommentStatus.NEW)
                ) {
                    // Свой коммент и реплику в подветке не выбрасываем, а помечаем
                    // SKIPPED: они остаются видимыми в панели как «ответили».
                    ledger.markComment(comment.id, MoltbookLedger.CommentStatus.SKIPPED, now = now)
                }
            }
            Log.i(TAG, "пост ${post.postId.take(8)} «${post.title.take(40)}»: комментов ${comments.size}, ждут ответа ${comments.count { deservesReply(it, answeredByUs) }}")
        }
        return scanned
    }

    /**
     * Репосты наших постов ищем в общей ленте. Явного поля на сервере нет, поэтому
     * сверяем id наших постов с текстом поста: репост почти всегда цитирует исходник.
     */
    private fun scanFeedForReposts(
        ledger: MoltbookLedger,
        client: MoltbookClient,
        now: Long,
    ): List<String> {
        val ourIds = ledger.ourPostIds()
        if (ourIds.isEmpty()) return emptyList()
        val found = mutableListOf<String>()
        val posts = client.feed("new")
        for (post in posts) {
            // Свой пост в общей ленте — это не репост. Без этой проверки наш же пост,
            // цитирующий другой наш пост, помечался как репост и завышал счётчик:
            // «репостов на них» показывал 2 при нуле настоящих репостов.
            if (post.author.equals(MoltbookLedger.OUR_AGENT, ignoreCase = true)) continue
            val repostOf =
                post.repostOf?.takeIf { it in ourIds }
                    ?: ourIds.firstOrNull { id -> post.body.contains(id) || post.title.contains(id) }
                    ?: continue
            ledger.upsertPost(post.postId, post.title, post.author, ours = false, repostOf = repostOf, post.commentsTotal, post.upvotes, now)
            if (!ledger.hasSeenRepost(post.postId)) {
                ledger.markRepostSeen(post.postId)
                found += "репост нашего поста: ${post.author} «${post.title.take(40)}»"
            }
        }
        // Наши же посты из ленты — чтобы «сколько наших постов» считалось само.
        posts.filter { it.author.equals(MoltbookLedger.OUR_AGENT, ignoreCase = true) }.forEach {
            ledger.upsertPost(it.postId, it.title, it.author, ours = true, repostOf = null, it.commentsTotal, it.upvotes, now)
        }
        return found
    }

    private sealed interface PostResult {
        data class Done(val commentId: String) : PostResult

        data class Verified(val commentId: String) : PostResult

        data class Failed(val reason: String) : PostResult
    }

    /**
     * Платформа может потребовать verification на коммент.
     *
     * Здесь была дыра: при отказе вызывался `deleteComment(parentId)` — то есть
     * удалялся комментарий СОБЕСЕДНИКА, на который мы отвечали. Удалять там нечего:
     * при `NeedsVerification` платформа не создаёт коммент, а возвращает задачу, так
     * что своего ID у нас не существует. Правильный путь — решить задачу и
     * опубликовать заново; упавший код просто тратим и берём следующий.
     */
    private fun postWithVerification(
        client: MoltbookClient,
        postId: String,
        parentId: String,
        draft: String,
    ): PostResult {
        var solved = false
        // Перед первой попыткой перечитываем ветку: прошлый тик мог опубликовать
        // ответ и упасть на чтении ответа — сервер уже создал комментарий, а мы об
        // этом не узнали. Найденный свой комментарий и есть доказательство, что
        // публиковать второй раз нельзя.
        client.ourReplyTo(postId, parentId)?.let { existing ->
            Log.i(TAG, "ответ на $parentId уже есть ($existing), повтор не публикуем")
            return PostResult.Done(existing)
        }
        repeat(ATTEMPTS_WITH_VERIFICATION) { attempt ->
            when (val outcome = client.postComment(postId, capForWaf(draft), parentId = parentId)) {
                is MoltbookClient.CommentOutcome.Posted ->
                    // Возвращаем ID своего комментария, а не parentId: в ledger он
                    // попадает как our_reply_id и по нему потом отличают наш ответ от чужого.
                    return if (solved) PostResult.Verified(outcome.commentId) else PostResult.Done(outcome.commentId)
                is MoltbookClient.CommentOutcome.Rejected -> return PostResult.Failed(outcome.reason)
                is MoltbookClient.CommentOutcome.NeedsVerification -> {
                    val answer = askModel(verificationPrompt(outcome.challengeText))
                        ?: return PostResult.Failed("verification без ответа модели")
                    val ok =
                        try {
                            client.verify(outcome.verificationCode, answer)
                        } catch (e: IOException) {
                            // Ответ не того формата или сеть упала. Код задачи после
                            // этого использовать нельзя, поэтому просто берём новую
                            // попытку — раньше здесь тик молча заканчивался целиком.
                            Log.w(TAG, "verification не отправлен: ${e.message}")
                            false
                        }
                    if (ok) {
                        solved = true
                    } else {
                        Log.w(TAG, "verification не пройден, попытка ${attempt + 1}/$ATTEMPTS_WITH_VERIFICATION")
                    }
                }
            }
        }
        return PostResult.Failed("verification не пройден за $ATTEMPTS_WITH_VERIFICATION попытки")
    }

    /**
     * CloudFront перед API режет тела примерно от килобайта, поэтому длинный ответ
     * режется сам и обрывается по границе слова — обрывок слова хуже, чем лишняя точка.
     */
    private fun capForWaf(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length <= MAX_COMMENT_CHARS) return trimmed
        // Режем до MAX_COMMENT_CHARS - 1: многоточие тоже символ, и take(MAX) плюс
        // «…» давали 901 символ при MAX_COMMENT_CHARS = 900 — то есть кламп был слабее
        // собственной константы и обещания в промпте модели.
        val budget = MAX_COMMENT_CHARS - 1
        val cut = trimmed.take(budget)
        val lastSpace = cut.lastIndexOf(' ')
        return cut.take(if (lastSpace > budget / 2) lastSpace else budget).trimEnd() + "…"
    }

    private fun draftPrompt(target: MoltbookLedger.PendingReply): String =
        """
        Ты отвечаешь в Moltbook от имени агента. Пост: «${target.postTitle}».
        Комментарий от ${target.author}: «${target.body}»

        Напиши ОДИН короткий ответ по-русски, своими словами, без преамбулы и без
        markdown-заголовков. Это последнее сообщение — оно уйдёт в ленту как есть.
        Не выдумывай факты о себе. Максимум ${MAX_COMMENT_CHARS / 2} символов.
        """.trimIndent()

    /**
     * Один запрос на два решения: какие посты поддержать и когда вернуться.
     * Апвоут — не фарм: модель видит текст и выбирает, а мы жёстко режем список
     * кандидатов (чужие, без репоста, от MIN_UPVOTES_FOR_CANDIDATE) и потолок.
     */
    private fun housekeepingPrompt(
        stats: MoltbookLedger.Stats,
        facts: List<String>,
        candidates: List<MoltbookClient.FeedPost>,
    ): String =
        buildString {
            appendLine("Ты агент на Moltbook. Сейчас:")
            appendLine("- ждут ответа: ${stats.awaitingReply}, отвечено всего: ${stats.repliedTotal}")
            appendLine("- наших постов: ${stats.ourPosts}, репостов на них: ${stats.repostsOfOurs}")
            appendLine("- апвоутов выдано: ${stats.upvotesGiven}, непрочитанных: ${stats.unread}")
            if (facts.isNotEmpty()) appendLine("- последнее: ${facts.take(3).joinToString("; ")}")
            appendLine()
            if (candidates.isEmpty()) {
                appendLine("Постов для апвоута нет.")
            } else {
                appendLine("Посты ленты (номер, апвоуты, автор):")
                candidates.forEachIndexed { index, post ->
                    appendLine("${index + 1}) [${post.upvotes}▲, ${post.author}] ${post.title} — ${post.body.take(240)}")
                }
            }
            appendLine()
            if (stats.pending.isEmpty()) {
                appendLine("Комментариев, ждущих ответа, нет.")
            } else {
                appendLine("Комментарии, ждущие ответа (номер, автор, текст):")
                stats.pending.forEachIndexed { index, pending ->
                    appendLine("${index + 1}) [${pending.author}] ${pending.body.take(400).replace('\n', ' ')}")
                }
            }
            appendLine()
            appendLine("UPVOTE: перечисли номера постов, которые СТОИТ поддержать (0-${MAX_UPVOTES_PER_TICK}). Пусто — если ни один.")
            appendLine("NEXT: через сколько минут вернуться (${MIN_NEXT_VISIT_MINUTES}-${MAX_NEXT_VISIT_MINUTES}).")
            // Перевод едет тем же запросом. Отдельный вызов на перевод — это лишние
            // токены и минуты ожидания в фоне ради текста, который читает человек,
            // а не модель. Здесь мы всё равно уже читаем эти комменты.
            if (stats.pending.isNotEmpty()) {
                appendLine("RU: переведи КАЖДЫЙ комментарий на русский — по одной строке `RU <номер>: <перевод>`. Это для панели, читает русский человек.")
            }
            appendLine("Больше ничего не пиши.")
        }

    private fun verificationPrompt(challenge: String): String =
        """
        Реши задачу: $challenge
        Ответь ТОЛЬКО числом в формате `число.00` — например `48.00`. Больше ничего.
        """.trimIndent()

    /**
     * Отдельная сессия на каждый запрос: черновики не должны сыпаться в чат
     * пользователя, а сессия удаляется, чтобы /session не пух от пустых «молотов».
     */
    private fun askModel(prompt: String): String? {
        val sessionId = LocalOpenCodeClient.post(OPENCODE_PORT, "/session", "{}")?.let(::sessionIdOf)
            ?: return null
        try {
            val body = JSONObject().apply {
                put("parts", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", prompt)))
            }
            if (!LocalOpenCodeClient.postAsync(OPENCODE_PORT, "/session/$sessionId/message", body.toString())) {
                return null
            }
            return awaitAssistantText(sessionId)
        } finally {
            LocalOpenCodeClient.delete(OPENCODE_PORT, "/session/$sessionId")
        }
    }

    private fun sessionIdOf(created: String): String? =
        runCatching { JSONObject(created).optString("id").takeIf { it.isNotEmpty() } }.getOrNull()

    /**
     * opencode отвечает на POST /message только после конца генерации, поэтому ответ
     * читается polls'ом из ленты сессии. Пустой текст — не «модель молчит», а повод
     * не публиковать мусор.
     */
    private fun awaitAssistantText(sessionId: String): String? {
        repeat(GENERATION_POLLS) {
            Thread.sleep(GENERATION_POLL_MS)
            val raw = LocalOpenCodeClient.get(OPENCODE_PORT, "/session/$sessionId/message") ?: return@repeat
            val messages = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return@repeat
            val text = lastAssistantText(messages)
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    /**
     * Дайджест уходит в память (её потом читает агент, когда спросят «как молтбук?»)
     * и в свежий файл. В чат проталкиваем только если там вообще что произошло —
     * пустой отчёт каждую два часа только шумит.
     */
    private fun persist(report: TickReport) {
        val stamp = clock()
        val lines =
            buildString {
                appendLine("Moltbook @ ${stamp / 1000}")
                appendLine("karma=${report.karma} непрочитанных=${report.unread}")
                appendLine("проверено постов=${report.postsChecked} ответов=${report.repliesPosted} verification=${report.verificationsSolved} апвоутов=${report.upvotesGiven}")
                if (report.deferredReplies > 0) {
                    appendLine("отложено до следующего тика: ${report.deferredReplies}")
                }
                // Решение агента о ритме пишем в дайджест: иначе «120 минут» в
                // AlarmManager неотличимы от fallback'а, и агент мог бы месяц
                // молча работать на чужом жёстком интервале.
                appendLine(
                    "следующий визит через ${report.nextVisitMinutes} мин" +
                        if (report.nextVisitChosenByModel) " (выбрал агент)" else " (fallback: модель не ответила)",
                )
                report.facts.forEach { appendLine("- $it") }
            }
        File(context.filesDir, DIGEST_PATH).writeText(lines)
        // Молчим в чат только когда делать нечего: иначе каждое пробуждение будит
        // пользователя пустым уведомлением, и он перестаёт читать полезные.
        if (report.repliesPosted > 0 || report.deferredReplies > 0) notifyChat(lines)
    }

    /** Последняя сессия пользователя: её ChatOverlay обновляет сам раз в пару секунд. */
    private fun notifyChat(digest: String) {
        val sessionId = lastTouchedUserSession() ?: return
        val body =
            JSONObject().apply {
                put(
                    "parts",
                    org.json.JSONArray()
                        .put(
                            JSONObject()
                                .put("type", "text")
                                .put("text", "Молтбук: $digest"),
                        ),
                )
            }
        LocalOpenCodeClient.postAsync(OPENCODE_PORT, "/session/$sessionId/message", body.toString())
    }

    private fun lastTouchedUserSession(): String? {
        val raw = LocalOpenCodeClient.get(OPENCODE_PORT, "/session") ?: return null
        val sessions = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return null
        return (0 until sessions.length())
            .mapNotNull { sessions.optJSONObject(it) }
            .maxByOrNull { it.optJSONObject("time")?.optLong("updated") ?: 0L }
            ?.optString("id")
            ?.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val TAG = "MoltbookTicker"
        const val OPENCODE_PORT = 4096

        /**
         * Кого тик будет отвечать. Три отсева, и каждый — про случай, который
         * иначе превратился бы в спам: наши собственные комменты, ответы в
         * подветках (это не запрос к нам) и уже отвеченные комменты.
         */
        internal fun pendingComments(comments: List<MoltbookClient.Comment>): List<MoltbookClient.Comment> {
            val answeredByUs = comments.filter { it.isOurs }.mapNotNull { it.parentId }.toSet()
            return comments.filter { deservesReply(it, answeredByUs) }
        }

        /**
         * Единственное правило «стоит ли отвечать». Живёт в companion, чтобы тик и
         * тесты решали по одному коду, а не по двум одинаково выглядящим копиям.
         *
         * Три отсева: наши собственные комменты, реплики в подветках (это не запрос
         * к нам) и комменты, на которые мы уже ответили.
         */
        internal fun deservesReply(
            comment: MoltbookClient.Comment,
            answeredByUs: Set<String>,
        ): Boolean = comment.parentId == null && !comment.isOurs && comment.id !in answeredByUs

        fun fallbackMinutes(): Int = (MoltbookScheduler.INTERVAL_MS / 60_000L).toInt()

        /**
         * Разбор строгих двух строк ответа модели. Терпимость здесь неуместна:
         * свободный текст превращается в `NaN` минут и расписание встаёт, а лишний
         * номер в UPVOTE — в апвоут, которого агент не выбирал. Мусор → безопасный
         * fallback, а не попытка угадать intent.
         */
        internal fun parseHousekeepingAnswer(
            answer: String?,
            candidatePostIds: List<String>,
            fallbackMinutes: Int,
            /** Сколько строк ожидающих мы показали модели: номера вне диапазона — выдумка. */
            glossableCount: Int = 0,
        ): Housekeeping {
            if (answer.isNullOrBlank()) return Housekeeping(nextVisitMinutes = fallbackMinutes)

            val lines = answer.lineSequence().map { it.trim() }.toList()
            // Хвост директивы обязан НАЧИНАться с числа, а не состоять только из чисел.
            // «NEXT: 90 минут» и «UPVOTE: 1, 2.» — валидные ответы с пояснением, их
            // надо принять. А «NEXT: через сколько минут вернуться (30-720)» начинается
            // со слова — это эхо промпта, а не решение агента, и такой строке мы обязаны
            // отказать. Раньше здесь стоял разбор на «любые цифры где есть», и эхо
            // промпта подставляло апвоут посту №3, которого агент не выбирал, и темп
            // 30 минут вместо выбранного.
            val strictNumbers = { after: String ->
                val head = Regex("^\\d[^\\n]*").find(after.trim())?.value
                head?.split(Regex("[^0-9]+"))?.mapNotNull { it.toIntOrNull() }
            }
            val picked =
                lines
                    .firstOrNull { it.startsWith("UPVOTE", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.let { strictNumbers(it) }
                    ?.filter { it in 1..candidatePostIds.size }
                    ?.distinct()
                    ?.take(MAX_UPVOTES_PER_TICK)
                    ?: emptyList()
            val nextLine = lines.firstOrNull { it.startsWith("NEXT", ignoreCase = true) }
            val nextMinutes =
                nextLine
                    ?.substringAfter(':')
                    ?.let { strictNumbers(it) }
                    ?.firstOrNull()
            // Переводы: строка вида `RU 3: текст`. Номер обязан быть в границах
            // показанного списка — иначе это не «перевод», а выдумка, и мы бы
            // подписали чужой вопрос нашим текстом. Номер вне диапазона — пропуск.
            val glosses =
                lines
                    .mapNotNull { line ->
                        if (!line.startsWith("RU", ignoreCase = true)) return@mapNotNull null
                        val after = line.removePrefix(line.takeWhile { !it.isDigit() }).trim()
                        val digits = Regex("^\\d+").find(after)?.value?.toIntOrNull() ?: return@mapNotNull null
                        val text = after.substringAfter(':', "").trim()
                        if (text.isBlank()) return@mapNotNull null
                        digits to text.take(GLOSS_MAX_CHARS)
                    }.filter { (index, _) -> index in 1..glossableCount }
                    .toMap()
            return Housekeeping(
                upvotePostIds = picked.map { candidatePostIds[it - 1] },
                nextVisitMinutes =
                    (nextMinutes ?: fallbackMinutes).coerceIn(MIN_NEXT_VISIT_MINUTES, MAX_NEXT_VISIT_MINUTES),
                nextVisitChosenByModel = nextMinutes != null,
                glosses = glosses,
            )
        }


        /**
         * Форма сообщения opencode: роль и время лежат во вложенном `info`, а `parts` —
         * на верхнем уровне. Раньше роль читалась с верхнего уровня, всегда была пустой,
         * и тик молча дожидался всех 180 с, ничего не публикуя.
         *
         * Ждём именно `time.completed`: незавершённый ответ обрезан, а обрезанный
         * комментарий в ленте выглядит как наш собственный недописанный пост.
         */
        internal fun lastAssistantText(messages: org.json.JSONArray): String? {
            for (index in messages.length() - 1 downTo 0) {
                val entry = messages.optJSONObject(index) ?: continue
                val info = entry.optJSONObject("info") ?: continue
                if (info.optString("role") != "assistant") continue
                if (info.optJSONObject("time")?.has("completed") != true) continue
                val parts = entry.optJSONArray("parts") ?: continue
                val chunks = StringBuilder()
                for (p in 0 until parts.length()) {
                    val part = parts.optJSONObject(p) ?: continue
                    if (part.optString("type") == "text") chunks.appendLine(part.optString("text"))
                }
                return chunks.toString().trim().ifEmpty { null }
            }
            return null
        }

        const val KEY_PATH = "moltbook/moltkey"

        const val DIGEST_PATH = "moltbook-digest.md"
        const val MOLTBOOK_MEMORY_TAG = "MOLTBOOK-SUMMARY"
        const val POSTS_PER_TICK = 3

        /**
         * Потолок ответов за один проход. Черновик рождается ~75 с, поэтому два
         * ответа — это ~2,5 минуты: тик успевает между двумячасовыми будильниками и
         * не пересекается с следующим. Больше — уже очередь спама в чужой ленте.
         */
        const val MAX_REPLIES_PER_TICK = 2

        /** Границы паузы, которую выбирает агент: чаще 30 мин — мы превращаемся в
         *  спам-бота, реже 12 ч — теряем живую беседу, на которую кто-то ждёт ответа. */

        const val MIN_NEXT_VISIT_MINUTES = 30
        const val MAX_NEXT_VISIT_MINUTES = 720

        /** Апвоутов за тик: поддержать можно и три поста, но не двадцать — это фарм. */
        const val MAX_UPVOTES_PER_TICK = 3

        /**
         * Сколько вопросов переводим за тик. Больше — не «больше пользы», а раздутый
         * промпт: модель на длинных списках начинает сокращать переводы и сбивать номера.
         */
        const val MAX_GLOSS_ROWS = 10

        /** Перевод — строка в панели, а не пересказ. Обрезаем, чтобы не ломать вёрстку. */
        const val GLOSS_MAX_CHARS = 220

        /** Ниже этого апвоут — кандидат в шум, а не в поддержку. */
        const val MIN_UPVOTES_FOR_CANDIDATE = 5

        const val ATTEMPTS_WITH_VERIFICATION = 2

        /**
         * Пауза перед повтором неудачного ответа. Короче — начинаем долбить платформу
         * тем же текстом; длиннее — человек, задавший вопрос, ждёт слишком долго.
         */
        const val RETRY_COOLDOWN_MS = 6 * 60 * 60 * 1000L
        const val MAX_COMMENT_CHARS = 900
        const val GENERATION_POLLS = 60
        const val GENERATION_POLL_MS = 3_000L    }
}
