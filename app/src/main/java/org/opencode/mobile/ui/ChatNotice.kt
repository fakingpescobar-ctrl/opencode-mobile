package org.opencode.mobile.ui

import org.json.JSONObject

/**
 * Статус сессии от самого opencode (GET /session/status): ретрай провайдера,
 * исчерпанная квота и т.п.
 *
 * Зачем: сервер честно отдаёт причину («Free usage exceeded, subscribe to Go»),
 * но ответ assistant при этом остаётся пустым, а thinking=true — без конца.
 * Без чтения этого эндпоинта пользователь видит только вечный спиннер и через
 * минуту — «зависло», из чего нельзя понять, что делать. Это молчание хуже
 * самой ошибки: тут нужен не кот, а конкретное действие.
 */
internal data class ChatNotice(
    val title: String,
    val message: String,
    val actionLabel: String? = null,
    val actionLink: String? = null,
    val attempt: Int = 0,
)

/**
 * Разбор одной записи /session/status в плашку. null — показывать нечего.
 *
 * Показываем только то, что реально мешает ходу (retry/error). Остальные статусы
 * отфильтровываются молча: про них и так видно индикатор думания, а лишняя плашка
 * учит человека её игнорировать.
 *
 * Вынесено отдельным файлом не ради красоты: функция чистая, и весь разбор
 * проверяется юнит-тестом на реальном ответе сервера, без ожидания, пока
 * провайдер реально исчерпает квоту.
 */
internal fun noticeOf(entry: JSONObject?): ChatNotice? {
    val type = entry?.optString("type", "").orEmpty()
    val action = entry?.optJSONObject("action")
    val msg = entry?.optString("message", "").orEmpty().trim()
    val actionMsg = action?.optString("message", "").orEmpty().trim()
    val detail = if (actionMsg.isBlank()) msg else actionMsg
    val interesting = type == "retry" || type == "error"
    val fallback = if (type == "error") "Модель ответила с ошибкой" else "Модель повторяет запрос"
    // Ничего внятного от сервера — лучше тишина, чем пустая плашка.
    return if (interesting && detail.isNotBlank()) {
        val actionTitle = action?.optString("title", "").orEmpty().trim()
        val title = if (actionTitle.isBlank()) fallback else actionTitle
        val label = action?.optString("label", "").orEmpty()
        val link = action?.optString("link", "").orEmpty()
        ChatNotice(
            title = title,
            message = if (msg.isBlank() || msg == detail) detail else "$detail ($msg)",
            actionLabel = label.ifBlank { null },
            actionLink = link.ifBlank { null },
            attempt = entry?.optInt("attempt", 0) ?: 0,
        )
    } else {
        null
    }
}

/**
 * Правило защёлки плашки причины.
 *
 * Retry — устойчивое состояние, а не событие: сервер отдаёт `free_tier_limit`
 * после любого TTL, пока квота не восстановится. Поэтому плашка не мигает, а
 * висит, пока идёт ход, и гаснет ровно на успешном assistant-шаге.
 *
 * Сделано функцией, а не inline в корутине, ради теста: переход «retry=true,
 * потом пришёл assistant-шаг» — это ровно тот случай, который нельзя поймать
 * живьём, не дожидаясь лимита квоты.
 *
 * [seen] — что пришло на этом тике, [held] — что висит сейчас,
 * [turnFinished] — opencode дошёл до успешного шага (thinking снят).
 */
internal fun nextNoticeLatch(
    held: ChatNotice?,
    seen: ChatNotice?,
    turnFinished: Boolean,
): ChatNotice? =
    when {
        turnFinished -> null
        seen != null -> seen
        else -> held
    }

/**
 * Решил ли abort проблему, или ход пора сбрасывать.
 *
 * Код ответа abort бесполезен: на несуществующей сессии тот же эндпоинт отдаёт
 * 200 + HTML (SPA-fallback на любой путь), то есть «успех» бывает всегда. Единственный
 * честный признак — уехала ли запись хода из /session/status.
 *
 * Тонкость, ради которой это отдельная функция, а не `!aborted`: если записи не
 * было до abort, останавливать нечего, состояние мёртвое само по себе и abort ничего
 * не изменил. Такой случай тоже требует сброса, хотя abort «прошёл».
 *
 * [runningBefore] — была ли сессия в /session/status до abort,
 * [runningAfter] — осталась ли после него.
 */
internal fun abortResolvedTurn(
    runningBefore: Boolean,
    runningAfter: Boolean,
): Boolean = runningBefore && !runningAfter

/**
 * Сколько попыток повтора считаем нормальным.
 *
 * Намеряно на живом сбое: провайдер `opencode` рвал сокет, opencode повторял
 * примерно раз в минуту и НИКОГДА не останавливался — 47 ошибок подряд за
 * 2.5 часа. Считать это нормой нельзя: пользователь всё это время смотрел на
 * плашку «Модель повторяет запрос» и ничего не мог сделать. Восемь попыток
 * хватает на обход кратковременной сетевой ямы и не хватает на залипание.
 */
internal const val RETRY_ATTEMPT_LIMIT = 8

