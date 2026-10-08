package org.opencode.mobile.social

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Архив сырых ответов и независимый разбор.
 *
 * Тесты без сети и без ключа: проверяется ровно то, что обязано выжить после
 * смерти процесса, — тело на диске и вердикт разбора, который не имеет права
 * пользоваться кодом писателя.
 */
class MoltbookRawArchiveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun body(
        id: String,
        content: String,
        already: Boolean = false,
    ): String =
        JSONObject()
            .put("success", true)
            .put("already_existed", already)
            .put(
                "comment",
                JSONObject().put("id", id).put("content", content).put("verification_status", "verified"),
            ).toString()

    @Test
    fun `сырое тело переживает разбор и лежит на диске`() {
        val dir = File(tmp.root, "raw")
        val raw = body("c-1", "текст ответа")
        val file = MoltbookRawArchive.record(dir, "POST", "/api/v1/posts/p1/comments", 201, raw, 1000L)

        assertNotNull("тело обязано оказаться на диске", file)
        assertTrue(file!!.isFile)
        val envelope = JSONObject(file.readText(Charsets.UTF_8))
        assertEquals("POST", envelope.getString("method"))
        assertEquals(201, envelope.getInt("status"))
        assertEquals(raw, envelope.getString("body"))
    }

    @Test
    fun `пустое тело не архивируется`() {
        val dir = File(tmp.root, "raw")
        assertNull(MoltbookRawArchive.record(dir, "GET", "/api/v1/home", 200, "   ", 1000L))
    }

    @Test
    fun `имя файла разводит одинаковые запросы по времени`() {
        val a = MoltbookRawArchive.fileName(1000L, "POST", "/p/1/comments")
        val b = MoltbookRawArchive.fileName(2000L, "POST", "/p/1/comments")
        assertFalse("повтор той же публикации не должен затирать прошлый ответ", a == b)
        assertTrue(a.endsWith(".json"))
    }

    @Test
    fun `усечение архива оставляет последние ответы`() {
        val dir = File(tmp.root, "raw")
        dir.mkdirs()
        repeat(10) { i ->
            File(dir, "000$i-response.json").writeText("{}", Charsets.UTF_8)
        }
        MoltbookRawArchive.prune(dir, keep = 3)

        val left = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("0007-response.json", "0008-response.json", "0009-response.json"), left)
    }

    @Test
    fun `разбор достаёт id и контент`() {
        val receipt = MoltbookRawVerifier.receipt(body("c-9", "мой ответ"))
        assertNotNull(receipt)
        assertEquals("c-9", receipt!!.commentId)
        assertEquals("мой ответ", receipt.content)
        assertEquals("verified", receipt.verificationStatus)
        assertFalse(receipt.alreadyExisted)
    }

    @Test
    fun `совпавший контент это подтверждение`() {
        val receipt = MoltbookRawVerifier.receipt(body("c-9", "мой ответ"))
        assertEquals(MoltbookRawVerifier.Verdict.Confirmed, MoltbookRawVerifier.verdict(receipt, "мой ответ"))
    }

    @Test
    fun `чужой текст под нашим id это расхождение а не успех`() {
        val receipt = MoltbookRawVerifier.receipt(body("c-9", "текст сервера"))
        assertEquals(MoltbookRawVerifier.Verdict.ContentMismatch, MoltbookRawVerifier.verdict(receipt, "мой ответ"))
    }

    @Test
    fun `already_existed не выдаётся за публикацию`() {
        val receipt = MoltbookRawVerifier.receipt(body("c-old", "чужое", already = true))
        assertEquals(MoltbookRawVerifier.Verdict.AlreadyExisted, MoltbookRawVerifier.verdict(receipt, "мой ответ"))
    }

    @Test
    fun `нечитаемый ответ это не знаю а не провал`() {
        assertNull(MoltbookRawVerifier.receipt("не json вовсе"))
        assertEquals(
            MoltbookRawVerifier.Verdict.Unknown,
            MoltbookRawVerifier.verdict(MoltbookRawVerifier.receipt("не json вовсе"), "мой ответ"),
        )
    }

    @Test
    fun `без отправленного текста сверки не было а значит и подтверждения нет`() {
        val receipt = MoltbookRawVerifier.receipt(body("c-9", "мой ответ"))
        assertEquals(MoltbookRawVerifier.Verdict.Unknown, MoltbookRawVerifier.verdict(receipt, null))
    }

    /**
     * Ответ без контента — это «не знаю», а не расхождение.
     *
     * Пустая строка не равна отправленному тексту, но называть это mismatch
     * значит обвинить сервер в подмене там, где он просто ничего не прислал.
     */
    @Test
    fun `ответ без контента это не знаю а не расхождение`() {
        val noContent = JSONObject().put("comment", JSONObject().put("id", "c-9")).toString()
        val receipt = MoltbookRawVerifier.receipt(noContent)
        assertEquals("c-9", receipt!!.commentId)
        assertNull(receipt.content)
        assertEquals(MoltbookRawVerifier.Verdict.Unknown, MoltbookRawVerifier.verdict(receipt, "мой ответ"))
    }

    /**
     * Формы ответа, которые уже встречались вживую, обязаны читаться.
     *
     * Верификатор намеренно не пользуется разбором клиента, поэтому перечень
     * ключей продублирован — и поэтому он обязан быть проверен на всех формах,
     * что платформа отдаёт. Иначе выходит худшее: клиент успешно публикует, а
     * верификатор пишет «сервер не дал доказательства публикации».
     */
    @Test
    fun `верификатор читает все формы ответа а не только одну`() {
        val underData =
            JSONObject()
                .put(
                    "data",
                    JSONObject().put("comment", JSONObject().put("comment_id", "c-d").put("content", "мой ответ")),
                ).toString()
        val data = MoltbookRawVerifier.receipt(underData)
        assertEquals("c-d", data!!.commentId)
        assertEquals("мой ответ", data.content)
        assertEquals(MoltbookRawVerifier.Verdict.Confirmed, MoltbookRawVerifier.verdict(data, "мой ответ"))

        val camelCase =
            JSONObject().put("commentId", "c-c").put("verificationStatus", "pending").put("content", "мой ответ")
                .toString()
        val camel = MoltbookRawVerifier.receipt(camelCase)
        assertEquals("c-c", camel!!.commentId)
        assertEquals("pending", camel.verificationStatus)
        assertEquals(MoltbookRawVerifier.Verdict.Confirmed, MoltbookRawVerifier.verdict(camel, "мой ответ"))
    }

    /**
     * Лента и скан не должны раздувать архив.
     *
     * 300 файлов без потолка по размеру — это десятки мегабайт в `filesDir`, а
     * лента доказательством публикации не является. Тело больше потолка
     * отбрасывается, но сам факт запроса и ответа остаётся.
     */
    @Test
    fun `огромное тело не попадает в архив но факт запроса остаётся`() {
        val dir = File(tmp.root, "raw")
        val huge = "x".repeat(MoltbookRawArchive.MAX_BODY_BYTES + 100)
        val file = MoltbookRawArchive.record(dir, "GET", "/api/v1/home", 200, huge, 1000L)

        assertNotNull(file)
        val envelope = JSONObject(file!!.readText(Charsets.UTF_8))
        assertFalse("тело не должно попасть на диск целиком", envelope.has("body"))
        assertEquals(huge.toByteArray(Charsets.UTF_8).size.toLong(), envelope.getLong("body_omitted_bytes"))
        assertEquals("/api/v1/home", envelope.getString("path"))
    }

    /** На пределе потолка тело пишется целиком — обрезать ровно граничное тело незачем. */
    @Test
    fun `тело на пределе потолка пишется целиком`() {
        val dir = File(tmp.root, "raw")
        val atLimit = "y".repeat(MoltbookRawArchive.MAX_BODY_BYTES)
        val file = MoltbookRawArchive.record(dir, "POST", "/api/v1/posts/p/comments", 201, atLimit, 1000L)
        assertTrue(JSONObject(file!!.readText(Charsets.UTF_8)).has("body"))
    }
}