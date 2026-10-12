package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Что именно и по какому признаку убирает [V1Leftovers]. */
class V1LeftoversTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun configHome(): File = tmp.newFolder("opencode")

    private fun writeV1Tree(home: File) {
        File(home, "node_modules/@opencode-ai/sdk").mkdirs()
        File(home, "node_modules/@opencode-ai/sdk/package.json").writeText("""{"version":"1.18.25"}""")
        File(home, "node_modules/zod").mkdirs()
        File(home, "node_modules/zod/package.json").writeText("""{"version":"3.0.0"}""")
        File(home, "package.json").writeText("""{"dependencies":{"@opencode-ai/plugin":"1.18.25"}}""")
        File(home, "package-lock.json").writeText("""{"packages":{"node_modules/@opencode-ai/sdk":{"version":"1.18.25"}}}""")
    }

    private fun writeV2Tree(home: File) {
        File(home, "node_modules/local-mcp-dep").mkdirs()
        File(home, "node_modules/local-mcp-dep/package.json").writeText("""{"version":"4.1.0"}""")
        File(home, "package.json").writeText("""{"dependencies":{"some-local-mcp":"4.1.0"}}""")
        File(home, "package-lock.json").writeText("""{"packages":{}}""")
    }

    private fun names(files: List<File>): List<String> = files.map { it.name }.sorted()

    @Test
    fun `дерево v1 убирается целиком вместе с манифестом и локом`() {
        val home = configHome()
        writeV1Tree(home)
        assertEquals(listOf("node_modules", "package-lock.json", "package.json"), names(V1Leftovers.leftoversIn(home)))
    }

    @Test
    fun `дерево v2 не трогаем даже если каталоги выглядят так же`() {
        // Главный риск: снести зависимости, которые поставит v2 для локального MCP.
        // Отличает их только содержимое манифеста.
        val home = configHome()
        writeV2Tree(home)
        assertTrue(V1Leftovers.leftoversIn(home).isEmpty())
    }

    @Test
    fun `манифест без скоупа v1 считается чужим`() {
        val home = configHome()
        writeV2Tree(home)
        assertFalse(V1Leftovers.isV1Manifest(File(home, "package.json")))
        writeV1Tree(home)
        assertTrue(V1Leftovers.isV1Manifest(File(home, "package.json")))
    }

    @Test
    fun `без манифеста ничего не удаляем даже при наличии node_modules`() {
        // Нет признака — нет действия. Мусор переживёт один запуск, но мы не
        // снесём чужое дерево по предположению.
        val home = configHome()
        File(home, "node_modules/zod").mkdirs()
        assertTrue(V1Leftovers.leftoversIn(home).isEmpty())
    }

    @Test
    fun `несуществующий каталог конфига это не ошибка`() {
        assertTrue(V1Leftovers.leftoversIn(File(tmp.root, "нет-такого")).isEmpty())
        assertEquals(0, V1Leftovers.purge(File(tmp.root, "нет-такого")))
    }

    @Test
    fun `purge действительно удаляет и освобождает место`() {
        val home = configHome()
        writeV1Tree(home)
        File(home, "node_modules/zod/big.bin").writeBytes(ByteArray(4096))
        val before = home.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue(before > 4096)

        assertEquals(3, V1Leftovers.purge(home))

        assertFalse(File(home, "node_modules").exists())
        assertFalse(File(home, "package.json").exists())
        assertFalse(File(home, "package-lock.json").exists())
        // Конфиг, ради которого всё затевалось, не пострадал.
        File(home, "opencode.jsonc").writeText("""{"model":"opencode/big-pickle"}""")
        assertTrue(File(home, "opencode.jsonc").isFile)
    }

    @Test
    fun `повторный запуск на чистом месте ничего не делает`() {
        val home = configHome()
        writeV1Tree(home)
        assertEquals(3, V1Leftovers.purge(home))
        assertEquals(0, V1Leftovers.purge(home))
    }

    @Test
    fun `после чистки версия в манифесте больше не врёт`() {
        // Та самая причина, ради которой всё затевалось: 1.18.25 в package.json.
        val home = configHome()
        writeV1Tree(home)
        V1Leftovers.purge(home)
        assertFalse(File(home, "node_modules/@opencode-ai/sdk/package.json").exists())
    }
}
