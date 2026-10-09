package org.opencode.mobile.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencode.mobile.social.MoltbookLedger.ScanDelta
import org.opencode.mobile.social.MoltbookLedger.SeenComment

/**
 * Дельта между сканами и канонический отпечаток базы — чистая логика, поэтому
 * проверяется здесь без SQLite: юнит-гейт не поднимает БД, а поднимать её ради
 * проверки сравнения списков дороже, чем доказать сравнение напрямую.
 *
 * Ошибка в этой арифметике не выглядит ошибкой: агент получает «три новых
 * комментария» там, где их не было, или наоборот молчит о пропавшем ответе. Первое
 * он тратит впустую, второе оставляет вопрос человека без ответа навсегда.
 */
class MoltbookLedgerDeltaTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun seen(
        commentId: String,
        postId: String = "p-1",
        status: String = "NEW",
        ours: Boolean = false,
        at: Long = now - 2 * day,
        hits: Int = MoltbookLedger.SURVIVED_SCANS,
    ) = SeenComment(postId = postId, commentId = commentId, status = status, ours = ours, at = at, hits = hits)

    /** Первое знакомство: пустой журнал, всё прочитанное — новое. Иначе агент молчит о ленте. */
    @Test
    fun `пустая база даёт сплошной added`() {
        val batch = listOf(seen("c-1"), seen("c-2"), seen("c-3"))

        val delta = MoltbookLedger.computeDelta(emptyList(), batch, now)

        assertEquals(listOf("c-1", "c-2", "c-3"), delta.added.map { it.commentId })
        assertTrue(delta.statusChanged.isEmpty())
        assertTrue(delta.vanished.isEmpty())
        assertFalse(delta.isEmpty)
    }

    /** Нормальный тик: лента не менялась, и дельта обязана быть пустой, иначе в дайджест идёт мусор. */
    @Test
    fun `повторный скан того же не даёт дельты`() {
        val previous = listOf(seen("c-1"), seen("c-2"))

        val delta = MoltbookLedger.computeDelta(previous, previous, now)

        assertTrue(delta.isEmpty)
        assertEquals("новых=0 сменило статус=0 исчезло=0", delta.summary)
    }

    /**
     * Смена статуса — это `statusChanged`, а НЕ пара «исчез + появился». Второй
     * вариант отправил бы агента «отвечать на комментарий, на который мы уже
     * ответили», то есть публиковать второй ответ на публичный вопрос.
     */
    @Test
    fun `смена статуса это statusChanged а не added плюс vanished`() {
        val before = seen("c-1", status = "NEW")
        val after = seen("c-1", status = "POSTED")

        val delta = MoltbookLedger.computeDelta(listOf(before), listOf(after), now)

        assertTrue(delta.added.isEmpty())
        assertTrue(delta.vanished.isEmpty())
        assertEquals(1, delta.statusChanged.size)
        assertEquals("NEW", delta.statusChanged[0].first.status)
        assertEquals("POSTED", delta.statusChanged[0].second)
    }

    /** Наш комментарий — тоже наблюдение: он не отвечает на вопрос, но появляется в ленте. */
    @Test
    fun `наш комментарий попадает в added со своим флагом`() {
        val batch = listOf(seen("c-1", ours = true))

        val delta = MoltbookLedger.computeDelta(emptyList(), batch, now)

        assertTrue(delta.added[0].ours)
    }

    /** Ключевой случай: пропал только прочитанный пост, а остальные посты даже не сканировались. */
    @Test
    fun `исчезновение считается только для просканированных постов`() {
        val previous = listOf(seen("c-1", postId = "p-1"), seen("c-9", postId = "p-2"))

        val delta = MoltbookLedger.computeDelta(previous, listOf(seen("c-2", postId = "p-1")), now)

        assertEquals(listOf("c-1"), delta.vanished.map { it.commentId })
        assertEquals(listOf("c-2"), delta.added.map { it.commentId })
    }

    /**
     * Мигание пагинации. Пост, который тик не добрался до конца, вернётся через
     * час; если бы мы объявили такое исчезновение сразу, агент счёл бы пост
     * вычищенным и перестал бы за ним следить. Порог — [MoltbookLedger.VANISHED_GRACE_MS].
     */
    @Test
    fun `свежее исчезновение не считается исчезновением`() {
        val previous = listOf(seen("c-1", at = now - 60_000L))

        val delta = MoltbookLedger.computeDelta(previous, listOf(seen("c-2", at = now)), now)

        assertTrue(delta.vanished.isEmpty())
    }

    /**
     * Первое наблюдение пережить скан не могло: комментарий видели один раз, и
     * «пропал» он прямо на следующем скане. Часовой порог это не отсекает —
     * интервал тика (2-12 ч) длиннее часа, и раньше каждое исчезновение
     * объявлялось на первом же пропуске.
     */
    @Test
    fun `первое наблюдение не считается исчезновением`() {
        val previous = listOf(seen("c-1", hits = 1))

        val delta = MoltbookLedger.computeDelta(previous, listOf(seen("c-2", at = now)), now)

        assertTrue(delta.vanished.isEmpty())
    }

    /**
     * Настоящее исчезновение старше часа — уже факт, а не шум пагинации.
     *
     * Батч непустой: пост обязан попасть в скан, иначе «ничего не пришло» означало
     * бы «пост не читали», а не «пост исчез».
     */
    @Test
    fun `давнее исчезновение считается исчезновением`() {
        val previous = listOf(seen("c-1", at = now - 2 * 60 * 60 * 1000))

        val delta = MoltbookLedger.computeDelta(previous, listOf(seen("c-2", at = now)), now)

        assertEquals(listOf("c-1"), delta.vanished.map { it.commentId })
    }

    /**
     * Двойной прогон (обрыв сети на середине скана, перезапуск тика) не двоит
     * `added`: иначе агент увидит «два новых комментария» там, где он один.
     */
    @Test
    fun `повтор строки в батче не двоит дельту`() {
        val batch = listOf(seen("c-1"), seen("c-1"), seen("c-2"))

        val delta = MoltbookLedger.computeDelta(emptyList(), batch, now)

        assertEquals(listOf("c-1", "c-2"), delta.added.map { it.commentId })
    }

    /** Порядок обхода не должен попадать в результат: тот же скан — тот же список. */
    @Test
    fun `порядок батча не меняет дельту`() {
        val batch = listOf(seen("c-3"), seen("c-1"), seen("c-2"))

        val delta = MoltbookLedger.computeDelta(emptyList(), batch, now)

        assertEquals(listOf("c-1", "c-2", "c-3"), delta.added.map { it.commentId })
    }

    /** Сводка обязана нести все три числа: по ней агент пишет факты дайджеста. */
    @Test
    fun `сводка содержит все три счётчика`() {
        val previous = listOf(seen("c-old", at = now - 2 * day), seen("c-st", status = "NEW"))

        val delta =
            MoltbookLedger.computeDelta(
                previous,
                listOf(seen("c-new"), seen("c-st", status = "POSTED")),
                now,
            )

        assertEquals("новых=1 сменило статус=1 исчезло=1", delta.summary)
    }

    /** `isEmpty` верен на каждом виде пустоты — по одному, а не только на полностью пустом. */
    @Test
    fun `isEmpty верен на каждом виде пустоты`() {
        assertTrue(ScanDelta().isEmpty)
        assertFalse(ScanDelta(added = listOf(seen("c-1"))).isEmpty)
        assertFalse(ScanDelta(statusChanged = listOf(seen("c-1") to "POSTED")).isEmpty)
        assertFalse(ScanDelta(vanished = listOf(seen("c-1"))).isEmpty)
    }

    /** База пуста — а сводка всё равно обязана быть внятной: три нуля, а не пустая строка. */
    @Test
    fun `пустая дельта даёт нули в сводке`() {
        assertEquals("новых=0 сменило статус=0 исчезло=0", ScanDelta().summary)
    }

    /**
     * Усечение по сроку жизни. Записи старше [MoltbookLedger.SEEN_KEEP_MS] удаляются
     * независимо от их количества: они физически не могут участвовать в сравнении
     * «прошлый скан против текущего».
     */
    @Test
    fun `усечение выкидывает записи старше срока жизни`() {
        val rows = listOf(seen("c-old", at = now - 10 * day), seen("c-fresh", at = now))

        val doomed = MoltbookLedger.prunePlan(rows, now)

        assertEquals(listOf("c-old"), doomed.map { it.commentId })
    }

    /** Всё просрочено — удаляем целиком, а не «оставляем пустое место до потолка». */
    @Test
    fun `усечение выкидывает всё когда просрочено всё`() {
        val rows = (1..5).map { seen("c-$it", at = now - 30 * day) }

        assertEquals(5, MoltbookLedger.prunePlan(rows, now).size)
    }

    /** Под потолком не трогаем ничего: усечение не должно работать на ровном месте. */
    @Test
    fun `под потолком усечение пустое`() {
        val rows = (1..3).map { seen("c-$it", at = now) }

        assertTrue(MoltbookLedger.prunePlan(rows, now).isEmpty())
    }

    /**
     * Потолок по числу. Старые записи уходят, и дельта против них заврёт «новое» —
     * это заложено и описано в KDoc [prunePlan]: ошибка в безопасную сторону
     * («лишний вопрос») вместо опасной («пропал ответ на свой же вопрос»).
     */
    @Test
    fun `превышение потолка выкидывает самые старые`() {
        val rows =
            (1..MoltbookLedger.MAX_SEEN + 10).map { i ->
                seen("c-$i", at = now - i * 1000L)
            }

        val doomed = MoltbookLedger.prunePlan(rows, now)

        assertEquals(10, doomed.size)
        // c-1 прочитан последним, c-{MAX_SEEN+10} — самым старым: режется хвост.
        assertTrue(doomed.any { it.commentId == "c-${MoltbookLedger.MAX_SEEN + 10}" })
        assertFalse(doomed.any { it.commentId == "c-1" })
    }

    /** Усечение не должно выкидывать то, что тик только что прочитал. */
    @Test
    fun `усечение не трогает только что прочитанное`() {
        val rows =
            (1..MoltbookLedger.MAX_SEEN + 5).map { i ->
                seen("c-$i", at = now - (i - 1) * 1000L)
            }

        val doomed = MoltbookLedger.prunePlan(rows, now).map { it.commentId }.toSet()

        assertFalse(doomed.contains("c-1"))
        assertFalse(doomed.contains("c-5"))
    }

    /** При усечении следующая дельта честно называет «новым» то, чего уже не помним. */
    @Test
    fun `после усечения забытое считается новым`() {
        val forgotten = seen("c-1", at = now - 30 * day)
        val doomed = MoltbookLedger.prunePlan(listOf(forgotten), now)
        val survivors = listOf(forgotten) - doomed.toSet()

        val delta = MoltbookLedger.computeDelta(survivors, listOf(seen("c-1", at = now)), now)

        assertEquals(listOf("c-1"), delta.added.map { it.commentId })
        assertTrue(delta.vanished.isEmpty())
    }

    /**
     * Ключ последнего отпечатка обязан быть на месте: без него тикер не сможет
     * положить хэш в базу, и весь startup-diff останется недописанным.
     */
    @Test
    fun `ключ отпечатка объявлен`() {
        assertEquals("last_digest", MoltbookLedger.KEY_LAST_DIGEST)
    }

    /**
     * Порядок обхода не попадает в хэш. Без сортировки ключей два одинаковых
     * состояния давали бы два разных отпечатка, и сверка кричала бы «порча» на
     * каждом тике — а после третьего такого крика её перестали бы читать.
     */
    @Test
    fun `одинаковое содержимое в разном порядке даёт одинаковый хэш`() {
        val first = linkedMapOf("comments[NEW]" to "3", "ourPosts" to "1", "upvotes" to "2")
        val second = linkedMapOf("upvotes" to "2", "ourPosts" to "1", "comments[NEW]" to "3")

        assertEquals(MoltbookLedger.digestOf(first), MoltbookLedger.digestOf(second))
    }

    /** Любой счётчик обязан менять хэш, иначе порча одного счётчика осталась бы незамеченной. */
    @Test
    fun `изменение любого счётчика меняет хэш`() {
        val base = MoltbookLedger.digestOf(mapOf("comments[NEW]" to "3", "ourPosts" to "1"))

        assertNotEquals(base, MoltbookLedger.digestOf(mapOf("comments[NEW]" to "4", "ourPosts" to "1")))
        assertNotEquals(base, MoltbookLedger.digestOf(mapOf("comments[NEW]" to "3", "ourPosts" to "2")))
        assertNotEquals(base, MoltbookLedger.digestOf(mapOf("comments[NEW]" to "3")))
    }

    /**
     * Наши ответы входят в отпечаток поштучно. Счётчик «наших ответов = 2» не
     * отличил бы базу, где мы ответили на два вопроса, от базы, где дважды ответили
     * на один, — а это ровно тот случай, где свип обязан увидеть разницу.
     */
    @Test
    fun `состав наших ответов меняет хэш`() {
        val two = MoltbookLedger.digestOf(mapOf("ourReply:r-1" to "1", "ourReply:r-2" to "1"))
        val other = MoltbookLedger.digestOf(mapOf("ourReply:r-1" to "1", "ourReply:r-3" to "1"))

        assertNotEquals(two, other)
    }

    /** Смена статуса комментария обязана менять хэш: POSTED и NEW — это разные базы. */
    @Test
    fun `смена статуса комментария меняет хэш`() {
        val posted = MoltbookLedger.digestOf(mapOf("comments[POSTED]" to "1"))
        val fresh = MoltbookLedger.digestOf(mapOf("comments[NEW]" to "1"))

        assertNotEquals(posted, fresh)
    }

    /** Пустая база — валидный хэш, а не пустая строка: тикер должен иметь что сверять в первый тик. */
    @Test
    fun `пустая база даёт валидный непустой хэш`() {
        val digest = MoltbookLedger.digestOf(emptyMap())

        assertTrue(digest.isNotEmpty())
        assertEquals(64, digest.length)
        assertFalse(digest.startsWith("err:"))
    }

    /** Хэш обязан быть стабильным между вызовами на одном состоянии — это и есть смысл доказательства. */
    @Test
    fun `повторный хэш того же состояния совпадает`() {
        val parts = mapOf("comments[NEW]" to "5", "seen" to "12", "ourPosts" to "2")

        assertEquals(MoltbookLedger.digestOf(parts), MoltbookLedger.digestOf(parts))
    }

    /** Отпечаток — строка фиксированной длины: иначе в журнал-свидетель попадёт мусор. */
    @Test
    fun `хэш это 64 hex-символа`() {
        val digest = MoltbookLedger.digestOf(mapOf("comments[NEW]" to "1"))

        assertTrue(digest.matches(Regex("[0-9a-f]{64}")))
    }

