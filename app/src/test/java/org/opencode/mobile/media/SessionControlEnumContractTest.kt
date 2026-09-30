package org.opencode.mobile.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Сверяет enum `session_control` в ynison.js с MediaCommand на стороне Kotlin.
 *
 * Источник истины тут один — Kotlin, а JS-строка существует отдельно и молча разъезжается с
 * ней: ничего не падает, просто агент шлёт `stop`, которого на мосте нет, и получает 400 на
 * команду, которую человек сформулировал правильно. Ровно тот класс расхождения, который
 * стоил C2, поэтому сверка делается в тесте, а не «на глаз при правке enum».
 *
 * Тест читает реальный assets-файл из репозитория. Если он не найден, тест падает с внятным
 * сообщением, а не молча проходит: иначе после переноса каталога проверка исчезла бы вместе
 * с путём к ней.
 */
class SessionControlEnumContractTest {
    private val ynisonSource: String = locateSource()

    private fun locateSource(): String {
        // От `app/src/test/java/org/opencode/mobile/media` поднимаемся к корню модуля.
        val candidates =
            listOf(
                File("../../../../main/assets/mcp/ynison.js"),
                File("src/main/assets/mcp/ynison.js"),
                File("app/src/main/assets/mcp/ynison.js"),
            )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue(
            "ynison.js не найден ни по одному из путей ${candidates.map { it.path }} - проверка enum молча выпала",
            found != null,
        )
        return found!!.readText()
    }

    /** Достаёт список из строки вида `enum: ["play", "pause", ...]`. */
    private fun jsEnumFor(tool: String): List<String> {
        val anchor = "name: \"$tool\""
        val start = ynisonSource.indexOf(anchor)
        assertTrue("инструмент $tool не найден в ynison.js", start >= 0)
        val enumAt = ynisonSource.indexOf("enum: [", start)
        assertTrue("у инструмента $tool нет enum - схема изменилась, тест надо поправить", enumAt >= 0)
        val open = ynisonSource.indexOf("[", enumAt)
        val close = ynisonSource.indexOf("]", open)
        assertTrue("enum у $tool не закрыт", close > open)
        val inside = ynisonSource.substring(open + 1, close)
        val cleaned = inside.split(",").map { it.trim().trim('"', '\'', ' ') }
        return cleaned.filter { it.isNotEmpty() }
    }

    @Test
    fun `js session_control enum matches MediaCommand wire names exactly`() {
        val kotlin = MediaCommand.values().map { it.wireName }
        val js = jsEnumFor("session_control")

        assertEquals(
            "session_control в ynison.js разошёлся с MediaCommand: $js против $kotlin. " +
                "Агент будет слать команду, которой нет на мосте, и получит 400 на корректный запрос.",
            kotlin,
            js,
        )
    }

    @Test
    fun `every js enum value maps to a real MediaCommand`() {
        // Обратная проверка: если в enum попадёт опечатка, set-сравнение её поймает, но с
        // бесполезным сообщением. Здесь видно, КАКОЕ именно значение не резолвится.
        for (name in jsEnumFor("session_control")) {
            assertTrue(
                "значение \"$name\" из session_control не резолвится в MediaCommand - " +
                    "мост его не обслужит",
                MediaCommand.fromWireName(name) != null,
            )
        }
    }

    @Test
    fun `order matches too - the enum is the agent's visible contract`() {
        // Порядок тоже не должен «случайно» отличаться: список печатается в ошибке
        // `action must be one of: ...`, и агент читает его как документацию.
        val kotlin = MediaCommand.values().map { it.wireName }
        assertEquals(kotlin, jsEnumFor("session_control"))
    }
}
