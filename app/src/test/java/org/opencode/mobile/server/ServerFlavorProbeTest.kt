package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Проверка определения версии сервера.
 *
 * Ключевой случай здесь не «2.0.27», а HTML-ловушка: v2 на любой неизвестный
 * путь отвечает 200 + страницей SPA. Если бы определение смотрело на код
 * ответа, любой v1-сервер объявлялся бы v2 и все запросы уехали бы в `/api/…`.
 * Поэтому тесты и проверяют тело, а не код.
 */
class ServerFlavorProbeTest {
    /** Тело, снятое с живого opencode 2.0.27 (Android, порт 4999). */
    private val v2Info =
        """{"version":"2.0.27","pid":8733,"urls":["http://127.0.0.1:4999"],""" +
            """"paths":{"tmp":"/data/local/tmp/opencode"},""" +
            """"capabilities":{"persistentPty":true}}"""

    /** Тело v1 — на тот же путь сервер отдаёт 404, но проверка должна быть устойчива. */
    private val v1Info = """{"version":"1.18.25","healthy":true}"""

    /** HTML-заглушка SPA: то, что v2 отдаёт на любой незнакомый путь. */
    private val spaHtml = """<!DOCTYPE html><html><head><title>opencode</title></head><body><div id="root"></div></body></html>"""

    @Test
    fun `v2 определяется по телу api info`() {
        val probe = ServerFlavorProbe.of(v2Info)
        assertEquals(ServerFlavor.V2, probe.flavor)
        assertEquals("2.0.27", probe.version)
    }

    @Test
    fun `v1 остаётся v1`() {
        val probe = ServerFlavorProbe.of(v1Info)
        assertEquals(ServerFlavor.V1, probe.flavor)
        assertEquals("1.18.25", probe.version)
    }

    /** Главный регресс: HTML от v2 обязан трактоваться как «не понял», а не как v2. */
    @Test
    fun `html заглушки SPA не считается описанием сервера`() {
        val probe = ServerFlavorProbe.of(spaHtml)
        assertEquals(ServerFlavor.V1, probe.flavor)
        assertNull(probe.version)
    }

    /** Отказ сети или 404 на v1 дают null — это должен быть UNKNOWN, а не v2. */
    @Test
    fun `пустое тело и null дают неизвестную версию`() {
        assertEquals(ServerFlavorProbe.Probe.UNKNOWN, ServerFlavorProbe.of(null))
        assertEquals(ServerFlavorProbe.Probe.UNKNOWN, ServerFlavorProbe.of(""))
        assertEquals(ServerFlavorProbe.Probe.UNKNOWN, ServerFlavorProbe.of("   "))
        assertNull(ServerFlavorProbe.of(null)?.version)
    }

    /** JSON без поля version — описание сервера, но версии в нём нет. */
    @Test
    fun `json без версии не переключает флаг сам по себе`() {
        val probe = ServerFlavorProbe.of("""{"data":[],"cursor":{}}""")
        assertEquals(ServerFlavor.V1, probe.flavor)
        assertNull(probe.version)
    }

    /** Мусор вместо JSON обязан отсеяться так же, как HTML. */
    @Test
    fun `не json тело не ломает определение`() {
        assertEquals(ServerFlavorProbe.Probe.UNKNOWN, ServerFlavorProbe.of("{{{ not json"))
    }

    /** Префикс строгий: `20.1.0` — это не v2, а `2.0` — ещё v2. */
    @Test
    fun `префикс версии сверяется строго`() {
        assertEquals(ServerFlavor.V1, ServerFlavorProbe.of("""{"version":"20.1.0"}""").flavor)
        assertEquals(ServerFlavor.V2, ServerFlavorProbe.of("""{"version":"2.0"}""").flavor)
    }

    /** Путь детектора зафиксирован: его смена ломает определение молча. */
    @Test
    fun `детектор ходит в api info`() {
        assertEquals("/api/info", ServerFlavorProbe.INFO_PATH)
    }
}
