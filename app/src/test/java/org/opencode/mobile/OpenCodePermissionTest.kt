package org.opencode.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.opencode.mobile.server.OpenCodePermissionApi
import org.opencode.mobile.server.PermissionDecision

class OpenCodePermissionTest {
    @Test
    fun `parses first pending request for requested session`() {
        val raw =
            """
            [
              {
                "id": "per_other",
                "sessionID": "ses_other",
                "permission": "bash",
                "patterns": ["other"],
                "always": []
              },
              {
                "id": "per_current",
                "sessionID": "ses_current",
                "permission": "external_directory",
                "patterns": ["/data/local/tmp", ""],
                "always": ["/data/local/tmp/**"],
                "metadata": {"command": "", "filepath": "/data/local/tmp/file.txt"}
              }
            ]
            """.trimIndent()

        val request = OpenCodePermissionApi.pendingForSession(raw, "ses_current")

        requireNotNull(request)
        assertEquals("per_current", request.id)
        assertEquals("ses_current", request.sessionId)
        assertEquals("external_directory", request.permission)
        assertEquals(listOf("/data/local/tmp"), request.patterns)
        assertEquals(listOf("/data/local/tmp/**"), request.alwaysPatterns)
        assertEquals("/data/local/tmp/file.txt", request.command)
    }

    @Test
    fun `returns null when session has no pending request`() {
        val raw =
            """
            [{"id":"per_other","sessionID":"ses_other","permission":"bash","patterns":[],"always":[]}]
            """.trimIndent()

        assertNull(OpenCodePermissionApi.pendingForSession(raw, "ses_current"))
    }

    @Test
    fun `malformed response is treated as no request`() {
        assertNull(OpenCodePermissionApi.pendingForSession("not-json", "ses_current"))
    }

    @Test
    fun `serializes all supported permission decisions`() {
        assertEquals("{\"reply\":\"once\"}", OpenCodePermissionApi.replyBody(PermissionDecision.ALLOW_ONCE))
        assertEquals("{\"reply\":\"always\"}", OpenCodePermissionApi.replyBody(PermissionDecision.ALLOW_ALWAYS))
        assertEquals("{\"reply\":\"reject\"}", OpenCodePermissionApi.replyBody(PermissionDecision.REJECT))
    }
}
