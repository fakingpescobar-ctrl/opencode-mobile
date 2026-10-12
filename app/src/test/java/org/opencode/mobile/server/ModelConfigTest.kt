package org.opencode.mobile.server

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Закрепление бесплатной модели по умолчанию в управляемом opencode.jsonc.
 *
 * Предыстория, из-за которой это нужно: в конфиге не было строки `model`, поэтому
 * opencode выбирал модель сам — по удалённому каталогу. Каталог отдавал `exo-free`
 * как active/enabled, а провайдер на неё отвечал 410 ModelDeprecated. Итог был хуже
 * отказа запуска: сервер поднимался, агент принимал промпт и не отвечал ничем.
 *
 * Проверяемое место опаснее правки mcp-секции: ключ `model` может стоять где угодно,
 * в том числе внутри агента, и его перезапись молча меняет то, что выбрал человек.
 */
class ModelConfigTest {
    private val managed =
        """{
          "${'$'}schema": "https://opencode.ai/config.json",
          "permission": { "edit": "ask" }
        }"""

    private fun written(plan: ModelConfigPlan): JSONObject {
        assertTrue("ожидалась запись, получен план $plan", plan is ModelConfigPlan.Write)
        return JSONObject((plan as ModelConfigPlan.Write).text)
    }

    @Test
    fun `в пустой управляемый конфиг дописывается бесплатная модель`() {
        val out = written(OpencodeRuntime.planModelEntry(managed))
        assertEquals(DEFAULT_FREE_MODEL, out.getString("model"))
        // Остальные поля не пострадали.
        assertEquals("ask", out.getJSONObject("permission").getString("edit"))
    }

    @Test
    fun `вставка не ломает конфиг без запятой после схемы`() {
        // Именно этот случай давал `,,` при вставке после строки schema.
        val out =
            written(
                OpencodeRuntime.planModelEntry("""{ "${'$'}schema": "https://opencode.ai/config.json" }"""),
            )
        assertEquals(DEFAULT_FREE_MODEL, out.getString("model"))
    }

    @Test
    fun `запись в пустой объект не оставляет висячую запятую`() {
        val out = written(OpencodeRuntime.planModelEntry("{}"))
        assertEquals(DEFAULT_FREE_MODEL, out.getString("model"))
        assertTrue(!out.toString().contains(",}"))
    }

    @Test
    fun `закрытый комментарием объект не получает висячую запятую`() {
        // `{ // TODO }` — объект визуально закрыт комментарием. По первому
        // непробельному символу он выглядел непустым, запись получала запятую,
        // и на устройство ехал jsonc с висячей запятой, который looksLikeManagedConfig
        // не ловит (считает только баланс скобок).
        val text = "{ // TODO \n }"
        val plan = OpencodeRuntime.planModelEntry(text)
        assertTrue("ожидалась запись, получен план $plan", plan is ModelConfigPlan.Write)
        val out = (plan as ModelConfigPlan.Write).text
        assertFalse("в записи не должно быть висячей запятой: $out", out.contains("\",\n  // TODO"))
    }

    @Test
    fun `пустой объект не получает лишней запятой`() {
        val out = written(OpencodeRuntime.planModelEntry("{}"))
        assertEquals(DEFAULT_FREE_MODEL, out.getString("model"))
        assertFalse(written(OpencodeRuntime.planModelEntry("{}")).toString().contains(", }"))
    }

    @Test
    fun `скобка в комментарии не считается корневой`() {
        // Ревью: indexOf('{') брал первую скобку вообще. С комментарием раньше
        // корня запись уезжала внутрь него, скобки сходились, looksLikeManagedConfig
        // такой файл пропускал — и на устройство ехал нечитаемый opencode.jsonc.
        val text = "/* раньше был {model} */\n{ \"permission\": {} }"
        val plan = OpencodeRuntime.planModelEntry(text)
        assertTrue("ожидалась запись, получен план $plan", plan is ModelConfigPlan.Write)
        val out = (plan as ModelConfigPlan.Write).text
        // Запись стоит внутри настоящего корня, а не в комментарии.
        assertTrue(out.indexOf(DEFAULT_FREE_MODEL) > out.indexOf("*/"))
        assertTrue(out.indexOf(DEFAULT_FREE_MODEL) < out.indexOf("permission"))
    }

