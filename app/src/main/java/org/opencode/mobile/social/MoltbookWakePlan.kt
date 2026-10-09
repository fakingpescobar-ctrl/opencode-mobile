package org.opencode.mobile.social

import org.json.JSONArray
import org.json.JSONObject

/**
 * Решение будильника: кого, когда и можно ли вообще будить.
 *
 * Вынесено отдельно от того, что будильник делает, по той же причине, что и
 * [MoltbookBranchCheck]: решение принимается из ответов сервера, значит его
 * можно проверить без телефона, без сети и без контекста — просто подав две
 * строки. Всё, что требует Context, остаётся в [MoltbookWake].
 *
 * Каждое правило здесь — отказ, который однажды стоил автономии:
 *
 *  - Занятую сессию не будим: там идёт ход, и лишнее сообщение встаёт в
 *    очередь к генерации. Это ровно тот способ, которым получались зависшие ходы.
 *  - Непрочитанный статус занятости — тоже отказ. На неопределённости мы не
 *    действуем, тот же принцип, что в `purgeAllowed` на стороне UI.
 *  - Сессию никогда не создаём: сообщение в никуда выглядит как «работа идёт».
 *  - Пауза считается от последнего УСПЕШНОГО будильника. Иначе серия отказов
 *    каждые RETRY_MS писала бы в журнал, что агента будили, хотя его не трогали.
 */
internal object MoltbookWakePlan {
    const val MIN_GAP_MS: Long = 30 * 60 * 1000L

    const val MILLIS_PER_MINUTE: Long = 60_000L

    /** Сессия, которой можно написать: что за сессия и когда её трогали. */
    data class Session(
        val id: String,
        val updated: Long,
    )

    /** Wake значит «пишем», Hold — «не пишем, и вот почему». */
    sealed interface Plan {
        data class Wake(
            val sessionId: String,
        ) : Plan

        data class Hold(
            val reason: String,
        ) : Plan
    }

    fun tag(plan: Plan): String =
        when (plan) {
            is Plan.Wake -> "wake"
            is Plan.Hold -> "hold"
        }

    fun reason(plan: Plan): String =
        when (plan) {
            is Plan.Wake -> "session=" + plan.sessionId
            is Plan.Hold -> plan.reason
        }

    /**
     * Сессии из `/session`. Мусор без id отбрасывается: будить несуществующую
     * сессию — значит получить отказ сервера и записать его как успех.
     * Отсутствующее время берётся как 0, а не выбрасывает запись: сортировать
     * по мусору нельзя, а терять сессию из выбора — можно.
     */
    fun parseSessions(raw: String?): List<Session> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").trim()
                if (id.isEmpty()) return@mapNotNull null
                val updated = obj.optJSONObject("time")?.optLong("updated") ?: 0L
                Session(id, updated)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Занятые сессии из `/session/status`. null — не прочитали, и это НЕ то же
     * самое, что «никто не занят»: `{}` это честное «свободны» (проверено на
     * живом сервере 1.18.25), а null это «сервер не сказал».
     */
    fun parseBusy(raw: String?): Set<String>? {
        val text = raw?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val obj = JSONObject(text)
            val out = LinkedHashSet<String>()
            for (key in obj.keys()) {
                if (key.isNotBlank()) out.add(key)
            }
            out
        }.getOrNull()
    }

    /**
     * Пауза проверяется первой: «будили недавно» — самая частая и самая дешёвая
     * причина, и она же самая обидная в отчёте, когда настоящая причина в том,
     * что сервер лежит.
     *
     * `since in 0 until MIN_GAP_MS`, а не `since < MIN_GAP_MS`: при откате часов
     * (NTP после ребута, ручная правка) разность уходит в минус, и проверка
     * «прошло меньше получаса» становится вечной — автономия выключается молча,
     * без единой ошибки. Отрицательная разность значит «прошло больше, чем мы
     * думаем», и будить можно.
     */
    fun choosePlan(
        sessions: List<Session>,
        busy: Set<String>?,
        now: Long,
        lastWakeAt: Long,
    ): Plan {
        val since = now - lastWakeAt
        // При неизвестной занятости считаем все сессии свободными: ветка
        // `busy == null` ниже всё равно запретит действие, а считать так
        // нагляднее, чем городить третье состояние.
        val free = sessions.filterNot { it.id in (busy ?: emptySet()) }
        return when {
            since in 0 until MIN_GAP_MS ->
                Plan.Hold("будили ${since / MILLIS_PER_MINUTE} мин назад")
            sessions.isEmpty() -> Plan.Hold("сессий нет — будить некого")
            busy == null -> Plan.Hold("занятость сессий не прочитана")
            free.isEmpty() -> Plan.Hold("все сессии заняты")
            else -> Plan.Wake(free.maxByOrNull { it.updated }!!.id)
        }
    }
}
