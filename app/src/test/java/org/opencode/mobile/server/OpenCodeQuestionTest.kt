package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор `GET /question` и тело ответа на него.
 *
 * Форма ответа бралась раньше на глаз и разошлась с сервером: приложение ждало
 * `{data:[{text, options:[{label}]}]}` на сессионном пути, а сервер отдаёт
 * верхнеуровневый объект с массивом `questions` и полем `question`. Из-за этого
 * карточка вопроса не рисовалась никогда, а ход модели висел. Тесты ниже
 * закрепляют РЕАЛЬНЫЙ ответ сервера (opencode 1.18.25), а не догадку.
 */
class OpenCodeQuestionTest {
    /** Тело из живого сервера: один вопрос, три варианта с пояснениями. */
    private val liveSingle =
        """{"id":"que_1200b4593001k9sNnpUzlg3caq","sessionID":"ses_ee0a3d193ffeDXVvHaRYMUAuHp","que""" +
        """stions":[{"question":"\u041a\u0430\u043a \u043f\u0440\u043e\u0434\u043e\u043b\u0436\u043""" +
        """0\u0435\u043c?","header":"\u0412\u0435\u0442\u043a\u0430 \u0431\u0435\u0437 \u0432\u043e""" +
        """\u043f\u0440\u043e\u0441\u0430","options":[{"label":"\u0424\u043e\u0440\u0441\u0438\u044""" +
        """0\u043e\u0432\u0430\u0442\u044c \u0442\u0438\u043a\u0438 \u0436\u0451\u0441\u0442\u0447\""" +
        """u0435","description":"\u0416\u0434\u0430\u0442\u044c \u043f\u043e\u0437\u0438\u0442\u043""" +
        """8\u0432\u043d\u043e\u0433\u043e \u0434\u043e\u043a\u0430\u0437\u0430\u0442\u0435\u043b\u""" +
        """044c\u0441\u0442\u0432\u0430"},{"label":"\u0421\u043e\u0431\u0440\u0430\u0442\u044c \u04""" +
        """3f\u043e\u0437\u0438\u0442\u0438\u0432\u043d\u044b\u0439 \u043d\u0430\u0431\u043e\u0440 """ +
        """\u0438\u0437 APK","description":"\u0414\u043e\u043b\u0433\u043e, \u043d\u043e \u0447\u04""" +
        """38\u0441\u0442\u043e"},{"label":"\u041e\u0441\u0442\u0430\u043d\u043e\u0432\u0438\u0442\""" +
        """u044c\u0441\u044f","description":"\u0422\u0435\u043a\u0443\u0449\u0435\u0433\u043e \u043""" +
        """d\u0430\u0431\u043e\u0440\u0430 \u0434\u043e\u0441\u0442\u0430\u0442\u043e\u0447\u043d\u""" +
        """043e"}]}],"tool":{"messageID":"msg_1200af7c3001QzXIIgcZiCp7BB","callID":"call_00_8jd8pnw""" +
        """pdllq4a1uuyidptgx"}}"""

    @Test
    fun `разбирает живой ответ сервера`() {
        val parsed = OpenCodeQuestionApi.pendingForSession(liveSingle, SESSION)
        assertEquals("len=" + liveSingle.length, "que_1200b4593001k9sNnpUzlg3caq", parsed?.id)
        assertEquals(SESSION, parsed?.sessionId)
        val prompt = requireNotNull(parsed?.prompts?.singleOrNull()) { "prompt missing" }
        assertEquals("Как продолжаем?", prompt.question)
        assertEquals("Ветка без вопроса", prompt.header)
        assertEquals(3, prompt.options.size)
        assertEquals("Форсировать тики жёстче", prompt.options[0].label)
        assertEquals("Ждать позитивного доказательства", prompt.options[0].description)
        assertFalse("в живом ответе multiple нет", prompt.multiple)
    }

    @Test
    fun `принимает массив запросов как в спеке`() {
        val array = "[$liveSingle]"
        assertEquals("que_1200b4593001k9sNnpUzlg3caq", OpenCodeQuestionApi.pendingForSession(array, SESSION)?.id)
    }

