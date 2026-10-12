package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Контракт путей serve. Тест намеренно жёсткий к строкам: смысл [ApiPaths] в
 * том, что до вызова лежит ровно то, что раньше было написано в коде, а в v2
 * — ровно то, что подтверждено на живом 2.0.27.
 */
class ApiPathsTest {
    // ---- v1: значения обязаны совпадать с теми, что были вшиты до рефакторинга ----

    @Test
    fun `v1 sessions`() {
        assertEquals("/session", ApiPathsV1.sessions())
    }

    @Test
    fun `v1 session by id`() {
        assertEquals("/session/ses_x", ApiPathsV1.session("ses_x"))
    }

    @Test
    fun `v1 message and prompt share the path`() {
        assertEquals("/session/ses_x/message", ApiPathsV1.message("ses_x"))
        assertEquals("/session/ses_x/message", ApiPathsV1.prompt("ses_x"))
    }

    @Test
    fun `v1 interrupt is abort`() {
        assertEquals("/session/ses_x/abort", ApiPathsV1.interrupt("ses_x"))
    }

    @Test
    fun `v1 status health and mcp`() {
        assertEquals("/session/status", ApiPathsV1.status())
        assertEquals("/global/health", ApiPathsV1.health())
        assertEquals("/mcp", ApiPathsV1.mcp())
    }

    @Test
    fun `v1 legacy question and permission paths`() {
        assertEquals("/question", ApiPathsV1.legacy.questionList())
        assertEquals("/question/req_1/reply", ApiPathsV1.legacy.questionAction("req_1", "reply"))
        assertEquals("/permission", ApiPathsV1.legacy.permissionList())
        assertEquals("/permission/per_1/reply", ApiPathsV1.legacy.permissionReply("per_1"))
    }

    /** Слэш в id обязан кодироваться: иначе путь уедет в соседний сегмент. */
    @Test
    fun `v1 encodes ids`() {
        assertEquals("/question/a%2Fb/reply", ApiPathsV1.legacy.questionAction("a/b", "reply"))
        assertEquals("/permission/a%2Fb/reply", ApiPathsV1.legacy.permissionReply("a/b"))
    }

    // ---- v2: подтверждено на живом сервере 2.0.27 ----

    @Test
    fun `v2 moves everything under api`() {
        assertEquals("/api/session", ApiPathsV2.sessions())
        assertEquals("/api/session/ses_x", ApiPathsV2.session("ses_x"))
        assertEquals("/api/session/ses_x/message", ApiPathsV2.message("ses_x"))
        assertEquals("/api/mcp", ApiPathsV2.mcp())
    }

    @Test
    fun `v2 splits message read and prompt write`() {
        assertEquals("/api/session/ses_x/message", ApiPathsV2.message("ses_x"))
        assertEquals("/api/session/ses_x/prompt", ApiPathsV2.prompt("ses_x"))
    }

    @Test
    fun `v2 renames abort to interrupt`() {
        assertEquals("/api/session/ses_x/interrupt", ApiPathsV2.interrupt("ses_x"))
    }

    @Test
    fun `v2 health is api info`() {
        assertEquals("/api/info", ApiPathsV2.health())
    }

    /** Статус в v2 едет псевдо-сообщением type=idle, отдельного пути нет. */
    @Test
    fun `v2 has no status path`() {
        assertNull(ApiPathsV2.status())
    }

    /** Легаси-пути вопросов/разрешений в v2 удалены, есть нативные form-эндпоинты. */
    @Test
    fun `v2 has no legacy question and permission paths`() {
        assertNull(ApiPathsV2.legacy.questionList())
        assertNull(ApiPathsV2.legacy.questionAction("req_1", "reply"))
        assertNull(ApiPathsV2.legacy.permissionList())
        assertNull(ApiPathsV2.legacy.permissionReply("per_1"))
    }

    // ---- переключатель версии ----

    @Test
    fun `active is v1 by default so behaviour does not change before detection`() {
        assertEquals(ServerFlavor.V1, ApiPaths.activeFlavor)
        assertEquals(ApiPathsV1.sessions(), ApiPaths.active.sessions())
    }

    @Test
    fun `install switches active contract both ways`() {
        try {
            ApiPaths.install(ServerFlavor.V2)
            assertEquals("/api/session", ApiPaths.active.sessions())
            assertNull(ApiPaths.active.status())

            ApiPaths.install(ServerFlavor.V1)
            assertEquals("/session", ApiPaths.active.sessions())
        } finally {
            ApiPaths.install(ServerFlavor.V1)
        }
    }
}
