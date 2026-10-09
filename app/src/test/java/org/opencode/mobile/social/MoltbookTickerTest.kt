package org.opencode.mobile.social

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отбор комментов — единственное место, где тик решает «кого отвечать». Ошибка
 * здесь не видна сразу: лишний ответ выглядит как активность агента, а пропущенный
 * — как «он перестал отвечать». Оба варианта проверяются тут.
 */
class MoltbookTickerTest {
    private fun comment(
        id: String,
        author: String,
        parentId: String? = null,
        verificationStatus: String = "",
    ) = MoltbookClient.Comment(id, author, "текст $id", parentId, verificationStatus)

    /**
     * Неопубликованный ответ ответом не считается: он удаляется свипом, после чего
     * вопрос должен вернуться в очередь. Если считать его ответом, вопрос помечается
     * SKIPPED навсегда — это и были те 27 веток, молча выпавших из работы.
     */
    @Test
    fun `непроверенный наш ответ не закрывает вопрос навсегда`() {
        val branch =
            listOf(
                comment("c-1", "человек"),
                comment("c-2", "opencodekz", parentId = "c-1", verificationStatus = "pending"),
            )

        assertTrue(MoltbookTicker.pendingComments(branch).map { it.id }.contains("c-1"))
    }

    /** Опубликованный ответ, наоборот, вопрос закрывает — иначе ответим второй раз. */
    @Test
    fun `опубликованный наш ответ вопрос закрывает`() {
        val branch =
            listOf(
                comment("c-1", "человек"),
                comment("c-2", "opencodekz", parentId = "c-1", verificationStatus = "verified"),
            )

        assertEquals(emptyList<String>(), MoltbookTicker.pendingComments(branch).map { it.id })
    }

    @Test
    fun `берёт только корневые чужие комменты`() {
        val pending =
            MoltbookTicker.pendingComments(
                listOf(
                    comment("c-1", "человек"),
                    comment("c-2", "opencodekz"),
                    comment("c-3", "человек", parentId = "c-1"),
                ),
            )

        assertEquals(listOf("c-1"), pending.map { it.id })
    }

    @Test
    fun `уже отвеченный коммент повторно не берёт`() {
        val pending =
            MoltbookTicker.pendingComments(
                listOf(
                    comment("c-1", "человек"),
                    comment("c-2", "opencodekz", parentId = "c-1"),
                    comment("c-3", "человек"),
                    comment("c-4", "opencodekz", parentId = "c-3"),
                ),
            )

        assertEquals(emptyList<String>(), pending.map { it.id })
    }

    @Test
    fun `наш коммент без parent_id считается корневым и не берётся`() {
        val pending = MoltbookTicker.pendingComments(
            listOf(
                comment("c-1", "opencodekz"),
                comment("c-2", "OPENCODEKZ"),
            ),
        )

        assertEquals(emptyList<String>(), pending.map { it.id })
    }

    @Test
    fun `пустая лента не роняет тик`() {
        assertEquals(emptyList<String>(), MoltbookTicker.pendingComments(emptyList()).map { it.id })
    }

    /**
     * Решения агента: ритм визита и апвоуты. Здесь ошибка не «краш», а тихая порча:
     * `NaN` минут останавливает расписание, а лишний апвоут — это фарм, который мы
     * обещали не делать. Поэтому мусор обязан давать fallback, а не угадывание.
     */
    private fun parse(
        answer: String?,
        vararg candidates: String,
    ) = MoltbookTicker.parseHousekeepingAnswer(answer, candidates.toList(), FALLBACK)

    @Test
    fun `берёт апвоуты и ритм из строгих двух строк`() {
        val result = parse("UPVOTE: 1 3\nNEXT: 90", "p-1", "p-2", "p-3")

        assertEquals(listOf("p-1", "p-3"), result.upvotePostIds)
        assertEquals(90, result.nextVisitMinutes)
    }

    @Test
    fun `пустой апвоут - это ноль апвоутов а не весь список`() {
        val result = parse("UPVOTE: 0\nNEXT: 120", "p-1", "p-2")

        assertEquals(emptyList<String>(), result.upvotePostIds)
        assertEquals(120, result.nextVisitMinutes)
    }

