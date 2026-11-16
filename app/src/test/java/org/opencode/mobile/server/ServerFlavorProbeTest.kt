package org.opencode.mobile.server

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * Проверка сверки версии сервера с версией, зашитой в APK.
 *
 * Ключевой случай здесь не «2.0.27», а HTML-ловушка: v2 на любой неизвестный
 * путь отвечает 200 + страницей SPA. Раньше по коду ответа определялась
 * «вкус» сервера, и это почти стоило нам неверных путей; сейчас по телу
 * читается версия, а решение принимает человек. Поэтому тесты и проверяют
 * тело, а не код.
 */
class ServerFlavorProbeTest {
    /** Тело, снятое с живого opencode 2.0.27 (Android, порт 4999). */
    private val v2Info =
        """{"version":"2.0.27","pid":8733,"urls":["http://127.0.0.1:4999"],""" +
            """"paths":{"tmp":"/data/local/tmp/opencode"},""" +
            """"capabilities":{"persistentPty":true}}"""

    /** HTML-заглушка SPA: то, что v2 отдаёт на любой незнакомый путь. */
    private val spaHtml = """<!DOCTYPE html><html><head><title>opencode</title></head><body><div id="root"></div></body></html>"""

    @Test
    fun `версия читается из тела api info`() {
        assertEquals("2.0.27", ServerFlavorProbe.of(v2Info)?.version)
    }

    /** Главный регресс: HTML от v2 обязан трактоваться как «не понял». */
    @Test
    fun `html заглушки SPA не считается описанием сервера`() {
        assertNull(ServerFlavorProbe.of(spaHtml)?.version)
    }

    /** Отказ сети или 404 дают неизвестную версию, а не выдуманную. */
    @Test
    fun `пустое тело и null дают неизвестную версию`() {
        assertEquals(ServerFlavorProbe.Probe.UNKNOWN, ServerFlavorProbe.of(null))
        assertEquals(ServerFlavorProbe.Probe.UNKNOWN, ServerFlavorProbe.of(""))
        assertEquals(ServerFlavorProbe.Probe.UNKNOWN, ServerFlavorProbe.of("   "))
        assertNull(ServerFlavorProbe.of(null)?.version)
    }

    /** JSON без поля version — это описание сервера, но версии в нём нет. */
    @Test
    fun `json без версии не даёт версии`() {
        assertNull(ServerFlavorProbe.of("""{"data":[],"cursor":{}}""")?.version)
    }

    /** Мусор вместо JSON обязан отсеяться так же, как HTML. */
    @Test
    fun `не json тело не ломает разбор`() {
        assertEquals(ServerFlavorProbe.Probe.UNKNOWN, ServerFlavorProbe.of("{{{ not json"))
    }

    /** Строка с числом внутри — не версия; из неё ничего не берётся. */
    @Test
    fun `мусорное значение version не считается версией`() {
        assertEquals("20.1.0", ServerFlavorProbe.of("""{"version":"20.1.0"}""")?.version)
    }

    /** Путь сверки зафиксирован: его смена ломает проверку молча. */
    @Test
    fun `сверка ходит в api info`() {
        assertEquals("/api/info", ServerFlavorProbe.INFO_PATH)
    }

    /**
     * Смысл всей проверки: версия в APK — это база, от которой считается
     * «есть ли новее». Сверяется не с числом «2.0.27» (оно меняется с каждой
     * сменой ядра — и тест краснел на любом обновлении), а с источником правды:
     * `gradle.properties` → `opencode.binaryVersion` → `BuildConfig`.
     *
     * Второе условие — что для этой версии вообще есть пин в `tools/natives.json`:
     * иначе сборка уедет с ядром, которого нет в реестре пинов, и CI-проверка
     * «версия из APK совпадает с пином» перестанет что-либо доказывать.
     *
     * На устройстве версия может быть новее APK — ядро обновляет сам CoreUpdater,
     * и расхождение из лога ServerFlavor теперь норма, а не поломка.
     */
    @Test
    fun `версия apk задаётся сборочной конфигурацией`() {
        val version = org.opencode.mobile.BuildConfig.OPENCODE_VERSION
        assertTrue("версия не похожа на semver: $version", VERSION_SHAPE.matches(version))
        assertEquals("BuildConfig расходится с gradle.properties", gradleProperty("opencode.binaryVersion"), version)
        val pins = pins()
        assertTrue("в tools/natives.json нет пина для $version", pins.has(version))
    }

    /** Свойство из gradle.properties: тесты запускаются из каталога модуля (app). */
    private fun gradleProperty(name: String): String? {
        val candidates = listOf(File("../gradle.properties"), File("gradle.properties"))
        val file = candidates.firstOrNull { it.isFile } ?: return null
        val props = Properties()
        file.inputStream().use { props.load(it) }
        return props.getProperty(name)?.trim()
    }

    private fun pins(): JSONObject {
        val candidates = listOf(File("../tools/natives.json"), File("tools/natives.json"))
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("не нашёл tools/natives.json — проверка пинов невозможна", file != null)
        return JSONObject(file!!.readText()).getJSONObject("opencode").getJSONObject("pins")
    }

    private companion object {
        /** Только цифры и точки: «2.0.27» да, «0.0.0-dev-1297» — нет (это не релиз). */
        private val VERSION_SHAPE = Regex("""\d+\.\d+\.\d+""")
    }
}
