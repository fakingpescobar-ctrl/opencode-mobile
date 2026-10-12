package org.opencode.mobile.tts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Границы резания текста.
 *
 * Фикстуры латиницей намеренно: кириллица в исходнике теста однажды не доехала
 * при записи файла и молча превратилась в «��», а нарезка от алфавита не зависит
 * ни одной величиной. Проверять надо геометрию знаков, а не буквы.
 *
 * Тесты не «на новое поведение», а на совпадение со старым разбором: приоритет
 * мягких границ там был неочевиден (мягкая граница предпочиталась более ранней
 * переводной строке), и при переносе в отдельный файл это единственное, что
 * могло тихо поменяться.
 */
class SentenceBoundariesTest {
    @Test
    fun `точка без пробела после неё границей не считается`() {
        assertEquals(0, findSentenceBoundary("a.b c", 90))
        assertEquals(0, findSentenceBoundary("3.14", 90))
    }

    @Test
    fun `знак конца фразы перед пробелом режет`() {
        assertEquals(3, findSentenceBoundary("ab. longer text here", 90))
    }

    @Test
    fun `знак конца фразы в самом конце текста режет`() {
        assertEquals(3, findSentenceBoundary("ab!", 90))
        assertEquals(3, findSentenceBoundary("ab?", 90))
    }

    @Test
    fun `перевод строки режет`() {
        assertEquals(3, findSentenceBoundary("ab\nlonger", 90))
    }

    @Test
    fun `двоеточие и точка с запятой режут`() {
        assertEquals(3, findSentenceBoundary("ab: more", 90))
        assertEquals(3, findSentenceBoundary("ab; more", 90))
    }

    @Test
    fun `самая ранняя граница в пределах лимита побеждает любую по виду`() {
        // Двоеточие на 3, точка на 8 — обе в лимите, берётся более ранняя.
        assertEquals(3, findSentenceBoundary("ab: cd. more text", 90))
    }

    @Test
    fun `жёсткая граница дальше лимита всё равно режет`() {
        // Старый разбор возвращал конец предложения и за лимитом: резать по пробелу
        // решает вызывающий, если границы в пределах лимита не нашлось.
        assertEquals(3, findSentenceBoundary("ab. longer text here", 1))
    }

    @Test
    fun `мягкая граница раньше конца предложения берётся`() {
        // Точка с запятой на 3, точка на 10: лимита хватает и там и там,
        // значит победит более ранняя — точка с запятой.
        assertEquals(3, findSentenceBoundary("ab; cd. more", 90))
    }

    @Test
    fun `приоритет мягких границ совпадает с исходным разбором`() {
        // Здесь старый код выбирал точку с запятой на 3, хотя перевод строки стоит
        // раньше, на 2. Это выглядит как ошибка, но это поведение, на котором
        // обучена озвучка; менять его можно только вместе с проверкой на слух.
        val text = "a\nb; c. d"
        assertEquals(2, findSentenceBoundary(text, 90))
        assertEquals(referenceBoundary(text, 90), findSentenceBoundary(text, 90))
    }

    @Test
    fun `эталон и новый разбор совпадают на наборе`() {
        val samples =
            listOf(
                "just text without marks",
                "a. b. c.",
                "a? b! c.",
                "a\nb\nc",
                "a: b; c.",
                "a\nb; c. d",
                "a.b c: d",
                "\n\n\n",
                "a: b; c! d?",
                "abc.",
                ".",
                "\n",
                ";",
                "",
            )
        for (text in samples) {
            for (limit in listOf(1, 3, 8, 20, 90, 500)) {
                assertEquals(
                    "text=$text limit=$limit",
                    referenceBoundary(text, limit),
                    findSentenceBoundary(text, limit),
                )
            }
        }
    }

    /**
     * Дословный перенос старого `findBoundary`: та же логика, те же приоритеты.
     * Живёт только ради сверки, в приложение не идёт. Сложность и число
     * возвратов здесь неизбежны — это буквальная копия того кода, который
     * переписывается, и «причесать» его значило бы сверять уже не с тем.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth")
    private fun referenceBoundary(
        text: String,
        limit: Int,
    ): Int {
        var soft: Int? = null
        var hard: Int? = null
        var softish: Int? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?') {
                val next = text.getOrNull(i + 1)
                if (next == null || next == ' ' || next == '\n') {
                    hard = i + 1
                    if (hard <= limit) return hard
                    break
                }
            }
            if (c == '\n') {
                softish = i + 1
                if (softish <= limit) return softish
            }
            if (c == ';' || c == ':') {
                soft = i + 1
                if (soft <= limit) return soft
            }
            i++
        }
        soft?.let { if (hard == null || it < hard) return it }
        softish?.let { if (hard == null || it < hard) return it }
        return hard ?: 0
    }
}