    /**
     * Регрессия: модель возвращала эхо строки промпта. Мягкий разбор доставал из
     * «(0-3)» номер 3 и ставил апвоут посту, которого агент не выбирал, а из
     * «(30-720)» — 30 минут, то есть выдумывал ритм. Мусорная строка обязана
     * игнорироваться целиком.
     */
    @Test
    fun `эхо промпта в директивах игнорируется целиком`() {
        val result = parse(
            "UPVOTE: перечисли номера постов, которые СТОИТ поддержать (0-3). Пусто — если ни один.\n" +
                "NEXT: через сколько минут вернуться (30-720).",
            "p-1",
            "p-2",
            "p-3",
        )

        assertEquals(emptyList<String>(), result.upvotePostIds)
        assertEquals(FALLBACK, result.nextVisitMinutes)
        assertFalse(result.nextVisitChosenByModel)
    }

    @Test
    fun `пояснение после числа - валидный ответ эхо промпта - нет`() {
        val ok = parse("NEXT: 90 минут\nUPVOTE: 1, 2.", "p-1", "p-2")

        // «90 минут» и «1, 2.» — ответ с пояснением, число впереди: принимаем.
        assertEquals(90, ok.nextVisitMinutes)
        assertEquals(listOf("p-1", "p-2"), ok.upvotePostIds)

        // Эхо промпта начинается словами, числа стоят в скобках уже после текста.
        val echo = parse("NEXT: через сколько минут вернуться (30-720).", "p-1", "p-2")

        assertEquals(FALLBACK, echo.nextVisitMinutes)
        assertFalse(echo.nextVisitChosenByModel)
    }

    @Test
    fun `номер вне списка кандидатов игнорируется`() {
        val result = parse("UPVOTE: 9\nNEXT: 60", "p-1")

        assertEquals(emptyList<String>(), result.upvotePostIds)
    }

    @Test
    fun `апвоуты ограничены потолком`() {
        val result = parse("UPVOTE: 1 2 3 4 5", "p-1", "p-2", "p-3", "p-4", "p-5")

        assertEquals(MoltbookTicker.MAX_UPVOTES_PER_TICK, result.upvotePostIds.size)
    }

    @Test
    fun `слишком частый ритм зажимается снизу а слишком редкий сверху`() {
        assertEquals(MoltbookTicker.MIN_NEXT_VISIT_MINUTES, parse("NEXT: 1", "p-1").nextVisitMinutes)
        assertEquals(MoltbookTicker.MAX_NEXT_VISIT_MINUTES, parse("NEXT: 99999", "p-1").nextVisitMinutes)
    }

    @Test
    fun `свободный текст вместо формата даёт безопасный fallback`() {
        val result = parse("Думаю, стоит зайти через пару часов", "p-1")

        assertEquals(emptyList<String>(), result.upvotePostIds)
        assertEquals(FALLBACK, result.nextVisitMinutes)
    }

    @Test
    fun `пустой ответ модели не ломает тик`() {
        assertEquals(FALLBACK, parse(null, "p-1").nextVisitMinutes)
        assertEquals(FALLBACK, parse("", "p-1").nextVisitMinutes)
    }

    @Test
    fun `переводы по номерам доезжают до панели`() {
        val result =
            parseGloss(
                "UPVOTE: \nNEXT: 45\nRU 1: Как ты сам себя улучшаешь без сети?\nRU 2: Расскажи про планировщик",
                glossable = 2,
            )

        assertEquals(45, result.nextVisitMinutes)
        assertEquals("Как ты сам себя улучшаешь без сети?", result.glosses[1])
        assertEquals("Расскажи про планировщик", result.glosses[2])
    }

    /**
     * Главный инвариант перевода: номер из ответа модели — это указание, КАКОЙ вопрос
     * переводить. Номер вне показанного списка — выдумка, и подписать ею чужой вопрос
     * хуже, чем не перевести ничего.
     */
    @Test
    fun `номер перевода вне списка отбрасывается`() {
        val result = parseGloss("NEXT: 45\nRU 9: выдуманный вопрос", glossable = 2)

        assertEquals(emptyMap<Int, String>(), result.glosses)
    }

    @Test
    fun `строка без перевода не становится пустым переводом`() {
        val result = parseGloss("NEXT: 45\nRU 2:", glossable = 2)

        assertEquals(emptyMap<Int, String>(), result.glosses)
    }

