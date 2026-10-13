package org.opencode.mobile.server

/**
 * Что делать с записью `"question": "ask"` в секции permission.
 *
 * Отдельный тип вместо Boolean — потому что у этой правки три исхода, а два из
 * них требуют разных действий: молча выйти, записать или сказать в лог, что мы
 * не поняли файл. Настоящий чужой выбор (пользователь поставил ключ сам) мы не
 * трогаем: переписать его — значит надеться на чужое решение по вопросу о том,
 * можно ли приложению задавать вопросы без спроса.
 */
internal sealed interface PermissionPlan {
    /** Ключ уже есть — нечего делать. */
    data object Keep : PermissionPlan

    /** Нужно записать [text]. */
    data class Write(
        val text: String,
    ) : PermissionPlan

    /** Файл не узнан: трогать его опасно. */
    data object Refuse : PermissionPlan
}

/** Запись целиком, вместе с двоеточием и значением. */
internal const val QUESTION_PERMISSION_ENTRY = "\"question\": \"ask\""

private const val PERMISSION_KEY = "permission"

private const val QUESTION_KEY = "question"

/**
 * План вставки `"question": "ask"` в секцию permission управляемого opencode.jsonc.
 *
 * Зачем это нужно. Карточка разрешения из `ApiAsk` читает `GET
 * /api/session/{id}/permission`. Пока в конфиге нет `"question": "ask"`, инструмент
 * question не запрашивает разрешение вовсе: он стартует, упирается в ожидание
 * ответа и навсегда остаётся в `status=running`, а запроса в `/permission` не
 * появляется — `pending` пуст, и карточке показывать нечего. На устройстве это
 * выглядело как «карточка вопроса опять не вызвалась», хотя дело было не в карточке.
 *
 * Чужое решение не перебиваем: если ключ question уже есть в секции permission,
 * план `Keep`. Если секции permission в файле нет, план `Refuse` — мы не будем
 * заводить её в чужом конфиге, и об этом честно скажет лог.
 */
internal fun planQuestionPermission(text: String): PermissionPlan {
    val span = memberObjectSpan(text, PERMISSION_KEY) ?: return PermissionPlan.Refuse
    // Подстрока начинается с открывающей скобки, поэтому «корень» для неё — это
    // и есть члены самой секции permission. Так ключ считается ключом, а не
    // словом внутри комментария или значения.
    val body = text.substring(span.first, span.second + 1)
    return if (hasTopLevelKey(body, QUESTION_KEY)) PermissionPlan.Keep else insertPlan(text, span)
}

/**
 * Текст с вставленной записью. Вставляем перед первым значимым символом после
 * открывающей скобки, а не сразу после неё: сразу после может оказаться
 * комментарий, и запись уехала бы внутрь него.
 */
private fun insertPlan(
    text: String,
    span: Pair<Int, Int>,
): PermissionPlan {
    val at = nextSignificant(text, span.first + 1)
    val entry =
        if (at >= span.second) {
            " $QUESTION_PERMISSION_ENTRY"
        } else {
            "$QUESTION_PERMISSION_ENTRY, "
        }
    return PermissionPlan.Write(text.substring(0, at) + entry + text.substring(at))
}
