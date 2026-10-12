package org.opencode.mobile.social

import org.json.JSONArray
import org.json.JSONObject
import org.opencode.mobile.server.ApiShape

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
 *  - Сессий нет — значит СОЗДАЁМ одну. Раньше здесь был отказ, и это была
 *    дыра: юзер чистит сессии умышленно (сбросить контекст), и после этого
 *    автономия молча умирала навсегда, потому что будить было некого. Создание
 *    ограничено тем же интервалом, что и обычное буждение, поэтому оно не
 *    превращается в фабрику сессий.
 *  - Молчание сервера и пустой список — РАЗНЫЕ вещи, и раньше они были слиты
 *    в одно сообщение. Сервер не ответил — это отказ, чинить надо сервер.
 *    Список пуст — это состояние данных, из него вырастает работа.
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

    /** Wake значит «пишем», Create — «сессий нет, создай и разбуди», Hold — «не пишем, и вот почему». */
    sealed interface Plan {
        data class Wake(
            val sessionId: String,
            /** true — сессию только что создали, а не нашли готовой. */
            val created: Boolean = false,
        ) : Plan

        data class Create(
            val reason: String,
        ) : Plan

        data class Hold(
            val reason: String,
            /**
             * true — это не поломка, а штатная пауза «будили недавно».
             *
             * Отдельное поле, а не разбор текста причины: панель читает журнал
             * и обязана отличать «будильник сломался» от «мы просто недавно
             * будили». Раньше пауза приходила под меткой `hold` наравне с
             * «сервер не ответил», и карточка краснела по расписанию.
             */
            val routine: Boolean = false,
        ) : Plan
    }

    /** Метки журнала. Панель читает их, поэтому это не «строка для красоты», а контракт. */
    const val TAG_WAKE: String = "wake"
    const val TAG_CREATE: String = "create"
    const val TAG_HOLD: String = "hold"

    /** Штатная пауза после недавнего буждения: будильник здоров, просто рано. */
    const val TAG_GAP: String = "gap"

    fun tag(plan: Plan): String =
        when (plan) {
            is Plan.Wake -> TAG_WAKE
            is Plan.Create -> TAG_CREATE
            is Plan.Hold -> if (plan.routine) TAG_GAP else TAG_HOLD
        }

    fun reason(plan: Plan): String =
        when (plan) {
            is Plan.Wake -> "session=" + plan.sessionId + if (plan.created) " создана" else ""
            is Plan.Create -> plan.reason
            is Plan.Hold -> plan.reason
        }

    /**
     * id только что созданной сессии, либо null.
     *
     * Ответ без id — это отказ, а не «сессия какая-то»: писать в несуществующую
     * сессию означает получить ошибку сервера и записать её как успех.
     */
    fun parseCreated(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return ApiShape.session(raw)?.optString("id")?.trim()?.ifBlank { null }
    }

    /**
     * Сессии из `/session`, либо null — ответ есть, но это НЕ список сессий.
     *
     * Разделение обязательное: `[]` это честное «сессий нет», из которого
     * вырастает создание, а HTML от прокси, `{}` от ошибки или обрыв ответа —
     * это «сервера мы не видели». Сливать их нельзя, потому что на мусоре
     * Create создавал бы сессию вместо отказа, то есть чинил бы сеть
     * созданием мусорных сессий, и по журналу это выглядело бы как работа.
     *
     * Мусор без id отбрасывается: будить несуществующую сессию — значит
     * получить отказ сервера и записать его как успех. Отсутствующее время
     * берётся как 0, а не выбрасывает запись: сортировать по мусору нельзя,
     * а терять сессию из выбора — можно.
     */
    private fun sessionsOrNull(raw: String): List<Session>? {
        // null от ApiShape — тело есть, но списка в нём нет (HTML от прокси, `{}`
        // от ошибки). Это ровно тот «сервера мы не видели», который обязан отличаться
        // от честного `[]`.
        val array = ApiShape.sessions(raw) ?: return null
        return runCatching {
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").trim()
                if (id.isEmpty()) return@mapNotNull null
                val updated = obj.optJSONObject("time")?.optLong("updated") ?: 0L
                Session(id, updated)
            }
        }.getOrNull()
    }

    /** То же, что [sessionsOrNull], но без «не разобралось»: пустой список на любой мусор. */
    fun parseSessions(raw: String?): List<Session> = raw?.takeIf { it.isNotBlank() }?.let { sessionsOrNull(it) } ?: emptyList()

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
        val gap = gapHold(now, lastWakeAt)
        if (gap != null) return gap
        return when {
            sessions.isEmpty() -> Plan.Create("сессий нет — создаю")
            busy == null -> Plan.Hold("занятость сессий не прочитана")
            else -> pick(sessions, busy)
        }
    }

    /**
     * Полное решение из сырых ответов сервера.
     *
     * Здесь появляется то, чего не хватало [choosePlan]: различие между «сервер
     * не ответил» и «ответил, а сессий ноль». Раньше обе пустые строки сводились
     * к `sessions.isEmpty()`, и после намеренной очистки сессий агент оставался
     * без автономии навсегда, а по журналу это выглядело как обычный отказ.
     *
     * Третье состояние — «ответ не список сессий» — тоже отказ, и отделено
     * именно потому, что тремя состояниями полон смысл этой функции.
     */
    fun planFor(
        sessionsRaw: String?,
        busyRaw: String?,
        now: Long,
        lastWakeAt: Long,
    ): Plan {
        val sessions =
            when {
                sessionsRaw.isNullOrBlank() -> null
                else -> sessionsOrNull(sessionsRaw)
            }
        if (sessions == null) {
            // Отказ с причиной. Молчание и нечитаемый ответ — разные поломки,
            // и чинить их надо по-разному, поэтому различаем и здесь.
            val why = if (sessionsRaw.isNullOrBlank()) "сервер не ответил" else "ответ сервера не список сессий"
            return gapHold(now, lastWakeAt) ?: Plan.Hold(why)
        }
        return choosePlan(sessions, parseBusy(busyRaw), now, lastWakeAt)
    }

    /** Пауза как отказ, либо null, когда паузы нет. routine=true: это не поломка. */
    private fun gapHold(
        now: Long,
        lastWakeAt: Long,
    ): Plan? {
        val since = now - lastWakeAt
        return if (since in 0 until MIN_GAP_MS) {
            Plan.Hold("будили ${since / MILLIS_PER_MINUTE} мин назад", routine = true)
        } else {
            null
        }
    }

    private fun pick(
        sessions: List<Session>,
        busy: Set<String>,
    ): Plan {
        val free = sessions.filterNot { it.id in busy }
        return if (free.isEmpty()) Plan.Hold("все сессии заняты") else Plan.Wake(free.maxByOrNull { it.updated }!!.id)
    }
}