    @Test
    fun `перевод не ломает ритм и апвоуты`() {
        // Мусор в переводах не должен выбивать остальные директивы: они в том же ответе.
        val result = parseGloss("UPVOTE: 1\nNEXT: 200\nRU xx: мусор", "p-1", glossable = 1)

        assertEquals(listOf("p-1"), result.upvotePostIds)
        assertEquals(200, result.nextVisitMinutes)
        assertEquals(emptyMap<Int, String>(), result.glosses)
    }

    /**
     * Публикация: единственное место, где тик решает «отвечать ли вообще».
     *
     * Ошибки тут необратимы — комментарий создаётся сразу и ждёт проверки, отозвать
     * его клиент не может. Проверяем поэтому каждое решение до похода в сеть:
     * `null` — «публикуй», остальное — «не публикуй, и вот почему».
     */
    @Test
    fun `опубликованный наш ответ это нечего делать`() {
        val decision = MoltbookTicker.probeDecision(MoltbookClient.ReplyProbe.Found("c-1"))
        // Reused, а не Done: коммент уже виден читателям, нового ответа не создаём и
        // счётчик не инкрементим — иначе снова «ответил» там, где ответ лежит давно.
        assertTrue(decision is MoltbookTicker.PostResult.Reused)
        assertEquals("c-1", (decision as MoltbookTicker.PostResult.Reused).commentId)
    }

    @Test
    fun `наш прошлый ответ ждёт проверки - публиковать второй нельзя`() {
        val decision = MoltbookTicker.probeDecision(MoltbookClient.ReplyProbe.Unpublished("c-2", "pending"))
        assertTrue("pending обязан блокировать публикацию", decision is MoltbookTicker.PostResult.Blocked)
        assertEquals("c-2", (decision as MoltbookTicker.PostResult.Blocked).pendingCommentId)
        assertEquals("pending", decision.status)
    }

    @Test
    fun `непроверенная сеть это ошибка а не разрешение публиковать`() {
        // Unknown обязан давать Failed: иначе тик публикует дубль вслепую.
        assertTrue(
            "Unknown не должен читаться как «можно публиковать»",
            MoltbookTicker.probeDecision(MoltbookClient.ReplyProbe.Unknown("сеть молчит")) is MoltbookTicker.PostResult.Failed,
        )
    }

    @Test
    fun `нет нашего ответа - публиковать можно`() {
        assertNull(MoltbookTicker.probeDecision(MoltbookClient.ReplyProbe.Absent))
    }

    @Test
    fun `созданный ответ это готово а дубль разводится по статусу`() {
        val posted = MoltbookTicker.outcomeDecision(MoltbookClient.CommentOutcome.Posted("c-3"))
        assertTrue(posted is MoltbookTicker.PostResult.Done)
        assertEquals("c-3", (posted as MoltbookTicker.PostResult.Done).commentId)

        // Дубль в статусе pending/failed — блокировка: коммент не виден, ветку надо чистить.
        val blockedDuplicate =
            MoltbookTicker.outcomeDecision(
                MoltbookClient.CommentOutcome.Duplicate(
                    existingCommentId = "c-old",
                    existingParentId = "",
                    status = "pending",
                ),
            )
        assertTrue("already_existed с pending - это блокировка", blockedDuplicate is MoltbookTicker.PostResult.Blocked)
        assertEquals("c-old", (blockedDuplicate as MoltbookTicker.PostResult.Blocked).pendingCommentId)

        // Дубль в статусе verified — это НЕ блокировка, а живой ответ (баг 09.10.2026):
        // раньше он уходил в Blocked и его удаляли из ленты.
        val reusedDuplicate =
            MoltbookTicker.outcomeDecision(
                MoltbookClient.CommentOutcome.Duplicate(
                    existingCommentId = "0a359f36",
                    existingParentId = "",
                    status = "verified",
                ),
            )
        assertTrue("verified-дубль обязан быть Reused, а не Blocked", reusedDuplicate is MoltbookTicker.PostResult.Reused)
        assertEquals("0a359f36", (reusedDuplicate as MoltbookTicker.PostResult.Reused).commentId)
    }

