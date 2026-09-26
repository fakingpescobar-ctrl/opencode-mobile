package org.opencode.mobile.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * Юнит-тесты счёта WER. Эталонные случаи посчитаны руками: смысл в том, чтобы
 * поймать именно ту ошибку, что и портила первые замеры VAD - правдоподобное
 * неправильное число вместо честного «не знаю».
 */
class WerTest {
    @Test
    fun `normalize убирает пунктуацию, регистр и приводит ё к е`() {
        assertEquals("привет как дела", Wer.normalize("Привет,  как  дела!"))
        assertEquals("нее", Wer.normalize("нее"))
        assertEquals("петь", Wer.normalize("петь"))
    }

    @Test
    fun `normalize не теряет слова-паразиты`() {
        // Паразиты остаются: иначе метрика подгоняется под удобный результат.
        assertEquals("ну вот это да", Wer.normalize("Ну, вот это да!"))
    }

    @Test
    fun `пустая строка даёт ноль слов, а не ошибку`() {
        assertEquals(0, Wer.normalize("").split(' ').count { it.isNotEmpty() })
    }

    @Test
    fun `точное совпадение даёт нулевой WER`() {
        assertEquals(0.0, Wer.of("привет как дела", "Привет, как дела!")!!, 1e-9)
    }

    @Test
    fun `одна замена из двух слов это 50 процентов`() {
        // ref=2 слова, hyp отличается одним -> 1/2
        assertEquals(0.5, Wer.of("привет мир", "привет тир")!!, 1e-9)
    }

    @Test
    fun `пропуск слова считается ошибкой и для ref и для hyp`() {
        // ref=3, hyp=2 (пропущено одно) -> 1/3
        assertEquals(1.0 / 3.0, Wer.of("раз два три", "раз три")!!, 1e-9)
    }

    @Test
    fun `вставка лишнего слова считается ошибкой`() {
        // ref=2, hyp=3 (лишнее) -> 1/2
        assertEquals(0.5, Wer.of("раз два", "раз и два")!!, 1e-9)
    }

    @Test
    fun `пустая гипотеза даёт WER равный единице`() {
        assertEquals(1.0, Wer.of("привет мир", "")!!, 1e-9)
    }

    @Test
    fun `пустой эталон возвращает null а не ноль`() {
        // Делить на ноль нельзя. «0% ошибок» на пустом эталоне - ложь,
        // которая замаскировала бы отсутствие эталонной разметки.
        assertNull(Wer.of("", "что-то"))
        assertNull(Wer.of("   ...   ", "что-то"))
    }

    @Test
    fun `пустой эталон форматируется как прочерк`() {
        assertEquals("-", Wer.format(null))
    }

    @Test
    fun `format использует точку и процент независимо от локали`() {
        // Locale.ROOT обязателен: в ru/en локали другой десятичный разделитель
        // тихо ломает последующий парсинг CSV.
        assertEquals("12.5%", Wer.format(0.125))
        val previous = Locale.getDefault()
        try {
            // Локаль с запятой-разделителем: без Locale.ROOT тест падал бы только
            // на части машин, а CSV молча получал бы другой формат числа.
            Locale.setDefault(Locale.GERMANY)
            assertEquals("12.5%", Wer.format(0.125))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun ` Levenshtein симметричен на 100 процентах`() {
        val a = listOf("a", "b", "c")
        val b = listOf("a", "x", "c", "d")
        assertEquals(Wer.distance(a, b), Wer.distance(b, a))
    }

    @Test
    fun `замена против вставки и удаления считаются по-разному`() {
        // Замена стоит 1, а пара вставка+удаление - 2. Если бы счётка не различала,
        // число молча уехало бы вдвое.
        assertEquals(1, Wer.distance(listOf("a", "b"), listOf("a", "c")))
        assertEquals(1, Wer.distance(listOf("a", "b"), listOf("a", "b", "c")))
        assertEquals(1, Wer.distance(listOf("a", "b", "c"), listOf("a", "c")))
    }
}
