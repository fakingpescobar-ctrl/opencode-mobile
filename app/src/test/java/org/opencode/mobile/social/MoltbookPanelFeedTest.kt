package org.opencode.mobile.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Разбор снимка панели и строки будильника.
 *
 * Панель рисует то, что вернёт [parsePanel], поэтому проверяем главное: отказ не
 * должен выглядеть как ноль, а «не знаю» не должно превращаться в «ноль».
 */
class MoltbookPanelFeedTest {
    @Test
    fun `пустой снимок это отказ а не нули`() {
        val state = parsePanel(null)
        assertFalse(state.ok)
        assertEquals("снимка нет", state.reason)
        assertEquals(0, state.awaiting)
        assertEquals(UNKNOWN_COUNT, state.karma)
    }

    @Test
    fun `мусор вместо снимка не выглядит как успех`() {
        val state = parsePanel("не json")
        assertFalse(state.ok)
        assertEquals("снимок не разобрать", state.reason)
    }

    @Test
    fun `отказ самого плагина сохраняет его причину`() {
        val state = parsePanel("""{"ok":false,"reason":"ledger-missing"}""")
        assertFalse(state.ok)
        assertEquals("ledger-missing", state.reason)
    }

    @Test
    fun `время попытки не выдаётся за время данных`() {
        // Плагин писал в отказе своё текущее время. Со стороны это читалось как
        // свежий снимок: подпись "только что" и зелёный ноль при том, что не
        // прочитано ничего. Признак честности - at <= 0, а не время попытки.
        val state = parsePanel("""{"at":${System.currentTimeMillis()},"ok":false,"reason":"ledger-missing"}""")
        assertFalse(state.ok)
        assertEquals(0L, state.at)
        assertTrue(state.stale)
        assertEquals("нет данных", cadenceHint(state))
    }

    @Test
    fun `снимок переносит все числа`() {
        val state = parsePanel(sample())
        assertTrue(state.ok)
        assertEquals(3L, state.at)
        assertEquals(4, state.awaiting)
        assertEquals(2, state.answered)
        assertEquals(1, state.verified)
        assertEquals(5, state.upvotes)
        assertEquals(0, state.failed)
    }

    @Test
    fun `неизвестная карма не становится нулём`() {
        val state = parsePanel(sample())
        assertEquals(UNKNOWN_COUNT, state.karma)
        assertEquals(7, state.unread)
    }

    @Test
    fun `карма подписана своим временем, а не временем снимка`() {
        val now = 1700000000000L
        val fresh = parsePanel("""{"at":$now,"ok":true,"karma":103,"karmaAt":$now}""")
        assertEquals("карма", karmaLabel(fresh, now))
        assertEquals("карма · 40 мин", karmaLabel(fresh, now + 40 * MINUTE))
        assertEquals("карма · 5 ч", karmaLabel(fresh, now + 5 * HOUR))
        assertEquals("карма · вчера", karmaLabel(fresh, now + 30 * HOUR))
        assertEquals("карма · 4 дн", karmaLabel(fresh, now + 4 * DAY))
    }

    @Test
    fun `полчаса это полчаса а не час`() {
        // При интервале сканирования 30-60 минут "час назад" на весь диапазон
        // после двух минут - это враньё: карма почти всегда была бы подписана
        // "час назад", хотя её видели десять минут назад.
        val now = 1700000000000L
        val fresh = parsePanel("""{"at":$now,"ok":true,"karma":103,"karmaAt":$now}""")
        assertEquals("карма · 2 мин", karmaLabel(fresh, now + STALE_AFTER))
        assertEquals("карма · 59 мин", karmaLabel(fresh, now + 59 * MINUTE))
        assertEquals("карма · 1 ч", karmaLabel(fresh, now + HOUR))
        assertEquals("карма · 23 ч", karmaLabel(fresh, now + 23 * HOUR))
    }

