package org.opencode.mobile.tts

/**
 * Перевод чисел в русские слова перед синтезом.
 *
 * Зачем: TTS-движок Supertonic, получив «59 записей», произносит это
 * по-английски («fifty-nine»). Для русской озвучки это звучит чужеродно, и
 * тем заметнее, что текст мобильного агента сплошь состоит из чисел — счётчики
 * памяти, размеры, времена, версии.
 *
 * Что НЕ трогаем:
 *  - куски, где цифры сидят вплотную в латинице (AArch64, utf8, base64) — это
 *    идентификаторы, и читать их словами бессмысленно;
 *  - номера версий вида 2.0.15 — их читают поразрядно, «два точка ноль точка
 *    пятнадцать»;
 *  - числа неразобранные (длиннее [MAX_DIGITS]) — чтобы не зависнуть на мусоре.
 *
 * Функция чистая и детерминированная: один и тот же текст всегда даёт один и
 * тот же результат, поэтому её можно тестировать без движка.
 *
 * Подавление [TooManyFunctions] честное: это одна замкнутая грамматика — таблицы
 * разрядов, правила склонений и разбор токена. Разносить её по файлам ради
 * счётчика нельзя, читать её как единое целое полезнее, чем соблюдать лимит.
 */
@Suppress("TooManyFunctions")
object RuNumbers {
    /** Длиннее — считаем мусором и оставляем как есть. */
    private const val MAX_DIGITS = 15

    /** Больше трёх знаков после запятой — уже не «три десятых», а точность прибора. */
    private const val MAX_FRACTION_DIGITS = 3

    // ---- Правила склонений. Числа здесь не «магические», а грамматические:
    // русская форма выбирается по двум последним цифрам, и «11»/«12» — особые.

    /** Делим на него, чтобы взять последнюю цифру. */
    private const val LAST_DIGIT_BASE = 10L

    /** Делим на него, чтобы взять последние две цифры. */
    private const val LAST_TWO_BASE = 100L

    private const val LAST_DIGIT_ONE = 1L
    private const val FEW_FIRST = 2L
    private const val FEW_LAST = 4L
    private const val TEENS_LOW = 11L
    private const val TEENS_FEW_LOW = 12L
    private const val TEENS_FEW_HIGH = 14L

    // ---- Разряды самого числа.

    private const val HUNDRED = 100
    private const val TEN = 10
    private const val TEEN_LOW = 10
    private const val TEEN_HIGH = 19
    private const val THOUSAND = 1_000L
    private const val MILLION = 1_000_000L
    private const val BILLION = 1_000_000_000L
    private const val TRILLION = 1_000_000_000_000L

    /** Индекс тысяч в [SCALES] — там нужна женская форма. */
    private const val THOUSAND_INDEX = 3

    /** Сколько кусков максимум набирается в одной группе: сотни + десятки + единицы. */
    private const val GROUP_PARTS = 3

    /** Сколько разрядов входит в название числа вместе с хвостом. */
    private const val SCALE_PARTS = 5

    /** Запас в StringBuilder, чтобы не расти в геометрической прогрессии. */
    private const val OUTPUT_SLACK = 32

    /** Длина дробной части, по ней выбирается знаменатель. */
    private const val FIRST_DIGIT = 1
    private const val SECOND_DIGIT = 2
    private const val THIRD_DIGIT = 3

    /** Сколько точек делают токен версией: 2.0.15 — две. */
    private const val VERSION_DOTS = 2

    /** Символ, которым склеены цифры в идентификаторе: AArch64, utf-8. */
    private const val GLUE_CHARS = ".-_"

    private const val DOT = '.'
    private const val COMMA = ','
    private const val PERCENT_SIGN = '%'
    private const val VERSION_SEPARATOR = " точка "

    private val ONES = arrayOf(
        "",
        "один",
        "два",
        "три",
        "четыре",
        "пять",
        "шесть",
        "семь",
        "восемь",
        "девять",
        "десять",
        "одиннадцать",
        "двенадцать",
        "тринадцать",
        "четырнадцать",
        "пятнадцать",
        "шестнадцать",
        "семнадцать",
        "восемнадцать",
        "девятнадцать",
    )

