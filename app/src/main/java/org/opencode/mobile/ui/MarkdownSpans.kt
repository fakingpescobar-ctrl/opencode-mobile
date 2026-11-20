package org.opencode.mobile.ui

/**
 * Разметка ответа модели, которую можно покрасить в UI.
 *
 * Этот файл НЕ знает про Compose: он возвращает чистые диапазоны, а раскраской
 * занимается [highlightMarkdown]. Так логику можно проверить обычным JVM-тестом
 * без эмулятора и без Compose-рантайма.
 */
internal enum class MarkKind {
    HEADING,
    BOLD,
    ITALIC,
    CODE,
    INLINE_CODE,
    LINK,
    QUOTE,
    LIST,
    RULE,
    TABLE_HEADER,
}

internal data class MarkSpan(
    val start: Int,
    val end: Int,
    val kind: MarkKind,
)

private val HEADING_RE = Regex("^#{1,6}\\s+")
private val LIST_RE = Regex("^\\s*(?:[-*+]|\\d+\\.)\\s+")
private val RULE_RE = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")
private val TABLE_SEP_RE = Regex("^\\s*\\|?[\\s:|-]+\\|?\\s*$")

/** Диапазоны markdown в исходном тексте. Символы не удаляются — копирование остаётся точным. */
internal fun markdownSpans(text: String): List<MarkSpan> {
    val out = mutableListOf<MarkSpan>()
    val lines = text.split('\n')
    var offset = 0
    var inFence = false

    for (i in lines.indices) {
        val line = lines[i]
        val lineEnd = offset + line.length
        val trimmed = line.trimStart()

        when {
            trimmed.startsWith("```") -> {
                out += MarkSpan(offset, lineEnd, MarkKind.CODE)
                inFence = !inFence
            }
            inFence -> out += MarkSpan(offset, lineEnd, MarkKind.CODE)
            RULE_RE.matches(line) || (trimmed.startsWith("|") && TABLE_SEP_RE.matches(line)) -> {
                out += MarkSpan(offset, lineEnd, MarkKind.RULE)
            }
            HEADING_RE.containsMatchIn(trimmed) -> out += MarkSpan(offset, lineEnd, MarkKind.HEADING)
            trimmed.startsWith(">") -> out += MarkSpan(offset, lineEnd, MarkKind.QUOTE)
            trimmed.startsWith("|") && isTableSeparator(lines.getOrNull(i + 1)) -> {
                out += MarkSpan(offset, lineEnd, MarkKind.TABLE_HEADER)
            }
            else -> {
                val marker = LIST_RE.find(line)
                if (marker != null) out += MarkSpan(offset, offset + marker.range.last + 1, MarkKind.LIST)
                out += inlineSpans(line, offset)
            }
        }
        offset = lineEnd + 1
    }
    return out.sortedBy { it.start }
}

private fun isTableSeparator(next: String?): Boolean =
    next != null && next.trimStart().startsWith("|") && TABLE_SEP_RE.matches(next) && next.contains('-')

/** Встроенные маркеры внутри одной строки. Диапазоны не пересекаются. */
private fun inlineSpans(line: String, offset: Int): List<MarkSpan> {
    val out = mutableListOf<MarkSpan>()
    var i = 0
    while (i < line.length) {
        val rest = line.substring(i)
        when {
            rest.startsWith("`") -> {
                val end = line.indexOf('`', i + 1)
                if (end > i + 1) {
                    out += MarkSpan(offset + i, offset + end + 1, MarkKind.INLINE_CODE)
                    i = end + 1
                    continue
                }
            }
            rest.startsWith("**") -> {
                val end = line.indexOf("**", i + 2)
                if (end > i + 2) {
                    out += MarkSpan(offset + i, offset + end + 2, MarkKind.BOLD)
                    i = end + 2
                    continue
                }
            }
            rest.startsWith("[") -> {
                val close = line.indexOf("]", i + 1)
                val paren = if (close > i) line.indexOf("(", close + 1) else -1
                val end = if (paren > close) line.indexOf(")", paren + 1) else -1
                if (end > paren) {
                    out += MarkSpan(offset + i, offset + end + 1, MarkKind.LINK)
                    i = end + 1
                    continue
                }
            }
            rest.startsWith("*") || rest.startsWith("_") -> {
                val mark = if (rest.startsWith("*")) "*" else "_"
                val end = line.indexOf(mark, i + 1)
                if (end > i + 1) {
                    out += MarkSpan(offset + i, offset + end + 1, MarkKind.ITALIC)
                    i = end + 1
                    continue
                }
            }
        }
        i++
    }
    return out
}
