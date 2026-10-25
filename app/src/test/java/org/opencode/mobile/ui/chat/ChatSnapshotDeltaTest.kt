package org.opencode.mobile.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencode.mobile.ui.ChatMsg
import org.opencode.mobile.ui.ChatNotice
import org.opencode.mobile.ui.ChatSnapshot
import org.opencode.mobile.ui.ChatTool
import org.opencode.mobile.ui.McpInfo

/**
 * Правило «ставить ли свежий снапшот в UI».
 *
 * Главное здесь — MCP. Раньше статус серверов обновлялся только вместе с лентой,
 * и в простое шапка навсегда показывала «0 MCP» красным, хотя сервер отдавал три
 * `connected` (поймано на устройстве 25.10.2026). Тесты на это и написаны:
 * убери сравнение MCP — краснеют сразу три.
 *
 * Второе — обратное: пинг НЕ должен перерисовывать ленту, иначе гейт теряет
 * смысл и Compose пересобирает всю ленту несколько раз в секунду.
 */
class ChatSnapshotDeltaTest {
    @Test
    fun `первый снапшот применяется всегда`() {
        assertTrue(snapshotChanged(null, snap(), null, null))
    }

    @Test
    fun `одинаковые снапшоты не применяются`() {
        assertFalse(snapshotChanged(snap(), snap(), null, null))
    }

    @Test
    fun `счётчик подключённых MCP применяется в простое`() {
        // Один из трёх серверов отвалился: всего их по-прежнему три.
        val before = snap().copy(mcpConnected = 3, mcpTotal = 3)
        val after = snap().copy(mcpConnected = 2, mcpTotal = 3)

        assertTrue(snapshotChanged(before, after, null, null))
    }

    @Test
    fun `изменение числа MCP-серверов применяется`() {
        val before = snap().copy(mcpConnected = 3, mcpTotal = 3)
        val after = snap().copy(mcpConnected = 3, mcpTotal = 4)

        assertTrue(snapshotChanged(before, after, null, null))
    }

    @Test
    fun `изменение списка MCP-серверов применяется`() {
        val before = snap().copy(mcpConnected = 1, mcpTotal = 1)
        val after = before.copy(mcpServers = listOf(McpInfo("memory", "connected", 12)))

        assertTrue(snapshotChanged(before, after, null, null))
    }

    @Test
    fun `смена статуса MCP-сервера применяется`() {
        val before = snap().copy(mcpServers = listOf(McpInfo("memory", "connected", 12)))
        val after = before.copy(mcpServers = listOf(McpInfo("memory", "disconnected", 12)))

        assertTrue(snapshotChanged(before, after, null, null))
    }

    @Test
    fun `новая реплика в ленте применяется`() {
        val after = snap().copy(messages = listOf(ChatMsg("assistant", "ответ")))

        assertTrue(snapshotChanged(snap(), after, null, null))
    }

    @Test
    fun `живой инструмент применяется`() {
        val after = snap().copy(liveTool = ChatTool("bash", "ls"))

        assertTrue(snapshotChanged(snap(), after, null, null))
    }

    @Test
    fun `переход защёлки применяется`() {
        val notice = ChatNotice("Ход остановлен", "квота")

        assertTrue(snapshotChanged(snap(), snap(), null, notice))
    }

    @Test
    fun `доступность сервера применяется на пустом чате`() {
        // Пустая сессия: лента в обоих состояниях пустая, поэтому «ловится
        // сравнением messages» — неправда. Без отдельного пункта шапка
        // застревает на «Online» после падения сервера (и на «Offline» после
        // подъёма).
        val before = snap().copy(reachable = true)
        val after = snap().copy(reachable = false)

        assertTrue(snapshotChanged(before, after, null, null))
    }

    @Test
    fun `смена названия сессии применяется`() {
        val before = snap()
        val after = snap().copy(label = "Новое имя")

        assertTrue(snapshotChanged(before, after, null, null))
    }

    @Test
    fun `смена модели применяется`() {
        val before = snap()
        val after = snap().copy(modelName = "Big Pickle")

        assertTrue(snapshotChanged(before, after, null, null))
    }

    @Test
    fun `пинг сам по себе ленту не перерисовывает`() {
        val before = snap().copy(pingMs = 42L)
        val after = snap().copy(pingMs = 900L)

        assertFalse(snapshotChanged(before, after, null, null))
    }

    private fun snap(): ChatSnapshot =
        ChatSnapshot(
            messages = emptyList(),
            label = "сессия",
            activeId = "ses_test",
        )
}
