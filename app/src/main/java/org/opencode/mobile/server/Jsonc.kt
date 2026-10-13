package org.opencode.mobile.server

/**
 * Поверхностный разбор jsonc — ровно столько, сколько нужно, чтобы не влезть
 * в комментарий и отличить ключ корневого объекта от такого же слова внутри.
 *
 * Отдельный файл не из-за красоты: управляемый opencode.jsonc пишется и читается
 * как текст (его нельзя отдать org.json — в файле бывают комментарии), и всё
 * это вместе с [JsonBody] не влезало в лимит detekt на число функций в файле.
 */

/**
 * Есть ли такой ключ в КОРНЕ объекта, а не во вложенных.
 *
 * Зачем не `contains`. `text.contains("\"model\"")` отвечает не на тот вопрос:
 * ключ `model` внутри агента или блока плагина — это не «пользователь выбрал
 * модель по умолчанию», и молча пропустить закрепление из-за него значит
 * оставить исходный баг (каталог отдаёт устаревшую модель, агент молчит) и не
 * оставить даже следа.
 *
 * Разбор намеренно поверхностный: достаточно знать, на какой глубине вложенности
 * встретилась строка-ключ и есть ли после неё двоеточие. Экранирование, комментарии и
 * строковые значения пропускаются целиком, чтобы `"model"` внутри строки или
 * комментария не считался ключом. Вложенность считается по `{`/`[` и `}`/`]` вне строк.
 */
internal fun hasTopLevelKey(
    text: String,
    key: String,
): Boolean {
    val needle = "\"$key\""
    var depth = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '"' -> {
                if (depth == 1 && text.startsWith(needle, i) && colonFollows(text, i + needle.length)) {
                    return true
                }
                i = skipString(text, i + 1)
            }
            isLineCommentAt(text, i) -> i = skipToLineEnd(text, i)
            isBlockCommentAt(text, i) -> i = skipToBlockEnd(text, i + 2)
            c == '{' || c == '[' -> {
                depth++
                i++
            }
            c == '}' || c == ']' -> {
                depth--
                i++
            }
            else -> i++
        }
    }
    return false
}

/**
 * Позиция открывающей скобки корневого объекта, минус строки и комментарии,
 * либо -1, если такого объекта в тексте нет.
 *
 * Нужна, чтобы вставка записи в jsonc не попала в комментарий. Наивный
 * `indexOf('{')` берёт первую скобку вообще — а в файле вполне может быть
 * `/* раньше был {model} */` раньше настоящего корня. Тогда запись уехала бы
 * внутрь комментария, скобки бы сошлись, а проверка `looksLikeManagedConfig`
 * (она смотрит только на баланс скобок и наличие ключей, JSON не парсит) такой
 * файл пропустила бы — и на устройство уехал бы нечитаемый opencode.jsonc,
 * на котором сервер не поднимется.
 */
internal fun rootObjectStart(text: String): Int {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        i =
            when {
                c == '"' -> skipString(text, i + 1)
                isLineCommentAt(text, i) -> skipToLineEnd(text, i)
                isBlockCommentAt(text, i) -> skipToBlockEnd(text, i + 2)
                c == '{' -> return i
                else -> i + 1
            }
    }
    return -1
}

/**
 * Позиция первого значащего символа после [from]: пробелы и комментарии
 * пропускаются.
 *
 * Зачем. Признак «объект пуст» для вставки записи искался первым непробельным
 * символом, но для `{ // TODO }` или `{ /* пусто */ }` этим символом оказывался
 * `/`, а не `}`. Запись ставилась с запятой, получалась висячая запятая перед
 * `}`, и `looksLikeManagedConfig` её не ловил — он считает только баланс скобок
 * и наличие ключей, JSON не парсит.
 */
internal fun nextSignificant(
    text: String,
    from: Int,
): Int {
    var i = from
    while (i < text.length) {
        val c = text[i]
        i =
            when {
                c.isWhitespace() -> i + 1
                isLineCommentAt(text, i) -> skipToLineEnd(text, i)
                isBlockCommentAt(text, i) -> skipToBlockEnd(text, i + 2)
                else -> return i
            }
    }
    return i
}

/** Есть ли после [from] необязательный пробел и двоеточие — то есть это ключ, а не значение. */
internal fun colonFollows(
    text: String,
    from: Int,
): Boolean {
    var i = from
    while (i < text.length && text[i].isWhitespace()) i++
    return i < text.length && text[i] == ':'
}

internal fun isLineCommentAt(
    text: String,
    i: Int,
): Boolean = text[i] == '/' && i + 1 < text.length && text[i + 1] == '/'

internal fun isBlockCommentAt(
    text: String,
    i: Int,
): Boolean = text[i] == '/' && i + 1 < text.length && text[i + 1] == '*'

/** Позиция закрывающей кавычки строки, начатой до [from]; переводы строк внутри строк не ждём. */
internal fun skipString(
    text: String,
    from: Int,
): Int {
    var i = from
    while (i < text.length) {
        val c = text[i]
        if (c == '\\') {
            i += 2
        } else if (c == '"') {
            return i + 1
        } else {
            i++
        }
    }
    return i
}

internal fun skipToLineEnd(
    text: String,
    from: Int,
): Int {
    var i = from
    while (i < text.length && text[i] != '\n') i++
    return i + 1
}

internal fun skipToBlockEnd(
    text: String,
    from: Int,
): Int {
    var i = from
    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
    return (i + 2).coerceAtMost(text.length)
}
