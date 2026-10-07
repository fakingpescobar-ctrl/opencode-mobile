package org.opencode.mobile.social

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверка публикации: задача платформы и «сервер ничего не создал».
 *
 * Отдельный файл, а не продолжение `MoltbookClientTest`, потому что форма ответа
 * платформы здесь и ломает тик: комментарий создаётся, помечается `pending` и ждёт
 * `/verify`, который не вызывался ни разу за всё время. Ошибка не в тексте, а в
 * уровне вложенности, поэтому проверки и должны жить рядом друг с другом.
 */
class MoltbookVerificationTest {
    /**
     * Задача проверки лежит внутри `comment`, а не в корне — корень бага.
     *
     * Фикстура снята с живого API 07.10.2026. Старый код искал `verification` в корне,
     * не находил, тикер писал «ответил» — и комментарий навсегда оставался `pending`.
     */
    @Test
    fun `задача проверки лежит внутри comment а не в корне`() {
        val root =
            JSONObject(
                """
                {
                  "success": true, "message": "Comment added!",
                  "comment": {
                    "id": "b3f4e213-9c1a-4c8e-9a44-3f4b2d1c0e77",
                    "post_id": "post-1", "parent_id": "c09866b7-2f3a-4d5c-8b6e-1a2b3c4d5e6f",
                    "content": "наш ответ", "author_id": "u-1",
                    "author": {"name": "opencodekz", "username": "opencodekz"},
                    "upvotes": 0, "downvotes": 0, "score": 0, "reply_count": 0,
                    "is_deleted": false, "depth": 0,
                    "verification_status": "pending", "is_spam": false,
                    "created_at": "2026-10-07T13:42:31Z", "updated_at": "2026-10-07T13:42:31Z",
                    "replies": [],
                    "verification": {
                      "verification_code": "moltbook_verify_e7cd6545d254c2eb10f9b060d0b188d7",
                      "challenge_text": "A] lOoObS tEr Um} SwImS^ aT/ TwEnTy ThReE] mEtErS PeR\\ SeCoNdS- AnD~ SlOwS{ bY/ SeVeN, WhAt< Is> ThE- NeW^ VeLoWcItEe?",
                      "expires_at": "2026-10-07 13:47:31.73428+00",
                      "instructions": "Solve the math problem and respond with ONLY the number (with 2 decimal places, e.g., '525.00'). Send your answer to POST /api/v1/verify with the verification_code."
                    },
                    "verificationStatus": "pending"
                  },
                  "tip": "..."
                }
                """.trimIndent(),
            )
        val challenge = MoltbookClient.verificationChallenge(root)
        assertTrue("задача обязана находиться, иначе /verify не вызывается", challenge != null)
        assertEquals(
            "moltbook_verify_e7cd6545d254c2eb10f9b060d0b188d7",
            challenge!!.optString("verification_code"),
        )
        assertTrue(
            "текст задачи обязан доезжать до модели",
            challenge.optString("challenge_text").contains("VeLoWcItEe"),
        )
        assertEquals("2026-10-07 13:47:31.73428+00", challenge.optString("expires_at"))
        assertEquals("статус достаётся из snake_case", "pending", MoltbookClient.verificationStatusOf(root))
    }

    @Test
    fun `задача ищется в корне и в data comment а не только в comment`() {
        val inRoot =
            JSONObject("""{"verification":{"verification_code":"moltbook_verify_root"}}""")
        assertEquals("moltbook_verify_root", MoltbookClient.verificationChallenge(inRoot)!!.optString("verification_code"))

        val inData =
            JSONObject("""{"data":{"comment":{"verification":{"verification_code":"moltbook_verify_data"}}}}""")
        assertEquals("moltbook_verify_data", MoltbookClient.verificationChallenge(inData)!!.optString("verification_code"))
    }

    @Test
    fun `пустой код это не задача`() {
        assertNull(
            "пустой verification_code читать как задачу нельзя — /verify вернёт 400",
            MoltbookClient.verificationChallenge(JSONObject("""{"verification":{"verification_code":""}}""")),
        )
        assertNull(MoltbookClient.verificationChallenge(JSONObject("""{"comment":{"id":"x"}}""")))
    }

    @Test
    fun `статус читается в обоих написаниях а null не становится строкой`() {
        val snake = JSONObject("""{"comment":{"verification_status":"verified"}}""")
        assertEquals("verified", MoltbookClient.verificationStatusOf(snake))

        val camel = JSONObject("""{"comment":{"verificationStatus":"failed"}}""")
        assertEquals("failed", MoltbookClient.verificationStatusOf(camel))

        val both = JSONObject("""{"comment":{"verification_status":"pending","verificationStatus":"pending"}}""")
        assertEquals("pending", MoltbookClient.verificationStatusOf(both))

        val nulls = JSONObject("""{"comment":{"verification_status":null,"verificationStatus":null}}""")
        assertEquals("", MoltbookClient.verificationStatusOf(nulls))

        assertEquals("", MoltbookClient.verificationStatusOf(JSONObject("""{"comment":{"id":"x"}}""")))
    }

    /**
     * `already_existed: true` — сервер не создал ничего, вернул наш старый коммент.
     *
     * Фикстура t9 с живого API: `parent_id` при этом пустой, хотя публиковали в
     * подветку. Помечать по такому ответу цель как отвеченную нельзя — это чужой
     * контекст, и тикер обязан остановиться.
     */
    @Test
    fun `already_existed это старый наш коммент а не новый ответ`() {
        val root =
            JSONObject(
                """
                {
                  "success": true, "message": "Comment already exists",
                  "comment": {
                    "id": "aa11bb22-cc33-dd44-ee55-ff6677889900",
                    "post_id": "post-1", "parent_id": "",
                    "content": "наш прошлый ответ", "author_id": "u-1",
                    "author": {"name": "opencodekz"},
                    "verification_status": "pending", "is_deleted": false, "replies": []
                  },
                  "already_existed": true
                }
                """.trimIndent(),
            )
        assertTrue("флаг лежит в корне ответа", MoltbookClient.isAlreadyExisted(root))
        assertFalse(
            "обычный ответ без флага",
            MoltbookClient.isAlreadyExisted(JSONObject("""{"success":true}""")),
        )
        assertEquals(
            "у уже-существовавшего коммента parent_id может быть чужим или пустым —",
            "",
            MoltbookClient.commentParentId(root).orEmpty(),
        )
    }

    @Test
    fun `родитель нового коммента достаётся из ответа`() {
        val root = JSONObject("""{"comment":{"id":"c-1","parent_id":"p-9"}}""")
        assertEquals("p-9", MoltbookClient.commentParentId(root))
        assertNull(MoltbookClient.commentParentId(JSONObject("""{"comment":{"id":"c-1"}}""")))
    }
}
