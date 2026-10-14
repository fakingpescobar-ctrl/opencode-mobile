package org.opencode.mobile.server

/**
 * Маршрутизация бесплатных моделей по ролям агентов.
 *
 * Зачем это отдельным шагом конфига. По умолчанию все агенты opencode берут одну
 * модель из корневого `model`, а он закреплён за `opencode/big-pickle`. Это значит,
 * что лёгкие агенты (explore, general) и служебные генерации (заголовок, сводка)
 * платят ту же цену за ход, что и тяжёлый `build`: бесплатный публичный эндпоинт
 * отвечает секундами, а лёгкой задаче хватило бы быстрой модели. v2-конфиг это
 * выражает словами `agent.<id>.model` и корневым `small_model`.
 *
 * Правка идёт как текст поверх уже существующего файла, а не пересборкой секций:
 * управляемый opencode.jsonc содержит чужие ключи (mcp, permission, $schema),
 * и потерять их нельзя.
 */

/** Выбор пользователя: какая модель у тяжёлых агентов, какая у лёгких, какая у служебных. */
data class ModelRouting(
    val heavy: String,
    val light: String,
    val small: String,
)

/** Что делать с маршрутизацией в управляемом opencode.jsonc. */
internal sealed interface RoutingPlan {
    /** Всё уже как надо — файл не трогаем. */
    data object Keep : RoutingPlan

    /** Перезаписать конфиг подготовленным [text]. */
    data class Write(
        val text: String,
    ) : RoutingPlan

    /** Форма файла не узнана: править опасно, оставляем как есть. */
    data object Refuse : RoutingPlan
}

/**
 * Итог попытки вписать маршрутизацию в управляемый конфиг.
 *
 * Отдельный тип вместо Boolean — потому что «файл изменился» и «файл уже был таким»
 * требуют от UI противоположных действий: первое просит перезапустить агента, второе
 * просит не дёргать его зря. Boolean сливал эти случаи в одно `true` (реальная
 * ошибка, пойманная ревью), и карточка требовала рестарта, хотя менять было нечего.
 */
enum class RoutingApply {
    /** Конфиг на диске теперь соответствует выбору — серверу нужен рестарт. */
    WRITTEN,

    /** Файл уже содержал этот выбор — ничего не писали, рестарт не обязателен. */
    UNCHANGED,

    /** Записать не вышло (нет файла, форма не узнана, ошибка ввода-вывода). */
    FAILED,
}

/** Агенты, которым достаётся тяжёлая модель (сложные многошаговые задачи). */
private val HEAVY_AGENTS = listOf("build", "plan")

/** Агенты, которым достаётся лёгкая быстрая модель (поиск, вспомогательные шаги). */
private val LIGHT_AGENTS = listOf("explore", "general")

private const val AGENT_KEY = "agent"

private const val SMALL_MODEL_KEY = "small_model"

private const val MODEL_KEY = "model"

/**
 * Решает, что делать с записями `small_model` и `agent.<id>.model`, ничего не записывая.
 *
 * Вынесено отдельной функцией (как [planModelEntry] и [planQuestionPermission]) по
 * одной причине: правка управляемого конфига — место, где легко тихо испортить
 * рабочую установку, и проверять её должен юнит-тест, а не устройство.
 *
 * Четыре агента (`build`, `plan` → тяжёлая; `explore`, `general` → лёгкая) и
 * `small_model` считаются управляемыми: пользователь включил маршрутизацию из UI,
 * поэтому их значения мы вправе переписать. Чужой формы не трогаем: если `agent`
 * или конкретный агент существует не объектом, а `model` внутри агента — не строкой,
 * план `Refuse` (лог честно скажет, что файл не понят).
 */
internal fun planRoutingEntry(
    text: String,
    routing: ModelRouting,
): RoutingPlan {
    if (rootObjectStart(text) < 0) return RoutingPlan.Refuse
    val updated = upsertRootString(text, SMALL_MODEL_KEY, routing.small)?.let { upsertAgents(it, routing) }
    return when {
        updated == null -> RoutingPlan.Refuse
        updated == text -> RoutingPlan.Keep
        else -> RoutingPlan.Write(updated)
    }
}

