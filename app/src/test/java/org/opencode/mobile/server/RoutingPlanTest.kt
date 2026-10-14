package org.opencode.mobile.server

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Маршрутизация моделей по ролям агентов: `small_model` и `agent.<id>.model`.
 *
 * Место опасное: правка идёт текстом поверх чужого opencode.jsonc, где уже лежат
 * mcp и permission. Ошибка здесь не роняет сборку — она молча портит конфиг на
 * устройстве, поэтому узнаваемость формы и сохранность чужих ключей проверяются
 * юнит-тестом, а не глазами.
 */
class RoutingPlanTest {
    private val routing =
        ModelRouting(
            heavy = "opencode/big-pickle",
            light = "opencode/mimo-v2.6-flash-free",
            small = "opencode/mimo-v2.6-flash-free",
        )

    private val managed =
        """{
          "${'$'}schema": "https://opencode.ai/config.json",
          "model": "opencode/big-pickle",
          "permission": { "question": "ask" }
        }"""

    private fun written(plan: RoutingPlan): JSONObject {
        assertTrue("ожидалась запись, получен план $plan", plan is RoutingPlan.Write)
        return JSONObject((plan as RoutingPlan.Write).text)
    }

    private fun applied(text: String): String {
        val plan = planRoutingEntry(text, routing)
        assertTrue("ожидалась запись, получен план $plan", plan is RoutingPlan.Write)
        return (plan as RoutingPlan.Write).text
    }

    @Test
    fun `в конфиг без маршрутизации дописываются agent и small_model`() {
        val out = written(planRoutingEntry(managed, routing))
        assertEquals(routing.small, out.getString("small_model"))
        val agent = out.getJSONObject("agent")
        assertEquals(routing.heavy, agent.getJSONObject("build").getString("model"))
        assertEquals(routing.heavy, agent.getJSONObject("plan").getString("model"))
        assertEquals(routing.light, agent.getJSONObject("explore").getString("model"))
        assertEquals(routing.light, agent.getJSONObject("general").getString("model"))
    }

    @Test
    fun `чужие ключи остаются нетронутыми`() {
        val out = written(planRoutingEntry(managed, routing))
        assertEquals("ask", out.getJSONObject("permission").getString("question"))
        assertEquals("opencode/big-pickle", out.getString("model"))
    }

    @Test
    fun `повторное применение не даёт записи`() {
        val once = applied(managed)
        assertEquals(RoutingPlan.Keep, planRoutingEntry(once, routing))
    }

    @Test
    fun `смена выбора переписывает значения в уже созданном блоке`() {
        val once = applied(managed)
        val other = routing.copy(heavy = "opencode/space-bunny-free", light = "opencode/nemotron-3.5-lightning-free")
        val out = written(planRoutingEntry(once, other))
        assertEquals(other.heavy, out.getJSONObject("agent").getJSONObject("build").getString("model"))
        assertEquals(other.light, out.getJSONObject("agent").getJSONObject("general").getString("model"))
        assertEquals("opencode/big-pickle", out.getString("model"))
    }

    @Test
    fun `существующий small_model переписывается`() {
        val text = """{ "small_model": "opencode/ling-3.1-flash-free", "permission": {} }"""
        assertEquals(routing.small, written(planRoutingEntry(text, routing)).getString("small_model"))
    }

    @Test
    fun `запись в пустой объект не оставляет висячую запятую`() {
        val out = written(planRoutingEntry("{}", routing))
        assertFalse(out.toString().contains(",}"))
        assertFalse(out.toString().contains(", }"))
    }

    @Test
    fun `комментарии сохраняются при правке`() {
        // jsonc: комментарий — обычное дело; org.json такой файл не читает, поэтому
        // сохранность проверяется текстом.
        val text = """{ "model": "x", /* keep me */ "permission": {} }"""
        val out = applied(text)
        assertTrue(out.contains("keep me"))
    }

    @Test
    fun `скобка в комментарии не мешает правке`() {
        val text = "/* раньше был {model} */\n{ \"model\": \"x\" }"
        val out = applied(text)
        assertTrue(out.indexOf(routing.small) > out.indexOf("*/"))
    }

    @Test
    fun `пустой без корня отказывает`() {
        assertEquals(RoutingPlan.Refuse, planRoutingEntry("   ", routing))
    }

    @Test
    fun `agent не объектом отказывает и не трогает файл`() {
        assertEquals(RoutingPlan.Refuse, planRoutingEntry("""{ "agent": "x", "permission": {} }""", routing))
    }

    @Test
    fun `агент не объектом отказывает`() {
        assertEquals(RoutingPlan.Refuse, planRoutingEntry("""{ "agent": { "build": "x" }, "permission": {} }""", routing))
    }

    @Test
    fun `model внутри агента не строкой отказывает`() {
        val text = """{ "agent": { "build": { "model": 5 }, "permission": {} } }"""
        assertEquals(RoutingPlan.Refuse, planRoutingEntry(text, routing))
    }

    @Test
    fun `small_model не строкой отказывает`() {
        assertEquals(RoutingPlan.Refuse, planRoutingEntry("""{ "small_model": 5, "permission": {} }""", routing))
    }
}