    private val TEENS = arrayOf("", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто")
    private val HUNDREDS = arrayOf("", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот")

    /** «Две тысячи», а не «два тысячи»: тысячи — женского рода. */
    private val FEMININE_ONES = mapOf(1 to "одна", 2 to "две")

    /** Знаменатель дробной части выбирается по числу знаков: 3 → «десятых». */
    private val FRACTION_DENOMINATORS = mapOf(FIRST_DIGIT to "десятых", SECOND_DIGIT to "сотых", THIRD_DIGIT to "тысячных")

    private val SCALES = longArrayOf(TRILLION, BILLION, MILLION, THOUSAND)

    private val SCALE_NAMES =
        arrayOf(
            arrayOf("триллион", "триллиона", "триллионов"),
            arrayOf("миллиард", "миллиарда", "миллиардов"),
            arrayOf("миллион", "миллиона", "миллионов"),
            arrayOf("тысяча", "тысячи", "тысяч"),
        )

    private fun isDigits(s: String): Boolean = s.isNotEmpty() && s.all { it.isDigit() }

    /** Цифра или разделитель внутри числа: так набирается токен «76,3». */
    private fun isNumberChar(c: Char): Boolean = c.isDigit() || c == DOT || c == COMMA

    /**
     * Множественные формы: [1 записей] нельзя — нужно «1 запись».
     *
     * Смотрим на две последние цифры, а не на одну: 11 записей — это «одиннадцать
     * записей», а не «одиннадцать запись».
     */
    private fun plural(
        n: Long,
        one: String,
        few: String,
        many: String,
    ): String {
        val last = n % LAST_DIGIT_BASE
        val lastTwo = n % LAST_TWO_BASE
        return when {
            last == LAST_DIGIT_ONE && lastTwo != TEENS_LOW -> one
            last in FEW_FIRST..FEW_LAST && lastTwo !in TEENS_FEW_LOW..TEENS_FEW_HIGH -> few
            else -> many
        }
    }

    /** 0..999. [feminine] — женская форма («две тысячи», а не «два тысячи»). */
    private fun underThousand(
        n: Int,
        feminine: Boolean = false,
    ): String {
        if (n == 0) return "ноль"
        val parts = ArrayList<String>(GROUP_PARTS)
        val hundreds = n / HUNDRED
        if (hundreds > 0) parts.add(HUNDREDS[hundreds])
        // Хвост может быть пустым («сто», «двести пятьдесят»), и пустую часть
        // добавлять нельзя: в склейке появятся лишние пробелы.
        val rest = tail(n % HUNDRED, feminine)
        if (rest.isNotEmpty()) parts.add(rest)
        return parts.joinToString(" ")
    }

    /** Две младшие цифры: 15 → «пятнадцать», 42 → «сорок два». */
    private fun tail(
        rest: Int,
        feminine: Boolean,
    ): String {
        // 10..19 одним словом. Раньше здесь стояло 11..19, и «десять» проваливалось
        // в ветку десятков, где для него нет слова: integer(10) давал пустую строку.
        if (rest in TEEN_LOW..TEEN_HIGH) return ONES[rest]
        val parts = ArrayList<String>(GROUP_PARTS - 1)
        val tens = rest / TEN
        if (tens > 0) parts.add(TEENS[tens])
        val ones = rest % TEN
        if (ones > 0) parts.add(if (feminine) FEMININE_ONES[ones] ?: ONES[ones] else ONES[ones])
        return parts.joinToString(" ")
    }

    /**
     * Целое число до триллионов. [thousands] — женская форма для тысяч
     * («две тысячи», «одна тысяча»), потому что дальше идёт «запись/записи».
     */
    fun integer(n: Long): String? =
        when {
            n < 0L -> null
            n == 0L -> "ноль"
            else -> readScales(n)
        }

    private fun readScales(n: Long): String {
        val out = ArrayList<String>(SCALE_PARTS)
        var rest = n
        for (i in SCALES.indices) {
            val part = rest / SCALES[i]
            if (part == 0L) continue
            rest %= SCALES[i]
            out.addAll(scaleWords(i, part))
        }
        // хвост до 999
        if (rest > 0L) out.add(underThousand(rest.toInt()))
        return out.joinToString(" ")
    }

    private fun scaleWords(
        index: Int,
        part: Long,
    ): List<String> {
        val names = SCALE_NAMES[index]
        val isThousand = index == THOUSAND_INDEX
        // «тысяча» — женского рода и в единственном числе звучит сама по
        // себе: 1000 = «тысяча», а не «один тысяча».
        if (isThousand && part == 1L) return listOf(names[0])
        val words = ArrayList<String>(GROUP_PARTS)
        words.add(underThousand(part.toInt(), isThousand))
        words.add(plural(part, names[0], names[1], names[2]))
        return words
    }

    /** Десятичная дробь: 76,3 → «семьдесят шесть целых три десятых». */
    private fun fraction(fracDigits: String): String? =
        fracDigits.toIntOrNull()?.let { n ->
            if (n == 0) {
                "ноль"
            } else {
                FRACTION_DENOMINATORS[fracDigits.length]?.let { denom -> "${underThousand(n)} $denom" }
            }
        }

    /**
     * Цифра приклеена к идентификатору? Смотрим влево сквозь «.», «-», «_».
     *
     * Ловит AArch64, utf-8, gpt-4, H.264 — за «словом» с буквами цифры
     * считаются частью имени, а не числом. Обычные «59 записей», «Android 16»
     * и «76,3 с» начинаются после пробела или открывающей скобки и остаются
     * числами.
     */
    private fun gluedToLetters(
        s: String,
        i: Int,
    ): Boolean {
        var k = i - 1
        // Через ВСЮ идущую подряд группу цифр: иначе в «AArch64» первая
        // шестёрка отскочила бы как буква, а четвёрку мы бы превратили.
        while (k >= 0 && (s[k].isDigit() || s[k] in GLUE_CHARS)) k--
        return k >= 0 && s[k].isLetter()
    }

    /**
     * Разбирает один числовой токен (без окружающего текста).
     * null — токен трогать нельзя: версия, идентификатор или слишком длинное число.
     */
    private fun token(word: String): String? = if (word.count { it == DOT } >= VERSION_DOTS) versionToken(word) else plainToken(word)

    /** 2.0.15 → «два точка ноль точка пятнадцать»: поразрядно, без «целых». */
    private fun versionToken(word: String): String? {
        val spoken = word.split(DOT).map { group -> versionGroup(group) }
        if (spoken.any { it == null }) return null
        return spoken.filterNotNull().joinToString(VERSION_SEPARATOR)
    }

    private fun versionGroup(group: String): String? = if (isDigits(group)) group.toLongOrNull()?.let { integer(it) } else null

    private fun plainToken(word: String): String? {
        val dot = word.indexOf(DOT)
        val comma = word.indexOf(COMMA)
        // «1,234.56» — не наш случай: непонятно, где запятая, где точка.
        if (dot >= 0 && comma >= 0) return null
        val sep = if (dot >= 0) dot else comma
        return if (sep < 0) wholeToken(word) else decimalToken(word, sep)
    }

    private fun wholeToken(word: String): String? =
        if (isDigits(word) && word.length <= MAX_DIGITS) {
            word.toLongOrNull()?.let { integer(it) }
        } else {
            null
        }

    private fun decimalToken(
        word: String,
        sep: Int,
    ): String? {
        val whole = word.substring(0, sep)
        val frac = word.substring(sep + 1)
        if (!isWholePart(whole) || !isFractionPart(frac)) return null
        return whole
            .toLongOrNull()
            ?.let { integer(it) }
            ?.let { spoken -> fraction(frac)?.let { tail -> "$spoken целых $tail" } }
    }

    private fun isWholePart(s: String): Boolean = isDigits(s) && s.length <= MAX_DIGITS

    private fun isFractionPart(s: String): Boolean = isDigits(s) && s.length <= MAX_FRACTION_DIGITS

    /** Один кусок разбора: что вставить в вывод и сколько символов исходника съесть. */
    private data class Step(
        val text: String,
        val consumed: Int,
    )

    /**
     * Основная точка входа: прогоняет весь текст и заменяет числа словами.
     *
     * Проценты и «процента/процентов» обрабатываются по соседству с числом,
     * поэтому при входе «85%» на выходе будет «восемьдесят пять процентов», а
     * не «восемьдесят пять процент».
     */
    fun convert(input: String): String {
        if (input.none { it.isDigit() }) return input
        val out = StringBuilder(input.length + OUTPUT_SLACK)
        var i = 0
        while (i < input.length) {
            val step = step(input, i)
            out.append(step.text)
            i += step.consumed
        }
        return out.toString()
    }

    /** Цифра начинается только на стыке не-буквы: AArch64 и H.264 остаются как есть. */
    private fun step(
        input: String,
        at: Int,
    ): Step = if (input[at].isDigit() && !gluedToLetters(input, at)) numericStep(input, at) else Step(input[at].toString(), 1)

    private fun numericStep(
        input: String,
        at: Int,
    ): Step {
        var end = at
        while (end < input.length && isNumberChar(input[end])) end++
        // хвостовые точки/запятые не входят в число
        val trimmed = input.substring(at, end).trimEnd(DOT, COMMA)
        // если сразу после цифр идёт буква — это идентификатор вроде utf8
        if (trimmed.isEmpty() || (end < input.length && input[end].isLetter())) {
            return Step(trimmed, trimmed.length)
        }
        val percentAt = at + trimmed.length
        val isPercent = input.getOrNull(percentAt) == PERCENT_SIGN
        val spoken = if (isPercent) withPercent(trimmed) else token(trimmed)
        // пропускаем и сам знак «%», иначе он останется в тексте
        val consumed = if (spoken != null && isPercent) percentAt + 1 - at else trimmed.length
        return Step(spoken ?: trimmed, consumed)
    }

    /** «85%» → «восемьдесят пять процентов», но «1%» → «один процент». */
    private fun withPercent(word: String): String? =
        token(word)?.let { spoken ->
            word
                .split(DOT, COMMA)
                .first()
                .toLongOrNull()
                ?.let { value -> "$spoken ${plural(value, "процент", "процента", "процентов")}" }
        }
}
