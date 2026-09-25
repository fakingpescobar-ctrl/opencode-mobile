package org.opencode.mobile.server

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class OpenCodePermissionRequest(
    val id: String,
    val sessionId: String,
    val permission: String,
    val patterns: List<String>,
    val alwaysPatterns: List<String>,
    val command: String = "",
)

internal enum class PermissionDecision(
    val wireValue: String,
) {
    ALLOW_ONCE("once"),
    ALLOW_ALWAYS("always"),
    REJECT("reject"),
}

internal object OpenCodePermissionApi {
    fun pendingForSession(
        raw: String,
        sessionId: String,
    ): OpenCodePermissionRequest? {
        if (raw.isBlank() || sessionId.isBlank()) return null

        return runCatching {
            val requests = JSONArray(raw)
            (0 until requests.length())
                .asSequence()
                .mapNotNull(requests::optJSONObject)
                .firstOrNull { it.optString("sessionID") == sessionId }
                ?.toPermissionRequest()
        }.getOrNull()
    }

    fun reply(
        port: Int,
        requestId: String,
        decision: PermissionDecision,
    ): Boolean {
        require(requestId.isNotBlank()) { "Permission request id must not be blank" }
        val encodedId = URLEncoder.encode(requestId, StandardCharsets.UTF_8.name())
        return LocalOpenCodeClient.post(
            port = port,
            path = "/permission/$encodedId/reply",
            body = replyBody(decision),
        ) != null
    }

    fun replyBody(decision: PermissionDecision): String =
        JSONObject()
            .put("reply", decision.wireValue)
            .toString()

    private fun JSONObject.toPermissionRequest(): OpenCodePermissionRequest? {
        val requestId = optString("id")
        if (requestId.isBlank()) return null
        return OpenCodePermissionRequest(
            id = requestId,
            sessionId = optString("sessionID"),
            permission = optString("permission", "tool"),
            patterns = stringList("patterns"),
            alwaysPatterns = stringList("always"),
            command = permissionDetail(),
        )
    }

    private fun JSONObject.permissionDetail(): String {
        val metadata = optJSONObject("metadata") ?: return ""
        return metadata.firstNonBlank("command", "filepath", "filePath", "parentDir")
    }

    private fun JSONObject.firstNonBlank(vararg names: String): String =
        names.firstNotNullOfOrNull { name -> optString(name).takeIf(String::isNotBlank) }.orEmpty()

    private fun JSONObject.stringList(name: String): List<String> {
        val values = optJSONArray(name) ?: return emptyList()
        return buildList(values.length()) {
            for (index in 0 until values.length()) {
                val value = values.optString(index, "")
                if (value.isNotBlank()) add(value)
            }
        }
    }
}
