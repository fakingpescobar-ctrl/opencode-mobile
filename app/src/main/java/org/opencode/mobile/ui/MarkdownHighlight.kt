package org.opencode.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/** Палитра подсветки ответа. Базовый цвет остаётся выбранным пользователем. */
private val HEADING_COLOR = Color(0xFF82AAFF)
private val BOLD_COLOR = Color(0xFFFFD479)
private val ITALIC_COLOR = Color(0xFFC792EA)
private val CODE_COLOR = Color(0xFFFFB86C)
private val LINK_COLOR = Color(0xFF7EE0C0)
private val QUOTE_COLOR = Color(0xFF9AA0A6)
private val LIST_COLOR = Color(0xFFC792EA)
private val RULE_COLOR = Color(0xFF6B7280)
private val TABLE_COLOR = Color(0xFFFFD479)
private val CODE_BACKGROUND = Color(0x18FFFFFF)

/**
 * Окрашивает markdown в ответе модели: заголовки, код, ссылки и т.д.
 * Передаётся ровно тот [base], который человек выбрал в настройках цвета —
 * подсветка добавляет акценты, а не заменяет его одним сплошным цветом.
 */
internal fun highlightMarkdown(
    text: String,
    base: Color,
): AnnotatedString {
    val spans = markdownSpans(text)
    if (spans.isEmpty()) return AnnotatedString(text)
    return AnnotatedString.Builder(text)
        .apply {
            for (span in spans) addStyle(styleFor(span.kind), span.start, span.end)
        }.toAnnotatedString()
}

private fun styleFor(kind: MarkKind): SpanStyle =
    when (kind) {
        MarkKind.HEADING -> SpanStyle(color = HEADING_COLOR, fontWeight = FontWeight.Bold)
        MarkKind.BOLD -> SpanStyle(color = BOLD_COLOR, fontWeight = FontWeight.Bold)
        MarkKind.ITALIC -> SpanStyle(color = ITALIC_COLOR, fontStyle = FontStyle.Italic)
        MarkKind.CODE, MarkKind.INLINE_CODE -> SpanStyle(
            color = CODE_COLOR,
            fontFamily = FontFamily.Monospace,
            background = CODE_BACKGROUND,
        )
        MarkKind.LINK -> SpanStyle(color = LINK_COLOR, textDecoration = TextDecoration.Underline)
        MarkKind.QUOTE -> SpanStyle(color = QUOTE_COLOR, fontStyle = FontStyle.Italic)
        MarkKind.LIST -> SpanStyle(color = LIST_COLOR, fontWeight = FontWeight.Bold)
        MarkKind.RULE -> SpanStyle(color = RULE_COLOR)
        MarkKind.TABLE_HEADER -> SpanStyle(color = TABLE_COLOR, fontWeight = FontWeight.Bold)
    }
