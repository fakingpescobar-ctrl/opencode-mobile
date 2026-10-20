package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор живого списка моделей (`GET /api/model`) и сборка списка для пикера.
 *
 * Форма ответа берётся из исходников opencode 2.x: `{location, data:[…]}`, где
 * элемент `data` — описание модели (`id`, `providerID`, `name`, `limit`,
 * `capabilities`). Сеть здесь не проверяется: проверяется разбор.
 */
class ModelListTest {
    @Test
    fun `модели из ответа разбираются в провайдера и имя`() {
        val models = ModelList.parse(response())
        assertEquals(2, models.size)
        assertEquals("opencode/big-pickle", models[0].id)
        assertEquals("opencode/minimax-m3.1-flash-free", models[1].id)
    }

    @Test
    fun `составной id читается так же как отдельные поля`() {
        val models = ModelList.parse("""{"data":[{"id":"opencode/glm-5.3-flash-free","providerID":"opencode"}]}""")
        assertEquals("opencode/glm-5.3-flash-free", models.single().id)
    }

    @Test
    fun `подпись берётся из каталога а не из имени сервера`() {
        val models = ModelList.parse(response())
        // Сервер отдаёт «MiniMax M3.1 Flash Free» — с хвостом и длиннее, чем
        // влезает в плашку. Каталог знает подпись покороче.
        assertEquals("MiniMax M3.1 Flash", models[1].label)
    }

    @Test
    fun `неизвестная модель получает имя сервера`() {
        val models = ModelList.parse("""{"data":[{"id":"new-model","providerID":"opencode","name":"New Model Free"}]}""")
        assertEquals("New Model", models.single().label)
    }

    @Test
    fun `неизвестная модель без имени получает разобранный id`() {
        val models = ModelList.parse("""{"data":[{"id":"some-new-model","providerID":"opencode"}]}""")
        assertEquals("Some New Model", models.single().label)
    }

    @Test
    fun `заметка собирается из лимита и модальностей`() {
        val models = ModelList.parse(response())
        assertEquals("контекст 200k · текст", models[0].note)
        assertEquals("контекст 1M · текст, картинки, видео", models[1].note)
    }

    @Test
    fun `сломанный ответ даёт пустой список`() {
        assertTrue(ModelList.parse("не json").isEmpty())
        assertTrue(ModelList.parse("{}").isEmpty())
        assertTrue(ModelList.parse("""{"data":{}}""").isEmpty())
    }

    @Test
    fun `модель без идентификатора пропускается`() {
        val models = ModelList.parse("""{"data":[{"providerID":"opencode","name":"Без имени"}]}""")
        assertTrue(models.isEmpty())
    }

    @Test
    fun `текущая модель добавляется в список если её нет в ответе`() {
        val shown = ModelCatalog.withCurrent(ModelList.parse(response()), "opencode/space-bunny-free")
        assertEquals("opencode/space-bunny-free", shown.first().id)
        assertEquals(ModelCatalog.MISSING_NOTE, shown.first().note)
    }

    @Test
    fun `текущая модель из списка не дублируется`() {
        val models = ModelList.parse(response())
        assertEquals(models, ModelCatalog.withCurrent(models, "opencode/big-pickle"))
    }

    @Test
    fun `заметка о известной модели берётся из каталога`() {
        val models = ModelList.parse(response())
        val note = ModelCatalog.noteFor("opencode/big-pickle", models)
        assertTrue(note.isNotEmpty())
        assertTrue(note.contains("200k"))
    }

    @Test
    fun `каталог не содержит мёртвых моделей`() {
        val ids = ModelCatalog.all.map { it.id }
        assertTrue(ids.contains("opencode/minimax-m3.1-flash-free"))
        assertTrue(ids.none { it.contains("space-bunny") })
        assertTrue(ids.none { it.contains("cat-jam") })
    }

    private fun response(): String =
        """
        {
          "location": {"directory": "/files/opencode", "project": "app"},
          "data": [
            {
              "id": "big-pickle",
              "providerID": "opencode",
              "name": "Big Pickle",
              "capabilities": {"tools": true, "input": ["text"], "output": ["text"]},
              "limit": {"context": 200000, "output": 32000},
              "status": "active",
              "enabled": true
            },
            {
              "id": "minimax-m3.1-flash-free",
              "providerID": "opencode",
              "name": "MiniMax M3.1 Flash Free",
              "capabilities": {"tools": true, "input": ["text", "image", "video"], "output": ["text"]},
              "limit": {"context": 1000000, "output": 512000},
              "status": "active",
              "enabled": true
            }
          ]
        }
        """.trimIndent()
}
