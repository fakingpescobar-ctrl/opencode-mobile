package org.opencode.mobile.social

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket

/**
 * Формат ответов Moltbook задаёт платформа и меняется без предупреждения, поэтому
 * разбор проверяется на реальных кусках JSON, а не на заглушках: ошибка здесь
 * выглядит как «агент молча перестал отвечать», и заметить её можно через сутки.
 */
class MoltbookClientTest {
    @Test
    fun `home читает karma и активность`() {
        val home =
            MoltbookClient.parseHome(
                JSONObject(
                    """
                    {
                      "your_account": { "karma": 137, "unread_notification_count": 4 },
                      "activity_on_your_posts": [
                        {
                          "post_id": "p-1",
                          "post_title": "Что такое агент",
                          "new_notification_count": 3,
                          "latest_commenters": ["someone", "opencodekz"]
                        },
                        {
                          "post_id": "p-2",
                          "post_title": "Тихий пост",
                          "new_notification_count": 0,
                          "latest_commenters": []
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(137, home.karma)
        assertEquals(4, home.unreadNotifications)
        assertEquals(2, home.posts.size)
        assertEquals("Что такое агент", home.posts[0].title)
        assertEquals(listOf("someone", "opencodekz"), home.posts[0].commenters)
    }

    @Test
    fun `awaitingReply отбирает посты с непрочитанными`() {
        val home =
            MoltbookClient.parseHome(
                JSONObject(
                    """
                    {
                      "your_account": { "karma": 0 },
                      "activity_on_your_posts": [
                        { "post_id": "p-1", "new_notification_count": 2 },
                        { "post_id": "p-2", "new_notification_count": 0 }
                      ]
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(listOf("p-1"), home.awaitingReply.map { it.postId })
    }

    @Test
    fun `home без your_account падает громко`() {
        val failure =
            runCatching { MoltbookClient.parseHome(JSONObject("""{"activity_on_your_posts":[]}""")) }
                .exceptionOrNull()

        assertTrue("ожидался IOException, получено: $failure", failure is IOException)
    }

    @Test
    fun `comments разбирают автора и родителя`() {
        val comments =
            MoltbookClient.parseComments(
                JSONObject(
                    """
                    {
                      "comments": [
                        {
                          "id": "c-1",
                          "author": { "name": "opencodekz" },
                          "content": "мой ответ",
                          "parent_id": "c-0"
                        },
                        {
                          "id": "c-2",
                          "author": { "name": "человек" },
                          "content": "вопрос"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(2, comments.size)
        assertTrue(comments[0].isOurs)
        assertFalse(comments[1].isOurs)
        assertEquals("c-0", comments[0].parentId)
        assertNull(comments[1].parentId)
    }

    @Test
    fun `comment без content не превращается в пустую реплику`() {
        val failure =
            runCatching {
                MoltbookClient.parseComments(
                    JSONObject("""{"comments":[{"id":"c-1","author":{"name":"x"},"content":""}]}"""),
                )
            }.exceptionOrNull()

        assertTrue("ожидался IOException, получено: $failure", failure is IOException)
    }

    /**
     * Страховка от возврата бага, который уже случился в проде: при `NeedsVerification`
     * тикер вызывал `deleteComment(parentId)`, то есть отзывал чужой комментарий.
     * Отзыв в публичной ленте необратим, поэтому запрет держится не на памяти,
     * а тестом: как только в клиенте появится удаление, сборка падает.
     * Если понадобится чистить свой мусор — сначала получи явное разрешение.
     */
    @Test
    fun `клиент не умеет ничего удалять`() {
        val names = MoltbookClient::class.java.declaredMethods.map { it.name }

        // Сначала доказываем, что сканирование вообще что-то видит, иначе проверка
        // выше прошла бы вхолостую и молча разрешила удаление в будущем.
        val expected = listOf("postComment", "verify", "upvote", "markPostRead")
        val missing = expected.filter { it !in names }
        assertTrue("сканирование методов не видит клиент, нет: $missing (всего ${names.size})", missing.isEmpty())

        val deleting =
            names
                .filter { it.contains("delete", true) || it.contains("remove", true) || it.contains("revoke", true) }
                .sorted()

        assertTrue(
            "MoltbookClient не должен уметь удалять, а нашлись: $deleting",
            deleting.isEmpty(),
        )
    }

    @Test
    fun `postComment узнаёт успех в любой форме ответа`() {
        val shapes =
            mapOf(
                """{"comment":{"id":"c-1"}}""" to "c-1",
                """{"id":"c-2"}""" to "c-2",
                """{"comment_id":"c-3"}""" to "c-3",
                """{"data":{"id":"c-4"}}""" to "c-4",
                """{"data":{"comment":{"id":"c-5"}}}""" to "c-5",
            )

        for ((body, expected) in shapes) {
            assertEquals(body, expected, MoltbookClient.postedCommentId(JSONObject(body)))
        }
    }

    @Test
    fun `пустой ответ без comment трактуется как неудача`() {
        // Раньше это молча читалось как «комментарий не создан» → следующий тик
        // публиковал второй ответ на тот же вопрос, а отозвать его нельзя.
        assertEquals("", MoltbookClient.postedCommentId(JSONObject("""{"message":"спам"}""")))
        assertEquals("", MoltbookClient.postedCommentId(JSONObject("""{}""")))
    }

    @Test
    fun `автор-строка распознаётся как наш`() {
        val comments =
            MoltbookClient.parseComments(
                JSONObject(
                    """
                    {"comments":[
                      {"id":"c-1","author":"opencodekz","content":"мой ответ","parent_id":"c-0"}
                    ]}
                    """.trimIndent(),
                ),
            )

        assertTrue("строка-автор не узнала себя — свой ответ сочтут чужим", comments[0].isOurs)
        assertEquals("opencodekz", comments[0].author)
    }

    @Test
    fun `verify с чужим форматом бросает IOException а не IllegalStateException`() {
        // IllegalStateException не ловился в тике, улетал в общий catch приёмника и
        // уносил весь остаток визита. IOException тикер ловит и берёт новую попытку.
        for (bad in listOf("48.0", "48", "48,00", "спам", "")) {
            val failure =
                runCatching { MoltbookClient("test-key").verify("code", bad) }
                    .exceptionOrNull()

            assertTrue("ответ «$bad» дал $failure вместо IOException", failure is IOException)
        }
    }

    @Test
    fun `лента берёт posts а не первый попавшийся массив`() {
        val root =
            JSONObject(
                """
                {"related":[{"id":"wrong-1"}],"posts":[{"id":"p-1"},{"id":"p-2"}]}
                """.trimIndent(),
            )

        val items = MoltbookClient.feedArray(root)

        assertEquals(2, items?.length())
        assertEquals("p-1", items?.optJSONObject(0)?.optString("id"))
    }

    @Test
    fun `лента падает на неизвестный ключ а не на пустой молчаливый ответ`() {
        assertEquals(null, MoltbookClient.feedArray(JSONObject("""{"meta":{"x":1}}""")))
        // Запасная ветка: неизвестное имя массива, разобрать всё равно должны.
        val odd = JSONObject("""{"whatever":[{"id":"p-9"}]}""")
        assertEquals(1, MoltbookClient.feedArray(odd)?.length())
    }

    @Test
    fun `verification-ответ требует ровно два знака`() {
        val matches = MoltbookClient.VERIFICATION_ANSWER

        assertTrue("48.00 — формат платформы", matches.matches("48.00"))
        assertTrue("0.00 — тоже валидно", matches.matches("0.00"))
        assertFalse("48 без знаков тратит код впустую", matches.matches("48"))
        assertFalse("запятая не принимается", matches.matches("48,00"))
        assertFalse("лишний знак не принимается", matches.matches("48.000"))
    }

/**
     * Проверка дублей обязана различать «ответа нет» и «не смог проверить».
     *
     * Раньше это был `runCatching { comments() }.getOrNull()`, где ошибка сети давала
     * тот же null, что и пустая ветка. Тикер читал null как «можно публиковать» и
     * публиковал второй ответ на тот же вопрос — при том, что прошлый ответ сервер
     * уже создал, мы просто о нём не узнали. Отзыв опубликованного запрещён, дубль
     * остаётся навсегда.
     */
    @Test
    fun `probeIn находит наш ответ именно в этой ветке`() {
        val branch =
            listOf(
                comment("c-1", OWN, "наш ответ", "p-0"),
                comment("c-2", "someone", "чужой ответ", "p-0"),
                comment("c-3", OWN, "ответ на другой вопрос", "p-9"),
            )
        val probe = MoltbookClient.probeIn(branch, "p-0")
        assertTrue("ответ на p-0 найден, а не потерян", probe is MoltbookClient.ReplyProbe.Found)
        assertEquals("c-1", (probe as MoltbookClient.ReplyProbe.Found).commentId)
    }

    @Test
    fun `probeIn не путает чужой комментарий и наш ответ на другом вопрос`() {
        val branch =
            listOf(
                comment("c-1", OWN, "наш ответ на другой вопрос", "p-9"),
                comment("c-2", "someone", "чужой ответ", "p-0"),
            )
        assertSame(
            "наш комментарий на другом parent и чужой на нашем — это не наш ответ",
            MoltbookClient.ReplyProbe.Absent,
            MoltbookClient.probeIn(branch, "p-0"),
        )
    }

    @Test
    fun `probeIn на пустой ветке честно говорит что ответа нет`() {
        assertSame(
            MoltbookClient.ReplyProbe.Absent,
            MoltbookClient.probeIn(emptyList(), "p-0"),
        )
    }

    @Test
    fun `ourReplyTo при недоступной сети говорит незнание а не отсутствие`() {
        // Порт, который никто не слушает: соединение падает на connect.
        // Никакого сервера не нужно — тесту достаточно, чтобы сеть не удалась.
        val deadPort = ServerSocket(0).use { it.localPort }
        val client = MoltbookClient("test-key", "http://127.0.0.1:$deadPort")
        val probe = client.ourReplyTo("p-1", "p-0")
        assertTrue(
            "упавшая сеть не должна читаться как «ответа нет» — иначе получим дубль",
            probe is MoltbookClient.ReplyProbe.Unknown,
        )
    }

    private fun comment(
        id: String,
        author: String,
        content: String,
        parentId: String,
    ) = MoltbookClient.Comment(
        id = id,
        author = author,
        content = content,
        parentId = parentId,
    )

    private companion object {
        const val OWN = "opencodekz"
    }
}
