package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Форма ответа `GET /api/config` приходит из внешнего процесса, поэтому её
 * разбор проверяется здесь, а не на устройстве. Именно этот разбор решает,
 * снимем ли мы плашку «применить» — ошибка здесь выглядела бы как «модель
 * применилась», хотя агент продолжает работать на старой.
 */
class RoutingReadinessTest {
    private val routing = ModelRouting(
        heavy = "opencode/space-bunny-free",
        light = "opencode/cat-jam-free",
        small = "opencode/mimo-v2.6-flash-free",
    )

    private fun config(agents: String): String = """{"data":[{"type":"document","info":{"agents":{$agents}}}]}"""

    /**
     * Форма, которую вживую отдаёт serve на телефоне: `/api/config` — это
     * МАССИВ документов без обёртки `data` (проверено на устройстве 14.10.2026).
     * С разбором только обёртки проверка рестарта получалась вечно ложной.
     */
    private fun realConfig(agents: String): String =
        """[{"type":"document","path":"/data/user/0/x/opencode.jsonc","info":{"agents":{$agents}}},""" +
            """{"type":"directory","path":"/data/user/0/x/opencode-config/opencode"}]"""

    @Test
    fun `массивом без обёртки тоже читается - так отдаёт настоящий serve`() {
        val body =
            realConfig(
                """"build":{"model":{"providerID":"opencode","model":"space-bunny-free"}},
                   "title":{"model":{"providerID":"opencode","model":"mimo-v2.6-flash-free"}}""",
            )
        val parsed = RoutingReadiness.parseAgentModels(body)
        assertEquals(mapOf("build" to "opencode/space-bunny-free", "title" to "opencode/mimo-v2.6-flash-free"), parsed)
    }

    @Test
    fun `строковая модель тоже читается`() {
        // Конфиг мы пишем строкой ("model": "провайдер/имя"), и serve может
        // отдать её хоть так, хоть разобранным объектом. Читать только объект —
        // значит потерять агента и вечно держать плашку «применить».
        val body = """[{"type":"document","info":{"agents":{"build":{"model":"opencode/space-bunny-free"}}}}]"""
        assertEquals(mapOf("build" to "opencode/space-bunny-free"), RoutingReadiness.parseAgentModels(body))
        assertTrue(RoutingReadiness.routingServed(RoutingReadiness.parseAgentModels(body), routing))
    }

    @Test
    fun `модель без префикса провайдера не ломает сверку`() {
        val body = """[{"type":"document","info":{"agents":{"build":{"model":"space-bunny-free"}}}}]"""
        val served = RoutingReadiness.parseAgentModels(body)
        assertEquals(mapOf("build" to "space-bunny-free"), served)
        assertTrue(RoutingReadiness.routingServed(served, routing))
        assertFalse(RoutingReadiness.routingServed(mapOf("build" to "cat-jam-free"), routing))
    }

    @Test
    fun `живой ответ со всеми ролями считается применённым`() {
        val body =
            realConfig(
                """"build":{"model":{"providerID":"opencode","model":"space-bunny-free"}},
                   "plan":{"model":{"providerID":"opencode","model":"space-bunny-free"}},
                   "explore":{"model":{"providerID":"opencode","model":"cat-jam-free"}},
                   "general":{"model":{"providerID":"opencode","model":"cat-jam-free"}},
                   "title":{"model":{"providerID":"opencode","model":"mimo-v2.6-flash-free"}}""",
            )
        val served = RoutingReadiness.parseAgentModels(body)
        assertTrue(RoutingReadiness.routingServed(served, routing))
        assertFalse(RoutingReadiness.smallRoleMismatch(served, routing))
    }

    @Test
    fun `старое расписание в живой форме не считается применённым`() {
        val body =
            realConfig(
                """"build":{"model":{"providerID":"opencode","model":"big-pickle"}},
                   "plan":{"model":{"providerID":"opencode","model":"big-pickle"}}""",
            )
        assertFalse(RoutingReadiness.routingServed(RoutingReadiness.parseAgentModels(body), routing))
    }

    @Test
    fun `модели агентов читаются из первого документа ответа`() {
        val body =
            config(
                """"build":{"model":{"providerID":"opencode","model":"space-bunny-free"}},
                   "title":{"model":{"providerID":"opencode","model":"mimo-v2.6-flash-free"}}""",
            )
        val parsed = RoutingReadiness.parseAgentModels(body)
        assertEquals(mapOf("build" to "opencode/space-bunny-free", "title" to "opencode/mimo-v2.6-flash-free"), parsed)
    }

    @Test
    fun `документы каталогов не мешают - берётся первый info`() {
        val body =
            """{"data":[
                {"type":"document","info":{"agents":{"build":{"model":{"providerID":"opencode","model":"big-pickle"}}}}},
                {"type":"directory","info":{"agents":{"build":{"model":{"providerID":"opencode","model":"wrong"}}}}}
            ]}"""
        assertEquals(mapOf("build" to "opencode/big-pickle"), RoutingReadiness.parseAgentModels(body))
    }

