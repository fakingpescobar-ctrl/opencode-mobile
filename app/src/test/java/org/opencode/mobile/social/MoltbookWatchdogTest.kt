package org.opencode.mobile.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencode.mobile.social.MoltbookWatchdog.Verdict
import org.opencode.mobile.social.MoltbookWitness.Entry

/**
 * Сторож проверяется на границах молчания, и почти все тесты — про границы.
 *
 * Ложная тревога и пропущенная тревога стоят одинаково дорого, причём первая
 * дороже: сторож, который кричит при откате часов, учит юзера его выключить, и
 * после этого не кричит уже никогда. Поэтому каждое решение о сравнении
 * зафиксировано тестом, а не оставлено на «очевидно».
 *
 * Ни одного мока Context здесь нет и быть не может: проверяемые функции —
 * чистые, без Context и без файлов, и если им понадобится Android, значит
 * чистыми они перестали быть.
 */
class MoltbookWatchdogTest {
    private val startedAt = 1_757_000_000_000L

    /** Три часа, как в проде, иначе тест проверяет другую границу, чем живую. */
    private val limitMs = MoltbookWatchdog.LIMIT_MS_FALLBACK

    private fun head(
        at: Long,
        seq: Long = 137L,
    ): Entry = Entry(seq = seq, at = at, kind = MoltbookWitness.KIND_EFFECT, payload = "digest", prevHash = "0", hash = "h")

    /** Цепочка из n записей: та же схема, что у `append`, но в памяти. */
    private fun chain(vararg at: Long): List<Entry> {
        var prevHash = MoltbookWitness.GENESIS_HASH
        return at.mapIndexed { index, stamp ->
            val seq = index.toLong()
            val payload = "payload-$seq"
            val hash = MoltbookWitness.hashOf(seq, stamp, MoltbookWitness.KIND_WAKE, payload, prevHash)
            val entry = Entry(seq, stamp, MoltbookWitness.KIND_WAKE, payload, prevHash, hash)
            prevHash = hash
            entry
        }
    }

    /** Живой тик: последняя запись только что. Молчать не с чем. */
    @Test
    fun `свежая голова — тик жив`() {
        val now = startedAt
        assertNull(MoltbookWatchdog.inspect(head(now), now, limitMs))
    }

    /** Запись двух часов назад при трёхчасовом пороге — ещё живой тик. */
    @Test
    fun `молчание меньше порога — тик жив`() {
        val now = startedAt
        val twoHoursAgo = now - 2 * 60 * 60 * 1000L
        assertNull(MoltbookWatchdog.inspect(head(twoHoursAgo), now, limitMs))
    }

    /** Молчание под порогом = смерть тика, и в вердикте видно, КОГДА он замолчал. */
    @Test
    fun `молчание больше порога — вердикт Silent с точным sinceMs`() {
        val now = startedAt
        val silence = 4 * 60 * 60 * 1000L + 12 * 60 * 1000L
        val verdict = MoltbookWatchdog.inspect(head(now - silence), now, limitMs) as Verdict.Silent
        assertEquals(silence, verdict.sinceMs)
        assertEquals(137L, verdict.lastSeq)
    }

    /**
     * Журнала нет вообще — это тоже молчание, причём худшее: нет НИ ОДНОГО
     * свидетельства жизни, а не «свидетельство есть, но старое». Отдельной
     * веткой это быть не может, потому что от юзера требуется одно и то же
     * действие в обоих случаях.
     */
    @Test
    fun `нет журнала — молчание без единого свидетельства`() {
        val verdict = MoltbookWatchdog.inspect(null, startedAt, limitMs) as Verdict.Silent
        assertEquals(Long.MAX_VALUE, verdict.sinceMs)
        assertEquals(MoltbookWatchdog.NO_ENTRY_SEQ, verdict.lastSeq)
    }

    /**
     * Ровно на пороге тик ещё жив: порог означает «терпим столько-то молчания»,
     * а не «терпим на миллисекунду меньше». Сравнение строгое `>`.
     */
    @Test
    fun `ровно на границе порога — ещё не молчание`() {
        assertNull(MoltbookWatchdog.inspect(head(startedAt - limitMs), startedAt, limitMs))
    }

