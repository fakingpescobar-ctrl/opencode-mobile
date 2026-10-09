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
     * Единственный разрушающий метод клиента — `deleteComment`, и решение владельца
     * от 07.10.2026 его разрешило: код задачи выдают только в момент создания комментария,
     * поэтому непроверенный ответ нельзя ни решить, ни перезаписать (`already_existed`),
     * ни пропустить — ветка остаётся заблокированной навсегда. Выход один: удалить свой.
     *
     * Тест теперь сторожит не сам факт удаления, а его границы. Удалять можно только то,
     * что опубликовал сам агент, поэтому клиент обязан уметь ровно `deleteComment` —
     * никаких `remove`/`revoke`/«удалить пост». Второе, что он обязан уметь: назвать
     * своё удаление словом, которое понимает `requireAllowedMethod`, иначе свип мёртвых
     * ответов тикает, а комментарии висят.
     */
    @Test
    fun `единственное разрушающее действие клиента это удалить свой комментарий`() {
        val names = MoltbookClient::class.java.declaredMethods.map { it.name }

        // Сначала доказываем, что сканирование вообще что-то видит, иначе проверка
        // ниже прошла бы вхолостую и молча разрешила бы что-нибудь ещё.
        val expected = listOf("postComment", "verify", "upvote", "markPostRead", "deleteComment")
        val missing = expected.filter { it !in names }
        assertTrue("сканирование методов не видит клиент, нет: $missing (всего ${names.size})", missing.isEmpty())

        val deleting =
            names
                .filter { it.contains("delete", true) || it.contains("remove", true) || it.contains("revoke", true) }
                .sorted()

        assertEquals("в клиенте должно быть ровно одно разрушающее действие, а нашлись: $deleting", listOf("deleteComment"), deleting)
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

    /**
     * Удаление разрешено ровно для одного метода — `DELETE`, и в любом регистре:
     * `HttpURLConnection` принимает "delete" так же спокойно, как "DELETE", а
     * `requireAllowedMethod` сравнивает регистронезависимо, поэтому чужой код не сможет
     * протолкнуть разрушающий метод через другой регистр. Проверка стоит ДО открытия
     * соединения: до этого места управление не доходит вовсе, сеть не трогается,
     * сервер ничего не получает. `PUT`/`PATCH`/`HEAD`/пустая строка остаются вне
     * белого списка — клиенту они не нужны, а молча пропустить их нельзя.
     */
    @Test
    fun `клиент пропускает удаление и по-прежнему отвергает остальное разрушающее`() {
        val client = MoltbookClient("test-key", "http://127.0.0.1:1")
        listOf("PUT", "PATCH", "HEAD", "").forEach { method ->
            val thrown =
                runCatching { client.requestBody("/api/v1/posts/p-1/comments", method) }
                    .exceptionOrNull()
            assertTrue(
                "метод «$method» должен быть отвергнут до обращения к сети",
                thrown is IllegalArgumentException,
            )
        }

        // DELETE обязан дойти до сети: запрет на разрушающий метод снят, значит
        // отказ теперь ровно один — соединение с мёртвым портом.
        val deadPort = ServerSocket(0).use { it.localPort }
        val deleting = MoltbookClient("test-key", "http://127.0.0.1:$deadPort")
        listOf("DELETE", "delete").forEach { method ->
            val thrown =
                runCatching { deleting.requestBody("/api/v1/comments/c-1", method) }
                    .exceptionOrNull()
            assertTrue(
                "метод «$method» разрешён и должен падать на сети, а не на проверке: $thrown",
                thrown is IOException,
            )
        }
    }

    @Test
    fun `клиент по-прежнему пропускает разрешённые методы`() {
        // Белый список не должен заблокировать то, чем клиент пользуется каждый тик.
        // Ходим в заведомо мёртвый порт: важно, что запрос ДОШЁЛ до сети и упал
        // именно на соединении, а не на проверке метода.
        val deadPort = ServerSocket(0).use { it.localPort }
        val client = MoltbookClient("test-key", "http://127.0.0.1:$deadPort")
        listOf("GET", "POST").forEach { method ->
            val thrown =
                runCatching { client.requestBody("/api/v1/feed", method) }
                    .exceptionOrNull()
            assertTrue(
                "метод «$method» разрешён и должен падать на сети, а не на проверке: $thrown",
                thrown is IOException,
            )
        }
    }

/**
     * Обход replies[] — не «полнота данных», а условие работы префлита против дублей.
     *
     * Замерено на живом API 06.10.2026: в треде 29 комментариев при 8 корневых, наш
     * ответ лежал на depth=3. Разбор брал только корневой массив, поэтому наш ответ
     * был не виден никогда: probeIn возвращал Absent при опубликованном ответе,
     * и тик отвечал повторно. Ниже — тот же по форме JSON, что отдаёт платформа.
     */
    @Test
    fun `разбор доходит до ответа на глубине`() {
        val parsed =
            MoltbookClient.parseComments(
                JSONObject(
                    """
                    {
                      "comments": [
                        {
                          "id": "root-1", "author": {"name": "doctor_memory"},
                          "content": "спросил", "parent_id": "",
                          "replies": [
                            {
                              "id": "our-1", "author": {"name": "opencodekz"},
                              "content": "наш ответ", "parent_id": "root-1",
                              "replies": [
                                {
                                  "id": "root-1-1", "author": {"name": "doctor_memory"},
                                  "content": "уточнил", "parent_id": "our-1",
                                  "replies": [
                                    {
                                      "id": "our-2", "author": {"name": "opencodekz"},
                                      "content": "ответ на глубине 3", "parent_id": "root-1-1"
                                    }
                                  ]
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )
        assertEquals("все четыре уровня должны попасть в список", 4, parsed.size)
        assertEquals(
            "содержимое на глубине 3 должно читаться, а не теряться",
            "ответ на глубине 3",
            parsed.first { it.id == "our-2" }.content
        )
        // Родительство не теряется: наш ответ на глубине 3 всё ещё указывает на
        // комментарий, на который отвечает. Иначе probeIn не сматчит пару.
        assertEquals("root-1-1", parsed.first { it.id == "our-2" }.parentId)
        assertEquals("our-1", parsed.first { it.id == "root-1-1" }.parentId)
    }

    @Test
    fun `разбор не путает наш глубокий ответ с чужим на том же треде`() {
        val parsed =
            MoltbookClient.parseComments(
                JSONObject(
                    """
                    {
                      "comments": [
                        {
                          "id": "root-1", "author": {"name": "doctor_memory"},
                          "content": "спросил", "parent_id": "",
                          "replies": [
                            { "id": "our-1", "author": {"name": "opencodekz"},
                              "content": "ответ на root-1", "parent_id": "root-1" }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )
        assertSame(
            "ответ был на root-1, а спрашиваем другой комментарий",
            MoltbookClient.ReplyProbe.Absent,
            MoltbookClient.probeIn(parsed, "some-other"),
        )
        val probe = MoltbookClient.probeIn(parsed, "root-1")
        assertTrue("ответ на root-1 обязан найтись, иначе тик ответит повторно", probe is MoltbookClient.ReplyProbe.Found)
        assertEquals("our-1", (probe as MoltbookClient.ReplyProbe.Found).commentId)
    }

    /**
     * `pending` — это ответ, который сервер создал, но ещё не опубликовал.
     *
     * Замерено на живом API: комментарий появляется сразу, сразу `pending`, а
     * `/verify` лишь публикует его («Your comment is now published»). Повторно
     * постить нельзя. Поэтому `pending` обязан читаться как «мы уже ответили, ждём
     * проверки» с id — иначе тикер либо повторно ответит, либо потеряет id.
     */
    @Test
    fun `pending это неопубликованный наш ответ а не отсутствие ответа`() {
        val parsed =
            MoltbookClient.parseComments(
                JSONObject(
                    """
                    {
                      "comments": [
                        { "id": "root-1", "author": {"name": "doctor_memory"},
                          "content": "спросил", "parent_id": "",
                          "replies": [
                            { "id": "our-1", "author": {"name": "opencodekz"},
                              "content": "ответ", "parent_id": "root-1",
                              "verification_status": "pending", "is_deleted": false }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )
        val probe = MoltbookClient.probeIn(parsed, "root-1")
        assertTrue(
            "pending — это наш ответ, который ждёт проверки, а не отсутствие ответа",
            probe is MoltbookClient.ReplyProbe.Unpublished,
        )
        val pending = probe as MoltbookClient.ReplyProbe.Unpublished
        assertEquals("id pending-коммента нужен, чтобы отличить его от прочих", "our-1", pending.commentId)
        assertEquals("статус берётся из ответа платформы", "pending", probe.status)
    }

    @Test
    fun `verified и без статуса считаются опубликованным ответом`() {
        val branch =
            listOf(
                comment("c-verified", OWN, "опубликован", "p-0", "verified"),
                comment("c-nostatus", OWN, "без статуса", "p-0"),
            )
        assertTrue(
            "verified — опубликован",
            MoltbookClient.probeIn(branch, "p-0") is MoltbookClient.ReplyProbe.Found,
        )
    }

    /**
     * Удалённый наш ответ не отвечает — его не видно и удалять нечего.
     *
     * `is_deleted` отсекается в разборе, поэтому в пробе его просто нет, и мы
     * честно отвечаем «ответа нет»: на удалённый комментарий можно ответить заново.
     */
    @Test
    fun `удалённый ответ не закрывает ветку`() {
        val parsed =
            MoltbookClient.parseComments(
                JSONObject(
                    """
                    {
                      "comments": [
                        { "id": "root-1", "author": {"name": "doctor_memory"},
                          "content": "спросил", "parent_id": "",
                          "replies": [
                            { "id": "deleted-1", "author": {"name": "opencodekz"},
                              "content": "удалённый", "parent_id": "root-1",
                              "verification_status": "pending", "is_deleted": true }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )
        assertSame(
            "удалённый ответ не считается ответом — ветка остаётся открытой",
            MoltbookClient.ReplyProbe.Absent,
            MoltbookClient.probeIn(parsed, "root-1"),
        )
        assertEquals("удалённый ответ не должен попадать в список", 1, parsed.size)
    }

    /**
     * Наш проваленный ответ обязан остаться в списке — иначе он неотличим от его отсутствия.
     *
     * Это баг, найденный вживую 08.10.2026. Модель решила задачу платформы неверно,
     * `POST /verify` вернул 400, код израсходовался, коммент стал `failed`. Разбор
     * выкидывал `failed` из списка, и дальше:
     *
     *  - [MoltbookClient.probeIn] видела «мы не отвечали» и публиковала второй ответ,
     *    а сервер отдавал тот же `already_existed` — то есть дубль вместо ответа;
     *  - свип не видел комментарий и не удалял его — ветка закрывалась навсегда,
     *    и ни один последующий тик до неё не доходил.
     *
     * Теперь он в списке и в пробе виден как `Unpublished` со статусом `failed`:
     * тикер знает его id, удаляет и отвечает заново с новым кодом.
     */
    @Test
    fun `наш проваленный ответ виден чтобы его удалить а не спрятан от пробы`() {
        val parsed =
            MoltbookClient.parseComments(
                JSONObject(
                    """
                    {
                      "comments": [
                        { "id": "root-1", "author": {"name": "doctor_memory"},
                          "content": "спросил", "parent_id": "",
                          "replies": [
                            { "id": "failed-1", "author": {"name": "opencodekz"},
                              "content": "не прошёл", "parent_id": "root-1",
                              "verification_status": "failed", "is_deleted": false }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )
        assertEquals("наш проваленный ответ обязан остаться в разборе — иначе его не удалить", 2, parsed.size)
        val probe = MoltbookClient.probeIn(parsed, "root-1")
        assertTrue(
            "наш failed виден пробе как неопубликованный, а не как отсутствие ответа",
            probe is MoltbookClient.ReplyProbe.Unpublished,
        )
        val dead = probe as MoltbookClient.ReplyProbe.Unpublished
        assertEquals("без id проваленного коммента тикер не сможет его удалить", "failed-1", dead.commentId)
        assertEquals(
            "статус провала доходит до тикера: он решает, чистить ли ветку",
            MoltbookClient.STATUS_FAILED,
            dead.status,
        )
    }

    /**
     * Чужой проваленный ответ — это мусор, а не наш затылок: его не ждут и удалять нельзя.
     */
    @Test
    fun `чужой проваленный ответ не отвечает на вопрос`() {
        val parsed =
            MoltbookClient.parseComments(
                JSONObject(
                    """
                    {
                      "comments": [
                        { "id": "root-1", "author": {"name": "doctor_memory"},
                          "content": "спросил", "parent_id": "",
                          "replies": [
                            { "id": "their-1", "author": {"name": "vina"},
                              "content": "не прошёл", "parent_id": "root-1",
                              "verification_status": "failed", "is_deleted": false }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )
        assertSame(
            "чужой провал не наш ответ — ветка открыта",
            MoltbookClient.ReplyProbe.Absent,
            MoltbookClient.probeIn(parsed, "root-1"),
        )
        assertEquals("чужой проваленный ответ в ленте — шум, его выкидываем", 1, parsed.size)
    }

    /**
     * Родитель удалён, наш ответ жив — ответ должен остаться в списке.
     *
     * Распространять `is_deleted` вниз по дереву нельзя: тогда живой ответ
     * исчез бы из разбора вместе с удалённым родителем, и мы бы решили, что
     * не отвечали, хотя ответ висит.
     */
    @Test
    fun `удалённый родитель не выбрасывает наш живой ответ`() {
        val parsed =
            MoltbookClient.parseComments(
                JSONObject(
                    """
                    {
                      "comments": [
                        { "id": "root-1", "author": {"name": "vina"},
                          "content": "спросила", "parent_id": "", "is_deleted": true,
                          "replies": [
                            { "id": "our-1", "author": {"name": "opencodekz"},
                              "content": "ответ", "parent_id": "root-1",
                              "verification_status": "pending", "is_deleted": false }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )
        val probe = MoltbookClient.probeIn(parsed, "root-1")
        assertTrue(
            "наш ответ под удалённым родителем всё равно виден и отвечает на root-1",
            probe is MoltbookClient.ReplyProbe.Unpublished,
        )
        val alive = probe as MoltbookClient.ReplyProbe.Unpublished
        assertEquals("id живого ответа под удалённым родителем обязан сохраниться", "our-1", alive.commentId)
    }

    /**
     * Явный JSON null в next_cursor не должен превращаться в курсор "null".
     *
     * Android-овский `optString` на JSON null возвращает строку "null" — непустую, то
     * есть «курсор есть». Такой курсор уходит в запрос как `cursor=null` и получает
     * 400, а comments() зовётся без try: тик падал целиком, а ourReplyTo получала
     * вечный Unknown и блокировала ответы навсегда. Условие продолжения — has_more.
     */
    @Test
    fun `курсор null на последней странице не рождает лишнего запроса`() {
        val explicitNull = JSONObject("""{ "has_more": true, "next_cursor": null }""")
        assertNull(
            "JSON null — это отсутствие курсора, а не курсор \"null\"",
            explicitNull.cursor(),
        )
        val missing = JSONObject("""{ "has_more": false }""")
        assertNull("нет поля — нет курсора", missing.cursor())
        val real = JSONObject("""{ "has_more": true, "next_cursor": "eyJjcmVhdGVkQXQiOiIyMDI2In0=" }""")
        assertEquals("настоящий курсор должен читаться как есть", "eyJjcmVhdGVkQXQiOiIyMDI2In0=", real.cursor())
    }

    /**
     * Одна битая вложенка не должна стоить нам всего треда.
     *
     * Обход идёт по всему дереву, а `requireField` на content бросает IOException.
     * Раньше он смотрел только на корневые комментарии, и одна пустая вложенка
     * роняла бы разбор всего треда — вместе с нашим ответом в нём.
     */
    @Test
    fun `битая вложенка не роняет остальной тред`() {
        val parsed =
            MoltbookClient.parseComments(
                JSONObject(
                    """
                    {
                      "comments": [
                        {
                          "id": "root-1", "author": {"name": "doctor_memory"},
                          "content": "спросил", "parent_id": "",
                          "replies": [
                            { "id": "broken", "author": {"name": "x"}, "content": "" },
                            { "id": "our-1", "author": {"name": "opencodekz"},
                              "content": "ответ", "parent_id": "root-1" }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )
        assertTrue(
            "наш ответ после битой вложенки обязан остаться в списке",
            parsed.any { it.id == "our-1" },
        )
        assertTrue("битый узел пропущен, а не превращён в пустой комментарий", parsed.none { it.id == "broken" })
    }

    /**
     * Пагинация идёт по курсору, а не по флагу has_more.
     *
     * Регрессия на замечание ревью: продолжение было завязано на `has_more`, которого
     * нет ни в одной записи репозитория. Отсутствие поля молча сворачивало обход в одну
     * страницу — `ourReplyTo` не видел наш ответ со второй и публиковал дубль, то есть
     * баг оставался, а починка выглядела рабочей. Тест отдаёт страницы БЕЗ has_more
     * и требует, чтобы обход всё равно дошёл до конца.
     */
    @Test
    fun `обход идёт по курсору даже без has_more`() {
        val pages =
            listOf(
                page("c-1", next = "CUR1"),
                page("c-2", next = "CUR2"),
                page("c-3", next = null),
            )
        var call = 0
        val parsed =
            MoltbookClient.collectPagedComments {
                pages[call++]
            }
        assertEquals("три страницы должны быть прочитаны все", 3, call)
        assertEquals(listOf("c-1", "c-2", "c-3"), parsed.map { it.id })
    }

    /**
     * Пустая страница продолжения — это не конец треда, а подозрительное чтение.
     *
     * Регрессия на замечание ревью: страница 200 с пустым телом читалась как «страниц
     * больше нет», выборка оставалась обрезанной, и `ourReplyTo` возвращал Absent по
     * неполным данным — второй ответ на тот же комментарий. Отличить «тред кончился»
     * от «сервер ответил пустотой» по одному ответу нельзя, поэтому падаем.
     */
    @Test
    fun `пустая страница продолжения роняет чтение а не выглядит концом треда`() {
        val pages =
            listOf(
                page("c-1", next = "CUR1"),
                JSONObject("""{"comments":[]}"""),
            )
        var call = 0
        val failure =
            runCatching {
                MoltbookClient.collectPagedComments {
                    pages[call++]
                }
            }.exceptionOrNull()
        assertTrue("чтение обязано упасть, а не вернуть обрезанный список: $failure", failure is IOException)
    }

    /**
     * Повтор курсора — сервер не двигает окно.
     *
     * Без проверки чтение зацикливалось бы до потолка страниц и вернуло бы одни и
     * те же комментарии в списке, а потолок сам по себе тихо усекает выборку.
     */
    @Test
    fun `повтор курсора роняет чтение`() {
        val pages = listOf(page("c-1", next = "CUR1"), page("c-2", next = "CUR1"))
        var call = 0
        val failure =
            runCatching {
                MoltbookClient.collectPagedComments {
                    pages[call++]
                }
            }.exceptionOrNull()
        assertTrue("повтор курсора обязан упасть, а не зациклиться: $failure", failure is IOException)
    }

    /**
     * Потолок страниц с живым курсором — это обрезанная выборка, а не ответ.
     *
     * Регрессия на замечание ревью: `break` на потолке возвращал неполный список,
     * который `ourReplyTo` читал как «нашего ответа нет».
     */
    @Test
    fun `потолок страниц с непрочитанным остатком роняет чтение`() {
        var call = 0
        val failure =
            runCatching {
                MoltbookClient.collectPagedComments {
                    call++
                    page("c-$call", next = "CUR$call")
                }
            }.exceptionOrNull()
        assertTrue("потолок с непрочитанным остатком обязан упасть: $failure", failure is IOException)
    }

    /**
     * Настоящий конец треда — обычное дело, а не ошибка.
     */
    @Test
    fun `обрыв треда без курсора не считается ошибкой`() {
        val pages = listOf(page("c-1", next = null))
        val parsed = MoltbookClient.collectPagedComments { pages.first() }
        assertEquals(1, parsed.size)
    }

    /**
     * Страница ответов БЕЗ `has_more` — как и приходит от платформы в наших фикстурах.
     *
     * `next_cursor` кладётся в корень объекта ответа, а не внутрь комментария:
     * по структуре платформы это поле соседствует с `comments`, иначе обход
     * никогда не увидит продолжения и тихо прочитает одну страницу.
     */
    private fun page(
        id: String,
        next: String?,
    ): JSONObject {
        val cursor = if (next == null) "" else ""","next_cursor":"$next""""
        return JSONObject("""{"comments":[{"id":"$id","author":{"name":"x"},"content":"текст"}]$cursor}""")
    }

    /**
     * Тред, где все комментарии удалены — это пустой результат, а не поломка.
     *
     * Регрессия на замечание ревью: громкая проверка стояла на «непустой вход, пустой
     * выход», но мёртвые узлы (`is_deleted`, `verification_status=failed`) выбрасываются
     * ДО разбора. Модерированный тред из удалённых комментов давал пустой список и
     * читался как «формат изменилась» — навсегда: пост не попадал в ledger, счётчик
     * непрочитанного не гасился, `ourReplyTo` давал вечный Unknown, и ответы в этот
     * пост не публиковались никогда.
     */
    @Test
    fun `тред из одних удалённых комментов даёт пустой список а не падает`() {
        val parsed =
            MoltbookClient.parseComments(
                JSONObject(
                    """
                    {
                      "comments": [
                        {"id":"d-1","author":{"name":"x"},"content":"Deleted comment","is_deleted":true},
                        {"id":"d-2","author":{"name":"y"},"content":"","verification_status":"failed"}
                      ]
                    }
                    """.trimIndent(),
                ),
            )
        assertTrue("все комменты мёртвые — результат пуст, но разбор не падает", parsed.isEmpty())
    }

    /**
     * Мёртвый родитель не должен утаскивать живого ребёнка.
     *
     * На живом API 06.10.2026 удалённые комменты приходят с content
     * «Deleted comment», но сам флаг стоит проверять на СВОЁМ узле: молча выбросить
     * наш живой ответ вместе с удалённым родителем нельзя.
     */
    @Test
    fun `живой ответ под удалённым родителем сохраняется`() {
        val parsed =
            MoltbookClient.parseComments(
                JSONObject(
                    """
                    {
                      "comments": [
                        {
                          "id":"del","author":{"name":"x"},"content":"Deleted comment","is_deleted":true,
                          "replies":[
                            {"id":"our-9","author":{"name":"opencodekz"},"content":"мой ответ","parent_id":"del"}
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )
        assertEquals("наш ответ под удалённым родителем обязан выжить", 1, parsed.size)
        assertEquals("our-9", parsed.first().id)
    }

    /**
     * Единственная битая вложенка не должна ронять тред.
     *
     * Регрессия на замечание ревью: проверка «формат изменилась» стояла ВНУТРИ
     * рекурсии, поэтому вложенный уровень с одним битым узлом выбрасывал IOException
     * и терял весь уже собранный тред. Тест с живым соседом по коду проходил и эту
     * ошибку не ловил — нужен ровно один битый узел в replies[].
     */
    @Test
    fun `единственная битая вложенка не роняет тред`() {
        val parsed =
            MoltbookClient.parseComments(
                JSONObject(
                    """
                    {
                      "comments": [
                        {
                          "id": "root-1", "author": {"name": "doctor_memory"},
                          "content": "спросил", "parent_id": "",
                          "replies": [
                            { "id": "broken", "author": {"name": "x"}, "content": "" }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )
        assertEquals("корень должен уцелеть", 1, parsed.size)
        assertEquals("root-1", parsed.first().id)
    }

    /**
     * А если не разобрался НИ ОДИН узел — это смена формата, и молчать нельзя.
     *
     * Пустой список тикер прочитал бы как «мы тут ничего не отвечали» и ответил бы
     * повторно. Лучше громко упасть и пропустить пост, чем выдумать отсутствие.
     */
    @Test
    fun `неразобранный тред падает громко а не выглядит пустым`() {
        val failure =
            runCatching {
                MoltbookClient.parseComments(
                    JSONObject(
                        """{"comments":[
                            {"id":"a","author":{"name":"x"},"content":""},
                            {"id":"b","author":{"name":"y"},"content":""}
                        ]}""",
                    ),
                )
            }.exceptionOrNull()
        assertTrue("неразобранный тред обязан падать, а не вернуть пустой список: $failure", failure is IOException)
    }

    private fun comment(
        id: String,
        author: String,
        content: String,
        parentId: String,
        verificationStatus: String = "",
    ) = MoltbookClient.Comment(
        id = id,
        author = author,
        content = content,
        parentId = parentId,
        verificationStatus = verificationStatus,
    )

    private companion object {
        const val OWN = "opencodekz"
    }


    // ---- сверка двух представлений ветки ----

    @Test
    fun `найденный ответ вторым представлением не переиговаривается`() {
        assertTrue(MoltbookBranchCheck.isSettled(MoltbookClient.ReplyProbe.Found("c-1")))
        assertTrue(MoltbookBranchCheck.isSettled(MoltbookClient.ReplyProbe.Unpublished("c-2", "pending")))
        assertTrue(!MoltbookBranchCheck.isSettled(MoltbookClient.ReplyProbe.Absent))
        assertTrue(!MoltbookBranchCheck.isSettled(MoltbookClient.ReplyProbe.Unknown("boom")))
    }

    @Test
    fun `две пустые ветки это отсутствие а не неизвестность`() {
        val decision = MoltbookBranchCheck.crossCheck(MoltbookClient.ReplyProbe.Absent, MoltbookClient.ReplyProbe.Absent)
        assertTrue(decision is MoltbookClient.ReplyProbe.Absent)
    }

    @Test
    fun `упавшее чтение отвечает за неизвестность а не за отсутствие`() {
        // Порядок обоих чтений важен одинаково: «нет» + «не знаю» и «не знаю» +
        // «нет» обязаны давать одно и то же, иначе исход зависит от того, какое
        // представление читалось первым.
        val absent = MoltbookClient.ReplyProbe.Absent
        val failed = MoltbookClient.ReplyProbe.Unknown("timeout")
        val absentThenFailed = MoltbookBranchCheck.crossCheck(absent, failed)
        val failedThenAbsent = MoltbookBranchCheck.crossCheck(failed, absent)
        assertTrue(absentThenFailed is MoltbookClient.ReplyProbe.Unknown)
        assertTrue(failedThenAbsent is MoltbookClient.ReplyProbe.Unknown)
    }

    @Test
    fun `второе представление дополняет первое там где первое молчит`() {
        val decision = MoltbookBranchCheck.crossCheck(MoltbookClient.ReplyProbe.Absent, MoltbookClient.ReplyProbe.Found("c-9"))
        assertEquals("c-9", (decision as MoltbookClient.ReplyProbe.Found).commentId)
    }

    @Test
    fun `найденное в первом представлении не отменяется упавшим вторым`() {
        val decision = MoltbookBranchCheck.crossCheck(MoltbookClient.ReplyProbe.Found("c-1"), MoltbookClient.ReplyProbe.Unknown("timeout"))
        assertEquals("c-1", (decision as MoltbookClient.ReplyProbe.Found).commentId)
    }

    @Test
    fun `два упавших чтения это неизвестность`() {
        val decision = MoltbookBranchCheck.crossCheck(MoltbookClient.ReplyProbe.Unknown("a"), MoltbookClient.ReplyProbe.Unknown("b"))
        assertTrue(decision is MoltbookClient.ReplyProbe.Unknown)
    }
}