/** Меняет/добавляет корневой строковый ключ [key]. null — ключ есть, но его значение не строка. */
private fun upsertRootString(
    text: String,
    key: String,
    value: String,
): String? =
    when (val span = rootStringValueSpan(text, key)) {
        null ->
            if (hasTopLevelKey(text, key)) {
                null
            } else {
                insertMember(text, "\"$key\": ${quoted(value)}", "  ", "")
            }
        else -> text.substring(0, span.first) + quoted(value) + text.substring(span.second)
    }

/** Дописывает/правит объект `agent` целиком, сохраняя остальные корневые ключи. */
private fun upsertAgents(
    text: String,
    routing: ModelRouting,
): String? {
    val span = memberObjectSpan(text, AGENT_KEY)
    return if (span == null) {
        if (hasTopLevelKey(text, AGENT_KEY)) {
            null // ключ есть, но значением не объект — текстовой правкой не разобрать
        } else {
            insertMember(text, "\"$AGENT_KEY\": ${agentBody(routing)}", "  ", "")
        }
    } else {
        val targets = HEAVY_AGENTS.map { it to routing.heavy } + LIGHT_AGENTS.map { it to routing.light }
        val startBody: String? = text.substring(span.first, span.second + 1)
        val body = targets.fold(startBody) { acc, (id, model) -> acc?.let { upsertAgent(it, id, model) } }
        body?.let { text.substring(0, span.first) + it + text.substring(span.second + 1) }
    }
}

/** Ставит `model` внутрь объекта агента [id], заводя сам агент при необходимости. */
private fun upsertAgent(
    body: String,
    id: String,
    model: String,
): String? {
    val span = memberObjectSpan(body, id)
    return if (span == null) {
        if (hasTopLevelKey(body, id)) {
            null
        } else {
            insertMember(body, "\"$id\": { \"model\": ${quoted(model)} }", "    ", "  ")
        }
    } else {
        replaceAgentModel(body, span, model)
    }
}

/** Правит `model` внутри уже существующего объекта агента. */
private fun replaceAgentModel(
    body: String,
    span: Pair<Int, Int>,
    model: String,
): String? {
    val agentObj = body.substring(span.first, span.second + 1)
    val modelSpan = rootStringValueSpan(agentObj, MODEL_KEY)
    val updated =
        when {
            modelSpan != null ->
                agentObj.substring(0, modelSpan.first) + quoted(model) + agentObj.substring(modelSpan.second)
            hasTopLevelKey(agentObj, MODEL_KEY) -> return null
            else -> insertMember(agentObj, "\"model\": ${quoted(model)}", "    ", "  ")
        }
    return body.substring(0, span.first) + updated + body.substring(span.second + 1)
}

/** Вставляет запись в объект [container] сразу после `{`, учитывая пустоту и отступ. */
private fun insertMember(
    container: String,
    entry: String,
    indent: String,
    closeIndent: String,
): String {
    val brace = rootObjectStart(container)
    val closing = nextSignificant(container, brace + 1)
    val tail =
        if (closing < container.length && container[closing] == '}') {
            "\n$indent$entry\n$closeIndent"
        } else {
            "\n$indent$entry,"
        }
    return container.substring(0, brace + 1) + tail + container.substring(brace + 1)
}

private fun agentBody(routing: ModelRouting): String {
    val entries =
        HEAVY_AGENTS.map { "\"$it\": { \"model\": ${quoted(routing.heavy)} }" } +
            LIGHT_AGENTS.map { "\"$it\": { \"model\": ${quoted(routing.light)} }" }
    return "{\n" + entries.joinToString(",\n") { "    $it" } + "\n  }"
}

private fun quoted(value: String): String = "\"$value\""
