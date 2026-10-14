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

    private val empty = RoutingReadiness.Served(null, emptyMap())

    private fun config(
        root: String,
        agents: String,
    ) = """{"data":[{"type":"document","info":{"model":$root,"agents":{$agents}}}]}"""

    /**
     * Форма, которую вживую отдаёт serve на телефоне: `/api/config` — это
     * МАССИВ документов без обёртки `data` (проверено на устройстве 14.10.2026).
     * С разбором только обёртки проверка рестарта получалась вечно ложной.
     */
    private fun realConfig(
        root: String,
        agents: String,
    ) = """[{"type":"document","path":"/data/user/0/x/opencode.jsonc","info":{"model":$root,"agents":{$agents}}},""" +
        """{"type":"directory","path":"/data/user/0/x/opencode-config/opencode"}]"""

    private fun servedOf(
        root: String?,
        agents: Map<String, String>,
    ) = RoutingReadiness.Served(root, agents)

    private fun objectModel(name: String) = """{"providerID":"opencode","model":"$name"}"""

    @Test
    fun `массивом без обёртки тоже читается - так отдаёт настоящий serve`() {
        val body =
            realConfig(
                objectModel("space-bunny-free"),
                """"build":{"model":${objectModel("space-bunny-free")}},
                   "title":{"model":${objectModel("mimo-v2.6-flash-free")}}""",
            )
        val served = RoutingReadiness.parseServed(body)
        assertEquals("opencode/space-bunny-free", served.root)
        assertEquals(mapOf("build" to "opencode/space-bunny-free", "title" to "opencode/mimo-v2.6-flash-free"), served.agents)
    }

    @Test
    fun `строковая модель тоже читается`() {
        // Конфиг мы пишем строкой ("model": "провайдер/имя"), и serve может
        // отдать её хоть так, хоть разобранным объектом. Читать только объект —
        // значит потерять агента и вечно держать плашку «применить».
        val body =
            """[{"type":"document","info":{"model":"opencode/space-bunny-free",""" +
                """"agents":{"build":{"model":"opencode/space-bunny-free"}}}}]"""
        val served = RoutingReadiness.parseServed(body)
        assertEquals("opencode/space-bunny-free", served.root)
        assertEquals(mapOf("build" to "opencode/space-bunny-free"), served.agents)
        assertTrue(RoutingReadiness.routingServed(served, routing))
    }

    @Test
    fun `модель без префикса провайдера не ломает сверку`() {
        val body = """[{"type":"document","info":{"model":"space-bunny-free","agents":{"build":{"model":"space-bunny-free"}}}}]"""
        val served = RoutingReadiness.parseServed(body)
        assertEquals(mapOf("build" to "space-bunny-free"), served.agents)
        assertTrue(RoutingReadiness.routingServed(served, routing))
        assertFalse(RoutingReadiness.routingServed(servedOf("space-bunny-free", mapOf("build" to "cat-jam-free")), routing))
    }

    @Test
    fun `живой ответ со всеми ролями считается применённым`() {
        val body =
            realConfig(
                objectModel("space-bunny-free"),
                """"build":{"model":${objectModel("space-bunny-free")}},
                   "plan":{"model":${objectModel("space-bunny-free")}},
                   "explore":{"model":${objectModel("cat-jam-free")}},
                   "general":{"model":${objectModel("cat-jam-free")}},
                   "title":{"model":${objectModel("mimo-v2.6-flash-free")}}""",
            )
        val served = RoutingReadiness.parseServed(body)
        assertTrue(RoutingReadiness.routingServed(served, routing))
        assertFalse(RoutingReadiness.smallRoleMismatch(served, routing))
    }

    @Test
    fun `старый корень при верных агентах не считается применённым`() {
        // Именно этот случай жаловался пользователь: расписание в конфиге уже
        // было правильным, а генерация шла от прежней модели. Сверка только по
        // агентам сняла бы плашку «применить» при агенте на старой модели.
        val body =
            realConfig(
                objectModel("big-pickle"),
                """"build":{"model":${objectModel("space-bunny-free")}},
                   "plan":{"model":${objectModel("space-bunny-free")}},
                   "explore":{"model":${objectModel("cat-jam-free")}},
                   "general":{"model":${objectModel("cat-jam-free")}}""",
            )
        assertFalse(RoutingReadiness.routingServed(RoutingReadiness.parseServed(body), routing))
    }

    @Test
    fun `ответ без корневой модели не считается применённым`() {
        val body =
            """[{"type":"document","info":{"agents":{"build":{"model":${objectModel("space-bunny-free")}}}}}]"""
        assertFalse(RoutingReadiness.routingServed(RoutingReadiness.parseServed(body), routing))
    }

    @Test
    fun `старое расписание в живой форме не считается применённым`() {
        val body =
            realConfig(
                objectModel("big-pickle"),
                """"build":{"model":${objectModel("big-pickle")}},
                   "plan":{"model":${objectModel("big-pickle")}}""",
            )
        assertFalse(RoutingReadiness.routingServed(RoutingReadiness.parseServed(body), routing))
    }

    @Test
    fun `модели агентов читаются из первого документа ответа`() {
        val body =
            config(
                objectModel("space-bunny-free"),
                """"build":{"model":${objectModel("space-bunny-free")}},
                   "title":{"model":${objectModel("mimo-v2.6-flash-free")}}""",
            )
        val parsed = RoutingReadiness.parseServed(body)
        assertEquals(mapOf("build" to "opencode/space-bunny-free", "title" to "opencode/mimo-v2.6-flash-free"), parsed.agents)
    }

    @Test
    fun `документы каталогов не мешают - берётся документ с info`() {
        val body =
"""{"data":[
                {"type":"document","info":{"model":${objectModel("big-pickle")},
                 "agents":{"build":{"model":${objectModel("big-pickle")}}}}},
                {"type":"directory","info":{"agents":{"build":{"model":${objectModel("wrong")}}}}}
            ]}"""
        val parsed = RoutingReadiness.parseServed(body)
        assertEquals("opencode/big-pickle", parsed.root)
        assertEquals(mapOf("build" to "opencode/big-pickle"), parsed.agents)
    }

    @Test
    fun `битый ответ даёт пустое расписание а не исключение`() {
        assertEquals(empty, RoutingReadiness.parseServed("не json"))
        assertEquals(empty, RoutingReadiness.parseServed("""{"data":[]}"""))
        assertEquals(empty, RoutingReadiness.parseServed("""{"data":[{"type":"document"}]}"""))
        assertEquals(empty, RoutingReadiness.parseServed("""[{"type":"directory"}]"""))
    }

    @Test
    fun `агент без модели считается неприменённым`() {
        // Агент без model не «молча пропускается»: build обязателен, и его
        // отсутствие в разобранном ответе означает, что сервер не отдал
        // расписание, а не что оно совпало.
        val body = config(objectModel("space-bunny-free"), """"build":{"description":"никакой модели"},"plan":{}""")
        val parsed = RoutingReadiness.parseServed(body)
        assertEquals(emptyMap<String, String>(), parsed.agents)
        assertFalse(RoutingReadiness.routingServed(parsed, routing))
    }

    @Test
    fun `совпавшее расписание считается применённым`() {
        val served =
            servedOf(
                routing.heavy,
                mapOf(
                    "build" to routing.heavy,
                    "plan" to routing.heavy,
                    "explore" to routing.light,
                    "general" to routing.light,
                    "title" to routing.small,
                ),
            )
        assertTrue(RoutingReadiness.routingServed(served, routing))
    }

    @Test
    fun `разъехавшаяся служебная роль не блокирует - но видна в статусе`() {
        // small_model сервер не отдаёт, поэтому проверить его напрямую нечем.
        // Если требовать от title/summary точного small, плашка «применить»
        // возвращалась бы навсегда — и повторный рестарт не помог бы.
        val served =
            servedOf(
                routing.heavy,
                mapOf(
                    "build" to routing.heavy,
                    "plan" to routing.heavy,
                    "explore" to routing.light,
                    "general" to routing.light,
                    "title" to "opencode/big-pickle",
                    "summary" to "opencode/big-pickle",
                ),
            )
        assertTrue(RoutingReadiness.routingServed(served, routing))
        assertTrue(RoutingReadiness.smallRoleMismatch(served, routing))
        assertFalse(RoutingReadiness.smallRoleMismatch(servedOf(routing.heavy, mapOf("title" to routing.small)), routing))
        assertFalse(RoutingReadiness.smallRoleMismatch(servedOf(routing.heavy, emptyMap()), routing))
    }

    @Test
    fun `старое расписание не считается применённым`() {
        val served = servedOf("opencode/big-pickle", mapOf("build" to "opencode/big-pickle", "plan" to "opencode/big-pickle"))
        assertFalse(RoutingReadiness.routingServed(served, routing))
    }

    @Test
    fun `дословный живой ответ со служебными разделами читается без потерь`() {
        // Снят с устройства 15.10.2026 целиком. Рядом с agents лежат model,
        // permissions и mcp - разбор не должен принять их за агентов (например
        // mcp-сервер за агента с моделью), иначе сверка прошла бы на мусоре.
        val body =
            """[{"type":"document","path":"/data/user/0/x/files/opencode-config/opencode/opencode.jsonc",""" +
                """"info":{"${'$'}schema":"https://opencode.ai/config.json",""" +
                """"model":{"providerID":"opencode","model":"space-bunny-free"},""" +
                """"permissions":[{"action":"question","resource":"*","effect":"ask"}],""" +
                """"agents":{"build":{"model":{"providerID":"opencode","model":"space-bunny-free"}},""" +
                """"plan":{"model":{"providerID":"opencode","model":"space-bunny-free"}},""" +
                """"explore":{"model":{"providerID":"opencode","model":"cat-jam-free"}},""" +
                """"general":{"model":{"providerID":"opencode","model":"cat-jam-free"}},""" +
                """"title":{"model":{"providerID":"opencode","model":"mimo-v2.6-flash-free"}}},""" +
                """"mcp":{"servers":{"memory":{"type":"remote","url":"http://127.0.0.1:4199/mcp"}}}}},""" +
                """{"type":"directory","path":"/data/user/0/x/files/opencode-config/opencode"}]"""
        val served = RoutingReadiness.parseServed(body)
        assertEquals("opencode/space-bunny-free", served.root)
        assertEquals(setOf("build", "plan", "explore", "general", "title"), served.agents.keys)
        assertTrue(RoutingReadiness.routingServed(served, routing))
    }

    @Test
    fun `пустой ответ не считается успехом - без этого рестарт прятался бы навсегда`() {
        assertFalse(RoutingReadiness.routingServed(empty, routing))
    }

    @Test
    fun `отсутствующий агент не роняет проверку но корень и build обязательны`() {
        // В этой сборке нет части агентов - их отсутствие не повод считать
        // рестарт неудачным. Обязательны корень и build: корень без build - это
        // разобранный ответ без раздела агентов, а build без корня означает
        // «агент на прежней модели».
        val withoutLight = servedOf(routing.heavy, mapOf("build" to routing.heavy, "plan" to routing.heavy))
        assertTrue(RoutingReadiness.routingServed(withoutLight, routing))
        assertFalse(RoutingReadiness.routingServed(servedOf(routing.heavy, mapOf("plan" to routing.heavy)), routing))
        assertFalse(RoutingReadiness.routingServed(servedOf(routing.heavy, emptyMap()), routing))
        assertFalse(RoutingReadiness.routingServed(servedOf("opencode/big-pickle", emptyMap()), routing))
        assertFalse(
            RoutingReadiness.routingServed(servedOf(routing.heavy, mapOf("build" to routing.heavy, "explore" to "x")), routing),
        )
    }

    @Test
    fun `одна неверная роль ломает всё расписание`() {
        val served = servedOf(routing.heavy, mapOf("build" to routing.heavy, "explore" to "opencode/big-pickle"))
        assertFalse(RoutingReadiness.routingServed(served, routing))
    }
}
