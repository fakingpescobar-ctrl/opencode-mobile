package org.opencode.mobile.social

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Будильник проверяется без телефона: решение принимается из двух ответов
 * сервера, поэтому оно и вынесено в чистые функции, которые берут эти ответы
 * строкой. Всё, что тут проверяется, однажды уже ложилось ровно так, как
 * написано в ожиданиях.
 */
class MoltbookWakeTest {
    private val now = 1_700_000_000_000L
    private val older = now - 10_000L
    private val fresher = now - 1_000L

    private fun sessionJson(id: String, updated: Long) =
        """{"id":"$id","title":"t","time":{"created":$updated,"updated":$updated}}"""

    // ---- разбор /session ----

    @Test
    fun `пустой ответ это ноль сессий а не ошибка`() {
        assertTrue(MoltbookWakePlan.parseSessions(null).isEmpty())
        assertTrue(MoltbookWakePlan.parseSessions("").isEmpty())
        assertTrue(MoltbookWakePlan.parseSessions("   ").isEmpty())
    }

    @Test
    fun `сессии читаются с временем обновления`() {
        val raw = "[${sessionJson("ses_a", older)},${sessionJson("ses_b", fresher)}]"
        val parsed = MoltbookWakePlan.parseSessions(raw)
        assertEquals(2, parsed.size)
        assertEquals("ses_a", parsed[0].id)
        assertEquals(older, parsed[0].updated)
        assertEquals(fresher, parsed[1].updated)
    }

    @Test
    fun `сессия без id в будильник не попадает`() {
        val raw = """[${sessionJson("ses_a", older)},{"title":"без идентификатора","time":{"updated":$fresher}}]"""
        val parsed = MoltbookWakePlan.parseSessions(raw)
        assertEquals(1, parsed.size)
        assertEquals("ses_a", parsed[0].id)
    }

    @Test
    fun `мусор вместо списка сессий это ноль сессий`() {
        assertTrue(MoltbookWakePlan.parseSessions("{\"id\":\"ses_a\"}").isEmpty())
        assertTrue(MoltbookWakePlan.parseSessions("не json").isEmpty())
        assertTrue(MoltbookWakePlan.parseSessions("<html>404</html>").isEmpty())
    }

    @Test
    fun `время обновления без числа это ноль а не выброс`() {
        val raw = """[{"id":"ses_a","time":{}},{"id":"ses_b","time":5}]"""
        val parsed = MoltbookWakePlan.parseSessions(raw)
        assertEquals(2, parsed.size)
        assertEquals(0L, parsed[0].updated)
        assertEquals(0L, parsed[1].updated)
    }

    // ---- разбор /session/status ----

    @Test
    fun `пустой статус это честное «никто не занят»`() {
        // Живой ответ сервера 1.18.25, когда генераций нет. Раньше он был ровно
        // таким, и «прочиталось как пусто» обязан отличаться от «не прочиталось».
        assertEquals(emptySet<String>(), MoltbookWakePlan.parseBusy("{}"))
    }

    @Test
    fun `занятые сессии читаются как множество`() {
        val busy = MoltbookWakePlan.parseBusy("""{"ses_a":{},"ses_b":{}}""")
        assertEquals(setOf("ses_a", "ses_b"), busy)
    }

    @Test
    fun `непрочитанный статус это null а не «все свободны»`() {
        assertNull(MoltbookWakePlan.parseBusy(null))
        assertNull(MoltbookWakePlan.parseBusy(""))
        assertNull(MoltbookWakePlan.parseBusy("   "))
        assertNull(MoltbookWakePlan.parseBusy("не json"))
    }

    // ---- решение, кому писать ----

    private val sessions =
        listOf(
            MoltbookWakePlan.Session("ses_old", older),
            MoltbookWakePlan.Session("ses_new", fresher),
        )

    @Test
    fun `свободную сессию будим`() {
        val plan = MoltbookWakePlan.choosePlan(sessions, emptySet(), now, lastWakeAt = 0L)
        assertTrue(plan is MoltbookWakePlan.Plan.Wake)
        assertEquals("ses_new", (plan as MoltbookWakePlan.Plan.Wake).sessionId)
    }

    @Test
    fun `занятую сессию не будим даже если она свежее всех`() {
        val plan = MoltbookWakePlan.choosePlan(sessions, setOf("ses_new"), now, lastWakeAt = 0L)
        assertTrue(plan is MoltbookWakePlan.Plan.Wake)
        assertEquals("ses_old", (plan as MoltbookWakePlan.Plan.Wake).sessionId)
    }

    @Test
    fun `когда заняты все не пишем никому`() {
        val plan = MoltbookWakePlan.choosePlan(sessions, setOf("ses_old", "ses_new"), now, lastWakeAt = 0L)
        assertTrue(plan is MoltbookWakePlan.Plan.Hold)
        assertEquals("все сессии заняты", (plan as MoltbookWakePlan.Plan.Hold).reason)
    }

    @Test
    fun `неизвестная занятость запрещает действие а не разрешает его`() {
        val plan = MoltbookWakePlan.choosePlan(sessions, null, now, lastWakeAt = 0L)
        assertTrue(plan is MoltbookWakePlan.Plan.Hold)
    }