    /** На миллисекунду за порогом — уже молчание. Граница проходит здесь. */
    @Test
    fun `на миллисекунду за порогом — молчание`() {
        val verdict =
            MoltbookWatchdog.inspect(head(startedAt - limitMs - 1L), startedAt, limitMs) as Verdict.Silent
        assertEquals(limitMs + 1L, verdict.sinceMs)
    }

    /**
     * Откат часов (NTP после ребута) даёт `at` в будущем, и разность уходит в
     * минус. Молчанием это НЕ считается: ложная тревога при переводе часов
     * обучила бы игнорировать сторожа навсегда.
     */
    @Test
    fun `откат часов не даёт ложной тревоги`() {
        val now = startedAt
        val fiveHoursAhead = now + 5 * 60 * 60 * 1000L
        assertNull(MoltbookWatchdog.inspect(head(fiveHoursAhead), now, limitMs))
    }

    /** Порог задаётся снаружи: с маленьким порогом та же запись уже мертва. */
    @Test
    fun `порог приходит снаружи`() {
        val now = startedAt
        val hourAgo = now - 60 * 60 * 1000L
        val small = 30 * 60 * 1000L
        assertTrue(MoltbookWatchdog.inspect(head(hourAgo), now, small) is Verdict.Silent)
        assertNull(MoltbookWatchdog.inspect(head(hourAgo), now, limitMs))
    }

    /** Дефолт порога — константа проекта: на её границе тик ещё жив, за ней мёртв. */
    @Test
    fun `порог по умолчанию равен константе`() {
        val now = startedAt
        assertNull(MoltbookWatchdog.inspect(head(now - limitMs), now))
        assertTrue(MoltbookWatchdog.inspect(head(now - limitMs - 1L), now) is Verdict.Silent)
    }

    /** Нулевой порог — это ошибка вызова, а не «молчит всегда». Молча не глотаем. */
    @Test(expected = IllegalArgumentException::class)
    fun `неположительный порог падает сразу`() {
        MoltbookWatchdog.inspect(head(startedAt), startedAt, 0L)
    }

    /** Целая цепочка и свежая голова — наружу не выходит ничего. */
    @Test
    fun `целая цепочка и свежая голова — всё живо`() {
        val now = startedAt
        val entries = chain(now - 1000L, now - 500L, now)
        assertNull(MoltbookWatchdog.inspectChain(entries, now, limitMs))
    }

    /** Цепочка цела, но тик замолчал — типичное молчание, не порча. */
    @Test
    fun `целая цепочка при молчании даёт Silent`() {
        val now = startedAt
        val silence = 5 * 60 * 60 * 1000L
        val entries = chain(now - silence - 1000L, now - silence)
        val verdict = MoltbookWatchdog.inspectChain(entries, now, limitMs) as Verdict.Silent
        assertEquals(silence, verdict.sinceMs)
    }

    /** Правка payload оставляет запись с чужим payload и старым хэшем — это порча. */
    @Test
    fun `правленый payload рвёт цепочку`() {
        val now = startedAt
        val entries = chain(now - 2000L, now - 1000L, now).toMutableList()
        entries[1] = entries[1].copy(payload = "подменено")
        val verdict = MoltbookWatchdog.inspectChain(entries, now, limitMs) as Verdict.Broken
        assertEquals(1L, verdict.brokenAt)
        assertTrue(verdict.reason.contains("не пересчитывается"))
    }

    /**
     * Порванная цепочка важнее молчания, даже когда тик молчит: доказанная
     * порча обесценивает любые выводы о времени.
     */
    @Test
    fun `порванная цепочка важнее молчания`() {
        val now = startedAt
        val silence = 9 * 60 * 60 * 1000L
        val entries = chain(now - silence).toMutableList()
        entries[0] = entries[0].copy(payload = "подменено")
        val verdict = MoltbookWatchdog.inspectChain(entries, now, limitMs) as Verdict.Broken
        assertEquals(0L, verdict.brokenAt)
    }

