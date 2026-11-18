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

/**
 * Чистый аналог первых трёх веток [OpencodeRuntime.ensureMemorySection].
 *
 * Пока повторяет те же проверки подстрок, чтобы первый шаг не менял поведение:
 * переход на структурный поиск ключа — отдельный PR. Параметр [memoryName]
 * передаётся явно, чтобы функция оставалась чистой и не знала про константы
 * рантайма.
 */
internal fun planMcpSection(
    text: String,
    memoryName: String,
): McpSectionPlan {
    val memoryKey = "\"$memoryName\""
    return when {
        !text.contains(memoryKey) && !text.contains("\"mcp\"") -> McpSectionPlan.Create
        !text.contains(memoryKey) -> McpSectionPlan.Refuse
        else -> McpSectionPlan.Migrate
    }
}
