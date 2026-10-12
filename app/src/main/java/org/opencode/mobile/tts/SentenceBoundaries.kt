package org.opencode.mobile.tts

/** Знаки, на которых резать поток текста. */
private enum class Mark {
    /** Конец предложения: `.` `!` `?` и дальше пробел, перевод строки или конец текста. */
    HARD,

    /** Перевод строки. */
    SOFTISH,

    /** `;` и `:`. */
    SOFT,

    /** Ничего. */
    NONE,
}

/**
 * Знаки границ, найденные в куске текста.
 *
 * Зачем объект, а не три `Int?`. Разбор шёл одним проходом с ранними выходами,
 * и всё состояние жило в трёх локальных переменных с проверками на каждую: цикл,
 * четыре `if` и `when` внутри — это сложность 21 при лимите 15 плюс вложенность.
 * Здесь учёт и выбор разнесены, а решение «остановиться» возвращается флагом.
 *
 * Две особенности перенесены из старого кода как есть, потому что это его
 * поведение, а не наше решение:
 * - мягкая метка запоминается ПОСЛЕДНЯЯ, а не первая (перезапись без сравнения);
 * - жёсткая метка дальше лимита обрывает разбор целиком, как `break` в старом
 *   цикле, — иначе мягкие метки после неё попали бы в выбор и изменили разрез.
 */
private class Marks {
    var hard = NONE
    var soft = NONE
    var softish = NONE

    /** Учесть метку. true — пора прекращать разбор. */
    fun record(
        kind: Mark,
        at: Int,
        limit: Int,
    ): Boolean =
        when (kind) {
            Mark.HARD -> {
                // Записывается в любом случае: попала в лимит или нет, разбор
                // прекращается, а позицию должен вернуть best().
                hard = at
                true
            }
            Mark.SOFTISH -> {
                softish = at
                at <= limit
            }
            Mark.SOFT -> {
                soft = at
                at <= limit
            }
            Mark.NONE -> false
        }

    /**
     * Итоговый разрез. **0 — границы нет**, наружу не выходит отрицательное число.
     *
     * Порядок предпочтений сохранён как был: сначала `;`/`:` (только если раньше
     * конца предложения), потом перевод строки (тоже раньше), иначе конец
     * предложения. Это не «самая ранняя метка»: при `soft`=30 и `softish`=20
     * вернётся 30. Выглядит странно, но это поведение, на котором обучена озвучка,
     * и менять его здесь нельзя — сначала надо доказать, что лучше.
     */
    fun best(): Int {
        val softWins = soft != NONE && (hard == NONE || soft < hard)
        val softishWins = !softWins && softish != NONE && (hard == NONE || softish < hard)
        return when {
            softWins -> soft
            softishWins -> softish
            hard != NONE -> hard
            else -> 0
        }
    }

    companion object {
        const val NONE = -1
    }
}

/** Какая метка стоит в позиции [i], если она там вообще есть. */
private fun markAt(
    text: String,
    i: Int,
): Mark {
    val c = text[i]
    return when {
        c == '.' || c == '!' || c == '?' -> if (endsSentence(text, i)) Mark.HARD else Mark.NONE
        c == '\n' -> Mark.SOFTISH
        c == ';' || c == ':' -> Mark.SOFT
        else -> Mark.NONE
    }
}

/** Знак конца предложения — только перед пробелом, переводом строки или концом текста. */
private fun endsSentence(
    text: String,
    i: Int,
): Boolean {
    val next = text.getOrNull(i + 1)
    return next == null || next == ' ' || next == '\n'
}

/** Конец предложения не дальше [limit]; `;` и `:` — только если предложение длинное. */
internal fun findSentenceBoundary(
    text: String,
    limit: Int,
): Int {
    val marks = Marks()
    var i = 0
    while (i < text.length) {
        val kind = markAt(text, i)
        if (kind != Mark.NONE && marks.record(kind, i + 1, limit)) break
        i++
    }
    return marks.best()
}