    /**
     * Дедуп вернул наш коммент из ДРУГОГО треда.
     *
     * Молтбук дедуплицирует по тексту и автору БЕЗ родителя, поэтому публикация в треде
     * B может вернуть наш же id из треда A (MOLTBOOK_ADVICE 1.5/3.3). Раньше
     * `existingParentId` разбирался и не использовался: ветка получала Reused с
     * чужим-parent id, и в дайджест уходило «уже отвечено ранее» про вопрос, на который
     * мы не ответили. Живой коммент при этом удалять нельзя ни в коем случае.
     */
    @Test
    fun `дубль из чужого треда это не наш ответ на этот вопрос`() {
        val crossParent =
            MoltbookClient.CommentOutcome.Duplicate(
                existingCommentId = "0a359f36",
                existingParentId = "parent-b",
                status = "verified",
            )
        val decision = MoltbookDedupCheck.reuseOrMisparent(crossParent, "parent-a")

        assertTrue(
            "дубль из чужого треда обязан стать Misparented, а не закрывать ветку",
            decision is MoltbookTicker.PostResult.Misparented,
        )
        decision as MoltbookTicker.PostResult.Misparented
        assertEquals("0a359f36", decision.existingCommentId)
        assertEquals("parent-a", decision.expectedParentId)
        assertEquals("parent-b", decision.actualParentId)
    }

    /**
     * Тот же родитель — это честный Reused, ветку можно закрывать.
     *
     * Зеркало предыдущего теста: разводить исходы имеет смысл только когда родители
     * действительно различаются. Иначе мы бы ломали рабочий сценарий повторного
     * ответа на тот же вопрос.
     */
    @Test
    fun `дубль с тем же родителем это наш ответ на этот вопрос`() {
        val sameParent =
            MoltbookClient.CommentOutcome.Duplicate(
                existingCommentId = "c-7",
                existingParentId = "parent-a",
                status = "verified",
            )

        val decision = MoltbookDedupCheck.reuseOrMisparent(sameParent, "parent-a")

        assertTrue("тот же родитель обязан быть Reused", decision is MoltbookTicker.PostResult.Reused)
        assertEquals("c-7", (decision as MoltbookTicker.PostResult.Reused).commentId)
    }

    /**
     * Сервер не вернул родителя — это «не знаю», и уверенно закрывать ветку нельзя.
     *
     * Отдельный случай, а не «разные родители»: отсутствие `parent_id` не доказывает
     * расхождение, но и не подтверждает совпадение. Проверять нечем, поэтому исход —
     * Misparented с пустым фактическим родителем, и живой коммент остаётся нетронутым
     * (MOLTBOOK_ADVICE 3.2: неопределённость = не удалять, не перезаписывать, не считать).
     */
    @Test
    fun `дубль без родителя это неопределённость а не повод закрыть ветку`() {
        val noParent =
            MoltbookClient.CommentOutcome.Duplicate(
                existingCommentId = "c-8",
                existingParentId = null,
                status = "verified",
            )

        val decision = MoltbookDedupCheck.reuseOrMisparent(noParent, "parent-a")

        assertTrue(
            "без родителя ветка закрываться не может",
            decision is MoltbookTicker.PostResult.Misparented,
        )
        assertNull((decision as MoltbookTicker.PostResult.Misparented).actualParentId)
    }

    @Test
    fun `отказ платформы это ошибка а не задача`() {
        assertTrue(
            MoltbookTicker.outcomeDecision(MoltbookClient.CommentOutcome.Rejected("пустой ответ")) is MoltbookTicker.PostResult.Failed,
        )
    }

    @Test
    fun `задача проверки уходит решаться а не считается исходом`() {
        assertNull(
            "NeedsVerification обязан уйти в solveChallenge",
            MoltbookTicker.outcomeDecision(
                MoltbookClient.CommentOutcome.NeedsVerification(
                    commentId = "c-4",
                    verificationCode = "moltbook_verify_abc",
                    challengeText = "TwEnTy ThReE mEtErS pEr SeCoNdS aNd SlOwS bY/ SeVeN?",
                    expiresAt = "2026-10-07 13:47:31.73428+00",
                ),
            ),
        )
    }

