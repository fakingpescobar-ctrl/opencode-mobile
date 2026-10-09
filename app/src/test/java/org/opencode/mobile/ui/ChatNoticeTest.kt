package org.opencode.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * Сработал ли abort. Это правило уже один раз сделало всю фичу недостижимой:
     * судили по коду ответа, а abort на несуществующей сессии отдаёт 200+HTML,
     * то есть всегда «успех», и диалог сброса не открывался никогда.
     */
    @Test
    fun `abort считается сработавшим только если запись хода уехала`() {
        // Нормальный случай: ход был, abort его снял — выходим, диалог не нужен.
        assertTrue(abortResolvedTurn(runningBefore = true, runningAfter = false))
        // Ход остался в статусе — abort не пробился, нужен сброс.
        assertFalse(abortResolvedTurn(runningBefore = true, runningAfter = true))
        // Записи не было ВООБЩЕ: останавливать нечего, состояние мёртвое само по
        // себе, abort ничего не изменил. Считать это успехом нельзя.
        assertFalse(abortResolvedTurn(runningBefore = false, runningAfter = false))
        assertFalse(abortResolvedTurn(runningBefore = false, runningAfter = true))
    }

    /**
     * Бюджет повторов: без него плашка «Модель повторяет запрос» висела 2.5 часа
     * (47 ошибок подряд у провайдера opencode) и пользователь ничего не мог.
     */
    @Test
    fun `бюджет повторов кончается по числу попыток и по времени`() {
        val few = ChatNotice("t", "m", attempt = 3)
        val many = ChatNotice("t", "m", attempt = RETRY_ATTEMPT_LIMIT)
        assertFalse(retryExhausted(few, elapsedMs = 0L))
        // Порог по счётчику попыток.
        assertTrue(retryExhausted(many, elapsedMs = 0L))
        // Порог по времени — попыток может не быть вовсе: при сетевом сбое сервер
        // их не шлёт, и тогда единственный признак залипания это молчание.
        assertFalse(retryExhausted(few, elapsedMs = RETRY_BUDGET_MS - 1))
        assertTrue(retryExhausted(few, elapsedMs = RETRY_BUDGET_MS))
        // Плашки нет — ждать нечего, останавливать нечего.
        assertFalse(retryExhausted(null, elapsedMs = Long.MAX_VALUE))
    }

    /** Об остановленном ходе нужно сказать явно: иначе тишина выглядит как игнор. */
    @Test
    fun `после остановки плашка говорит что остановили мы`() {
        val stopped =
            stoppedRetryNotice(
                ChatNotice("Модель повторяет запрос", "socket closed", attempt = 8),
                elapsedMs = 1_000L,
            )
        assertEquals("Ход остановлен", stopped.title)
        assertEquals(8, stopped.attempt)
        assertTrue(stopped.message.contains("8"))
        assertTrue(stopped.actionLabel == null)
    }

    /**
     * Причина остановки в тексте названа честно: сервер при сетевом сбое может
     * насчитать две попытки, и тогда «остановил после 8 попыток» было бы ложью.
     */
    @Test
    fun `при остановке по времени текст не врёт про число попыток`() {
        val stopped =
            stoppedRetryNotice(
                ChatNotice("Модель повторяет запрос", "socket closed", attempt = 2),
                elapsedMs = RETRY_BUDGET_MS,
            )
        assertTrue(stopped.message.contains("10 мин"))
        assertTrue(stopped.message.contains("2 попыток"))
        assertTrue(!stopped.message.contains("после 2 попыток"))
        // По счётчику причина остаётся прежней.
        val byCount =
            stoppedRetryNotice(
                ChatNotice("Модель повторяет запрос", "socket closed", attempt = 8),
                elapsedMs = 1L,
            )
        assertTrue(byCount.message.contains("после 8 попыток"))
    }

    /**
     * «Ход остановлен» не должен молча затираться серверным retry на следующем
     * тике опроса: сервер про нашу остановку не знает и вернёт свой статус.
     */
    @Test
    fun `плашка об остановке переживает серверный статус`() {
        val serverRetry = ChatNotice("Модель повторяет запрос", "socket closed", attempt = 1)
        val stopped = ChatNotice("Ход остановлен", "остановил", attempt = 8)
        // Сервер продолжает слать свой retry — наша плашка всё равно главнее.
        assertEquals(stopped, nextRetryLatch(stopped, stopped, serverRetry, turnFinished = false))
        // А вот успешный шаг модели гасит обе.
        assertNull(nextRetryLatch(stopped, stopped, serverRetry, turnFinished = true))
        // Без нашей остановки работает обычное правило защёлки.
        assertEquals(serverRetry, nextRetryLatch(null, null, serverRetry, turnFinished = false))
        assertEquals(stopped, nextRetryLatch(null, null, stopped, turnFinished = false))
    }

    /**
     * Удалять сессию можно только когда сервер точно перестал её писать.
     * Иначе opencode продолжает писать message/part для уже удалённой строки и
     * падает на FOREIGN KEY constraint failed.
     */
    @Test
    fun `удалять сессию можно только если статус виден и ход ушёл`() {
        assertTrue(purgeAllowed(statusReachable = true, stillRunning = false))
        assertFalse(purgeAllowed(statusReachable = true, stillRunning = true))
        // Статус недоступен: «не знаю» здесь означает «сломаем внешние ключи».
        assertFalse(purgeAllowed(statusReachable = false, stillRunning = false))
        assertFalse(purgeAllowed(statusReachable = false, stillRunning = true))
    }
}
