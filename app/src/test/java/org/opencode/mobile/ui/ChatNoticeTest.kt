package org.opencode.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор GET /session/status без провайдера.
 *
 * Зачем этот тест: плашка причины отказа появилась из-за того, что при
 * исчерпанной квоте пользователь видел вечный спиннер и вывод «проверь
 * сеть/провайдера» — то есть сервер знал причину, а приложение её молчало.
 * Ждать, пока провайдер реально упрётся в лимит, чтобы это проверить, нельзя:
 * лимит может и не наступить, а регрессия в разборе вернётся незамеченной.
 *
 * Фикстуры — реальные ответы сервера, снятые с устройства (в т.ч. из logcat
 * при исчерпанной квоте). Не выдуманы: форма полей меняется вместе с
 * opencode, и тогдаевный тест ломается вместе с ним, показывая правку.
 */
class ChatNoticeTest {
    /** Реальный ответ при исчерпанной квоте: message и action.message разные. */
    @Test
    fun `retry с лимитом квоты даёт плашку с действием`() {
        val raw =
            """{"ses_abc":{"type":"retry","attempt":1,
            |"message":"Free usage exceeded, subscribe to Go",
            |"action":{"reason":"free_tier_limit","provider":"opencode",
            |"title":"Free limit reached","label":"subscribe",
            |"link":"https://opencode.ai/go"},
            |"next":1790899200537}}
            """.trimMargin()

        val n = noticeFromStatus(raw, "ses_abc")
        assertTrue("ожидалась плашка, получено $n", n != null)
        n!!
        assertEquals("Free limit reached", n.title)
        // action.message пуст -> в текст идёт message, без дубля сам с собой.
        assertEquals("Free usage exceeded, subscribe to Go", n.message)
        assertEquals("subscribe", n.actionLabel)
        assertEquals("https://opencode.ai/go", n.actionLink)
        assertEquals(1, n.attempt)
    }

    /**
     * Порядок приоритета: если сервер кладёт текст в action.message, он важнее
     * общего message — иначе плашка скажет «Free usage exceeded» вместо того,
     * что провайдер конкретно отказал.
     */
    @Test
    fun `action message важнее общего message`() {
        val raw =
            """{"ses_x":{"type":"error",
            |"message":"rate limited",
            |"action":{"message":"provider rejected the request, quota exhausted"}}}
            """.trimMargin()

        val n = noticeFromStatus(raw, "ses_x")
        assertTrue(n != null)
        n!!
        assertTrue(
            "в тексте должен быть action.message, был: ${n.message}",
            n.message.startsWith("provider rejected the request"),
        )
        // Общее message не потерялось — дописывается скобкой.
        assertTrue("ожидался хвост с message, был: ${n.message}", n.message.contains("rate limited"))
        // Без action.title нужен собственный заголовок, а не пустота.
        assertEquals("Модель ответила с ошибкой", n.title)
    }

    /**
     * Главный ложноположительный риск: на живом ходе плашка не должна появляться
     * вообще. Проверяем на реальных статусах успешного хода.
     */
    @Test
    fun `штатные статусы не дают плашку`() {
        assertNull("busy на живом ходе — не повод для плашки", noticeFromStatus("""{"ses_a":{"type":"busy"}}""", "ses_a"))
        assertNull("idle без записи", noticeFromStatus("{}", "ses_a"))
        assertNull("retry без текста — тишина лучше пустой плашки", noticeFromStatus("""{"ses_a":{"type":"retry","attempt":2}}""", "ses_a"))
        assertNull("чужой sessionId", noticeFromStatus("""{"ses_b":{"type":"retry","message":"x"}}""", "ses_a"))
    }

    /** Мусор на входе не должен ронять опрос: тихо игнорируем. */
    @Test
    fun `битый ответ не бросает исключение`() {
        assertNull(noticeFromStatus(null, "ses_a"))
        assertNull(noticeFromStatus("", "ses_a"))
        assertNull(noticeFromStatus("<html>502</html>", "ses_a"))
        assertNull(noticeFromStatus("{не json", "ses_a"))
    }

    /**
     * Защёлка, а не TTL-кэш: причина — устойчивое состояние (сервер отдаёт
     * free_tier_limit после любого TTL, пока квота не восстановится), поэтому
     * плашка не должна мигать вслед за опросом. Гаснуть она обязана ровно на
     * успешном assistant-шаге — это и есть «проблема решена».
     */
    @Test
    fun `защёлка держит причину через пустые тики и гаснет на успешном шаге`() {
        val reason = noticeFromStatus("""{"ses_a":{"type":"retry","message":"quota"}}""", "ses_a")
        assertTrue(reason != null)

        // Тик, где статус ещё есть — обновляем плашку.
        var latch = nextNoticeLatch(null, reason, turnFinished = false)
        assertEquals(reason, latch)

        // Тик, где сервер моргнул и статуса нет: ход ещё идёт — плашка обязана
        // остаться. Иначе причина мигает и её перестаёшь читать.
        latch = nextNoticeLatch(latch, null, turnFinished = false)
        assertEquals("плашка не должна исчезать, пока ход идёт", reason, latch)
        latch = nextNoticeLatch(latch, null, turnFinished = false)
        assertEquals(reason, latch)

        // Пришёл успешный assistant-шаг (thinking снят) — гасим.
        latch = nextNoticeLatch(latch, null, turnFinished = true)
        assertNull("на успешном шаге плашка обязана погаснуть", latch)

        // И следующая причина появляется снова, то есть защёлка не «слипла».
        latch = nextNoticeLatch(null, reason, turnFinished = false)
        assertEquals(reason, latch)
    }

    /** Статус, пришедший на последнем тике, должен перебивать устаревшую защёлку. */
    @Test
    fun `свежая причина заменяет старую в защёлке`() {
        val old = noticeFromStatus("""{"ses_a":{"type":"retry","attempt":1,"message":"quota"}}""", "ses_a")
        val fresh = noticeFromStatus("""{"ses_a":{"type":"error","message":"provider down"}}""", "ses_a")
        val latch = nextNoticeLatch(old, fresh, turnFinished = false)
        assertEquals(fresh, latch)
    }
}
