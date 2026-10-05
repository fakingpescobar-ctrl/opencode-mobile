package org.opencode.mobile.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Числа в русские слова перед синтезом.
 *
 * Это не косметика: Supertonic произносит «59 записей» по-английски, а мобильный
 * агент состоит из чисел почти целиком. Тесты держат три вещи, которые легко
 * сломать незаметно:
 *
 *  - **формы множественного числа.** «21 запись», «2 записи», «5 записей» и
 *    «тысяча/тысячи/тысяч» — это грамматика, а не счёт: число 11 здесь
 *    внезапно «одиннадцать записей»;
 *  - **неприклеенные идентификаторы.** «AArch64», «utf8», «H.264» читать
 *    словами нельзя, это имена;
 *  - **версии.** «2.0.15» читается поразрядно: «два ноль точка пятнадцать».
 */
class RuNumbersTest {
    // ---------- integer(): разряды и склонения ----------

    @Test
    fun integer_zeroIsSpelled() {
        assertEquals("ноль", RuNumbers.integer(0))
    }

    @Test
    fun integer_negativeIsNotSpoken() {
        assertNull(RuNumbers.integer(-1))
        assertNull(RuNumbers.integer(Long.MIN_VALUE))
    }

    @Test
    fun integer_onesAndTeens() {
        assertEquals("один", RuNumbers.integer(1))
        assertEquals("два", RuNumbers.integer(2))
        assertEquals("десять", RuNumbers.integer(10))
        assertEquals("одиннадцать", RuNumbers.integer(11))
        assertEquals("девятнадцать", RuNumbers.integer(19))
    }

    @Test
    fun integer_tensUseTensTable() {
        assertEquals("двадцать", RuNumbers.integer(20))
        assertEquals("сорок пять", RuNumbers.integer(45))
        assertEquals("девяносто девять", RuNumbers.integer(99))
    }

    @Test
    fun integer_hundreds() {
        assertEquals("сто", RuNumbers.integer(100))
        assertEquals("сто один", RuNumbers.integer(101))
        assertEquals("двести пятьдесят", RuNumbers.integer(250))
        assertEquals("девятьсот девяносто девять", RuNumbers.integer(999))
    }

    /** «тысяча» женского рода: 2000 = «две тысячи», а не «два тысячи». */
    @Test
    fun integer_thousandsAreFeminine() {
        assertEquals("тысяча", RuNumbers.integer(1_000))
        assertEquals("тысяча один", RuNumbers.integer(1_001))
        assertEquals("две тысячи", RuNumbers.integer(2_000))
        assertEquals("пять тысяч", RuNumbers.integer(5_000))
        assertEquals("двадцать одна тысяча", RuNumbers.integer(21_000))
        assertEquals("сто тысяч", RuNumbers.integer(100_000))
    }

    @Test
    fun integer_bigScales() {
        assertEquals("один миллион", RuNumbers.integer(1_000_000))
        assertEquals("два миллиона", RuNumbers.integer(2_000_000))
        assertEquals("пять миллионов", RuNumbers.integer(5_000_000))
        assertEquals("один миллиард", RuNumbers.integer(1_000_000_000))
        assertEquals("два миллиарда", RuNumbers.integer(2_000_000_000))
        assertEquals("один триллион", RuNumbers.integer(1_000_000_000_000))
    }

    /** Разряды склеиваются, а не теряются: «триллион пятьсот миллиардов». */
    @Test
    fun integer_scalesCombine() {
        assertEquals("один триллион пятьсот миллиардов", RuNumbers.integer(1_500_000_000_000))
    }

    // ---------- convert(): числа в тексте ----------

    @Test
    fun convert_textWithoutDigitsIsUntouched() {
        assertEquals("", RuNumbers.convert(""))
        assertEquals("ничего тут нет", RuNumbers.convert("ничего тут нет"))
    }

    @Test
    fun convert_numberInSentence() {
        assertEquals("пятьдесят девять записей", RuNumbers.convert("59 записей"))
        assertEquals("Android шестнадцать", RuNumbers.convert("Android 16"))
    }

    @Test
    fun convert_decimalWithComma() {
        assertEquals("семьдесят шесть целых три десятых с", RuNumbers.convert("76,3 с"))
        assertEquals("ноль целых двадцать пять сотых", RuNumbers.convert("0,25"))
    }

    /**
     * Известная шероховатость, зафиксированная чтобы не потерялась: у дробной
     * части своё склонение («целых»), а у целого — то же слово. 1,5 корректно
     * читается «одна целая пять десятых». Пока чиним только detekt, поведение
     * не трогаем — тест служит маяком: когда поправят, упадёт здесь.
     */
    @Test
    fun convert_knownRoughEdge_wholePartAlwaysPlural() {
        assertEquals("один целых пять десятых", RuNumbers.convert("1,5"))
    }

    /** Проценты склоняются по самому числу: 1 процент, 2 процента, 5 процентов. */
    @Test
    fun convert_percentInflects() {
        assertEquals("один процент", RuNumbers.convert("1%"))
        assertEquals("два процента", RuNumbers.convert("2%"))
        assertEquals("пять процентов", RuNumbers.convert("5%"))
        assertEquals("одиннадцать процентов", RuNumbers.convert("11%"))
        assertEquals("двадцать один процент", RuNumbers.convert("21%"))
        assertEquals("восемьдесят пять процентов", RuNumbers.convert("85%"))
    }

    /** Версии читаются поразрядно, без «целых» и без молчаливого пропуска. */
    @Test
    fun convert_versionIsReadDigitwise() {
        assertEquals("два точка ноль точка пятнадцать", RuNumbers.convert("2.0.15"))
        assertEquals("один точка два точка три точка четыре", RuNumbers.convert("1.2.3.4"))
    }

    /** Цифры внутри идентификаторов — часть имени, читать их словами нельзя. */
    @Test
    fun convert_identifiersStayUntouched() {
        assertEquals("AArch64", RuNumbers.convert("AArch64"))
        assertEquals("utf8", RuNumbers.convert("utf8"))
        assertEquals("base64", RuNumbers.convert("base64"))
        assertEquals("gpt-4", RuNumbers.convert("gpt-4"))
        assertEquals("H.264", RuNumbers.convert("H.264"))
        assertEquals("5кг", RuNumbers.convert("5кг"))
    }

    /** Числа длиннее лимита и неоднозначный разделитель остаются как есть. */
    @Test
    fun convert_unsupportedShapesStayUntouched() {
        assertEquals("1234567890123456", RuNumbers.convert("1234567890123456"))
        assertEquals("1,234.56", RuNumbers.convert("1,234.56"))
    }

    /** Хвостовые точки/запятые не входят в число, но остаются в тексте. */
    @Test
    fun convert_trailingSeparatorStays() {
        assertEquals("один.", RuNumbers.convert("1."))
        assertEquals("сорок два.", RuNumbers.convert("42."))
    }

    /** Запятая с пробелом — это список, а не десятичная дробь. */
    @Test
    fun convert_commaFollowedBySpaceIsListSeparator() {
        assertEquals("модель пятнадцать, пять ГБ", RuNumbers.convert("модель 15, 5 ГБ"))
    }

    /** Минус не читается словом, но число после него — читается. */
    @Test
    fun convert_negativeKeepsSignChar() {
        assertEquals("-сорок два", RuNumbers.convert("-42"))
    }
}
