package org.opencode.mobile.server

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Токен локального MCP-сервера Яндекс.Музыки (ynison.js, [OpencodeRuntime.YNISON_PORT])
 * для исходящих HTTP-запросов из приложения.
 *
 * Отдельный секрет от [MemoryAuth] и — что важнее — от самого OAuth-токена Яндекса.
 * Их три, и путать их нельзя:
 *  - OAuth access token уходит в env `YNISON_TOKEN` и даёт доступ к МУЗЫКЕ;
 *  - этот bearer защищает локальный MCP-порт и живёт ОДИН виток runtime;
 *  - bearer памяти защищает соседний порт.
 *
 * Раньше bearer-ом для MCP служил сам OAuth-токен. Это не «просто неаккуратно»: конфиг
 * serve ссылается на токен как `{env:...}`, и любой, кто сумеет его подсмотреть, получает
 * не «доступ к музыке на устройстве», а полноценную сессию аккаунта на год. Плюс виток
 * runtime, погасивший память, обнулял бы и доступ к Яндексу.
 *
 * Как и [MemoryAuth] — не персистится и сбрасывается при остановке: после смерти витка
 * проверка продолжила бы ходить с мёртвым токеном и показывала бы аварию на погашенном
 * сервере.
 */
object YnisonAuth {
    /** Токен текущего витка runtime; null — музыка не поднята или остановлена. */
    @Volatile
    var token: String? = null
        private set

    private val lock = ReentrantLock()

    /** Готовый Authorization-заголовок для HttpURLConnection. */
    fun bearerHeader(): String? = token?.let { "Bearer $it" }

    fun set(token: String) {
        lock.withLock { this.token = token }
    }

    fun clear() {
        lock.withLock { token = null }
    }
}
