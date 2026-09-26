package org.opencode.mobile.stt

import java.util.Locale

/**
 * Подсчёт WER (word error rate) для бенча STT.
 *
 * Лежит в main, а не в androidTest, намеренно: `BenchSttTest` живёт в androidTest и
 * androidTest-классы не видны юнит-тестам. Если бы счётка лежала там, её нечем было бы
 * покрыть тестом, а ошибка в расстоянии Левенштейна даёт неверный WER — то есть ровно тот
 * класс проблемы, когда число выглядит правдоподобно и вводит в заблуждение.
 *
 * Считается word-level WER: расстояние между последовательностями слов, делённое на
 * число эталонных слов. Это стандарт: посимвольный WER для STT не используют, он плохо
 * переносится между языками и искажает оценку пунктуацией.
 */
object Wer {
    private const val PERCENT = 100.0

    /**
     * Приводит текст к сравнимому виду: нижний регистр, `ё`=`е`, без пунктуации, с
     * одним пробелом между словами.
     *
     * Нормализация обязательна перед сравнением: модель ставит запятые где попало и
     * пишет `ё` вместо `е`, и без этого различия в оформлении засчитываются как
     * ошибки распознавания. Слова-паразиты (`ну`, `вот`, `это`) НЕ выбрасываются:
     * они part of the reference, и выкидывать их на стороне сравнения - значит
     * подгонять метрику под удобный результат.
     */
    fun normalize(text: String): String {
        val stripped = text
            .lowercase()
            .replace('ё', 'е')
            .map { if (it.isLetter() || it == ' ') it else ' ' }
            .joinToString("")
        return stripped
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    /** Расстояние Левенштейна между двумя списками слов. */
    fun distance(
        ref: List<String>,
        hyp: List<String>,
    ): Int {
        // Одна строка вместо двух: detekt не любит >2 return, а три тут не о чем.
        if (ref.isEmpty() || hyp.isEmpty()) return maxOf(ref.size, hyp.size)
        var prev = IntArray(hyp.size + 1) { it }
        val cur = IntArray(hyp.size + 1)
        for (i in 1..ref.size) {
            cur[0] = i
            for (j in 1..hyp.size) {
                val sub = prev[j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1
                val del = prev[j] + 1
                val ins = cur[j - 1] + 1
                cur[j] = minOf(sub, del, ins)
            }
            prev = cur.copyOf()
        }
        return prev[hyp.size]
    }

    /**
     * WER как доля: `substitutions + deletions + insertions` / размер эталона.
     * Возвращает `null`, если эталон пуст - делить на ноль бессмысленно, и «0 ошибок»
     * в этом случае было бы ложью.
     */
    fun of(
        reference: String,
        hypothesis: String,
    ): Double? {
        val ref = normalize(reference).split(' ').filter { it.isNotEmpty() }
        if (ref.isEmpty()) return null
        val hyp = normalize(hypothesis).split(' ').filter { it.isNotEmpty() }
        return distance(ref, hyp).toDouble() / ref.size
    }

    /** `12.5%`, либо `-` если WER посчитать нельзя. */
    fun format(wer: Double?): String {
        if (wer == null) return "-"
        return String.format(Locale.ROOT, "%.1f%%", wer * PERCENT)
    }
}