    private fun parseGloss(
        answer: String,
        vararg candidates: String,
        glossable: Int,
    ) = MoltbookTicker.parseHousekeepingAnswer(
        answer,
        candidates.toList(),
        FALLBACK,
        glossableCount = glossable,
    )

    /**
     * Свип наших ответов. Проверяется без сервера ровно потому, что ошибка тут
     * необратима и невидима: удалённый живой ответ читают люди, а забытый мёртвый
     * закрывает ветку навсегда, и ни один из двух исходов не виден в логе тика.
     */
    private fun ourComment(
        id: String,
        status: String,
        author: String = "opencodekz",
    ) = MoltbookClient.Comment(id, author, "текст $id", null, status)

    private fun known(
        commentId: String,
        postId: String = "p-1",
    ) = MoltbookLedger.OurReply(commentId, postId)

    @Test
    fun `непроверенный ответ удаляется`() {
        val plan = MoltbookTicker.replySweep(listOf(ourComment("r-1", "pending")), listOf(known("r-1")))

        assertEquals(listOf("r-1"), plan.delete)
        assertEquals(emptyList<String>(), plan.forget)
    }

    @Test
    fun `опубликованный ответ не удаляется а просто забывается`() {
        val plan = MoltbookTicker.replySweep(listOf(ourComment("r-1", "verified")), listOf(known("r-1")))

        // Успех, а не ошибка: такой ответ в ленте виден, и сносить его нельзя. Забываем
        // только адрес, чтобы свип не ходил к нему каждый тик.
        assertEquals(emptyList<String>(), plan.delete)
        assertEquals(listOf("r-1"), plan.forget)
    }

    @Test
    fun `чужой коммент с тем же id не трогаем`() {
        val plan =
            MoltbookTicker.replySweep(
                listOf(ourComment("r-1", "pending", author = "человек")),
                listOf(known("r-1")),
            )

        assertEquals(emptyList<String>(), plan.delete)
        assertEquals(emptyList<String>(), plan.forget)
    }

    @Test
    fun `ответа нет в ветке - не трогаем и не забываем`() {
        val plan = MoltbookTicker.replySweep(listOf(ourComment("r-другой", "pending")), listOf(known("r-1")))

        assertEquals(emptyList<String>(), plan.delete)
        assertEquals(emptyList<String>(), plan.forget)
    }

    /**
     * Регрессия к доктрине «удаляем только своё»: `our_reply_id` писался старым кодом
     * для КАЖДОГО ответа, включая успешно проверенные. Снести выборку целиком — значит
     * удалить живые ответы, которые нельзя пересоздать.
     */
    @Test
    fun `в ветке смешанная история - удаляется только неопубликованное`() {
        val plan =
            MoltbookTicker.replySweep(
                listOf(
                    ourComment("r-pending", "pending"),
                    ourComment("r-live", "verified"),
                    ourComment("r-foreign", "pending", author = "человек"),
                ),
                listOf(known("r-pending"), known("r-live"), known("r-foreign")),
            )

        assertEquals(listOf("r-pending"), plan.delete)
        assertEquals(listOf("r-live"), plan.forget)
    }

    /**
     * Пустой статус — это «сервер не отдал», а не «pending». Считать его
     * неопубликованным нельзя: иначе свип удалил бы ответ, который в ленте виден.
     */
    @Test
    fun `неизвестный статус это не повод удалять`() {
        val plan = MoltbookTicker.replySweep(listOf(ourComment("r-1", "")), listOf(known("r-1")))

        assertEquals(emptyList<String>(), plan.delete)
        assertEquals(listOf("r-1"), plan.forget)
    }

    @Test
    fun `пустая выборка и пустая ветка не роняют свип`() {
        val empty = MoltbookTicker.replySweep(emptyList(), emptyList())

        assertEquals(emptyList<String>(), empty.delete)
        assertEquals(emptyList<String>(), empty.forget)
        assertEquals(emptyList<String>(), MoltbookTicker.replySweep(emptyList(), listOf(known("r-1"))).delete)
    }

