package org.opencode.mobile.social

/**
 * Разбор журнала будильника для панели: строка журнала в строку для красного ряда.
 *
 * Отдельный файл не ради размера, а ради одного правила, которое иначе
 * пришлось бы размазывать по панели: строка журнала — это СВИДЕТЕЛЬСТВО, и
 * читать её надо строже, чем данные снимка. Данные снимка мы пересчитываем, а
 * журнал не усекается никогда, и одна запись месячной давности сидит в нём
 * навсегда. Значит, у каждой строки есть время, и читать надо только то, что
 * ещё про сегодня.
 *
 * Правила, каждое из которых однажды сделало бы карточку лжецом:
 *
 *  - Успешное буждение — не причина для красной строки: время снимка и так
 *    показывает, что агент работал.
 *  - Штатная пауза после недавнего буждения (`gap`) — тоже не поломка.
 *    Будильник здоров, просто рано. Раньше пауза приходила под меткой `hold`
 *    наравне с «сервер не ответил», и карточка краснела по расписанию.
 *  - Давний отказ — не текущая причина. Журнал не усекается, поэтому без
 *    проверки свежести ошибка месячной давности держала бы красную причину
 *    вечно и панель врала бы, что будильник сломан.
 *  - Строка без разборчивого времени отбрасывается целиком. Иначе меткой
 *    оказалось бы второе слово, и в красную строку уехал бы обрывок чужой
 *    фразы. Мусор в журнале — не причина.
 *
 * Всё здесь чистое: время передаётся снаружи, чтобы «давний отказ не красит
 * карточку» проверялось обычным тестом, а не ожиданием суток.
 */
internal fun wakeLineOf(
    raw: String?,
    now: Long = 0L,
): String = wakeRefusalOf(raw, now)?.reason ?: ""

/**
 * Отказ будильника вместе со своим временем, либо null, если отказа нет.
 *
 * Время отказа нужно не для красоты, а для одного правила: если снимок панели
 * СВЕЖЕЕ отказа, то агент после отказа всё-таки поработал, и красить карточку
 * прошлой ошибкой нельзя. Журнал append-only, поэтому отказ там остаётся
 * навсегда, а снимок обновляется при каждом вызове тула.
 */
internal fun wakeRefusalOf(
    raw: String?,
    now: Long = 0L,
): WakeRefusal? {
    val record = lastWakeRecord(raw)
    if (record == null || isStale(record.at, now)) return null
    val reason = reasonOf(record.parts)
    return if (reason.isEmpty()) null else WakeRefusal(record.at, reason)
}

/** Метка и причина из разобранной строки, либо пусто, когда это не отказ. */
private fun reasonOf(parts: List<String>): String {
    val tag = if (parts.size >= TAG_AT) parts[TAG_AT - 1] else ""
    val refused = tag != MoltbookWakePlan.TAG_WAKE && tag != MoltbookWakePlan.TAG_GAP && tag.isNotEmpty()
    return if (refused) (tag + " " + parts.drop(TAG_AT).joinToString(" ")).trim().take(MAX_REASON_CHARS) else ""
}

/**
 * Последняя значащая строка журнала.
 *
 * Берётся именно строка, а не весь журнал: журнал append-only, как и положено
 * свидетелю, и раньше искать надо было последнюю запись, а не первую.
 * Время обязано разбираться — иначе свежесть проверить нечем.
 */
private fun lastWakeRecord(raw: String?): WakeRecord? = wakeRecordOf(raw?.lines()?.lastOrNull { it.isNotBlank() })

/** Строка в запись, либо null, если это не строка журнала: нет времени или он мусор. */
private fun wakeRecordOf(line: String?): WakeRecord? {
    val trimmed = line?.trim().orEmpty()
    val parts = if (trimmed.isEmpty()) emptyList() else trimmed.split(' ').filter { it.isNotEmpty() }
    val at = parts.firstOrNull()?.toLongOrNull() ?: 0L
    return if (parts.size >= TAG_AT && at > 0L) WakeRecord(at, parts) else null
}

/** Свежесть отказа: прошлое больше суток — это уже не «сейчас агента не разбудили». */
private fun isStale(
    at: Long,
    now: Long,
): Boolean = now > 0L && now - at >= WAKE_STALE_MS

/** Разобранная строка журнала: время в эпохе и части строки как есть. */
private data class WakeRecord(
    val at: Long,
    val parts: List<String>,
)

/** Отказ будильника: когда он случился и почему. */
internal data class WakeRefusal(
    val at: Long,
    val reason: String,
)

/** Индекс метки в строке `<время> <метка> <причина>`: время, метка, причина. */
private const val TAG_AT: Int = 2

private const val MAX_REASON_CHARS: Int = 80

/** Сутки: свежий отказ читаем, давний — это уже история, а не текущая причина. */
private const val WAKE_STALE_MS: Long = 24 * 60 * 60 * 1000L
