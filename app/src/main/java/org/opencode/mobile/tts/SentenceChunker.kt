package org.opencode.mobile.tts

/**
 * Режет поток текста от LLM на куски, пригодные для offline-синтеза.
 *
 * Границы: конец предложения (`.` `!` `?` + пробел/конец), перевод строки,
 * а при длинном предложении — `;` и `:`. Если границы нет дольше [maxChars],
 * режем по последнему пробелу: незаконченный фрагмент лучше, чем тишина.
 *
 * Первый кусок специально короче ([firstChunkChars]): ориентир TTFA — первый
 * звук за ~1 с, а предложение на 4 с звука синтезируется 1.3 с. Короткий
 * первый кусок укладывается в окно, длинный — нет.
 */
class SentenceChunker(
    private val maxChars: Int = 220,
    private val firstChunkChars: Int = 90,
) {
    private var buffer = StringBuilder()
    private var firstChunkDone = false

    /** Добавляет новый текст, возвращает куски, готовые к синтезу. */
    fun push(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        buffer.append(text)
        return drain(force = false)
    }

    /** Конец ответа: отдаёт остаток буфера, даже без знака конца предложения. */
    fun flush(): List<String> = drain(force = true)

    fun reset() {
        buffer = StringBuilder()
        firstChunkDone = false
    }

    /**
     * Длина невыданного буфера — для диагностики и тестов.
     *
     * Именно `getPendingChars()`, а не свойство: так вызывает androidTest
     * `SmokeTtsTest`, написанный мобильным агентом против боевого DEX. Ломать его
     * ради идиоматичности смысла нет — тест ловит главный дефект плеера.
     */
    fun getPendingChars(): Int = buffer.length

    private fun drain(force: Boolean): List<String> {
        val out = ArrayList<String>(2)
        while (true) {
            val chunk = takeChunk(force) ?: break
            if (chunk.isNotBlank()) out += chunk
        }
        return out
    }

    private fun takeChunk(force: Boolean): String? {
        if (buffer.isEmpty()) return null

        val limit = if (firstChunkDone) maxChars else firstChunkChars
        val text = buffer.toString()

        // Граница предложения → разрез по пробелу (предложение не кончилось) →
        // весь остаток, но только когда пришёл конец ответа.
        val boundary = findBoundary(text, limit) ?: if (text.length >= maxChars) {
            findSpace(text, maxChars)
        } else if (force) {
            text.length
        } else {
            null
        }

        if (boundary == null) return null

        // Без trim(): пробел после знака конца предложения принадлежит разрыву
        // и уезжает в начало следующего куска. Обрезка здесь роняла его, и склейка
        // кусков переставала совпадать с исходным текстом («проверки.Второе»).
        // Для синтеза ведущий пробел безобиден, для потока текста — потеря данных.
        val chunk = text.substring(0, boundary)
        buffer = StringBuilder(text.substring(boundary))
        if (chunk.isNotBlank()) firstChunkDone = true
        return chunk
    }

    /** Конец предложения не дальше [limit]; `;` и `:` — только если предложение длинное. */
    private fun findBoundary(text: String, limit: Int): Int? {
        var soft: Int? = null
        var hard: Int? = null
        var softish: Int? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?') {
                // Знак конца предложения, если дальше пробел или конец текста.
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
        // Мягкие границы берём, только если до них не дотянуться жёсткой.
        soft?.let { if (hard == null || it < hard) return it }
        softish?.let { if (hard == null || it < hard) return it }
        return hard
    }

    /** Последний пробел в пределах [limit], иначе сам [limit]. */
    private fun findSpace(text: String, limit: Int): Int? {
        if (text.length <= limit) return null
        val idx = text.lastIndexOf(' ', limit - 1)
        return if (idx > 0) idx + 1 else limit
    }
}