/**
     * Граница усечения — пара (время, id), а не одно время: весь батч пишется
     * одним `now`, и `at <= edge` вычистил бы за раз весь тик целиком, а не одну
     * лишнюю строку. Здесь все строки с ОДНИМ временем — то есть ровно тот случай,
     * где продовый `DELETE` обязан отрезать одну строку, а эталон — резать одну
     * строку. Расхождение этих двух величин и было багом.
     */
    @Test
    fun `усечение при равных временах режет одну строку а не весь батч`() {
        val batch = (1..MoltbookLedger.MAX_SEEN + 1).map { seen("c-$it", at = now - day) }

        val dropped = MoltbookLedger.prunePlan(batch, now)

        assertEquals(1, dropped.size)
        assertEquals(MoltbookLedger.MAX_SEEN, batch.size - dropped.size)
        // Кто именно режется — последний в порядке «новые вперёд, при равном
        // времени id по возрастанию». Ожидаемый id вычисляется тем же порядком,
        // а не зашит строкой: сравнение строк лексикографическое, и зашитый
        // «c-1» оказался бы не тем, что режет эталон.
        val lastInOrder =
            batch
                .sortedWith(compareByDescending<MoltbookLedger.SeenComment> { it.at }.thenBy { it.commentId })
                .last()
        assertEquals(lastInOrder.commentId, dropped[0].commentId)
    }

    /**
     * Контракт между писателем и пересчётом: enum пишет `wire`, SQL читает `wire`.
     *
     * Счётчик ответов теперь проекция журнала (MOLTBOOK_ADVICE 3.13), а проекция — это
     * SQL-запрос, где исходы зашиты строкой. Опечатка в `wire` или в запросе не падает:
     * SQLite просто не находит строк и тик честно отчитается «ноль ответов», то есть
     * молча перестанет считать вообще. Robolectric в проекте нет, базу в unit-тесте не
     * поднять, поэтому тест фиксирует обе стороны — писателя и читателя — плюс форму
     * запроса.
     */
    @Test
    fun `wire исходов совпадает с тем что читает пересчёт`() {
        assertEquals("created", MoltbookLedger.ReplyOutcome.CREATED.wire)
        assertEquals("verified", MoltbookLedger.ReplyOutcome.VERIFIED.wire)
        assertEquals("reused", MoltbookLedger.ReplyOutcome.REUSED.wire)

        // REUSED обязан быть в пересчёте, а CREATED — обязаны оба. Проверяем
        // строками запроса, а не фактом выполнения: единственное, что мы можем
        // проверить без базы, это сам текст запроса.
        assertTrue(
            "REUSED не должен попадать в счётчик созданных",
            MoltbookLedger.CREATED_SQL.contains("?") && !MoltbookLedger.CREATED_SQL.contains(MoltbookLedger.ReplyOutcome.REUSED.wire),
        )
        assertTrue(
            "пересчёт должен фильтровать по статусу POSTED",
            MoltbookLedger.CREATED_SQL.contains("status = ?") && MoltbookLedger.CREATED_SQL.contains("replied_at >= ?"),
        )
        assertTrue(
            "пересчёт проверок должен фильтровать по reply_outcome",
            MoltbookLedger.VERIFIED_SQL.contains("reply_outcome = ?"),
        )
    }

    /**
     * Плейсхолдеров в запросе ровно столько же, сколько аргументов передаёт вызов.
     *
     * SQLite не жалуется на лишний или недостающий аргумент при `rawQuery` — он
     * подставляет NULL, и запрос возвращает 0 строк. То есть опечатка в количестве
     * плейсхолдеров даёт ровно то молчание, ради которого этот счётчик и выносили в
     * пересчёт. Считаем `?` и сверяем с числом плейсхолдеров в каждом запросе.
     */
    @Test
    fun `в запросах пересчёта столько же плейсхолдеров сколько условий`() {
        assertEquals(4, MoltbookLedger.CREATED_SQL.count { it == '?' })
        assertEquals(3, MoltbookLedger.VERIFIED_SQL.count { it == '?' })
    }
}
