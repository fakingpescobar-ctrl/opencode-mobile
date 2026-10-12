package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Контракт путей serve. Тест намеренно жёсткий к строкам: смысл [ApiPaths] в
 * том, что до вызова лежит ровно то, что было написано в коде до этапа 1
 * миграции, и это подтверждено на живом сервере 2.0.27.
 *
 * Двух версий больше нет: этап 3 подменил бинарь в APK, вернуться на 1.x
 * можно лишь пересборкой с другим `libopencode.so`, а такой откат всё равно
 * требует вернуть код. Поэтому один контракт — тот, что на устройстве.
 */
class ApiPathsTest {
    @Test
    fun `everything lives under api`() {
        assertEquals("/api/session", ApiPaths.SESSIONS)
        assertEquals("/api/session/ses_x", ApiPaths.session("ses_x"))
        assertEquals("/api/session/ses_x/message", ApiPaths.message("ses_x"))
        assertEquals("/api/mcp", ApiPaths.MCP)
    }

    /** Чтение сообщений и отправка промпта разъехались по разным путям. */
    @Test
    fun `message read and prompt write are different paths`() {
        assertEquals("/api/session/ses_x/message", ApiPaths.message("ses_x"))
        assertEquals("/api/session/ses_x/prompt", ApiPaths.prompt("ses_x"))
    }

    @Test
    fun `abort became interrupt`() {
        assertEquals("/api/session/ses_x/interrupt", ApiPaths.interrupt("ses_x"))
    }

    /**
     * Статуса как отдельного пути в v2 нет: он едет псевдо-сообщением
     * `type=idle` внутри ленты сообщений. Пути для него здесь нет намеренно —
     * добавление пустого метода означало бы вернуть вызов, который сервер
     * молча проглотит как SPA-страницу.
     */
    @Test
    fun `status has no path of its own`() {
        val declared = ApiPaths::class.java.declaredMethods.map { it.name }
        assertEquals(false, declared.contains("status"))
        assertEquals(false, declared.contains("health"))
        assertEquals(false, declared.contains("legacy"))
    }
}