    /**
     * Регрессия на весь путь «проверка провалилась → ветка разблокирована → ответ ушёл».
     *
     * Живой замер 08.10.2026, который всё это сломал:
     *
     * ```
     * POST /posts/2e7ee554.../comments -> comment=213f89f6 status=failed already_existed=true
     * публикация в ... не выполняется: Blocked(pendingCommentId=213f89f6-..., status=failed)
     * ```
     *
     * `already_existed` — это блокировка, у которой id известен только из ответа на
     * POST, то есть ПОСЛЕ первой попытки. Старый код на нём возвращался, коммент
     * оставался, и каждый следующий тик получал тот же `already_existed`: ветка
     * закрывалась навсегда, при этом ни одной ошибки в логе — тик отчитывался о
     * нормальной работе.
     *
     * Путь проверяется тремя чистыми решениями подряд, потому что каждый шаг можно
     * проверить без сервера, а ошибка любого из них даёт тот же самый тихий клин:
     *
     *  1. проба видит наш `failed` как неопубликованный, а не как «ответа нет»
     *     — иначе следующий шаг был бы вторым POST с тем же дублем;
     *  2. решение после успешного удаления возвращает null, то есть «публикуй»;
     *  3. решение после неудачного удаления возвращает блокировку, а не «пробуй
     *     ещё раз» — без комментария сервер продолжит отдавать тот же дубль.
     */
    @Test
    fun `провалившийся ответ разблокирует ветку и ответ уходит заново`() {
        val dead = MoltbookTicker.PostResult.Blocked("213f89f6", MoltbookClient.STATUS_FAILED)

        // 1. Проба на живой фикстуре того же замера: наш коммент в статусе failed —
        //    он виден и его надо снести, а не считать ветку отвеченной.
        val fixture =
            JSONObject(
                """
                {
                  "comments": [
                    { "id": "2e7ee554", "author": {"name": "opencodekz"},
                      "content": "пост агента", "parent_id": "", "replies": [
                        { "id": "213f89f6", "author": {"name": "opencodekz"},
                          "content": "наш ответ", "parent_id": "2e7ee554",
                          "verification_status": "failed", "is_deleted": false }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            )
        val branch = MoltbookClient.parseComments(fixture)
        val probe = MoltbookClient.probeIn(branch, "2e7ee554")
        assertTrue("наш failed обязан быть виден пробе как неопубликованный", probe is MoltbookClient.ReplyProbe.Unpublished)
        assertEquals("213f89f6", (probe as MoltbookClient.ReplyProbe.Unpublished).commentId)
        assertEquals(
            "проба обязана превратить провал в блокировку, а не разрешить публикацию",
            dead,
            MoltbookTicker.probeDecision(probe),
        )

        // 2. Удаление прошло, ветка перечитана и наших комментов в ней нет — публикуем.
        assertNull(
            "после успешного удаления ветка свободна — повторная публикация обязана состояться",
            MoltbookTicker.afterPurge(
                purged = true,
                reProbe = MoltbookClient.ReplyProbe.Absent,
                blocked = dead,
            ),
        )

        // 3. Удаление не прошло — блокировка остаётся, второй POST не делаем.
        assertEquals(
            "без удалённого коммента сервер вернёт тот же already_existed — повтор бессмыслен",
            dead,
            MoltbookTicker.afterPurge(
                purged = false,
                reProbe = MoltbookClient.ReplyProbe.Absent,
                blocked = dead,
            ),
        )
    }

    /**
     * Удалённый, но всё ещё видимый платформой коммент на том же вопросе — это
     * [MoltbookClient.ReplyProbe.Found]: ветка закрыта, повторный ответ был бы дублем.
     * Сценарий не из живого замера, но ровно тот, что закрывает `afterPurge` с двух
     * сторон: успешное удаление не обязано открывать ветку, если сервер считает её
     * занятой по другому признаку.
     */
    @Test
    fun `после удаления чужой ответ всё равно закрывает ветку`() {
        assertEquals(
            "наш живой ответ после удаления мёртвого — дубль не публикуем",
            MoltbookTicker.PostResult.Reused("c-live"),
            MoltbookTicker.afterPurge(
                purged = true,
                reProbe = MoltbookClient.ReplyProbe.Found("c-live"),
                blocked = MoltbookTicker.PostResult.Blocked("213f89f6", MoltbookClient.STATUS_FAILED),
            ),
        )
    }

    private companion object {
        const val FALLBACK = 120
    }
}
