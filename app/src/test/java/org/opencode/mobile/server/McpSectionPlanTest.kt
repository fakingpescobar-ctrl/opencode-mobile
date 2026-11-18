package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Первые три ветки [OpencodeRuntime.ensureMemorySection] как чистое решение.
 *
 * Тест фиксирует текущую семантику подстрок до перехода на структурный поиск
 * ключа: Create/Refuse/Migrate должны сохраниться при любом последующем
 * рефакторинге, иначе чужой конфиг можно будет переписать.
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
    fun `ключ памяти без секции mcp - мигрировать`() {
        assertEquals(McpSectionPlan.Migrate, planMcpSection("\"memory\"", memory))
    }
}