    /** Порвать цепочку по одной голове невозможно — отсюда отдельная функция. */
    @Test
    fun `по одной голове порча не определяется`() {
        val now = startedAt
        val broken = chain(now - 1000L).toMutableList().apply { this[0] = this[0].copy(payload = "подменено") }
        // Голова читается как обычная свежая запись: целостность цепочки в ней не видна.
        assertNull(MoltbookWatchdog.inspect(broken.last(), now, limitMs))
        assertTrue(MoltbookWatchdog.inspectChain(broken, now, limitMs) is Verdict.Broken)
    }

    /** Пустой журнал — это молчание, а не «нечего проверять». */
    @Test
    fun `пустой список записей — молчание без свидетельств`() {
        assertTrue(MoltbookWatchdog.inspectChain(emptyList(), startedAt, limitMs) is Verdict.Silent)
    }

    /** Мёртвый тик и так кричит каждый час — второй раз в час промолчим. */
    @Test
    fun `о тревоге не чаще раза в час`() {
        val now = startedAt
        val recent = listOf(staleEntry(0L, now - 10 * 60 * 1000L))
        assertFalse(MoltbookWatchdog.shouldReportStale(recent, now))
    }

    /** Прошлый час тревоги по журналу не считается: можно говорить снова. */
    @Test
    fun `после часа тишины тревога разрешена`() {
        val now = startedAt
        val old = listOf(staleEntry(0L, now - 61 * 60 * 1000L))
        assertTrue(MoltbookWatchdog.shouldReportStale(old, now))
        assertTrue(MoltbookWatchdog.shouldReportStale(emptyList(), now))
    }

    /** Записи тика тревогой не являются: он молчит, но сам пишет. */
    @Test
    fun `записи тика не глушат тревогу`() {
        val now = startedAt
        val entries = chain(now - 10 * 60 * 1000L)
        assertTrue(MoltbookWatchdog.shouldReportStale(entries, now))
    }

    /** Окно частоты тоже снаружи — его можно ужать тестом и разжарить в проде. */
    @Test
    fun `окно частоты приходит снаружи`() {
        val now = startedAt
        val entries = listOf(staleEntry(0L, now - 30 * 60 * 1000L))
        // Окно в час шире самой тревоги: молчать ещё рано.
        assertFalse(MoltbookWatchdog.shouldReportStale(entries, now, 60 * 60 * 1000L))
        // Окно в десять минут уже истекло: о тревоге можно сказать снова.
        assertTrue(MoltbookWatchdog.shouldReportStale(entries, now, 10 * 60 * 1000L))
    }

    /** Сообщение о молчании обязано называть и время, и номер записи. */
    @Test
    fun `сообщение о молчании читается человеком`() {
        val silence = 4 * 60 * 60 * 1000L + 12 * 60 * 1000L
        val text = MoltbookWatchdog.describe(Verdict.Silent(silence, 137L))
        assertEquals("Молтбук-сторож: тик молчит 4 ч 12 мин (последний seq 137)", text)
    }

    /** Отсутствие свидетельств читается иначе, чем «молчит 0 минут». */
    @Test
    fun `сообщение об отсутствии свидетельств отличается от нулевого молчания`() {
        val never = MoltbookWatchdog.describe(Verdict.Silent(MoltbookWatchdog.NEVER_SEEN, -1L))
        assertTrue(never.contains("нет ни одного свидетельства жизни"))
        assertEquals("0 мин", MoltbookWatchdog.describeSilence(0L))
    }

    /** Причина обрыва приходит с номером записи — в сообщении он должен быть один. */
    @Test
    fun `сообщение о порче не дублирует номер записи`() {
        val verdict = Verdict.Broken(42L, "seq=42: prevHash не ссылается на предыдущую запись")
        assertEquals(
            "Молтбук-сторож: журнал порван на seq 42: prevHash не ссылается на предыдущую запись",
            MoltbookWatchdog.describe(verdict),
        )
    }

