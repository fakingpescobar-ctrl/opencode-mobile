package org.opencode.mobile.server

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор запросов, на которых opencode ждёт пользователя.
 *
 * Все тела ниже — настоящие ответы живого 2.0.27 с устройства, а не
 * придуманные. В частности `resources:["*"]` у вопроса взят из ответа сервера:
 * он не несёт текста вопроса, и текст приходится доставать из части ленты.
 */
class ApiAskTest {
    /** Настоящий ответ на `GET /api/session/{id}/permission` для команды. */
    private val shellPermission =
        """
        {"data":[{"id":"per_13204f1eb001cqobhR7uFoIjLJ",
        "sessionID":"ses_ece8d377fffeepyt12LjE08B6O",
        "action":"shell","resources":["date"],"save":["date *"],
        "source":{"type":"tool","messageID":"msg_1","id":"call_shell1"}}]}
        """.trimIndent()

    /** Настоящий ответ для вопроса. Текста в нём нет вообще. */
    private val questionPermission =
        """
        {"data":[{"id":"per_132060036001qI3xCrboF5wG73",
        "sessionID":"ses_ece8d377fffeepyt12LjE08B6O",
        "action":"question","resources":["*"],
        "source":{"type":"tool","messageID":"msg_13205f8b8001JPoMy2e4rsoNvm","id":"call_q1"}}]}
        """.trimIndent()

    /** Часть ленты в том виде, в котором её отдаёт `ApiShape.messages`. */
    private fun feedWithQuestion(callId: String): JSONArray =
        JSONArray(
            """
            [{"info":{"role":"assistant","time":{"created":1}},
            "parts":[{"type":"tool","id":"$callId","tool":"question",
            "state":{"status":"running","input":{"questions":[
            {"question":"Kakoy variant: A ili B?","header":"Vybor",
            "options":[{"label":"A","description":"pervyy"},{"label":"B","description":"vtoroy"}]}]}}}]}]
            """.trimIndent(),
        )

    /** Настоящий вызов, где модель задала два вопроса одним инструментом. */
    private fun feedWithTwoQuestions(callId: String): JSONArray =
        JSONArray(
            """
            [{"info":{"role":"assistant","time":{"created":1}},
            "parts":[{"type":"tool","id":"$callId","tool":"question",
            "state":{"status":"running","input":{"questions":[
            {"question":"Kakoy variant: A ili B?","header":"Variant A/B",
            "options":[{"label":"A"},{"label":"B"}]},
            {"question":"Chto delat dalshe?","header":"Dalneyshie deystviya",
            "options":[{"label":"Prodolzhit"},{"label":"Ostanovitsya"}]}]}}}]}]
            """.trimIndent(),
        )

    /** Второй вопрос пришёл без текста, но с вариантами — терять его нельзя. */
    private fun feedWithBlankSecondQuestion(callId: String): JSONArray =
        JSONArray(
            """
            [{"info":{"role":"assistant","time":{"created":1}},
            "parts":[{"type":"tool","id":"$callId","tool":"question",
            "state":{"status":"running","input":{"questions":[
            {"question":"Kakoy variant: A ili B?","header":"Variant A/B",
            "options":[{"label":"A"},{"label":"B"}]},
            {"question":"","header":"Dalneyshie deystviya",
            "options":[{"label":"Prodolzhit"}]}]}}}]}]
            """.trimIndent(),
        )

    /** Отдельные функции вместо `block.options.map { it.label }`: ktlint не даёт цепочек из трёх звеньев. */
    private fun labelsOf(block: AskQuestion): List<String> = block.options.map { it.label }

    private fun descriptionsOf(block: AskQuestion): List<String> = block.options.map { it.description }

    @Test
    fun `pustoy spisok znachit sprashivat nechego`() {
        assertEquals(emptyList<PendingAsk>(), ApiAsk.pending("""{"data":[]}"""))
    }

    @Test
    fun `nechitaemoe telo eto ne pustoy spisok a neizvestnost`() {
        // Различие обязательное: скрыть запрос — значит оставить ход висеть.
        assertNull(ApiAsk.pending(null))
        assertNull(ApiAsk.pending(""))
        assertNull(ApiAsk.pending("   "))
        // На v2 неизвестный путь отдаёт HTML-заглушку с кодом 200.
        assertNull(ApiAsk.pending("<!DOCTYPE html><html><body>OpenCode</body></html>"))
        assertNull(ApiAsk.pending("""{"data":{}}"""))
    }

    @Test
    fun `komanda prinodit v samom zaprose`() {
        val ask = ApiAsk.pending(shellPermission)!!.single()
        assertEquals("per_13204f1eb001cqobhR7uFoIjLJ", ask.id)
        assertEquals("shell", ask.action)
        assertEquals(listOf("date"), ask.resources)
        assertEquals("call_shell1", ask.callId)
    }

    @Test
    fun `tekst voprosa beretsya iz chasti lenty po id vyzova`() {
        val ask = ApiAsk.pending(questionPermission)!!.single()
        assertEquals("question", ask.action)
        assertEquals("call_q1", ask.callId)
        // resources:["*"] — это не текст, поэтому карточка собирается из ленты.
        assertEquals(listOf("*"), ask.resources)

        val card = ApiAsk.card(ask, feedWithQuestion("call_q1"))
        assertEquals("Vybor", card.title)
        val only = card.questions.single()
        assertEquals("Kakoy variant: A ili B?", only.text)
        assertEquals(listOf("A", "B"), labelsOf(only))
        assertEquals(listOf("pervyy", "vtoroy"), descriptionsOf(only))
    }

