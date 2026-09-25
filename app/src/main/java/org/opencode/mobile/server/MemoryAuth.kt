package org.opencode.mobile.server

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Токен локального MCP-сервера памяти (memory.js, [RuntimeValidation.MEMORY_PORT])
 * для исходящих HTTP-запросов ИЗ приложения.
 *
 * Второй локальный сервер со своей авторизацией — ровно как [ServerAuth] для
 * opencode serve, только bearer вместо Basic. memory.js отвечает 401 на любой
 * запрос без `Authorization: Bearer <token>`, поэтому клиент, который хочет
 * ПРОВЕРИТЬ живость сервера, обязан слать заголовок: без него GET /mcp даёт 401
 * и протокольная проверка в диагностике всегда красная, даже когда MCP жив.
 *
 * Токен живёт ОДИН виток runtime: генерируется в RuntimeManager на каждый старт
 * памяти и умирает вместе с ней. Поэтому он НЕ персистится (в отличие от пароля
 * serve) и обязан сбрасываться при остановке — иначе проверка продолжит ходить
 * с мёртвым токеном и покажет ложную аварию на уже погашенном сервере.
 */
object MemoryAuth {
    /** Токен текущего витка runtime; null — память не поднята или остановлена. */
    @Volatile
    var token: String? = null
        private set

    private val lock = ReentrantLock()

    /** Готовый Authorization-заголовок для HttpURLConnection. */
    fun bearerHeader(): String? = token?.let { "Bearer $it" }

    /** Токен текущего витка (для логов/диагностики). */
    fun current(): String? = token

    fun set(token: String) {
        lock.withLock { this.token = token }
    }

    fun clear() {
        lock.withLock { token = null }
    }
}
