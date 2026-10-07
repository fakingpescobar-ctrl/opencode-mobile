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

        // Свип мёртвых ответов идёт ДО цикла ответов: цикл упирается ровно в эти ветки
        // (см. PostResult.Blocked), и пока комменты живы, он на каждом тике платит за
        // попытку публикации, чтобы услышать already_existed. Ошибки свипа не должны
        // отменять тик: чистка — это подготовка, а не работа.
        val swept = sweepDeadReplies(client, ledger)
        if (swept > 0) facts += "снял $swept непроверенных коммента"

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
                is PostResult.Blocked -> {
                    // Ответ не ушёл и не может уйти, пока наш прошлый коммент не пройдёт
                    // проверку или не будет удалён вручную. SKIPPED тут врал бы «не нужен»,
                    // поэтому FAILED: ответ не отправлен, коммент не потерян, вернёмся
                    // после чистки треда. Факт в дайджест обязателен — с id заблокировавшего
                    // комментария, иначе ветку найти нечем.
                    Log.w(TAG, "пост заблокирован нашим непроверенным комментом: $outcome")
                    facts += "пропущено ${target.author} в «${target.postTitle}»: наш прошлый ответ ждёт проверки " +
                        "(${outcome.status}, ${outcome.pendingCommentId.take(MoltbookClient.ID_LOG_CHARS)})"
                    ledger.markComment(target.commentId, MoltbookLedger.CommentStatus.FAILED, now = now)
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
        // Срез берётся ДО чтения, поэтому упавший пост остаётся в awaitingReply и
        // снова занимает один из трёх слотов следующего тика: один заведомо мёртвый
        // пост съедал треть бюджета, а три таких останавливали сканирование целиком.
        // Поэтому смотрим дальше POSTS_PER_TICK, а счётчик ведём по УСПЕШНЫМ
        // проходам — плохой пост стоит одного потраченного запроса, а не слота
        // навсегда.
        //
        // Ограничение честно названо: lookahead держит худший случай в
        // POSTS_PER_TICK * SCAN_LOOKAHEAD_FACTOR запросах. Если в ленте окажется
        // столько же постоянно падающих постов, сколько lookahead, сканирование
        // встанет — но это уже диагностируемое состояние (лог на каждый пропуск),
        // а не молчаливое голодание остальных постов.
        for (post in home.awaitingReply.take(POSTS_PER_TICK * SCAN_LOOKAHEAD_FACTOR)) {
            if (scanned.size >= POSTS_PER_TICK) break
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
            // Падение на чтении одного поста не должно убивать весь тик. Раньше здесь
            // был один запрос и риск был терпимым; с пагинацией страниц стало до трёх,
            // и необёрнутое исключение уносило ответы, апвоуты и снятие счётчиков
            // непрочитанного по ВСЕМ остальным постам.
            //
            // Exception, а не IOException: getJsonObject внутри бросает ещё
            // JSONException на неожиданном теле и IllegalStateException на пустой
            // JSONObject — ловить надо все, иначе заявленная цель не достигнута.
            //
            // scanned += post.postId стоит ПОСЛЕ чтения, а не до: для непрогнанного
            // поста счётчик непрочитанного считается вакуумно нулевым, markPostRead
            // обнуляет его на сервере, и пост навсегда выпадает из awaitingReply,
            // а его комменты — из ledger. Потеря данных ровно та, ради которой
            // написан блок со снятием счётчиков.
            val comments =
                try {
                    client.comments(post.postId)
                } catch (e: Exception) {
                    Log.w(TAG, "не прочитал комменты поста ${post.postId.take(8)}: ${e.message}")
                    continue
                }
            scanned += post.postId
            ledger.upsertPost(post.postId, post.title, "", true, null, comments.size, 0, now)
            val answeredByUs = answeredParents(comments)
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
            Log.i(
                TAG,
                "пост ${post.postId.take(
                    8
                )} «${post.title.take(40)}»: комментов ${comments.size}, ждут ответа ${comments.count { deservesReply(it, answeredByUs) }}"
            )
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
            ledger.upsertPost(
                post.postId,
                post.title,
                post.author,
                ours = false,
                repostOf = repostOf,
                post.commentsTotal,
                post.upvotes,
                now
            )
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

    internal sealed interface PostResult {
        data class Done(
            val commentId: String,
        ) : PostResult

        data class Verified(
            val commentId: String,
        ) : PostResult

        /**
         * Ответ не отправлен, потому что наш ПРОШЛЫЙ ответ на этом посте создан, но не
         * прошёл проверку платформы и не виден читателям.
         *
         * Отдельный исход, а не `Done`: такой коммент не отвечает ни на что, и платформа
         * не даст ответить на этот вопрос снова (замерено 07.10.2026 — вернула
         * `already_existed` со старым комментарием). Считать это успехом — значит писать
         * в лог и в дайджест «ответил» при полном молчании.
         */
        data class Blocked(
            val pendingCommentId: String,
            val status: String,
        ) : PostResult

        data class Failed(
            val reason: String,
        ) : PostResult
    }

    /**
     * Публикация комментария с прохождением проверки платформы.
     *
     * Здесь была дыра: при отказе вызывался `deleteComment(parentId)` — то есть
     * удалялся комментарий СОБЕСЕДНИКА, на который мы отвечали. Удалять там нечего.
     * Теперь удаляется только наш собственный непроверенный комментарий и только по
     * id, который сервер вернул нам же (см. `MoltbookClient.deleteComment`).
     */
    private fun postWithVerification(
        client: MoltbookClient,
        postId: String,
        parentId: String,
        draft: String,
    ): PostResult {
        // Перед публикацией перечитываем ветку: прошлый тик мог создать ответ и упасть
        // на чтении ответа — сервер уже создал комментарий, а мы об этом не узнали.
        //
        // Четыре исхода. Found — дубль запрещён. Unpublished — наш коммент создан, но не
        // опубликован: его задачу мы уже не решим (код отдают исключительно в момент
        // создания), поэтому сначала удаляем его, а ответ публикуем заново. Unknown —
        // «не знаю»: на нём публикацию НЕ выполняем, иначе упавшая сеть прочиталась бы
        // как «ответа нет» и мы опубликовали бы второй ответ на тот же вопрос.
        var stop = probeDecision(client.ourReplyTo(postId, parentId))
        if (stop is PostResult.Blocked && purgeDeadPending(client, stop)) {
            // Ветка разблокирована, но верить этому без нового чтения нельзя: сервер
            // мог не принять удаление, а второй POST вернул бы тот же already_existed.
            stop = probeDecision(client.ourReplyTo(postId, parentId))
        }
        if (stop != null) {
            Log.i(TAG, "публикация в $postId на $parentId не выполняется: $stop")
            return stop
        }

        return when (val outcome = client.postComment(postId, capForWaf(draft), parentId = parentId)) {
            is MoltbookClient.CommentOutcome.NeedsVerification -> solveChallenge(client, outcome)
            // Всё остальное решается чистой функцией, чтобы решение проверялось тестом
            // без сервера и без ключа.
            else ->
                outcomeDecision(outcome)
                    ?: PostResult.Failed("платформа ответила неизвестным исходом: $outcome")
        }
    }

    /**
     * Убирает наш собственный непроверенный комментарий, который закрыл ветку.
     *
     * Такой коммент нельзя ни решить (код отдают только в момент создания), ни
     * перезаписать (сервер вернёт `already_existed`), ни пропустить (он виден в ленте
     * и блокирует все следующие ответы). Удаление — единственный выход, и решение
     * владельца от 07.10.2026: трогаем только то, что опубликовали сами.
     *
     * @return true если комментарий действительно убран — тогда ветку читают заново.
     */
    private fun purgeDeadPending(
        client: MoltbookClient,
        blocked: PostResult.Blocked,
    ): Boolean {
        val id = blocked.pendingCommentId
        if (id.isBlank()) return false
        val short = id.take(MoltbookClient.ID_LOG_CHARS)
        return try {
            client.deleteComment(id)
            Log.i(TAG, "удалил свой непроверенный коммент $short (${blocked.status}) — ветка разблокирована")
            true
        } catch (e: IOException) {
            Log.w(TAG, "не удалил свой непроверенный коммент $short: ${e.message}")
            false
        }
    }

    /**
     * Свип наших ответов, которые сервер так и не показал читателям.
     *
     * [purgeDeadPending] чистит ветку, на которой тик уже пытался ответить. Это
     * половина задачи: 27 комментов, созданных ДО того фикса, лежат в `our_reply_id`
     * вечно, ветки с ними закрыты, и до них никто не доходит — очередь не доходит.
     * Чинить их автоматически нельзя (код задачи выдают только в момент создания),
     * поэтому решение владельца от 07.10.2026 — снести через DELETE.
     *
     * Доктрина: удаляем ТОЛЬКО то, что опубликовал сам агент — коммент с нашим
     * автором, чей id вернул сервер нам же и который лежит в нашем учёте. Совпадение
     * id с чужим комментарием, отсутствие коммента в ветке и любой сбой сети ведут к
     * «не трогать»: цена ошибки тут несимметрична — лишний живой ответ в ленте мы
     * удалить не сможем.
     *
     * Запрос идёт на ПОСТ, а не на коммент: [MoltbookClient.comments] отдаёт ветку
     * целиком со всеми глубинами, и одна выкатка решает сразу несколько записей
     * [MoltbookLedger.ourReplies] с этого поста.
     *
     * @return сколько комментов действительно удалили — это идёт в дайджест.
     */
    private fun sweepDeadReplies(
        client: MoltbookClient,
        ledger: MoltbookLedger,
    ): Int {
        val known = ledger.ourReplies(SWEEP_LIMIT)
        if (known.isEmpty()) return 0
        var deleted = 0
        for ((postId, replies) in known.groupBy { it.postId }) {
            val branch =
                try {
                    client.comments(postId)
                } catch (e: Exception) {
                    // Exception, а не IOException: клиент внутри бросает ещё
                    // JSONException и IllegalStateException, а любое из них унесло бы
                    // из runOnce ответы, апвоуты и снятие счётчиков. Непрочитанный
                    // пост — это потеря одного прохода свипа, а не тика.
                    Log.w(TAG, "не прочитал ветку $postId для свипа: ${e.message}")
                    continue
                }
            val plan = replySweep(branch, replies)
            for (commentId in plan.forget) {
                // Живой ответ не удаляем — просто снимаем адрес, чтобы свип не ходил
                // к нему снова. Log.i, а не Log.d: таких записей единицы, и по ним
                // видно, где именно старый код принял pending за успех.
                Log.i(TAG, "ответ ${commentId.take(MoltbookClient.ID_LOG_CHARS)} опубликован — снимаю его из свипа")
                ledger.forgetOurReply(commentId)
            }
            for (commentId in plan.delete) {
                val short = commentId.take(MoltbookClient.ID_LOG_CHARS)
                try {
                    client.deleteComment(commentId)
                    ledger.forgetOurReply(commentId)
                    deleted++
                    Log.i(TAG, "свип: удалил свой неопубликованный коммент $short в посте $postId")
                } catch (e: IOException) {
                    // Не забываем: запись остаётся в выборке и следующий тик пробует
                    // снова. Иначе одна сетевая ошибка навсегда оставила бы ветку
                    // закрытой — ровно то, ради чего свип и написан.
                    Log.w(TAG, "свип не удалил свой коммент $short: ${e.message}")
                } catch (e: Exception) {
                    // То же самое для не-IOException: клиент и SQLite умеют бросать
                    // своё, а цена отмены тика (все ответы, апвоуты, снятие счётчиков)
                    // несопоставима с ценой одной неудачной чистки.
                    Log.w(TAG, "свип сорвался на комменте $short: ${e.message}")
                }
            }
        }
        return deleted
    }

    /**
     * Решаем задачу платформы и публикуем УЖЕ СОЗДАННЫЙ коммент.
     *
     * Повторно публиковать нельзя. Замерено 07.10.2026: `POST /comments` создаёт
     * коммент сразу и сразу с `verification_status: "pending"`, а `POST /verify`
     * лишь делает его видимым («Your comment is now published»). Старый код после
     * успешного verify уходил на следующую итерацию цикла и постил тот же текст
     * заново — то есть на каждую неудачу заводил ещё один непроверенный коммент.
     *
     * Повторяется только verify: код задачи выдают исключительно в момент создания,
     * поэтому новый код без нового комментария получить нельзя, а вот послать ответ
     * ещё раз — можно и нужно.
     */
    private fun solveChallenge(
        client: MoltbookClient,
        challenge: MoltbookClient.CommentOutcome.NeedsVerification,
    ): PostResult {
        // Задача приходит с замутнённым текстом (случайный регистр и пунктуация внутри
        // слов) и сроком в пять минут, поэтому она в логе целиком: без неё диагностика
        // «модель не ответила» превращается в гадание.
        Log.i(
            TAG,
            "задача платформы: коммент=${challenge.commentId.take(MoltbookClient.ID_LOG_CHARS)} " +
                "код=${challenge.verificationCode.take(MoltbookClient.ID_LOG_CHARS)} " +
                "до=${challenge.expiresAt} :: ${challenge.challengeText}",
        )
        repeat(VERIFY_ATTEMPTS) { attempt ->
            val answer = askModel(verificationPrompt(challenge.challengeText))
            if (answer == null) {
                Log.w(TAG, "модель не ответила на задачу, попытка ${attempt + 1}/$VERIFY_ATTEMPTS")
                return@repeat
            }
            val ok =
                try {
                    client.verify(challenge.verificationCode, answer)
                } catch (e: IOException) {
                    // Ответ не того формата или сеть упала. Коммент при этом уже создан,
                    // поэтому новый POST вернул бы already_existed — повторяем только verify.
                    Log.w(TAG, "verification не отправлен: ${e.message}")
                    false
                }
            if (ok) return PostResult.Verified(challenge.commentId)
        }
        return PostResult.Failed("задача платформы не решена за $VERIFY_ATTEMPTS попытки")
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
                appendLine(
                    "RU: переведи КАЖДЫЙ комментарий на русский — по одной строке `RU <номер>: <перевод>`. Это для панели, читает русский человек."
                )
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
                appendLine(
                    "проверено постов=${report.postsChecked} ответов=${report.repliesPosted} verification=${report.verificationsSolved} апвоутов=${report.upvotesGiven}"
                )
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
                    org.json
                        .JSONArray()
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
        internal fun pendingComments(comments: List<MoltbookClient.Comment>): List<MoltbookClient.Comment> =
            comments.filter { deservesReply(it, answeredParents(comments)) }

        /**
         * Вопросы, на которые мы УЖЕ ответили публично.
         *
         * Неопубликованный ответ сюда не входит, и это не утечка: он блокирует ветку
         * только пока висит (замерено 07.10.2026 — сервер отдал `already_existed`),
         * а свип его удалит и ветка разблокируется. Учтя его здесь, мы пометили бы сам
         * вопрос терминальным `SKIPPED` навсегда: `upsertComment` перезаписывает только
         * NULL/NEW, и после удаления коммента вопрос в очередь не вернулся бы уже
         * никогда — ровно те 27 веток, ради которых свип и написан.
         */
        internal fun answeredParents(comments: List<MoltbookClient.Comment>): Set<String> =
            comments.filter { it.isOurs && it.isPublished }.mapNotNull { it.parentId }.toSet()

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

        /**
         * Решение по проверке дублей: чем остановить публикацию. `null` — публиковать
         * можно, ответа на этот комментарий у нас точно нет.
         *
         * Чистая функция в companion, а не ветка внутри `postWithVerification`: это
         * единственное место, где решается «молчать или публиковать», и проверять его
         * можно без сервера и без ключа.
         */
        internal fun probeDecision(probe: MoltbookClient.ReplyProbe): PostResult? =
            when (probe) {
                is MoltbookClient.ReplyProbe.Found -> PostResult.Done(probe.commentId)
                is MoltbookClient.ReplyProbe.Unpublished -> PostResult.Blocked(probe.commentId, probe.status)
                is MoltbookClient.ReplyProbe.Unknown -> PostResult.Failed("проверка дубля не удалась: ${probe.reason}")
                MoltbookClient.ReplyProbe.Absent -> null
            }

        /** Разбор ветки для свипа: что удалить, а что только забыть. */
        data class ReplySweep(
            val delete: List<String>,
            val forget: List<String>,
        )

        /**
         * Что делать с нашими ответами после сверки с сервером. Чистая функция от
         * выкачанной ветки и нашей выборки — ровно то, что обязано проверяться тестом
         * без сервера, потому что ошибка здесь необратима и невидима: лишний удалённый
         * ответ читают люди, а пропущенный мёртвый тик не объясняет в логе ничего.
         *
         * Три исхода, и «не трогать» здесь — полноценное решение, а не пропуск:
         * ответ видим читателям (`isPublished`) — живой, только снимаем адрес, чтобы
         * не проверять его каждый тик; ответ есть и не наш по автору — не наш, чужое
         * не трогаем никогда; ответа в ветке нет — удалять нечего, и забывать запись
         * нельзя: он мог просто не попасть в выборку страниц, и тогда мы навсегда
         * потеряли бы след блокировки, из-за которой ветка закрыта.
         */
        internal fun replySweep(
            branch: List<MoltbookClient.Comment>,
            known: List<MoltbookLedger.OurReply>,
        ): ReplySweep {
            val byId = branch.associateBy { it.id }
            val delete = mutableListOf<String>()
            val forget = mutableListOf<String>()
            for (reply in known) {
                val comment = byId[reply.commentId] ?: continue
                if (!comment.isOurs) continue
                if (comment.isPublished) {
                    forget += reply.commentId
                } else {
                    delete += reply.commentId
                }
            }
            return ReplySweep(delete = delete, forget = forget)
        }

        /**
         * Решение по ответу платформы на публикацию. `null` — нужна проверка задачи,
         * её разбирает тикер (там нужен вызов модели и сеть).
         *
         * `already_existed` обязан давать Blocked, а не успех: сервер не создал ничего
         * нового и вернул чужой нам комментарий, у которого parent_id может указывать на
         * другой вопрос (замерено 07.10.2026). Старый код читал его id как успех и писал
         * «ответил» — при том что нового ответа не существовало.
         */
        internal fun outcomeDecision(outcome: MoltbookClient.CommentOutcome): PostResult? =
            when (outcome) {
                is MoltbookClient.CommentOutcome.Posted -> PostResult.Done(outcome.commentId)
                is MoltbookClient.CommentOutcome.Rejected -> PostResult.Failed(outcome.reason)
                is MoltbookClient.CommentOutcome.Duplicate ->
                    PostResult.Blocked(outcome.existingCommentId, outcome.status)
                is MoltbookClient.CommentOutcome.NeedsVerification -> null
            }

        const val KEY_PATH = "moltbook/moltkey"

        const val DIGEST_PATH = "moltbook-digest.md"
        const val MOLTBOOK_MEMORY_TAG = "MOLTBOOK-SUMMARY"
        const val POSTS_PER_TICK = 3

        /**
         * Во сколько раз больше постов смотрим, чем разрешено прочитать за тик.
         *
         * Нужен из-за постов, которые читаются не всегда: упавший пост не попадает
         * в `scanned`, остаётся в `awaitingReply` и без lookahead занимал бы слот
         * каждый тик. Тройка переживает два постоянно падающих поста; больше трёх
         * подряд — уже не случайность, а повод смотреть лог.
         */
        const val SCAN_LOOKAHEAD_FACTOR = 3

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
         * Сколько наших ответов свип проверяет за один тик.
         *
         * Потолок не из вежливости: каждый кандидат стоит запроса за ветку, и 27
         * записей подряд — это двадцать с лишним походов в сеть в тик, который и так
         * должен уложиться между двучасовыми будильниками. Остаток добирается следующим
         * тиком, а ждать придётся в худшем случае два круга.
         */
        const val SWEEP_LIMIT = 20

        /**
         * Сколько вопросов переводим за тик. Больше — не «больше пользы», а раздутый
         * промпт: модель на длинных списках начинает сокращать переводы и сбивать номера.
         */
        const val MAX_GLOSS_ROWS = 10

        /** Перевод — строка в панели, а не пересказ. Обрезаем, чтобы не ломать вёрстку. */
        const val GLOSS_MAX_CHARS = 220

        /** Ниже этого апвоут — кандидат в шум, а не в поддержку. */
        const val MIN_UPVOTES_FOR_CANDIDATE = 5

        /**
         * Сколько раз пробуем решить задачу платформы на ОДНОМ коде.
         *
         * Повторяется именно verify, а не публикация: код выдают только в момент
         * создания комментария, а комментарий уже создан. Три попытки — потому что
         * единственная причина неудачи не в нас (задача арифметическая), а в том, что
         * модель ответила не в том формате или сеть качнулась; обе причины исчезают при
         * повторе сами.
         */
        const val VERIFY_ATTEMPTS = 3

        /**
         * Пауза перед повтором неудачного ответа. Короче — начинаем долбить платформу
         * тем же текстом; длиннее — человек, задавший вопрос, ждёт слишком долго.
         */
        const val RETRY_COOLDOWN_MS = 6 * 60 * 60 * 1000L
        const val MAX_COMMENT_CHARS = 900
        const val GENERATION_POLLS = 60
        const val GENERATION_POLL_MS = 3_000L
    }
}
