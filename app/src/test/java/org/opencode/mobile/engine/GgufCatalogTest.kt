package org.opencode.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Чистые JVM-тесты каталога: Android здесь не нужен, org.json есть в testImplementation. */
class GgufCatalogTest {
    private val sha = "a".repeat(64)
    private val sha2 = "b".repeat(64)

    private fun row(
        id: String = "qwen3-0.6b-q4km",
        file: String = "Qwen3-0.6B-Q4_K_M.gguf",
        size: Long = 396705472L,
        hash: String = sha,
        url: String = "https://huggingface.co/x/resolve/main/model.gguf",
    ): String =
        """{"id":"$id","repo":"unsloth/Qwen3-0.6B-GGUF","file":"$file",""" +
            """"size":$size,"sha256":"$hash","url":"$url"}"""

    private fun json(vararg rows: String): String = rows.joinToString(",", "[", "]")

    @Test
    fun parseReadsAllFields() {
        val list = GgufCatalog.parse(json(row(), row("smollm2-135m-q4km", "SmolLM2.gguf", 105L, sha2)))
        assertEquals(2, list.size)
        assertEquals("qwen3-0.6b-q4km", list[0].id)
        assertEquals("unsloth/Qwen3-0.6B-GGUF", list[0].repo)
        assertEquals("Qwen3-0.6B-Q4_K_M.gguf", list[0].file)
        assertEquals(396705472L, list[0].size)
        assertEquals(sha, list[0].sha256)
        assertTrue(list[0].url.startsWith("https://"))
        assertEquals(sha2, list[1].sha256)
    }

    @Test
    fun preferredIsFixedByQualityGate() {
        assertEquals("qwen3-0.6b-q4km", GgufCatalog.preferred())
    }

    @Test
    fun rejectsTraversalAndNonGguf() {
        for (bad in listOf("../escape.gguf", "sub/model.gguf", "c:\\x.gguf", "model.bin", "..")) {
            assertRejected(json(row(file = bad)), "имя модели")
        }
    }

    @Test
    fun rejectsBadHashUrlSizeAndDuplicates() {
        assertRejected(json(row(hash = "abc")), "sha256")
        assertRejected(json(row(hash = "z".repeat(64))), "sha256")
        assertRejected(json(row(url = "http://huggingface.co/x")), "https")
        assertRejected(json(row(size = 0L)), "размер")
        assertRejected(json(row(), row()), "дубль")
        assertRejected("[]", "пуст")
    }

    private fun assertRejected(
        value: String,
        hint: String,
    ) {
        try {
            GgufCatalog.parse(value)
            fail("ожидали отказ: $hint")
        } catch (e: IllegalArgumentException) {
            assertTrue("у отказа должно быть объяснение ($hint)", e.message.orEmpty().isNotBlank())
        }
    }
}
