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
 *  - номера версий вида 2.0.15 — их читают поразрядно, «два ноль пятнадцать»;
 *  - числа неразобранные (длиннее [MAX_DIGITS]) — чтобы не зависнуть на мусоре.
 *
 * Функция чистая и детерминированная: один и тот же текст всегда даёт один и
 * тот же результат, поэтому её можно тестировать без движка.
 */
object RuNumbers {

    /** Длиннее — считаем мусором и оставляем как есть. */
    private const val MAX_DIGITS = 15

    private val ONES = arrayOf(
        "", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
        "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
        "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать",
    )

    private val TEENS = arrayOf("", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто")
    private val HUNDREDS = arrayOf("", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот")

    /** Множественные формы: [1 записей] нельзя — нужно «1 запись». */
    private fun plural(n: Long, one: String, few: String, many: String): String = when {
        n % 10L == 1L && n % 100L != 11L -> one
        n % 10L in 2L..4L && n % 100L !in 12L..14L -> few
        else -> many
    }

    /** 0..999. [feminine] — женская форма («две тысячи», а не «два тысячи»). */
    private fun underThousand(n: Int, feminine: Boolean = false): String {
        if (n == 0) return "ноль"
        val h = n / 100
        val rest = n % 100
        val parts = ArrayList<String>(3)
        if (h > 0) parts.add(HUNDREDS[h])
        if (rest in 11..19) {
            parts.add(ONES[rest])
        } else {
            val t = rest / 10
            val u = rest % 10
            if (t > 0) parts.add(TEENS[t])
            if (u > 0) {
                parts.add(
                    when {
                        feminine && u == 1 -> "одна"
                        feminine && u == 2 -> "две"
                        else -> ONES[u]
                    },
                )
            }
        }
        return parts.joinToString(" ")
    }

    /**
     * Целое число до триллионов. [thousands] — женская форма для тысяч
     * («две тысячи», «одна тысяча»), потому что дальше идёт «запись/записи».
     */
    fun integer(n: Long): String? {
        if (n < 0) return null
        if (n == 0L) return "ноль"
        val scales = longArrayOf(1_000_000_000_000L, 1_000_000_000L, 1_000_000L, 1_000L)
        val scaleNames = arrayOf(
            arrayOf("триллион", "триллиона", "триллионов"),
            arrayOf("миллиард", "миллиарда", "миллиардов"),
            arrayOf("миллион", "миллиона", "миллионов"),
            arrayOf("тысяча", "тысячи", "тысяч"),
        )
        val out = ArrayList<String>(6)
        var rest = n
        for (i in scales.indices) {
            val part = rest / scales[i]
            if (part == 0L) continue
            rest %= scales[i]
            val names = scaleNames[i]
            // «тысяча» — женского рода и в единственном числе звучит сама по
            // себе: 1000 = «тысяча», а не «один тысяча».
            val isThousand = i == scales.lastIndex
            if (isThousand && part == 1L) {
                out.add(names[0])
            } else {
                out.add(underThousand(part.toInt(), isThousand))
                out.add(plural(part, names[0], names[1], names[2]))
            }
        }
        // хвост до 999
        if (rest > 0L) out.add(underThousand(rest.toInt()))
        return out.joinToString(" ")
    }

    /** Десятичная дробь: 76,3 → «семьдесят шесть целых три десятых». */
    private fun fraction(fracDigits: String): String? {
        val n = fracDigits.toIntOrNull() ?: return null
        if (n == 0) return "ноль"
        val denom = when (fracDigits.length) {
            1 -> "десятых"
            2 -> "сотых"
            3 -> "тысячных"
            else -> return null
        }
        return underThousand(n) + " " + denom
    }

    /**
     * Цифра приклеена к идентификатору? Смотрим влево сквозь «.», «-», «_».
     *
     * Ловит AArch64, utf-8, gpt-4, H.264 — за «словом» с буквами цифры
     * считаются частью имени, а не числом. Обычные «59 записей», «Android 16»
     * и «76,3 с» начинаются после пробела или открывающей скобки и остаются
     * числами.
     */
    private fun gluedToLetters(s: String, i: Int): Boolean {
        var k = i - 1
        // Через ВСЮ идущую подряд группу цифр: иначе в «AArch64» первая
        // шестёрка отскочила бы как буква, а четвёрку мы бы превратили.
        while (k >= 0 && (s[k].isDigit() || s[k] == '.' || s[k] == '-' || s[k] == '_')) k--
        return k >= 0 && s[k].isLetter()
    }

    /**
     * Разбирает один числовой токен (без окружающего текста).
     * null — токен трогать нельзя: версия, идентификатор или слишком длинное число.
     */
    private fun token(word: String): String? {
        val isVersion = word.count { it == '.' } >= 2
        if (isVersion) {
            // 2.0.15 → «два ноль точка пятнадцать»: поразрядно, без «целых».
            val spoken = word.split('.').map { group ->
                if (group.isEmpty() || !group.all { it.isDigit() }) return null
                integer(group.toLongOrNull() ?: return null) ?: return null
            }
            return spoken.joinToString(" точка ")
        }

        val dotIdx = word.indexOf('.')
        val commaIdx = word.indexOf(',')
        if (dotIdx >= 0 && commaIdx >= 0) return null // «1,234.56» — не наш случай
        val sepIdx = if (dotIdx >= 0) dotIdx else commaIdx

        if (sepIdx < 0) {
            if (!word.all { it.isDigit() }) return null
            if (word.length > MAX_DIGITS) return null
            return integer(word.toLongOrNull() ?: return null)
        }

        val whole = word.substring(0, sepIdx)
        val frac = word.substring(sepIdx + 1)
        if (whole.isEmpty() || frac.isEmpty()) return null
        if (!whole.all { it.isDigit() } || !frac.all { it.isDigit() }) return null
        if (whole.length > MAX_DIGITS || frac.length > 3) return null

        val w = whole.toLongOrNull() ?: return null
        val wholePart = integer(w) ?: return null
        val fracPart = fraction(frac) ?: return null
        return "$wholePart целых $fracPart"
    }

    /**
     * Основная точка входа: прогоняет весь текст и заменяет числа словами.
     *
     * Проценты и «процента/процентов» обрабатываются по соседству с числом,
     * поэтому при входе «85%» на выходе будет «восемьдесят пять процентов», а
     * не «восемьдесят пять процент».
     */
    fun convert(input: String): String {
        if (input.none { it.isDigit() }) return input
        val out = StringBuilder(input.length + 32)
        var i = 0
        while (i < input.length) {
            val c = input[i]

            // Границы слова: цифра начинается только на стыке не-буквы.
            // Цифры могут быть приклеены к буквам (AArch64, H.264) — тогда не трогаем.
            if (c.isDigit() && !gluedToLetters(input, i)) {
                var j = i
                while (j < input.length && (input[j].isDigit() || input[j] == '.' || input[j] == ',')) j++
                // если сразу после цифр идёт буква — это идентификатор вроде utf8
                val gluedRight = j < input.length && input[j].isLetter()
                val raw = input.substring(i, j)
                // хвостовые точки/запятые не входят в число
                val trimmed = raw.trimEnd('.', ',')
                if (!gluedRight && trimmed.isNotEmpty()) {
                    val percentPos = i + trimmed.length
                    val isPercent = input.getOrNull(percentPos) == '%'
                    val spoken = if (isPercent) withPercent(trimmed) else token(trimmed)
                    if (spoken != null) {
                        out.append(spoken)
                        // пропускаем и сам знак «%», иначе он останется в тексте
                        i = if (isPercent) percentPos + 1 else percentPos
                        continue
                    }
                }
                out.append(trimmed)
                i += trimmed.length
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** «85%» → «восемьдесят пять процентов», но «1%» → «один процент». */
    private fun withPercent(word: String): String? {
        val spoken = token(word) ?: return null
        val value = word.split('.', ',').firstOrNull()?.toLongOrNull() ?: return null
        val noun = plural(value, "процент", "процента", "процентов")
        return "$spoken $noun"
    }
}