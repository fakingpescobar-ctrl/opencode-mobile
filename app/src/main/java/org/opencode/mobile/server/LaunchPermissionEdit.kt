package org.opencode.mobile.server

/** Запись целиком, вместе с двоеточием и значением. */
internal const val MOBILE_LAUNCH_PERMISSION_ENTRY = "\"mobile_launch_app\": \"ask\""

private const val MCP_SECTION_KEY = "mcp"

/**
 * Добавление managed-разрешения без переписывания секции `mcp` целиком.
 *
 * Раньше ключ добавлялся заменой всей сгенерированной формы `mcp` на форму плюс
 * `permission`. Точное совпадение переставало находиться, стоило внутрь `mcp`
 * появиться записи moltbook после телефона: замена не срабатывала, файл
 * перезаписывался прежним текстом, а метод возвращал `true`. Потеря разрешения
 * при этом была невидима — конфиг выглядел здоровым, сервер поднимался.
 *
 * Здесь вставка идёт по границам уже найденного корневого объекта `mcp`: всё,
 * что внутри него и после него, остаётся как есть — music, moltbook, комментарии
 * и ручная форматировка пользователя. [null] означает, что объект не найден или
 * его скобка не закрыта: писать такой текст нельзя.
 */
internal fun insertLaunchPermissionAfterMcp(
    text: String,
    permissionEntry: String,
): String? {
    val mcp = memberObjectSpan(text, MCP_SECTION_KEY) ?: return null
    val close = mcp.second
    val entry = ",\n  \"permission\": {\n    $permissionEntry\n  }"
    return text.substring(0, close + 1) + entry + text.substring(close + 1)
}
