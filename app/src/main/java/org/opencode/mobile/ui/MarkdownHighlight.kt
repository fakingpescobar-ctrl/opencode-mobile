package org.opencode.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

private const val HEADING_ARGB = 0xFF82AAFF
private const val BOLD_ARGB = 0xFFFFD479
private const val ITALIC_ARGB = 0xFFC792EA
private const val CODE_ARGB = 0xFFFFB86C
private const val LINK_ARGB = 0xFF7EE0C0
private const val QUOTE_ARGB = 0xFF9AA0A6
private const val LIST_ARGB = 0xFFC792EA
private const val RULE_ARGB = 0xFF6B7280
private const val TABLE_ARGB = 0xFFFFD479
private const val CODE_BG_ARGB = 0x18FFFFFF

private val linkStyle =
    SpanStyle(color = Color(LINK_ARGB), textDecoration = TextDecoration.Underline)

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
    return AnnotatedString
        .Builder(text)
        .apply {
            addStyle(SpanStyle(color = base), 0, text.length)
            for (span in spans) {
                addStyle(styleFor(span.kind), span.start, span.end)
                val target = span.link
                if (target != null) {
                    addLink(
                        LinkAnnotation.Url(
                            url = target.url,
                            styles = TextLinkStyles(style = linkStyle),
                        ),
                        target.start,
                        target.end,
                    )
                }
            }
        }.toAnnotatedString()
}

private fun styleFor(kind: MarkKind): SpanStyle =
    when (kind) {
        MarkKind.HEADING -> SpanStyle(color = Color(HEADING_ARGB), fontWeight = FontWeight.Bold)
        MarkKind.BOLD -> SpanStyle(color = Color(BOLD_ARGB), fontWeight = FontWeight.Bold)
        MarkKind.ITALIC -> SpanStyle(color = Color(ITALIC_ARGB), fontStyle = FontStyle.Italic)
        MarkKind.CODE, MarkKind.INLINE_CODE -> SpanStyle(
            color = Color(CODE_ARGB),
            fontFamily = FontFamily.Monospace,
            background = Color(CODE_BG_ARGB),
        )
        MarkKind.LINK -> linkStyle
        MarkKind.QUOTE -> SpanStyle(color = Color(QUOTE_ARGB), fontStyle = FontStyle.Italic)
        MarkKind.LIST -> SpanStyle(color = Color(LIST_ARGB), fontWeight = FontWeight.Bold)
        MarkKind.RULE -> SpanStyle(color = Color(RULE_ARGB))
        MarkKind.TABLE_HEADER -> SpanStyle(color = Color(TABLE_ARGB), fontWeight = FontWeight.Bold)
    }
