package org.opencode.mobile.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разведение `already_existed` на «наш ответ на этот вопрос» и «наш ответ на соседний».
 *
 * Отдельно от тестов тикера: решение чистое, журнал и сеть не нужны, и держится оно
 * ровно на одном признаке — совпал ли родитель. Если правило однажды перестанет быть
 * таким, эти тесты падают первыми.
 */
class MoltbookDedupCheckTest {
    private fun duplicate(parentId: String?, id: String = "c-1") =
        MoltbookClient.CommentOutcome.Duplicate(
            existingCommentId = id,
            existingParentId = parentId,
            status = "verified",
        )

    @Test
    fun `тот же родитель это наш ответ на этот вопрос`() {
        val result = MoltbookDedupCheck.reuseOrMisparent(duplicate("parent-a"), "parent-a")
        assertEquals(MoltbookTicker.PostResult.Reused("c-1"), result)
    }

    @Test
    fun `чужой родитель это не наш ответ на этот вопрос`() {
        val result = MoltbookDedupCheck.reuseOrMisparent(duplicate("parent-b"), "parent-a")
        val misparented = result as MoltbookTicker.PostResult.Misparented
        assertEquals("c-1", misparented.existingCommentId)
        assertEquals("parent-a", misparented.expectedParentId)
        assertEquals("parent-b", misparented.actualParentId)
    }

    @Test
    fun `отсутствие родителя это неизвестность а не совпадение`() {
        val result = MoltbookDedupCheck.reuseOrMisparent(duplicate(null), "parent-a")
        val misparented = result as MoltbookTicker.PostResult.Misparented
        assertNull(misparented.actualParentId)
    }

    @Test
    fun `чужая ветка не выглядит как наш ответ даже с тем же id`() {
        // id совпадает, статус verified — а вопрос другой. Именно этот случай раньше
        // тихо запирал ветку и писал в журнал «уже отвечено ранее».
        val first = MoltbookDedupCheck.reuseOrMisparent(duplicate("parent-a", id = "c-7"), "parent-a")
        val second = MoltbookDedupCheck.reuseOrMisparent(duplicate("parent-b", id = "c-7"), "parent-a")
        assertTrue(first is MoltbookTicker.PostResult.Reused)
        assertTrue(second is MoltbookTicker.PostResult.Misparented)
    }

    @Test
    fun `метка неизвестного родителя отличается от пустого`() {
        // Пустая строка в журнале читается как «родитель пустой», а это другая история.
        assertEquals("unknown-parent", MoltbookDedupCheck.UNKNOWN_PARENT)
        assertTrue(MoltbookDedupCheck.UNKNOWN_PARENT.isNotBlank())
    }

    @Test
    fun `ни один исход не публикует и не удаляет`() {
        val results =
            listOf(
                MoltbookDedupCheck.reuseOrMisparent(duplicate("parent-a"), "parent-a"),
                MoltbookDedupCheck.reuseOrMisparent(duplicate("parent-b"), "parent-a"),
                MoltbookDedupCheck.reuseOrMisparent(duplicate(null), "parent-a"),
            )
        results.forEach { result ->
            assertTrue(
                "исход $result не должен вести себя как публикация",
                result !is MoltbookTicker.PostResult.Done &&
                    result !is MoltbookTicker.PostResult.Verified,
            )
        }
    }
}
