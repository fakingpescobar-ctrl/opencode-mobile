package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Правила выбора рабочей папки.
 *
 * Проверяется то, что решает судьбу настройки: годная строка принимается,
 * негодная отбивается с причиной, а каталог после проверки действительно
 * существует и в него можно писать. `Context`-часть (SharedPreferences,
 * каталоги по умолчанию) без Android не проверяется — она тонкая, а вот
 * «приняли путь, в который нельзя писать» как раз и был бы тихий отказ.
 */
class WorkspacePathTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `пустой путь отбивается`() {
        assertEquals("путь пустой", WorkspacePath.syntaxProblem("   "))
    }

    @Test
    fun `относительный путь отбивается`() {
        assertNotNull(WorkspacePath.syntaxProblem("Documents/project"))
    }

    @Test
    fun `ссылка content отбивается отдельной причиной`() {
        val problem = WorkspacePath.syntaxProblem("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
        assertTrue(problem.orEmpty().contains("ссылка"))
    }

    @Test
    fun `корень файловой системы не принимается`() {
        assertNotNull(WorkspacePath.syntaxProblem("/"))
    }

    @Test
    fun `переход вверх по дереву отбивается`() {
        assertNotNull(WorkspacePath.syntaxProblem("/storage/emulated/0/../0/Documents"))
    }

    @Test
    fun `слишком длинный путь отбивается`() {
        assertNotNull(WorkspacePath.syntaxProblem("/" + "a".repeat(5000)))
    }

    @Test
    fun `обычный абсолютный путь принимается`() {
        assertNull(WorkspacePath.syntaxProblem("/storage/emulated/0/Documents/OpencodeTerminal"))
    }

    @Test
    fun `подготовка создаёт вложенные каталоги`() {
        val target = File(temp.root, "a/b/c")
        val prepared = WorkspacePath.prepare(target.absolutePath)
        assertTrue(prepared.ok)
        assertNull(prepared.problem)
        assertEquals(target, prepared.dir)
        assertTrue(target.isDirectory)
    }

    @Test
    fun `пробный файл не остаётся в каталоге`() {
        val target = temp.newFolder("project")
        assertTrue(WorkspacePath.prepare(target.absolutePath).ok)
        assertTrue(target.listFiles().isNullOrEmpty())
    }

    @Test
    fun `завершающий слеш не мешает`() {
        val target = temp.newFolder("with-slash")
        val prepared = WorkspacePath.prepare(target.absolutePath + "/")
        assertTrue(prepared.ok)
        assertEquals(target, prepared.dir)
    }

    @Test
    fun `файл вместо каталога отбивается`() {
        val file = temp.newFile("not-a-dir.txt")
        val prepared = WorkspacePath.prepare(file.absolutePath)
        assertFalse(prepared.ok)
        assertTrue(prepared.problem.orEmpty().contains("файл"))
    }

    @Test
    fun `каталог без права записи отбивается`() {
        val readOnly = temp.newFolder("read-only")
        readOnly.setWritable(false)
        try {
            // Под root права игнорируются — тогда проверка «не пишется» ничего не
            // доказывает, и тест честнее пропустить, чем объявить успехом.
            assumeFalse("под root права на запись не действуют", readOnly.canWrite())
            val prepared = WorkspacePath.prepare(File(readOnly, "nested").absolutePath)
            assertFalse(prepared.ok)
            assertNotNull(prepared.problem)
        } finally {
            readOnly.setWritable(true)
        }
    }
}
