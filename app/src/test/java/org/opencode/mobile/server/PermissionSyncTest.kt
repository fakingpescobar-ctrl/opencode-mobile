package org.opencode.mobile.server

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionSyncTest {
    @Test
    fun `пустой ключ question дописывается в секцию permission`() {
        val text = """{"permission": {"mobile_launch_app": "ask"}}"""
        val plan = planQuestionPermission(text)
        assertTrue(plan is PermissionPlan.Write)
        val out = JSONObject((plan as PermissionPlan.Write).text)
        assertEquals("ask", out.getJSONObject("permission").getString("question"))
        assertEquals("ask", out.getJSONObject("permission").getString("mobile_launch_app"))
    }

    @Test
    fun `чужой выбор не перебивается`() {
        val text = """{"permission": {"question": "deny"}}"""
        assertTrue(planQuestionPermission(text) is PermissionPlan.Keep)
    }

    @Test
    fun `без секции permission не лезем в чужой конфиг`() {
        assertTrue(planQuestionPermission("""{"model": "opencode/big-pickle"}""") is PermissionPlan.Refuse)
    }

    @Test
    fun `пустой объект permission не получает висячую запятую`() {
        val text = """{"permission": {}}"""
        val plan = planQuestionPermission(text) as PermissionPlan.Write
        assertEquals("""{"permission": { "question": "ask"}}""", plan.text)
    }

    @Test
    fun `ключ из соседней секции не считается ключом question`() {
        val text = """{"mcp": {"q": {"question": "ask"}}, "permission": {}}"""
        val plan = planQuestionPermission(text) as PermissionPlan.Write
        val out = JSONObject(plan.text)
        assertEquals("ask", out.getJSONObject("permission").getString("question"))
    }

    @Test
    fun `слово question в комментарии ключом не считается`() {
        val text = "{\n  \"permission\": {\n    // question: ask уже пробовали\n    \"edit\": \"ask\"\n  }\n}"
        val plan = planQuestionPermission(text) as PermissionPlan.Write
        assertTrue(plan.text.contains("\"question\": \"ask\""))
        assertEquals(1, Regex("\"question\": \"ask\"").findAll(plan.text).count())
        assertTrue(plan.text.contains("// question: ask уже пробовали"))
    }

    @Test
    fun `вставка остаётся внутри объекта permission`() {
        val text = """{"permission": {"mobile_launch_app": "ask"}, "model": "opencode/big-pickle"}"""
        val plan = planQuestionPermission(text) as PermissionPlan.Write
        val out = JSONObject(plan.text)
        assertEquals("ask", out.getJSONObject("permission").getString("question"))
        assertEquals("opencode/big-pickle", out.getString("model"))
    }

    @Test
    fun `границы секции находятся при многострочном виде`() {
        val text =
            "{\n" +
                "  \"permission\": {\n" +
                "    \"mobile_launch_app\": \"ask\"\n" +
                "  },\n" +
                "  \"mcp\": { \"memory\": { \"url\": \"http://127.0.0.1:4199/mcp\" } }\n" +
                "}"
        val span = memberObjectSpan(text, "permission")
        assertNotNull(span)
        assertTrue(span!!.first < span.second)
        assertEquals("{", text.substring(span.first, span.first + 1))
        assertEquals("}", text.substring(span.second, span.second + 1))
    }

    @Test
    fun `вложенный объект с таким же именем не сбивает поиск`() {
        val text = """{"note": {"permission": {}}, "permission": {"edit": "ask"}}"""
        val plan = planQuestionPermission(text) as PermissionPlan.Write
        val out = JSONObject(plan.text)
        assertEquals("ask", out.getJSONObject("permission").getString("question"))
        assertEquals(0, out.getJSONObject("note").getJSONObject("permission").length())
    }

    @Test
    fun `незакрытая скобка означает отказ а не порчу файла`() {
        assertEquals(null, memberObjectSpan("""{"permission": {"edit": "ask"""", "permission"))
    }
}
