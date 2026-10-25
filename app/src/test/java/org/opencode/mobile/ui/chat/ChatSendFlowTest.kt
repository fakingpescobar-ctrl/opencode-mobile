package org.opencode.mobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отправка как одна операция: создать сессию, отправить, проверить, откатить.
 *
 * Ценность здесь ровно в одном правиле, которое нельзя проверить «на глаз»:
 * `postAsync` отдаёт true сразу после записи тела, поэтому `false` НЕ значит
 * «сервер ничего не получил». На живой сессии неизвестная модель даёт HTTP 500,
 * а user-сообщение уже лежит в базе — и слепой откат съел бы его вместе с
 * сессией. Подсунуть серверу такую модель и дождаться 500 в тесте нельзя,
 * поэтому транспорт подменён.
 */
class ChatSendFlowTest {
    private class FakeTransport(
        private val created: String? = null,
        private val postOk: Boolean = true,
        private val savedAnyway: Boolean = false,
    ) : ChatSendTransport {
        var createCalls = 0
        val posted = mutableListOf<Pair<String, String>>()
        var hasMessagesCalls = 0
        val deleted = mutableListOf<String>()

        override fun createSession(): String? {
            createCalls++
            return created
        }

        override fun postPrompt(
            sessionId: String,
            text: String,
        ): Boolean {
            posted += sessionId to text
            return postOk
        }

        override fun sessionHasMessages(sessionId: String): Boolean {
            hasMessagesCalls++
            return savedAnyway
        }

        override fun deleteSession(sessionId: String): Boolean {
            deleted += sessionId
            return true
        }
    }

    @Test
    fun `существующая сессия - отправляем прямо в неё`() {
        val transport = FakeTransport()

        val outcome = deliverPrompt(transport, "ses_old", "привет")

        assertTrue(outcome.delivered)
        assertNull("новую сессию создавать не за чем", outcome.createdSession)
        assertEquals(0, transport.createCalls)
        assertEquals(listOf("ses_old" to "привет"), transport.posted)
        assertTrue("откатывать нечего", transport.deleted.isEmpty())
    }

    @Test
    fun `отказ в существующей сессии не создаёт новую`() {
        // Сессия уже есть — её история дороже сообщения. Новую не создаём,
        // иначе одно нажатие плодило бы сессии при каждом сбое сети.
        val transport = FakeTransport(postOk = false)

        val outcome = deliverPrompt(transport, "ses_old", "привет")

        assertFalse(outcome.delivered)
        assertNull(outcome.createdSession)
        assertEquals(0, transport.createCalls)
        assertTrue(transport.deleted.isEmpty())
    }

    @Test
    fun `создать сессию не удалось - отправлять некуда`() {
        val transport = FakeTransport(created = null)

        val outcome = deliverPrompt(transport, null, "привет")

        assertFalse(outcome.delivered)
        assertNull(outcome.createdSession)
        assertTrue("без сессии постить нечего", transport.posted.isEmpty())
        assertEquals("и проверять нечего", 0, transport.hasMessagesCalls)
        assertTrue(transport.deleted.isEmpty())
    }

    @Test
    fun `новая сессия и удачная отправка`() {
        val transport = FakeTransport(created = "ses_new")

        val outcome = deliverPrompt(transport, null, "привет")

        assertTrue(outcome.delivered)
        assertNull("откатывать нечего", outcome.createdSession)
        assertEquals(listOf("ses_new" to "привет"), transport.posted)
        assertTrue("при успехе сервер не переспрашиваем", transport.hasMessagesCalls == 0)
        assertTrue(transport.deleted.isEmpty())
    }

    @Test
    fun `сервер сохранил сообщение и ответил ошибкой - сессию не стираем`() {
        // Главное правило файла. Сообщение уже в базе, откат съел бы его вместе
        // с сессией — и человек потерял бы текст, который сервер принял.
        val transport = FakeTransport(created = "ses_new", postOk = false, savedAnyway = true)

        val outcome = deliverPrompt(transport, null, "привет")

        assertFalse("ответа об успехе не было — черновик остаётся", outcome.delivered)
        assertEquals("ses_new", outcome.createdSession)
        assertEquals(1, transport.hasMessagesCalls)
        assertTrue("сессию с сохранённым сообщением удалять нельзя", transport.deleted.isEmpty())
    }

    @Test
    fun `пустая сессия после сбоя откатывается`() {
        // Сирота (session.created.1 и ноль сообщений) засоряет список сессий —
        // ровно то, что нашлось в базе при разборе.
        val transport = FakeTransport(created = "ses_new", postOk = false, savedAnyway = false)

        val outcome = deliverPrompt(transport, null, "привет")

        assertFalse(outcome.delivered)
        assertEquals("ses_new", outcome.createdSession)
        assertEquals(listOf("ses_new"), transport.deleted)
    }
}
