package org.opencode.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Форма пути эталона конфига — условие работоспособности, а не стилистика.
 *
 * Эталон раньше лежал по `opencode-config/opencode/opencode.jsonc`, то есть по
 * форме корня конфига opencode (`Global.Path.config = <base> + "/opencode"`).
 * На внешнем хранилище opencode принимал эталон за свой каталог, запускал там
 * `bun install` и падал на EACCES при создании symlink в `node_modules/.bin`,
 * оставляя десятки мегабайт полуразобранных зависимостей после каждой попытки.
 *
 * Тест фиксирует форму, а не конкретный путь: переименовать каталог можно,
 * вернуть opencode-корень — нельзя.
 */
class ConfigMirrorPathTest {
    private val base = File("/storage/emulated/0/Documents/OpencodeTerminal/opencode")

    private fun relativeParts(file: File): List<String> =
        file.absolutePath
            .removePrefix(base.absolutePath)
            .trim('/')
            .split('/')
            .filter { it.isNotEmpty() }

    @Test
    fun `эталон не лежит в каталоге с именем opencode-config`() {
        val parts = relativeParts(OpencodeApp.ServerConfig.mirrorFileFor(base))

        assertFalse(
            "эталон не должен попадать в opencode-config: opencode примет его за свой корень",
            parts.contains("opencode-config"),
        )
    }

    @Test
    fun `эталон не содержит вложенного каталога opencode`() {
        val parts = relativeParts(OpencodeApp.ServerConfig.mirrorFileFor(base))

        assertFalse(
            "вложенный opencode/ делает путь неотличимым от Global.Path.config",
            parts.dropLast(1).contains("opencode"),
        )
    }

    @Test
    fun `эталон содержит сам файл конфига`() {
        val mirror = OpencodeApp.ServerConfig.mirrorFileFor(base)

        assertEquals(OpencodeApp.ServerConfig.CONFIG_FILE, mirror.name)
    }

    @Test
    fun `эталон лежит внутри базы, а не рядом с ней`() {
        val mirror = OpencodeApp.ServerConfig.mirrorFileFor(base)

        assertTrue(mirror.absolutePath.startsWith(base.absolutePath + File.separator))
    }

    @Test
    fun `эталон не совпадает с приватным конфигом`() {
        val mirror = OpencodeApp.ServerConfig.mirrorFileFor(base)
        val privateConfig = File(File(base, "opencode-config"), "opencode/${OpencodeApp.ServerConfig.CONFIG_FILE}")

        assertFalse("иначе зеркало писало бы файл в себя же", mirror.absolutePath == privateConfig.absolutePath)
    }
}
