package org.opencode.mobile.server

import org.json.JSONArray
import org.json.JSONObject

/**
 * Дорога до «занята ли сессия». Отдельного эндпоинта статуса в v2 нет,
 * состояние собирается из лент сообщений, где ход отмечается
 * псевдо-сообщением `type:"idle"`. Разбор живёт в
 * [ApiShape] (чистый, под юнит-тест), здесь только поход по сети.
 *
 * Цена вопроса разная, поэтому методов три:
 *
 * - [reachable] — «сервер вообще отвечает». Карта статусов стоила бы 1 + N
 *   запросов, поэтому дешёвый ответ берём из списка сессий, который и так идёт
 *   каждый тик.
 * - [forSession] — состояние одной сессии. Основной потребитель: плашка
 *   «модель думает» и ожидание abort.
 * - [record] — карта занятых сессий целиком. Это 1 + N запросов, поэтому
 *   зовётся только там, где нужны все сразу: чистка сессий-сирот и
 *   планировщик moltbook.
 */
internal object ApiStatus {
    /**
     * Отвечает ли сервер. Не «свободны ли сессии» — только факт связи.
     */
    fun reachable(port: Int): Boolean = LocalOpenCodeClient.get(port, ApiPaths.SESSIONS) != null

    /**
     * Карта занятости для одной сессии в виде, который ждали потребители v1:
     * ключ есть ⇔ идёт ход.
     *
     * null — «не знаю»: сервер не ответил либо ленту разобрать не удалось.
     * Вызывающие обязаны трактовать null как отказ, а не как «сессия свободна»
     * (см. `purgeAllowed` в ChatOverlay).
     */
    fun forSession(
        port: Int,
        sessionId: String,
    ): String? = statusRecord(LocalOpenCodeClient.get(port, ApiPaths.message(sessionId)), sessionId)

    /**
     * Идёт ли сейчас ход в сессии.
     *
     * Отдельного эндпоинта нет, и вместо него в ленту кладётся
     * псевдо-сообщение `type:"idle"`, когда ход закончен. Разбирать его по версии
     * на местах нельзя — сюда и сносят все потребители статуса.
     *
     * null — определить нечем (лента не пришла или не разобралась). Это НЕ «свободна»:
     * вызывающие обязаны трактовать это как «не знаю» и ничего не удалять.
     *
     * Важная деталь, найденная на живом сервере: в начале ленты может лежать
     * сохранённое сообщение `type:"system"` («список инструментов изменился»), и
     * `idle` при этом не появляется ВООБЩЕ. Наивная проверка «новейшее == idle»
     * объявила бы такую сессию вечно занятой. Поэтому идём от новых к старым,
     * системные пропускаем, и решение принимает первый «содержательный» тип.
     * Пустая лента (или состоящая из одних system) — хода не было.
     */
    fun isBusy(rawMessages: String?): Boolean? {
        val arr = asJsonArray(rawMessages) ?: asJsonObject(rawMessages)?.optJSONArray("data")
        return if (arr == null) null else verdictIn(arr)
    }

    /** Ищем от новых к старым первый тип, который что-то значит. Не нашли — хода нет. */
    private fun verdictIn(arr: JSONArray): Boolean {
        for (i in 0 until arr.length()) {
            val verdict = asBusyVerdict(arr.optJSONObject(i))
            if (verdict != null) return verdict
        }
        return false
    }

    /**
     * Карта занятости одной сессии для потребителей, ждавших её от v1:
     * ключ есть ⇔ сессия занята. Синтезируется из [isBusy].
     *
     * null — как и раньше, «не знаю»: сервер не ответил ИЛИ ленту прочитать
     * не удалось. Потребитель в этом случае ничего не чистит.
     */
    fun statusRecord(
        rawMessages: String?,
        sessionId: String,
    ): String? =
        when (isBusy(rawMessages)) {
            null -> null
            true -> JSONObject().put(sessionId, JSONObject()).toString()
            false -> "{}"
        }

    /**
     * Карта занятости всех сессий.
     *
     * null, если ленту хоть одной сессии прочитать не удалось: частично
     * неизвестная карта опаснее полностью неизвестной — под нож попала бы
     * живая сессия, которую посчитали свободной.
     */
    fun record(port: Int): String? = sweep(port)

    /** Сборка карты по лентам — единственный способ в v2. */
    private fun sweep(port: Int): String? {
        val sessions = ApiShape.sessions(LocalOpenCodeClient.get(port, ApiPaths.SESSIONS)) ?: return null
        val busy = JSONObject()
        var unknown = false
        for (i in 0 until sessions.length()) {
            val sid = sessions.optJSONObject(i)?.optString("id")?.takeIf(String::isNotBlank)
            if (sid != null && merge(port, sid, busy)) unknown = true
        }
        return if (unknown) null else busy.toString()
    }

    /** true — ленту этой сессии прочитать не удалось, карта вышла неполной. */
    private fun merge(
        port: Int,
        sessionId: String,
        busy: JSONObject,
    ): Boolean {
        val raw = LocalOpenCodeClient.get(port, ApiPaths.message(sessionId))
        val running = isBusy(raw)
        if (running == null) return true
        if (running) busy.put(sessionId, JSONObject())
        return false
    }
}
