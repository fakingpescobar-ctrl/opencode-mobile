package org.opencode.mobile.server

/**
 * Поиск вложенного объекта по его ключу в корне jsonc.
 *
 * Отдельный файл, потому что [Jsonc] уже упирается в лимит detekt на число
 * функций в файле, а его низкоуровневые примитивы нужны сразу двум задачам:
 * поиском ключа в корне и поиском вложенного объекта.
 *
 * Возвращается пара позиций: открывающая и закрывающая скобки, либо null, если
 * такого объекта в тексте нет либо его скобка не закрыта.
 */

/**
 * Границы объекта, который является значением ключа [key] в КОРНЕ.
 *
 * Зачем. Управляемый opencode.jsonc дописывается как текст, и чтобы добавить
 * запись внутрь секции `permission`, нужно знать, где секция кончается. Поиск
 * последней скобки в файле не годится: в конфиге есть секции и после
 * `permission`, и закрывающая скобка уехала бы не туда — запись встала бы за
 * пределами объекта и оказалась в следующей секции.
 */
internal fun memberObjectSpan(
    text: String,
    key: String,
): Pair<Int, Int>? {
    val open = openBraceOf(text, key)
    val close = if (open >= 0) matchBrace(text, open) else -1
    return if (open >= 0 && close >= 0) open to close else null
}

/**
 * Границы строкового значения корневого ключа [key]: (кавычка_открывающая,
 * позиция_за_закрывающей). null — ключа нет, либо его значение не строка.
 *
 * Нужна, чтобы менять скалярную запись (`small_model`) заменой ровно одного
 * токена, а не пересборкой секции: так чужой конфиг остаётся байт-в-байт, кроме
 * самой строки. Не-строка возвращает null — тогда вызывающий решает, что форма
 * не узнана, вместо того чтобы вслепую переписать число или объект.
 */
internal fun rootStringValueSpan(
    text: String,
    key: String,
): Pair<Int, Int>? {
    val afterKey = rootKeyValue(text, key) ?: return null
    val start = valueStart(text, afterKey)
    return when {
        start < 0 || start >= text.length || text[start] != '"' -> null
        else ->
            skipString(text, start + 1).let { end ->
                if (end > start + 1) start to end else null
            }
    }
}

/**
 * Границы значения корневого ключа [key] ЛЮБОЙ формы: (начало_значения,
 * позиция_за_значением). null — ключа нет либо значение не удалось разобрать.
 *
 * Нужна там, где запись обязана быть переписана в любом случае: строковое
 * значение меняется одним токеном, объект/массив — от `open` до парной скобки,
 * а скаляр (`null`, число, `true`) — до запятой или закрывающей скобки. Отказ по
 * форме здесь обнулял бы всю правку, а для корневого `model` это значило бы, что
 * маршрутизация не применима никогда.
 */
internal fun rootValueSpan(
    text: String,
    key: String,
): Pair<Int, Int>? {
    val afterKey = rootKeyValue(text, key) ?: return null
    val start = valueStart(text, afterKey)
    return if (start in text.indices) valueSpanAt(text, start) else null
}

/** Границы значения, первый значимый символ которого стоит в [start]. */
private fun valueSpanAt(
    text: String,
    start: Int,
): Pair<Int, Int>? =
    when (text[start]) {
        '"' -> skipString(text, start + 1).let { end -> if (end > start + 1) start to end else null }
        '{', '[' -> closeBracket(text, start).let { close -> if (close > start) start to close + 1 else null }
        else -> endOfScalar(text, start).let { end -> if (end > start) start to end else null }
    }

/** Позиция открывающей скобки значения ключа [key] в корне, либо -1. */
private fun openBraceOf(
    text: String,
    key: String,
): Int {
    val afterKey = rootKeyValue(text, key) ?: return -1
    val open = valueStart(text, afterKey)
    return if (open >= 0 && open < text.length && text[open] == '{') open else -1
}

/**
 * Позиция сразу за закрывающей кавычкой ключа [key], если он стоит в корне и
 * за ним есть двоеточие, иначе null.
 *
 * Ключ ищется на глубине 1 и только вне строк и комментариев. Иначе `"permission"`
 * внутри секции `mcp` или внутри комментария выдавали бы себя за корневой ключ,
 * и мы бы правили чужой объект.
 */
