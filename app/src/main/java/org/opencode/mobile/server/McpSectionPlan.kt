package org.opencode.mobile.server

/**
 * Решение по секции `mcp` до начала миграций.
 *
 * Выделено из [OpencodeRuntime.ensureMemorySection] не ради краткости: здесь
 * решается, имеем ли мы дело со своим конфигом. Три исхода нельзя свести к
 * Boolean — «создать», «отказаться» и «уже своё, мигрировать дальше» требуют
 * разных действий. Чистая функция легко тестируется на любом тексте, не трогая
 * файл.
 */
internal sealed interface McpSectionPlan {
    /** Своей секции mcp ещё нет — можно записать управляемую форму. */
    data object Create : McpSectionPlan

    /** Секция mcp есть, но нашего сервера памяти в ней нет: чужая, не трогаем. */
    data object Refuse : McpSectionPlan

    /** Управляемая форма распознана — дальше идёт лестница миграций. */
    data object Migrate : McpSectionPlan
}

private const val MCP_SECTION_KEY = "mcp"

/**
 * Структурный аналог первых трёх веток [OpencodeRuntime.ensureMemorySection].
 *
 * Вместо `text.contains("\"mcp\"")` и `text.contains("\"memory\"")` здесь
 * различаются ключ корня, ключ внутри секции и просто слово в строке или
 * комментарии. Ложное срабатывание раньше уводило чужой конфиг в лестницу
 * миграций; теперь он сразу получает [McpSectionPlan.Refuse].
 *
 * Консервативность важнее полноты: если ключ mcp есть, но его значение не объект,
 * либо ключ памяти найден вне mcp — файл не трогаем. [McpSectionPlan.Create]
 * выдаётся только когда нет ни корневого mcp, ни ключа памяти ни на какой глубине.
 */
internal fun planMcpSection(
    text: String,
    memoryName: String,
): McpSectionPlan {
    val hasMcp = hasTopLevelKey(text, MCP_SECTION_KEY)
    val mcpSpan = memberObjectSpan(text, MCP_SECTION_KEY)
    val memoryInMcp =
        mcpSpan
            ?.let { (open, close) -> hasTopLevelKey(text.substring(open, close + 1), memoryName) }
            ?: false
    return when {
        !hasMcp && !hasAnyKey(text, memoryName) -> McpSectionPlan.Create
        memoryInMcp -> McpSectionPlan.Migrate
        else -> McpSectionPlan.Refuse
    }
}

/** Ключ [key] на любой глубине, но не внутри строк и комментариев. */
private fun hasAnyKey(
    text: String,
    key: String,
): Boolean {
    val needle = "\"$key\""
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '"' -> {
                if (text.startsWith(needle, i) && colonFollows(text, i + needle.length)) return true
                i = skipString(text, i + 1)
            }
            isLineCommentAt(text, i) -> i = skipToLineEnd(text, i)
            isBlockCommentAt(text, i) -> i = skipToBlockEnd(text, i + 2)
            else -> i++
        }
    }
    return false
}
