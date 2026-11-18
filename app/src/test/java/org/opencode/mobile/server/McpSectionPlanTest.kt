package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Структурное решение по секции mcp.
 *
 * Тест фиксирует не только три исхода, но и причину перехода со строковых
 * проверок: слово `mcp` в комментарии и `memory` как строковое значение раньше
 * принимались за ключи, и чужой конфиг уходил в лестницу миграций.
 */
class McpSectionPlanTest {
    private val memory = "memory"

    @Test
    fun `нет секции mcp - создать`() {
        assertEquals(McpSectionPlan.Create, planMcpSection("{\"theme\": \"dark\"}", memory))
    }

    @Test
    fun `чужой mcp без нашего сервера - отказаться`() {
        assertEquals(McpSectionPlan.Refuse, planMcpSection("{\"mcp\": {\"custom\": {}}}", memory))
    }

    @Test
    fun `наш сервер памяти - мигрировать`() {
        assertEquals(McpSectionPlan.Migrate, planMcpSection("{\"mcp\": {\"memory\": {}}}", memory))
    }

    @Test
    fun `ключ памяти без секции mcp - отказаться`() {
        assertEquals(McpSectionPlan.Refuse, planMcpSection("{\"memory\": {}}", memory))
    }

    @Test
    fun `mcp в комментарии - создать`() {
        val text = "{\n  // \"mcp\": {}\n  \"theme\": \"dark\"\n}"
        assertEquals(McpSectionPlan.Create, planMcpSection(text, memory))
    }

    @Test
    fun `memory как строковое значение - создать`() {
        assertEquals(McpSectionPlan.Create, planMcpSection("{\"note\": \"memory\"}", memory))
    }

    @Test
    fun `mcp не объект - отказаться`() {
        assertEquals(McpSectionPlan.Refuse, planMcpSection("{\"mcp\": null}", memory))
    }

    @Test
    fun `memory в комментарии внутри mcp - отказаться`() {
        val text = "{\n  \"mcp\": {\n    // \"memory\": {}\n    \"custom\": {}\n  }\n}"
        assertEquals(McpSectionPlan.Refuse, planMcpSection(text, memory))
    }
}