private fun rootKeyValue(
    text: String,
    key: String,
): Int? {
    val needle = "\"$key\""
    var depth = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '"') {
            val hit = depth == 1 && text.startsWith(needle, i) && colonFollows(text, i + needle.length)
            val after = skipString(text, i + 1)
            if (hit) return after
            i = after
        } else if (isLineCommentAt(text, i)) {
            i = skipToLineEnd(text, i)
        } else if (isBlockCommentAt(text, i)) {
            i = skipToBlockEnd(text, i + 2)
        } else {
            depth +=
                when (c) {
                    '{', '[' -> 1
                    '}', ']' -> -1
                    else -> 0
                }
            i++
        }
    }
    return null
}

/**
 * Позиция первого значимого символа ЗНАЧЕНИЯ ключа, либо -1, если после [from]
 * не идёт `:`.
 *
 * Отдельная мелочь, но именно на ней спотыкается поиск объекта: [nextSignificant]
 * умеет пропускать пробелы и комментарии, но не умеет перешагнуть двоеточие
 * между ключом и значением. Без неё `memberObjectSpan` возвращал null на каждом
 * корректном файле — указатель попадал на `:`, а не на `{`.
 */
private fun valueStart(
    text: String,
    from: Int,
): Int {
    var i = from
    while (i < text.length && text[i].isWhitespace()) i++
    if (i >= text.length || text[i] != ':') return -1
    return nextSignificant(text, i + 1)
}

/**
 * Позиция закрывающей скобки, парной скобке в [open] (`{` или `[`), либо -1.
 *
 * Отдельный примитив вместо [matchBrace]: корневой `model` может оказаться не
 * только объектом, но и массивом, а искать пару для `[]` тем же счётчиком
 * дешевле, чем заводить второй почти такой же скан.
 */
private fun closeBracket(
    text: String,
    open: Int,
): Int {
    var depth = 0
    var i = open
    while (i < text.length) {
        val c = text[i]
        i =
            when {
                c == '"' -> skipString(text, i + 1)
                isLineCommentAt(text, i) -> skipToLineEnd(text, i)
                isBlockCommentAt(text, i) -> skipToBlockEnd(text, i + 2)
                c == '{' || c == '[' -> {
                    depth++
                    i + 1
                }
                c == '}' || c == ']' -> {
                    depth--
                    if (depth == 0) return i
                    i + 1
                }
                else -> i + 1
            }
    }
    return -1
}

/**
 * Позиция сразу за скалярным значением, начавшимся в [start].
 *
 * Скаляр в jsonc не разбирать нечем: он идёт до запятой, закрывающей скобки или
 * конца строки — что встретилось первым. Останавливаемся и на `/`: это начало
 * комментария, и «склеить» его со значением нельзя (`5//мост` — это `5` плюс
 * комментарий, а не одно число), иначе замена значения оставила бы в файле
 * обрывок комментария обычным текстом и opencode.jsonc перестал бы парситься.
 */
private fun endOfScalar(
    text: String,
    start: Int,
): Int {
    var i = start
    while (i < text.length) {
        val c = text[i]
        if (c in ",}]" || c.isWhitespace() || c == '/') break
        i++
    }
    return i
}

/**
 * Позиция закрывающей скобки, парной скобке в [open], либо -1, если пара не
 * закрылась — тогда файл битый и вставлять в него нечего.
 */
private fun matchBrace(
    text: String,
    open: Int,
): Int {
    var depth = 0
    var i = open
    while (i < text.length) {
        val c = text[i]
        i =
            when {
                c == '"' -> skipString(text, i + 1)
                isLineCommentAt(text, i) -> skipToLineEnd(text, i)
                isBlockCommentAt(text, i) -> skipToBlockEnd(text, i + 2)
                c == '{' -> {
                    depth++
                    i + 1
                }
                c == '}' -> {
                    depth--
                    // Возврат именно здесь: пока пара не сошлась, скан идёт дальше.
                    // Если продолжить, счётчик уйдёт в минус на закрывающей скобке
                    // корня и функция вернёт -1 на любом корректном файле.
                    if (depth == 0) return i
                    i + 1
                }
                else -> i + 1
            }
    }
    return -1
}
