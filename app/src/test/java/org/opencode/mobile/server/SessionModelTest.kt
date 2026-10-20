package org.opencode.mobile.server

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тела запросов к opencode: форма `Model.Ref` и её разбор. Сеть здесь не проверяется. */
class SessionModelTest {
    @Test
    fun `модель с провайдером разбирается на пару`() {
        assertEquals("opencode" to "big-pickle", SessionModel.ref("opencode/big-pickle"))
    }

    @Test
    fun `имя без провайдера отвергается`() {
        // Тело с одним полем `model` сервер игнорирует целиком и берёт модель по
        // умолчанию — лучше отказ, чем молчаливое применение не той модели.
        assertNull(SessionModel.ref("big-pickle"))
        assertNull(SessionModel.ref("/big-pickle"))
        assertNull(SessionModel.ref("opencode/"))
        assertNull(SessionModel.ref(""))
    }

    @Test
    fun `тело смены содержит id и providerID`() {
        val body = JSONObject(SessionModel.body("opencode/minimax-m3.1-flash-free")!!)
        val model = body.getJSONObject("model")
        assertEquals("minimax-m3.1-flash-free", model.getString("id"))
        assertEquals("opencode", model.getString("providerID"))
    }

    @Test
    fun `тело без модели не строится`() {
        assertNull(SessionModel.body(null))
        assertNull(SessionModel.body("minimax-m3.1-flash-free"))
    }

    @Test
    fun `создание сессии без выбора шлёт пустой объект`() {
        assertEquals("{}", SessionModel.createBody(null))
        assertEquals("{}", SessionModel.createBody("mimo-v2.6-flash-free"))
    }

    @Test
    fun `создание сессии с выбором несёт модель`() {
        val body = JSONObject(SessionModel.createBody("opencode/mimo-v2.6-flash-free"))
        assertEquals("mimo-v2.6-flash-free", body.getJSONObject("model").getString("id"))
    }

    @Test
    fun `модель сессии сравнивается вместе с провайдером`() {
        assertTrue(SessionModel.matches("opencode/big-pickle", "opencode/big-pickle"))
        assertFalse(SessionModel.matches("opencode/big-pickle", "opencode/minimax-m3.1-flash-free"))
        // Тот же id у другого провайдера — другая модель (квоты, цены, эндпоинт).
        assertFalse(SessionModel.matches("zai-coding-plan/big-pickle", "opencode/big-pickle"))
    }

    @Test
    fun `имя без провайдера не считается совпадением`() {
        assertFalse(SessionModel.matches("opencode/big-pickle", "big-pickle"))
    }

    @Test
    fun `плохое имя модели никогда не совпадает`() {
        assertFalse(SessionModel.matches("opencode/big-pickle", "opencode/"))
    }

    @Test
    fun `обёртка сессии это data-объект а не массив`() {
        // Живой ответ 2.0.27 на GET /api/session/{id}: {"data":{…,"model":{…}}}.
        // Список сессий отдаёт data-массивом — обе формы обязаны разбираться.
        val body = "{\"data\":{\"id\":\"ses_1\",\"model\":{\"id\":\"minimax-m3.1-flash-free\",\"providerID\":\"opencode\"}}}"
        val session = SessionModel.sessionObject(body)
        assertEquals("ses_1", session?.optString("id"))
        assertEquals("opencode/minimax-m3.1-flash-free", SessionModel.refText(session?.opt("model")))
    }

    @Test
    fun `список сессий разбирается как массив`() {
        val body = "[{\"data\":{\"id\":\"ses_1\",\"model\":{\"id\":\"big-pickle\",\"providerID\":\"opencode\"}}}]"
        assertEquals("opencode/big-pickle", SessionModel.refText(SessionModel.sessionObject(body)?.opt("model")))
    }

    @Test
    fun `голая сессия без обёртки тоже читается`() {
        val body = "{\"id\":\"ses_1\",\"model\":{\"id\":\"big-pickle\",\"providerID\":\"opencode\"}}"
        assertEquals("opencode/big-pickle", SessionModel.refText(SessionModel.sessionObject(body)?.opt("model")))
    }

    @Test
    fun `модель строкой тоже читается`() {
        assertEquals("opencode/big-pickle", SessionModel.refText("opencode/big-pickle"))
        assertNull(SessionModel.refText(""))
        assertNull(SessionModel.refText(null))
    }

    @Test
    fun `модель без провайдера или id не выдумывается`() {
        assertNull(SessionModel.refText(JSONObject("""{"id":"big-pickle"}""")))
        assertNull(SessionModel.refText(JSONObject("""{"providerID":"opencode"}""")))
    }

    @Test
    fun `мусор вместо json не роняет разбор`() {
        assertNull(SessionModel.sessionObject("not json at all"))
        assertNull(SessionModel.sessionObject(""))
    }
}