    @Test
    fun `пустой список сессий это отказ а не повод завести сессию`() {
        val plan = MoltbookWakePlan.choosePlan(emptyList(), emptySet(), now, lastWakeAt = 0L)
        assertTrue(plan is MoltbookWakePlan.Plan.Hold)
        assertEquals("сессий нет — будить некого", (plan as MoltbookWakePlan.Plan.Hold).reason)
    }

    @Test
    fun `только что будили значит не будим снова`() {
        val since = 5 * 60 * 1000L
        val plan = MoltbookWakePlan.choosePlan(sessions, emptySet(), now, lastWakeAt = now - since)
        assertTrue(plan is MoltbookWakePlan.Plan.Hold)
        assertTrue((plan as MoltbookWakePlan.Plan.Hold).reason.contains("5 мин"))
    }

    @Test
    fun `на границе паузы будить можно а ровно на границе ещё рано`() {
        val inside = MoltbookWakePlan.choosePlan(sessions, emptySet(), now, now - MoltbookWakePlan.MIN_GAP_MS + 1)
        assertTrue(inside is MoltbookWakePlan.Plan.Hold)
        val exactly = MoltbookWakePlan.choosePlan(sessions, emptySet(), now, now - MoltbookWakePlan.MIN_GAP_MS)
        assertTrue(exactly is MoltbookWakePlan.Plan.Wake)
    }

    @Test
    fun `откат часов не вешает будильник навсегда`() {
        // Часы поехали назад: прошло «больше, чем мы думаем». Старая проверка
        // «прошло меньше получаса» на минусе вечна, и автономия выключалась
        // молча, без единой ошибки.
        val plan = MoltbookWakePlan.choosePlan(sessions, emptySet(), now, lastWakeAt = now + 60_000L)
        assertTrue(plan is MoltbookWakePlan.Plan.Wake)
    }

    @Test
    fun `первый будильник в жизни проходит потому что метки ещё нет`() {
        val plan = MoltbookWakePlan.choosePlan(sessions, emptySet(), now, lastWakeAt = 0L)
        assertTrue(plan is MoltbookWakePlan.Plan.Wake)
    }

    // ---- текст и форма запроса ----

    @Test
    fun `текст будильника называет три инструмента и их порядок`() {
        val text = MoltbookWake.wakeText(now)
        val scan = text.indexOf("moltbook_scan")
        val publish = text.indexOf("moltbook_publish")
        val verify = text.indexOf("moltbook_verify")
        val report = text.indexOf("moltbook_report")
        assertTrue("нет moltbook_scan", scan >= 0)
        assertTrue("порядок нарушен: scan", scan < publish)
        assertTrue("порядок нарушен: publish", publish < verify)
        assertTrue("порядок нарушен: verify", verify < report)
    }

    @Test
    fun `текст будильника говорит что отказ это результат`() {
        val text = MoltbookWake.wakeText(now)
        assertTrue(text.contains("отказ"))
        assertTrue(text.contains("не повторяй"))
    }

    @Test
    fun `текст будильника не велит трогать репозиторий и старый тикер`() {
        val text = MoltbookWake.wakeText(now)
        assertTrue(text.contains("не править файлы"))
        assertTrue(text.contains("не запускать старый тикер"))
        assertFalse(text.contains("MoltbookTicker.kt"))
    }

    @Test
    fun `тело сообщения это ровно одна текстовая часть`() {
        val body = MoltbookWake.messageBody("привет")
        val obj = JSONObject(body)
        val parts = obj.getJSONArray("parts")
        assertEquals(1, parts.length())
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("привет", parts.getJSONObject(0).getString("text"))
    }

    @Test
    fun `тело сообщения переживает кавычки и переносы в тексте`() {
        val nasty = "кавычка \" и перенос\nвнутри"
        val obj = JSONObject(MoltbookWake.messageBody(nasty))
        assertEquals(nasty, obj.getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun `путь сообщения это сессия и её лента`() {
        assertEquals("/session/ses_x/message", MoltbookWake.messagePath("ses_x"))
    }

    // ---- журнал ----

    @Test
    fun `строка будильника это одна строка на одну попытку`() {
        val wake = MoltbookWake.line(now, MoltbookWakePlan.Plan.Wake("ses_x"))
        assertEquals("$now wake session=ses_x", wake)
        val hold = MoltbookWake.line(now, MoltbookWakePlan.Plan.Hold("все сессии заняты"))
        assertEquals("$now hold все сессии заняты", hold)
        assertFalse(wake.contains("\n"))
        assertFalse(hold.contains("\n"))
    }

    @Test
    fun `переносы в причине не разрывают журнал на две записи`() {
        val hold = MoltbookWake.line(now, MoltbookWakePlan.Plan.Hold("первая\r\nвторая   третья"))
        assertEquals("$now hold первая вторая третья", hold)
    }

    @Test
    fun `пустой ответ сервера и журнал это разные вещи`() {
        // Список сессий пуст и статус пуст — это разные отказы, и пустой ответ
        // сервера не должен выглядеть как запись в журнале.
        assertTrue(MoltbookWakePlan.parseSessions("").isEmpty())
        assertEquals(emptySet<String>(), MoltbookWakePlan.parseBusy("{}"))
        assertTrue(JSONArray("[]").length() == 0)
    }
}