/**
 * Сколько ждём повторов одного и того же хода, пока не остановим его сами.
 *
 * Верхняя граница поверх счётчика попыток: сервер шлёт `attempt`, но при
 * сетевом сбое он может не расти вовсе, и тогда единственный честный признак
 * залипания — время. Десять минут — это уже не «медленно», это «не идёт».
 */
internal const val RETRY_BUDGET_MS = 600_000L

/**
 * Исчерпан ли бюджет повторов: пора перестать ждать и остановить ход.
 *
 * [held] — плашка причины, которая сейчас висит (null = повторов нет, ничего
 * трогать нельзя). [elapsedMs] — сколько миллисекунд она висит.
 *
 * Сделано функцией рядом с [nextNoticeLatch] по той же причине: решение «ждать
 * дальше или хватит» измеряется на живом провайдере раз в несколько минут, а
 * проверяется за наносекунды в тесте. `attempt` и время — два независимых
 * признака, потому что каждый из них покрывает свой отказ: счётчик растёт при
 * ответах провайдера, таймер — при молчании.
 */
internal fun retryExhausted(held: ChatNotice?, elapsedMs: Long): Boolean =
    held != null && (held.attempt >= RETRY_ATTEMPT_LIMIT || elapsedMs >= RETRY_BUDGET_MS)

/**
 * Защёлка плашки повторов с учётом того, что ход уже остановили мы.
 *
 * [stopped] отдел от [held] намеренно. Если держать «Ход остановлен» в той же
 * переменке, что и серверный retry, то следующий тик опроса безо всякого нашего
 * участия вернёт `seen` (сервер-то про остановку не знает) и тихо затрёт нашу
 * плашку — человек увидит «Модель повторяет запрос» вместо «Ход остановлен»
 * и решит, что ничего не сработало.
 *
 * [turnFinished] гасит и то, и то: ход дошёл до успешного шага, повторов больше
 * нет по-настоящему.
 */
internal fun nextRetryLatch(
    held: ChatNotice?,
    stopped: ChatNotice?,
    seen: ChatNotice?,
    turnFinished: Boolean,
): ChatNotice? =
    when {
        turnFinished -> null
        stopped != null -> stopped
        seen != null -> seen
        else -> held
    }

/**
 * Чем заменить плашку повторов, когда ход остановлен нами.
 *
 * Залипание молча заменить на тишину нельзя: человек должен видеть, что это
 * мы остановили ход, а не модель сама замолчала.
 *
 * Причина остановки в тексте названа честно, потому что счётчик `attempt`
 * сервер при сетевом сбое может вообще не расти — и тогда «остановил после 8
 * попыток» было бы прямой ложью, хотя попыток было две. [elapsedMs] отличает
 * счётчик от времени.
 */
internal fun stoppedRetryNotice(
    held: ChatNotice,
    elapsedMs: Long,
): ChatNotice =
    ChatNotice(
        title = "Ход остановлен",
        message =
            if (held.attempt >= RETRY_ATTEMPT_LIMIT) {
                "Модель повторяла запрос и не ответила. Остановил ход после " +
                    "${held.attempt} попыток, чтобы он не крутился дальше."
            } else {
                val minutes = elapsedMs / 60_000L
                "Модель повторяла запрос $minutes мин и не ответила. Сервер насчитал " +
                    "всего ${held.attempt} попыток — остановил ход по времени."
            },
        attempt = held.attempt,
    )

/**
 * Можно ли удалять сессию с сервера.
 *
 * Разбор FOREIGN KEY constraint failed в логе opencode (хронический, с 07.10,
 * на многих сессиях) привёл сюда: стек целиком серверный — `Session.updateMessage`
 * ← `SessionProcessor.cleanup` ← `SessionPrompt.loop`, и падает он на
 * `insert into "part"` / `insert into "message"` для сессии, строки которой в
 * таблице уже нет. opencode держит сессии в памяти и продолжает писать в них
 * после того, как строка удалена. Значит удалять бегущую сессию нельзя.
 *
 * [statusReachable] — ответил ли вообще GET /session/status. Если нет, мы не
 * можем ЧЕСТНО сказать, что ход остановлен, а «не знаю» тут означает
 * «неизбежно сломаем внешние ключи». Мусорная сессия в списке — переживёт;
 * битая база — нет. Поэтому недоступный статус запрещает удаление.
 * [stillRunning] — осталась ли сессия в статусе после abort.
 */
internal fun purgeAllowed(
    statusReachable: Boolean,
    stillRunning: Boolean,
): Boolean = statusReachable && !stillRunning

/**
 * GET /session/status → Record<sessionId, {...}>. Любая сессия без записи в карте
 * даёт null, то есть тихо и дёшево.
 */
internal fun noticeFromStatus(
    raw: String?,
    sessionId: String,
): ChatNotice? {
    val entry =
        try {
            raw?.trim()?.takeIf { it.startsWith("{") }?.let { JSONObject(it).optJSONObject(sessionId) }
        } catch (_: Exception) {
            null
        }
    return noticeOf(entry)
}