    @Test
    fun `карма без времени остаётся просто кармой`() {
        val now = 1700000000000L
        // Число есть, а времени нет - значит неизвестно, когда его видели.
        // Подпись «только что» была бы выдумкой.
        val noStamp = parsePanel("""{"at":$now,"ok":true,"karma":103}""")
        assertEquals("карма", karmaLabel(noStamp, now + 100 * 24 * 60 * 60 * 1000L))
        val unknown = parsePanel(sample())
        assertEquals("карма", karmaLabel(unknown, now))
    }

    @Test
    fun `вопрос без id в списке не вопрос`() {
        val state = parsePanel(sample())
        assertEquals(2, state.pending.size)
        assertEquals("c-1", state.pending.first().id)
    }

    @Test
    fun `успешный будильник это не причина для красной строки`() {
        assertEquals("", wakeLineOf("1791588980508 wake session=ses_1"))
        assertEquals("", wakeLineOf(null))
        assertEquals("", wakeLineOf("   \n\n  "))
    }

    @Test
    fun `отказ будильника попадает в панель вместе с причиной`() {
        assertEquals("hold все сессии заняты", wakeLineOf("1791588980508 hold все сессии заняты"))
    }

    @Test
    fun `берётся последняя строка а не первая`() {
        val log = "1791 hold первый\n1792 wake ses_1\n1793 hold второй"
        assertEquals("hold второй", wakeLineOf(log))
    }

    @Test
    fun `длинная причина обрезается чтобы не сломать высоту карточки`() {
        val line = wakeLineOf("1793 hold " + "я".repeat(500))
        assertTrue(line.length <= 80)
    }

    private fun sample(): String =
        """
        {"at":3,"ok":true,"awaiting":4,"answered":2,"verified":1,"reused":1,"failed":0,
         "unknown":9,"ourPosts":6,"repliesToOurs":11,"reposts":2,"upvotes":5,
         "karma":null,"unread":7,
         "pending":[{"id":"c-1","author":"a","title":"t","body":"b"},
                    {"id":"","author":"x"},
                    {"id":"c-2","author":"y","title":"t2","body":"b2"}]}
        """.trimIndent().replace("\n", "")

    @Test
    fun `отказ будильника это тоже отказ, если снимок чистый`() {
        val snapshot = MoltbookPanelState(at = 3L, ok = true, answered = 2)

        val merged = withWakeRefusal(snapshot, refusal(9L, "hold будили 12 мин назад"))

        assertFalse(merged.ok)
        assertEquals("hold будили 12 мин назад", merged.reason)
        assertEquals(2, merged.answered)
        assertEquals(3L, merged.at)
    }

    @Test
    fun `пустой отказ ничего не меняет`() {
        val snapshot = MoltbookPanelState(at = 3L, ok = true, answered = 2)

        val merged = withWakeRefusal(snapshot, null)

        assertTrue(merged.ok)
        assertEquals("", merged.reason)
    }

    @Test
    fun `отказ не затирает причину, которую у снимка уже имел`() {
        val snapshot = MoltbookPanelState(ok = false, reason = "снимка нет")

        val merged = withWakeRefusal(snapshot, refusal(9L, "hold сервер не ответил"))

        assertFalse(merged.ok)
        assertEquals("снимка нет", merged.reason)
    }

    @Test
    fun `давний отказ не красит здоровую карточку`() {
        val log = "1791500000000 hold сервер не ответил"

        assertEquals("", wakeLineOf(log, now = 1791500000000L + STALE_PLUS))
    }

    @Test
    fun `свежий отказ остаётся виден`() {
        val log = "1791500000000 hold сервер не ответил"

        assertEquals("hold сервер не ответил", wakeLineOf(log, now = 1791500000000L + 60_000L))
    }

    @Test
    fun `строка без разборчивого времени это не запись будильника`() {
        assertEquals("", wakeLineOf("hold сервер не ответил", now = 1791500000000L))
        assertEquals("", wakeLineOf("0 hold сервер не ответил", now = 1791500000000L))
        assertEquals("", wakeLineOf("вчера hold сервер не ответил", now = 1791500000000L))
    }

    @Test
    fun `штатная пауза это не поломка будильника`() {
        assertEquals("", wakeLineOf("1791500000000 gap будили 12 мин назад", now = 1791500000001L))
    }

