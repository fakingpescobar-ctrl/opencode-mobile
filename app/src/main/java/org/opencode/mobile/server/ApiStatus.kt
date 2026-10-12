package org.opencode.mobile.server

import org.json.JSONObject

/**
 * Дорога до «занята ли сессия» — то, что в v1 было одним GET /session/status.
 *
 * В v2 этого эндпоинта нет, и состояние приходится собирать из лент сообщений,
 * где ход отмечается псевдо-сообщением `type:"idle"`. Разбор живёт в
 * [ApiShape] (чистый, под юнит-тест), здесь только поход по сети.
 *
 * Цена вопроса разная, поэтому методов три:
 *
 * - [reachable] — «сервер вообще отвечает». В v1 это стоило бы запроса карты
 *   статусов, а в v2 карта стоит ещё и N запросов по лентам, поэтому дешёвый
 *   ответ берём из списка сессий, который и так идёт каждый тик.
 * - [forSession] — состояние одной сессии. Основной потребитель: плашка
 *   «модель думает» и ожидание abort.
 * - [record] — карта занятых сессий целиком, как в v1. В v2 это 1 + N запросов,
 *   поэтому зовётся только там, где нужны все сразу: чистка сессий-сирот и
 *   планировщик moltbook.
 */
internal object ApiStatus {
    /**
     * Отвечает ли сервер. Не «свободны ли сессии» — только факт связи.
     */
    fun reachable(port: Int): Boolean =
        LocalOpenCodeClient.get(port, ApiPaths.active.sessions()) != null

    /**
     * Карта занятости в v1-виде для одной сессии: ключ есть ⇔ идёт ход.
     *
     * null — «не знаю»: сервер не ответил либо ленту разобрать не удалось.
     * Вызывающие обязаны трактовать null как отказ, а не как «сессия свободна»
     * (см. `purgeAllowed` в ChatOverlay).
     */
    fun forSession(
        port: Int,
        sessionId: String,
    ): String? {
        // v2 отдаёт состояние в ленте, v1 — отдельной картой.
        val raw =
            if (ApiPaths.activeFlavor == ServerFlavor.V2) {
                LocalOpenCodeClient.get(port, ApiPaths.active.message(sessionId))
            } else {
                ApiPaths.active.status()?.let { LocalOpenCodeClient.get(port, it) }
            }
        return ApiShape.statusRecord(raw, sessionId)
    }

    /**
     * Карта занятости всех сессий.
     *
     * null, если ленту хоть одной сессии прочитать не удалось: частично
     * неизвестная карта опаснее полностью неизвестной — под нож попала бы
     * живая сессия, которую посчитали свободной.
     */
    fun record(port: Int): String? {
        val direct = ApiPaths.active.status()
        if (direct != null) return LocalOpenCodeClient.get(port, direct)
        return sweep(port)
    }

    /** Сборка карты по лентам — только для v2, где отдельного эндпоинта нет. */
    private fun sweep(port: Int): String? {
        val sessions = ApiShape.sessions(LocalOpenCodeClient.get(port, ApiPaths.active.sessions())) ?: return null
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
        val raw = LocalOpenCodeClient.get(port, ApiPaths.active.message(sessionId))
        val running = ApiShape.isBusy(raw)
        if (running == null) return true
        if (running) busy.put(sessionId, JSONObject())
        return false
    }
}