    @Test
    fun `скобка в строковом значении не считается корневой`() {
        val text = """{ "note": "{model}", "permission": {} }"""
        val out = written(OpencodeRuntime.planModelEntry(text))
        assertEquals(DEFAULT_FREE_MODEL, out.getString("model"))
        assertEquals("{model}", out.getString("note"))
    }

    @Test
    fun `пустой объект без единой скобки отказывает`() {
        assertEquals(ModelConfigPlan.Refuse, OpencodeRuntime.planModelEntry("   "))
    }

    @Test
    fun `своя модель пользователя не переписывается`() {
        val text = """{ "model": "zai/glm-5.2", "permission": {} }"""
        assertEquals(ModelConfigPlan.Keep, OpencodeRuntime.planModelEntry(text))
    }

    @Test
    fun `модель агента не отменяет закрепление дефолта`() {
        // Ключ лежит не в корне, поэтому это НЕ выбор пользователя для всего
        // приложения. Раньше проверка искала подстроку и молча пропускала закрепление,
        // возвращая исходный баг без единого признака.
        val text = """{ "agent": { "build": { "model": "zai/glm-5.2" } } }"""
        assertEquals(DEFAULT_FREE_MODEL, written(OpencodeRuntime.planModelEntry(text)).getString("model"))
    }

    @Test
    fun `модель в комментарии и в строковом значении не считается ключом`() {
        // jsonc, поэтому комментарий — обычное дело; org.json такой файл не читает,
        // и проверять тут приходится текстом, а не разобранным объектом.
        val text =
            """{
              // здесь был "model": "old"
              "prompt": "в строке тоже \"model\": \"old\""
            }"""
        val plan = OpencodeRuntime.planModelEntry(text)
        assertTrue("ожидалась запись, получен план $plan", plan is ModelConfigPlan.Write)
        val out = (plan as ModelConfigPlan.Write).text
        assertTrue(out.contains("\"$DEFAULT_FREE_MODEL\""))
        assertTrue(out.indexOf(DEFAULT_FREE_MODEL) < out.indexOf("prompt"))
    }

    @Test
    fun `вложенный объект на два уровня глубже ключа не поднимает его в корень`() {
        val text = """{ "a": { "b": { "model": "x" } } }"""
        assertEquals(DEFAULT_FREE_MODEL, written(OpencodeRuntime.planModelEntry(text)).getString("model"))
    }

    @Test
    fun `small_model не обманул поиск ключа`() {
        // `small_model` — другой ключ: у него перед model стоит подчёркивание,
        // а не кавычка, поэтому верхнеуровневым model он не считается.
        val text = """{ "small_model": "opencode/ling-3.1-flash-free" }"""
        val out = written(OpencodeRuntime.planModelEntry(text))
        assertEquals(DEFAULT_FREE_MODEL, out.getString("model"))
        assertEquals("opencode/ling-3.1-flash-free", out.getString("small_model"))
    }

    @Test
    fun `неопознанная форма оставляет выбор opencode`() {
        assertEquals(ModelConfigPlan.Refuse, OpencodeRuntime.planModelEntry("не json вовсе"))
    }

    @Test
    fun `модель бесплатная и от того же провайдера что и остальной каталог`() {
        // Провайдер opencode отдаёт её без ключа (apiKey public), поэтому закрепление
        // не требует ни VPN, ни ввода секрета — в отличие от любой другой строки.
        assertEquals("opencode/big-pickle", DEFAULT_FREE_MODEL)
    }
}
