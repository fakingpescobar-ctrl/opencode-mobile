package org.opencode.mobile.server

import org.json.JSONObject

/**
 * Один вариант ответа на вопрос, предложенный инструментом `question`.
 */
internal data class AskOption(
    val label: String,
    val description: String,
)

/**
 * Один вопрос инструмента `question` с его вариантами.
 *
 * Их бывает несколько на один вызов: живой сервер отдаёт `questions[]` хоть
 * из двух элементов («Какой вариант: A или B?» и «Что делать дальше?»).
 * Раньше карточка брала только `questions[0]`, и остальные вопросы молча
 * терялись — пользователь отвечал на первый, а агент считал, что получил
 * ответ. Поэтому вопрос это список блоков, а не один блок.
 */
internal data class AskQuestion(
    val header: String,
    val text: String,
    val options: List<AskOption>,
)

/** Блок одного вопроса из `state.input.questions[]`. */
internal fun questionOf(source: JSONObject): AskQuestion =
    AskQuestion(
        header = source.optString("header").takeIf { it.isNotBlank() }.orEmpty(),
        text = source.optString("question").takeIf { it.isNotBlank() }.orEmpty(),
        options = optionsOf(source),
    )

/** Варианты ответа. `label` обязателен, без него вариант не показать. */
internal fun optionsOf(question: JSONObject?): List<AskOption> {
    val arr = question?.optJSONArray("options")
    return (0 until (arr?.length() ?: 0)).mapNotNull { i ->
        val option = arr?.optJSONObject(i)
        val label = option?.optString("label").orEmpty()
        if (label.isEmpty()) null else AskOption(label, option?.optString("description").orEmpty())
    }
}