    @Test
    fun `чужой вопрос не показываем`() {
        // /question общий на все сессии каталога: без фильтра карточка из чужой
        // сессии появилась бы здесь и предлагала бы ответить чужой ход.
        assertNull(OpenCodeQuestionApi.pendingForSession(liveSingle, "ses_other"))
    }

    @Test
    fun `пустой список это не вопрос`() {
        assertNull(OpenCodeQuestionApi.pendingForSession("[]", SESSION))
        assertNull(OpenCodeQuestionApi.pendingForSession("", SESSION))
        assertNull(OpenCodeQuestionApi.pendingForSession(null, SESSION))
    }

    @Test
    fun `старая форма v1 больше не проходит`() {
        // Ровно то, что приложение ждало раньше. Если бы parser по-прежнему
        // верил этой форме, тест бы падал: значит, старый путь не воскреснет.
        val v1 = """{"data":[{"id":"que_old","text":"Старый вопрос","options":[{"label":"да"}]}]}"""
        assertNull(OpenCodeQuestionApi.pendingForSession(v1, SESSION))
    }

    @Test
    fun `вопрос без текста отбрасывается`() {
        // `text` из v1 вместо `question`: карточку с пустотой рисовать нечего.
        val noText = """{"id":"que_1","sessionID":"$SESSION","questions":[{"header":"h","options":[{"label":"a"}]}]}"""
        assertNull(OpenCodeQuestionApi.pendingForSession(noText, SESSION))
    }

    @Test
    fun `битый json не роняет опрос`() {
        assertNull(OpenCodeQuestionApi.pendingForSession("{не json", SESSION))
        assertNull(OpenCodeQuestionApi.pendingForSession("<html>502</html>", SESSION))
    }

    @Test
    fun `несколько вопросов идут в сервер в том же порядке`() {
        // Сервер сверяет `answers` позициями, поэтому порядок вопросов — часть
        // контракта: переставить его нельзя, а проверить можно только здесь.
        val two =
            """{"id":"que_two","sessionID":"$SESSION","questions":[""" +
                """{"question":"Первый?","header":"раз","options":[{"label":"a1"}],"multiple":true},""" +
                """{"question":"Второй?","header":"два","options":[{"label":"b1"},{"label":"b2"}]}]}"""
        val parsed = requireNotNull(OpenCodeQuestionApi.pendingForSession(two, SESSION))
        assertEquals(2, parsed.prompts.size)
        assertTrue(parsed.prompts[0].multiple)
        assertFalse(parsed.prompts[1].multiple)
        val body = OpenCodeQuestionApi.replyBody(listOf(listOf("a1"), listOf("b2")))
        assertTrue("ответ на первый вопрос", body.contains("""["a1"]"""))
        assertTrue("ответ на второй вопрос", body.contains("""["b2"]"""))
        assertFalse("невыбранный вариант не уходит", body.contains("b1"))
    }

    @Test
    fun `тело ответа это массив массивов меток`() {
        val body = OpenCodeQuestionApi.replyBody(listOf(listOf("да")))
        assertEquals("""{"answers":[["да"]]}""", body)
    }

    @Test
    fun `пустой id вопроса не отправляем`() {
        // Молча уйти в POST с пустым id значило бы зависнуть на том же вопросе.
        var threw = false
        try {
            OpenCodeQuestionApi.reply(4096, "", listOf(listOf("да")))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("пустой requestId обязан падать сразу", threw)
    }

    @Test
    fun `ответ уходит по корневому пути а не по сессионному`() {
        // Живой сервер 1.18.25 отвечает 404 на /api/session/{id}/question/{id}/reply,
        // хотя тот же путь есть в спеке. Ошибка невидима: карточка сбрасывается,
        // агент остаётся висеть, плашка возвращается.
        val id = "que_1200b4593001k9sNnpUzlg3caq"
        assertEquals("/question/$id/reply", OpenCodeQuestionApi.actionPath(id, "reply"))
        assertEquals("/question/$id/reject", OpenCodeQuestionApi.actionPath(id, "reject"))
    }

    @Test
    fun `id вопроса кодируется в пути`() {
        assertEquals("/question/a%2Fb/reply", OpenCodeQuestionApi.actionPath("a/b", "reply"))
    }

    private companion object {
        const val SESSION = "ses_ee0a3d193ffeDXVvHaRYMUAuHp"
    }
}
