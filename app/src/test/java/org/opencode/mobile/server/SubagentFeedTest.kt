package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.opencode.mobile.ui.chat.SubagentFeed

/**
 * Сабагенты не должны уводить наш чат в свою сессию.
 *
 * Проверяем ровно два правила: дочерние сессии отсекаются от выбора активной,
 * а работающие — собираются в индикатор. Плюс: промпт и ответ сабагента не
 * должны дойти до ленты даже через нормализацию v2→v1.
 */
class SubagentFeedTest {
    private fun session(
        id: String,
        parent: String? = null,
        outcome: String? = "succeeded",
        busy: Long = 100L,
        idle: Long = 200L,
    ): JSONObject {
        val o = JSONObject()
            .put("id", id)
            .put("agent", "explore")
            .put("time", JSONObject().put("busy", busy).put("idle", idle))
        if (parent != null) o.put("parentID", parent)
        if (outcome != null) o.put("outcome", outcome)
        return o
    }

    @Test
    fun `дочерняя сессия определяется по обоим форматам родителя`() {
        assertTrue(SubagentFeed.isChild(session("c1", parent = "root")))
        assertFalse(SubagentFeed.isChild(session("root")))
        val old = JSONObject().put("id", "c2").put("parent_id", "root")
        assertTrue(SubagentFeed.isChild(old))
    }

    @Test
    fun `в индикатор попадают только работающие сабагенты под активной сессией`() {
        val work1 = session("work1", parent = "root", outcome = null, busy = 300L, idle = 0L)
            .apply { put("title", "Разбор кода") }
        val work2 = session("work2", parent = "root", outcome = null, busy = 310L, idle = 0L)
            .apply { put("title", "Разбор тестов") }
        val sessions =
            JSONArray()
                .put(session("root"))
                .put(session("done", parent = "root"))
                .put(work1)
                .put(work2)
                .put(session("alien", parent = "other", outcome = null, busy = 320L, idle = 0L))
        val active = SubagentFeed.activeOf(sessions, "root")
        assertEquals(2, active.size)
        assertEquals("Разбор кода", active[0].label)
        assertEquals("Разбор тестов", active[1].label)
        assertTrue(active.all { it.agent == "explore" })
    }

    @Test
    fun `индикатор находит и вложенных сабагентов`() {
        val level2 = session("level2", parent = "level1", outcome = null, busy = 400L, idle = 0L)
            .apply { put("title", "Внутренний") }
        val sessions =
            JSONArray()
                .put(session("root"))
                .put(session("level1", parent = "root"))
                .put(level2)
        val active = SubagentFeed.activeOf(sessions, "root")
        assertEquals(1, active.size)
        assertEquals("Внутренний", active[0].label)
    }

    @Test
    fun `давний сабагент без outcome и idle не горит в индикаторе`() {
        // Живой случай: сессия Staged assets audit завершилась, но сервер не
        // записал ей ни outcome, ни idle. Один busy оставлять нельзя — строка
        // ⚡ висела бы в чате до перезапуска приложения.
        val now = 1_000_000L
        val old = session("old", parent = "root", outcome = null, busy = 900_000L, idle = 0L)
            .apply { getJSONObject("time").put("updated", 100_000L) }
        val fresh = session("fresh", parent = "root", outcome = null, busy = 990_000L, idle = 0L)
            .apply { getJSONObject("time").put("updated", 999_000L) }
        val sessions =
            JSONArray()
                .put(session("root"))
                .put(old)
                .put(fresh)
        val active = SubagentFeed.activeOf(sessions, "root", now)
        assertEquals(1, active.size)
        assertEquals("fresh", active[0].id)
    }

    @Test
    fun `завершённый outcome гасит индикатор даже при свежем updated`() {
        val now = 1_000_000L
        val done = session("done", parent = "root", outcome = "succeeded", busy = 990_000L, idle = 0L)
            .apply { getJSONObject("time").put("updated", now) }
        val sessions =
            JSONArray()
                .put(session("root"))
                .put(done)
        assertTrue(SubagentFeed.activeOf(sessions, "root", now).isEmpty())
    }

    @Test
    fun `пустая или неизвестная сессия не даёт индикатор`() {
        assertTrue(SubagentFeed.activeOf(JSONArray(), "root").isEmpty())
        assertTrue(SubagentFeed.activeOf(JSONArray().put(session("x")), null).isEmpty())
    }

    @Test
    fun `промпт и ответ сабагента не попадают в нормализованную ленту`() {
        val tool =
            JSONObject()
                .put("type", "tool")
                .put("name", "subagent")
                .put(
                    "state",
                    JSONObject()
                        .put("input", JSONObject().put("prompt", "СЕКРЕТНЫЙ ПРОМПТ"))
                        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "ОТВЕТ САБАГЕНТА"))),
                )
        val text = JSONObject().put("type", "text").put("text", "Наш обычный ответ")
        val msg =
            JSONObject()
                .put("id", "m1")
                .put("type", "assistant")
                .put("content", JSONArray().put(tool).put(text))
        val envelope = ApiShape.toV1Envelope(msg)
        val parts = envelope.getJSONArray("parts")
        assertEquals(1, parts.length())
        assertEquals("text", parts.getJSONObject(0).optString("type"))
        assertFalse(JSONObject(envelope.toString()).toString().contains("СЕКРЕТНЫЙ"))
        assertFalse(JSONObject(envelope.toString()).toString().contains("ОТВЕТ САБАГЕНТА"))
    }
}