    /** Причина незнакомого формата не теряется и не съедается вместе с префиксом. */
    @Test
    fun `причина неизвестного формата проходит насквозь`() {
        assertEquals("что-то новое", MoltbookWatchdog.detailOf("что-то новое"))
        assertEquals("", MoltbookWatchdog.detailOf("seq=7: "))
    }

    /** Порог протухания метки взведения — полторы периода, и он тоже снаружи. */
    @Test
    fun `порог протухания метки — полторы периода`() {
        val period = 1000L
        assertEquals(1500L, MoltbookWatchdog.stalenessLimitMs(period))
    }

    /** Ритма ещё нет — берётся фолбэк, а не выдуманное число. */
    @Test
    fun `без ритма порог равен фолбэку`() {
        val onlyEffects = chain(startedAt, startedAt + 1_000L).map { it.copy(kind = MoltbookWitness.KIND_EFFECT) }
        assertEquals(limitMs, MoltbookWatchdog.observedLimitMs(emptyList()))
        assertEquals(limitMs, MoltbookWatchdog.observedLimitMs(chain(startedAt)))
        // effect-записи ритм не задают: тик начинал работу один раз.
        assertEquals(limitMs, MoltbookWatchdog.observedLimitMs(onlyEffects))
    }

    /**
     * Тик, который честно ходит раз в два часа, даёт порог в три часа: тот же
     * фолбэк, что и при пустом журнале, — паузу за полтора периода.
     */
    @Test
    fun `ритм два часа даёт порог три часа`() {
        val twoHours = 2 * 60 * 60 * 1000L
        val entries = chain(startedAt, startedAt + twoHours, startedAt + 2 * twoHours)
        assertEquals(3 * 60 * 60 * 1000L, MoltbookWatchdog.observedLimitMs(entries))
    }

    /**
     * Главный случай: агент законно взял паузу в 12 часов. Фиксированные три
     * часа кричали бы «тик умер» на живой системе, и юзер перестал бы читать
     * сторожа. Здесь порог становится 18 часов.
     */
    @Test
    fun `длинная законная пауза поднимает порог`() {
        val twelveHours = 12 * 60 * 60 * 1000L
        val entries = chain(startedAt, startedAt + twelveHours, startedAt + 2 * twelveHours)
        assertEquals(18 * 60 * 60 * 1000L, MoltbookWatchdog.observedLimitMs(entries))
    }

    /** Берётся самый длинный промежуток, а не среднее: одна длинная пауза — норма. */
    @Test
    fun `берётся самый длинный промежуток`() {
        val twelveHours = 12 * 60 * 60 * 1000L
        val hour = 60 * 60 * 1000L
        val entries = chain(startedAt, startedAt + hour, startedAt + hour + twelveHours)
        assertEquals(18 * 60 * 60 * 1000L, MoltbookWatchdog.observedLimitMs(entries))
    }

/**
     * Молчание между тиками ритмом НЕ является: если бы его учитывали, мёртвый тик
     * мог бы сам себе продлить порог до произвольной величины и отключить сторож
     * именно тогда, когда тот нужен.
     */
    @Test
    fun `молчание не попадает в ритм`() {
        val hour = 60 * 60 * 1000L
        val wake = chain(startedAt, startedAt + hour)
        // Тридцать часов тишины после последнего старта — это молчание, а не ритм.
        val afterSilence = wake + listOf(wake.last().copy(at = startedAt + 30 * hour))
        assertEquals(limitMs, MoltbookWatchdog.observedLimitMs(afterSilence))
    }

    /** Откат часов даёт отрицательный промежуток — он не ритм, а мусор. */
    @Test
    fun `откат часов не попадает в ритм`() {
        val entries = chain(startedAt, startedAt - 5 * 60 * 60 * 1000L)
        assertEquals(limitMs, MoltbookWatchdog.observedLimitMs(entries))
    }

    /** Испорченная история не может разрешить тику молчать месяц. */
    @Test
    fun `порог упирается в потолок`() {
        val week = 7 * 24 * 60 * 60 * 1000L
        val entries = chain(startedAt, startedAt + week, startedAt + 2 * week)
        assertEquals(MoltbookWatchdog.LIMIT_MS_CEILING, MoltbookWatchdog.observedLimitMs(entries))
    }

