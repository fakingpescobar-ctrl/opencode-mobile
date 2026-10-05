package org.opencode.mobile.social

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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

    @Test
    fun `verification-ответ требует ровно два знака`() {
        val matches = MoltbookClient.VERIFICATION_ANSWER

        assertTrue("48.00 — формат платформы", matches.matches("48.00"))
        assertTrue("0.00 — тоже валидно", matches.matches("0.00"))
        assertFalse("48 без знаков тратит код впустую", matches.matches("48"))
        assertFalse("запятая не принимается", matches.matches("48,00"))
        assertFalse("лишний знак не принимается", matches.matches("48.000"))
    }
}