    @Test
    fun `neskolko voprosov v odnom vyzove ne poteryany`() {
        // Живой вызов, в котором модель задала ДВА вопроса разом.
        val ask = ApiAsk.pending(questionPermission)!!.single()
        val card = ApiAsk.card(ask, feedWithTwoQuestions("call_q1"))
        assertEquals(2, card.questions.size)
        val first = card.questions[0]
        val second = card.questions[1]
        assertEquals("Kakoy variant: A ili B?", first.text)
        assertEquals("Chto delat dalshe?", second.text)
        assertEquals(listOf("A", "B"), labelsOf(first))
        assertEquals(listOf("Prodolzhit", "Ostanovitsya"), labelsOf(second))
        // Заголовок карточки один, а вопросов два — значит это не «Выбор».
        assertEquals("Variant A/B", card.title)
    }

    @Test
    fun `vopros bez teksta poluchen ostatetsya v spiske`() {
        // Второй вопрос может прийти без текста. Убрать его — значит потерять
        // варианты, которые в нём были.
        val ask = ApiAsk.pending(questionPermission)!!.single()
        val card = ApiAsk.card(ask, feedWithBlankSecondQuestion("call_q1"))
        assertEquals(2, card.questions.size)
        val second = card.questions[1]
        assertEquals("Dalneyshie deystviya", second.header)
        assertEquals(ApiAsk.NO_TEXT_HINT, second.text)
        assertEquals(listOf("Prodolzhit"), labelsOf(second))
    }

    @Test
    fun `chast s drugim id ne podkhvatyvaetsya`() {
        val ask = ApiAsk.pending(questionPermission)!!.single()
        val card = ApiAsk.card(ask, feedWithQuestion("call_ne_tot"))
        assertEquals("Вопрос", card.title)
        // CHASTA S DRUGIM ID: the join fails, so the question text is unknown.
        // An empty body would look broken and would leave nothing to react to,
        // so the card must explain itself instead of showing a blank bubble.
        assertEquals(1, card.questions.size)
        val only = card.questions.single()
        assertTrue(only.text.isNotBlank())
        assertTrue(only.options.isEmpty())
    }

    @Test
    fun `vopros bez chasti lenty vse ravno pokazyvaetsya`() {
        // Гонка: запрос пришёл раньше, чем часть ленты долетела. Карточка с
        // заголовком лучше, чем ничего — ход-то ждёт ответа.
        val ask = ApiAsk.pending(questionPermission)!!.single()
        val card = ApiAsk.card(ask, null)
        assertEquals("Вопрос", card.title)
        assertEquals(ask.id, card.ask.id)
        assertEquals(1, card.questions.size)
    }

    @Test
    fun `razreshenie risuetsya iz samogo zaprosa bez lenty`() {
        val card = ApiAsk.card(ApiAsk.pending(shellPermission)!!.single(), null)
        assertEquals("shell", card.title)
        val only = card.questions.single()
        assertEquals("date", only.text)
        assertTrue(only.options.isEmpty())
    }

    @Test
    fun `neskolko zaprosov razbirayutsya vse`() {
        val raw =
            """
            {"data":[{"id":"per_a","action":"shell","resources":["ls"],
            "source":{"type":"tool","messageID":"m","id":"c1"}},
            {"id":"per_b","action":"question","resources":["*"],
            "source":{"type":"tool","messageID":"m","id":"c2"}}]}
            """.trimIndent()
        val asks = ApiAsk.pending(raw)!!
        assertEquals(listOf("per_a", "per_b"), asks.map { it.id })
    }

    @Test
    fun `zapros bez id otbrasyvaetsya`() {
        val raw = """{"data":[{"action":"shell","resources":["ls"]},{"id":"  ","action":"shell"}]}"""
        assertEquals(emptyList<PendingAsk>(), ApiAsk.pending(raw))
    }

    @Test
    fun `puti otveta vedut na session i request`() {
        assertEquals(
            "/api/session/ses_x/permission/per_a/reply",
            ApiPaths.permissionReply("ses_x", "per_a"),
        )
        assertEquals("/api/session/ses_x/permission", ApiPaths.permissions("ses_x"))
    }

    @Test
    fun `telo otveta eto decision a ne reply`() {
        val body = JSONObject(ApiAsk.replyBody("once"))
        assertEquals(1, body.length())
        assertEquals("once", body.getString("decision"))
        assertNotNull(body.opt("decision"))
    }

    @Test
    fun `otvet na vpros uezzhaet s tekstom`() {
        val body = JSONObject(ApiAsk.replyBody("once", "Vybirayu B"))
        assertEquals(2, body.length())
        assertEquals("once", body.getString("decision"))
        assertEquals("Vybirayu B", body.getString("message"))
    }

    @Test
    fun `pustoy tekst otveta ne pishetsya`() {
        val body = JSONObject(ApiAsk.replyBody("reject", "   "))
        assertEquals(1, body.length())
        assertEquals("reject", body.getString("decision"))
    }
}