    /** Порог никогда не опускается ниже фолбэка: ритм быстрее трёх часов — не причина. */
    @Test
    fun `короткий ритм не опускает порог ниже фолбэка`() {
        val tenMinutes = 10 * 60 * 1000L
        val entries = chain(startedAt, startedAt + tenMinutes, startedAt + 2 * tenMinutes)
        assertEquals(limitMs, MoltbookWatchdog.observedLimitMs(entries))
    }

    /** Часы и минуты в сообщении считаются целыми минутами, без месяцев и локали. */
    @Test
    fun `длительность молчания читается без локали`() {
        assertEquals("59 мин", MoltbookWatchdog.describeSilence(59 * 60_000L + 59_999L))
        assertEquals("1 ч 0 мин", MoltbookWatchdog.describeSilence(60 * 60_000L))
        assertEquals("2 д 3 ч 4 мин", MoltbookWatchdog.describeSilence((2 * 24 * 60 + 3 * 60 + 4) * 60_000L))
    }

    private fun staleEntry(
        seq: Long,
        at: Long,
    ): Entry = Entry(seq, at, MoltbookWitness.KIND_STALE, "молчит", MoltbookWitness.GENESIS_HASH, "h")

    /** Тик, который только что открыт, — идущий, а не оборванный. */
    @Test
    fun `свежеоткрытый тик не считается оборванным`() {
        val now = startedAt
        assertNull(MoltbookWatchdog.interruptedTick(now - 60_000L, 0L, now))
    }

    /** Открытый тик старше получаса — тот самый, у которого нет следа (Starfish). */
    @Test
    fun `открытый тик без следа старше получаса это Interrupted`() {
        val now = startedAt
        val openedAt = now - MoltbookWatchdog.INTERRUPT_MIN_AGE_MS - 1L
        val verdict = MoltbookWatchdog.interruptedTick(openedAt, openedAt - 1L, now) as Verdict.Interrupted
        assertEquals(openedAt, verdict.openedAt)
        assertEquals(now - openedAt, verdict.ageMs)
    }

    /** Ровно на пороге ещё идёт: «терпим столько-то» означает и здесь «не меньше». */
    @Test
    fun `на пороге оборва ещё нет`() {
        val now = startedAt
        val openedAt = now - MoltbookWatchdog.INTERRUPT_MIN_AGE_MS
        assertNull(MoltbookWatchdog.interruptedTick(openedAt, openedAt - 1L, now))
    }

    /** Тик закрыт штатно — тревоги нет. */
    @Test
    fun `закрытый тик не роняет сторож`() {
        assertNull(MoltbookWatchdog.interruptedTick(null, startedAt, startedAt))
    }

    /**
     * Живой, но медленный тик — не оборванный.
     *
     * Тик с моделью и ретраями живёт дольше получаса вполне обычно. Объявлять
     * такой тик оборванным — ложная тревога каждые полчаса ровно у того, кто
     * честно работает; след в журнале и есть доказательство, что он идёт.
     */
    @Test
    fun `тик который продолжает писать след не считается оборванным`() {
        val now = startedAt
        val openedAt = now - MoltbookWatchdog.INTERRUPT_MIN_AGE_MS - 1L
        assertNull(MoltbookWatchdog.interruptedTick(openedAt, openedAt + 1000L, now))
    }

    /** Формулировка обязана запрещать дорисовывать успех, а не описывать тишину. */
    @Test
    fun `сообщение об обрыве запрещает считать публикации сделанными`() {
        val verdict =
            MoltbookWatchdog.interruptedTick(
                startedAt - MoltbookWatchdog.INTERRUPT_MIN_AGE_MS - 1L,
                0L,
                startedAt,
            )
        val text = MoltbookWatchdog.describe(verdict!!)
        assertTrue(text, text.contains("оборвался на середине"))
        assertTrue(text, text.contains("недоказаны"))
    }
}