    @Test
    fun `битый ответ даёт пустую карту а не исключение`() {
        assertEquals(emptyMap<String, String>(), RoutingReadiness.parseAgentModels("не json"))
        assertEquals(emptyMap<String, String>(), RoutingReadiness.parseAgentModels("""{"data":[]}"""))
        assertEquals(emptyMap<String, String>(), RoutingReadiness.parseAgentModels("""{"data":[{"type":"document"}]}"""))
        assertEquals(emptyMap<String, String>(), RoutingReadiness.parseAgentModels("""[{"type":"directory"}]"""))
    }

    @Test
    fun `агент без модели пропускается молча`() {
        val body = config(""""build":{"description":"никакой модели"},"plan":{}}""")
        assertEquals(emptyMap<String, String>(), RoutingReadiness.parseAgentModels(body))
    }

    @Test
    fun `совпавшее расписание считается применённым`() {
        val agents =
            mapOf(
                "build" to routing.heavy,
                "plan" to routing.heavy,
                "explore" to routing.light,
                "general" to routing.light,
                "title" to routing.small,
            )
        assertTrue(RoutingReadiness.routingServed(agents, routing))
    }

    @Test
    fun `разъехавшаяся служебная роль не блокирует - но видна в статусе`() {
        // small_model сервер не отдаёт, поэтому проверить его напрямую нечем.
        // Если требовать от title/summary точного small, плашка «применить»
        // возвращалась бы навсегда — и повторный рестарт не помог бы.
        val agents =
            mapOf(
                "build" to routing.heavy,
                "plan" to routing.heavy,
                "explore" to routing.light,
                "general" to routing.light,
                "title" to "opencode/big-pickle",
                "summary" to "opencode/big-pickle",
            )
        assertTrue(RoutingReadiness.routingServed(agents, routing))
        assertTrue(RoutingReadiness.smallRoleMismatch(agents, routing))
        assertFalse(RoutingReadiness.smallRoleMismatch(mapOf("title" to routing.small), routing))
        assertFalse(RoutingReadiness.smallRoleMismatch(emptyMap(), routing))
    }

    @Test
    fun `старое расписание не считается применённым`() {
        val agents = mapOf("build" to "opencode/big-pickle", "plan" to "opencode/big-pickle")
        assertFalse(RoutingReadiness.routingServed(agents, routing))
    }

    @Test
    fun `дословный живой ответ со служебными разделами читается без потерь`() {
        // Снят с устройства 15.10.2026 целиком. Рядом с agents лежат model,
        // permissions и mcp - разбор не должен принять их за агентов (например
        // mcp-сервер за агента с моделью), иначе сверка прошла бы на мусоре.
        val body =
            """[{"type":"document","path":"/data/user/0/x/files/opencode-config/opencode/opencode.jsonc",""" +
                """"info":{"${'$'}schema":"https://opencode.ai/config.json",""" +
                """"model":{"providerID":"opencode","model":"big-pickle"},""" +
                """"permissions":[{"action":"question","resource":"*","effect":"ask"}],""" +
                """"agents":{"build":{"model":{"providerID":"opencode","model":"space-bunny-free"}},""" +
                """"plan":{"model":{"providerID":"opencode","model":"space-bunny-free"}},""" +
                """"explore":{"model":{"providerID":"opencode","model":"cat-jam-free"}},""" +
                """"general":{"model":{"providerID":"opencode","model":"cat-jam-free"}},""" +
                """"title":{"model":{"providerID":"opencode","model":"mimo-v2.6-flash-free"}}},""" +
                """"mcp":{"servers":{"memory":{"type":"remote","url":"http://127.0.0.1:4199/mcp"}}}}},""" +
                """{"type":"directory","path":"/data/user/0/x/files/opencode-config/opencode"}]"""
        val served = RoutingReadiness.parseAgentModels(body)
        assertEquals(setOf("build", "plan", "explore", "general", "title"), served.keys)
        assertTrue(RoutingReadiness.routingServed(served, routing))
    }

    @Test
    fun `пустой ответ не считается успехом - без этого рестарт прятался бы навсегда`() {
        assertFalse(RoutingReadiness.routingServed(emptyMap(), routing))
    }

    @Test
    fun `отсутствующий агент не роняет проверку но build обязателен`() {
        // В этой сборке нет части агентов - их отсутствие не повод считать
        // рестарт неудачным, но build без нужной модели - повод.
        val withoutLight = mapOf("build" to routing.heavy, "plan" to routing.heavy)
        assertTrue(RoutingReadiness.routingServed(withoutLight, routing))
        assertFalse(RoutingReadiness.routingServed(mapOf("plan" to routing.heavy), routing))
        assertFalse(RoutingReadiness.routingServed(mapOf("build" to routing.heavy, "explore" to "x"), routing))
    }

    @Test
    fun `одна неверная роль ломает всё расписание`() {
        val agents =
            mapOf(
                "build" to routing.heavy,
                "explore" to "opencode/big-pickle",
            )
        assertFalse(RoutingReadiness.routingServed(agents, routing))
    }
}