    @Test
    fun `снимок свеже отказа не красит карточку`() {
        val snapshot = MoltbookPanelState(at = 1000L, ok = true, answered = 4)

        val merged = withWakeRefusal(snapshot, refusal(500L, "hold сервер не ответил"))

        assertTrue(merged.ok)
        assertEquals("", merged.reason)
    }

    @Test
    fun `отказ новее сье отказа красит карточку`() {
        val snapshot = MoltbookPanelState(at = 500L, ok = true, answered = 4)

        val merged = withWakeRefusal(snapshot, refusal(1000L, "hold сервер не ответил"))

        assertFalse(merged.ok)
        assertEquals("hold сервер не ответил", merged.reason)
    }

    @Test
    fun `большое число сворачивается а не обрезается`() {
        assertEquals("999", countLabel(999))
        assertEquals("1к", countLabel(1000))
        assertEquals("1.2к", countLabel(1234))
        assertEquals("10.3к", countLabel(10347))
        assertEquals("1.2м", countLabel(1234567))
        assertEquals("—", countLabel(UNKNOWN_COUNT))
    }

    @Test
    fun `короткий журнал читается целиком`() {
        val log = writeLog("первая\nвторая\nтретья\n")
        assertEquals("первая\nвторая\nтретья\n", tailText(log, LONG_TAIL))
    }

    @Test
    fun `из длинного журнала берётся только хвост а первая строка не режется`() {
        val many = (1..200).joinToString("\n") { "строка $it" } + "\n"
        val log = writeLog(many)
        // Предел меньше файла: первая строка хвоста начинается с середины
        // настоящей строки, и её обрывок обязан исчезнуть.
        val tail = tailText(log, SHORT_TAIL)
        assertFalse(tail.orEmpty().contains("строка 1\n"))
        assertTrue(tail.orEmpty().contains("строка 200"))
        // Одна прочитанная строка, а не весь журнал.
        assertTrue((tail.orEmpty().length) < many.length)
    }

    @Test
    fun `отсутствующий журнал это отсутствие а не падение`() {
        assertNull(tailText(File("нет-такого-журнала.log"), LONG_TAIL))
    }

    // ---- На карточке две разные величины об одном: крупная цифра - сколько
    // вопросов ждут ВСЕГО, список - сколько поместилось. Пользователь увидел
    // «67 ждут ответа» и шесть строк под ним и спросил, где остальные. Без
    // явной оговорки это выглядит как ошибка.
    @Test
    fun `список короче счёта и об этом сказано вслух`() {
        assertEquals("ЖДУТ ОТВЕТА · ЕЩЁ 61", pendingHeadline(61))
    }

    @Test
    fun `когда показано всё заголовок молчит про остаток`() {
        assertEquals("ЖДУТ ОТВЕТА", pendingHeadline(0))
    }

    @Test
    fun `счёт меньше показанного не рождает отрицательный остаток`() {
        // Расхождение возможно: снимок читается в момент, когда список уже успел
        // поредьеть, а count считает по всей таблице. Отрицательное «ЕЩЁ -3»
        // было бы враньём наоборот.
        assertEquals("ЖДУТ ОТВЕТА", pendingHeadline(-3))
    }

    private fun writeLog(text: String): File {
        val file = File.createTempFile("wake-", ".log")
        file.deleteOnExit()
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    private fun refusal(
        at: Long,
        reason: String,
    ): WakeRefusal = WakeRefusal(at, reason)

    private companion object {
        /** Сутки и две минуты: снимок старше этого уже не «только что». */
        const val STALE_PLUS: Long = 24L * 60L * 60L * 1000L + 1000L

        const val MINUTE: Long = 60L * 1000L
        const val HOUR: Long = 60L * MINUTE
        const val DAY: Long = 24L * HOUR
        const val STALE_AFTER: Long = 2L * MINUTE

        /** Предел хвоста журнала в тестах: больше файла и меньше файла. */
        const val LONG_TAIL = 1024L * 1024L
        const val SHORT_TAIL = 128L
    }
}
