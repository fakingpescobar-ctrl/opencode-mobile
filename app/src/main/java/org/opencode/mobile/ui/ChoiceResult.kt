package org.opencode.mobile.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencode.mobile.server.ModelCatalog
import org.opencode.mobile.server.ModelRouting
import org.opencode.mobile.server.ModelRoutingPrefs
import org.opencode.mobile.server.OpencodeRuntime
import org.opencode.mobile.server.OpencodeServerService
import org.opencode.mobile.server.RoutingApply
import org.opencode.mobile.server.RoutingReadiness
import org.opencode.mobile.server.SessionModel

/**
 * Итог нажатия на плашку: что показать пользователю и надо ли ещё что-то делать.
 *
 * `pending` — единственный признак, по которому карточка решает, показывать ли
 * плашку «применить». Обычный путь её не требует: модель активной сессии
 * меняется на лету (`POST /api/session/{id}/model`), а новая сессия рождается с
 *разу на выбранной. Плашка появляется только там, где этот путь не сработал.
 *
 * Плашка при этом не «вечная просьба»: если помочь нечем (нет сессии, сервер
 * не отвечает), она не показывается, а статус говорит правду. Иначе карточка
 * требовала бы нажать на кнопку, которая ничего не изменит.
 */
internal data class ChoiceResult(
    val pending: Boolean,
    val message: String,
)

/**
 * Сохраняет выбор в prefs, патчит управляемый конфиг и говорит, что с этим
 * делать дальше. Блокирующая (диск + локальный HTTP), поэтому suspend и зовётся
 * только из `withContext(Dispatchers.IO)`.
 *
 * Порядок именно такой: сначала запись, потом сверка с живым сервером. Сверка
 * до записи врала бы в обе стороны — до неё сервер ещё не видел бы даже только
 * что дописанный блок.
 *
 * Прежнее значение тяжёлой роли читается ДО сохранения: правка лёгких или
 * служебных моделей не должна дёргать активную сессию (она о ней ничего не
 * знает) и уж тем более пугать пользователя «нажми применить».
 */
internal suspend fun commitRoutingChoice(
    context: Context,
    enabled: Boolean,
    routing: ModelRouting,
    onSaved: (Boolean) -> Unit,
): ChoiceResult =
    withContext(Dispatchers.IO) {
        val previous = if (ModelRoutingPrefs.isEnabled(context)) ModelRoutingPrefs.current(context).heavy else null
        if (!enabled) {
            ModelRoutingPrefs.disable(context)
            onSaved(false)
            ChoiceResult(pending = false, message = DISABLED_TEXT)
        } else {
            ModelRoutingPrefs.save(context, routing)
            onSaved(true)
            settleChoice(context, routing, previous)
        }
    }

/**
 * Записали выбор — теперь решаем, что сказать пользователю.
 *
 * Порядок именно такой: сначала запись конфига (она нужна для новых сессий и
 * для любого другого клиента), потом горячая смена модели текущей сессии.
 * Рестарт в этом пути не участвует: он рвёт стриминг сессии и остаётся только
 * запасным действием плашки «применить».
 *
 * Единственный источник правды — ответ сервера: если он принял смену, выбор
 * применён к тому, на чём пользователь реально работает.
 */
private fun settleChoice(
    context: Context,
    routing: ModelRouting,
    previousHeavy: String?,
): ChoiceResult {
    val applied = OpencodeRuntime.applyModelRouting(context)
    if (applied == RoutingApply.FAILED) return ChoiceResult(pending = false, message = FAILED_TEXT)
    // Два независимых факта складываются в один ответ: что стало с активной
    // сессией (переключается на лету) и что с лёгкими/служебными (живут только
    // в конфиге). Молчаливый сервер выпадает из текста, но не из проверки.
    val heavyChanged = previousHeavy != routing.heavy
    val heavyOk = !heavyChanged || alignActiveSession(routing.heavy)
    val heavyText =
        when {
            !heavyChanged -> KEPT_TEXT
            heavyOk -> appliedText(routing)
            else -> SWITCH_FAILED_TEXT
        }
    val roles = rolesOutcome(routing)
    val text = listOf(heavyText, roles.message).filter(String::isNotBlank).joinToString("; ")
    return ChoiceResult(pending = !heavyOk || roles.pending, message = text)
}

/**
 * Что с лёгкими и служебными ролями.
 *
 * Их смена живёт ТОЛЬКО в конфиге, а работающий serve его не перечитывает — в
 * отличие от тяжёлой роли, которую мы умеем переключать на лету. Поэтому сверка
 * идёт по живому `/api/config`: пока сервер отдаёт старые `agent.explore` и
 * `small_model`, выбор не применён и рестарт нужен по-настоящему.
 *
 * Три исхода, а не два: молчащий сервер судить не о чем, и требовать от него
 * рестарт бессмысленно.
 */
private fun rolesOutcome(routing: ModelRouting): ChoiceResult {
    val served = RoutingReadiness.served(OpencodeServerService.state.value.port)
    val inSync =
        served != null &&
            RoutingReadiness.routingServed(served, routing) &&
            !RoutingReadiness.smallRoleMismatch(served, routing)
    return when {
        served == null -> ChoiceResult(pending = false, message = NO_ANSWER_TEXT)
        inSync -> ChoiceResult(pending = false, message = "")
        else -> ChoiceResult(pending = true, message = ROLES_PENDING_TEXT)
    }
}

/**
 * Приводит модель активной сессии к выбранной.
 *
 * Нет активной сессии — переключать нечего, и это не отказ: следующая сессия
 * родится уже с этой моделью (см. `createSession` в ChatOverlay). Сессия уже на
 * нужной модели — тоже успех, повторный POST был бы лишним.
 */
private fun alignActiveSession(model: String): Boolean {
    val port = OpencodeServerService.state.value.port
    val sessionId = ChatCache.sessionId
    if (sessionId == null) return true
    val served = SessionModel.current(port, sessionId)
    val alreadyOn = served != null && SessionModel.matches(served, model)
    return alreadyOn || SessionModel.switch(port, sessionId, model)
}

/**
 * Состояние карточки при открытии, а не по нажатию.
 *
 * Отдельная проверка, потому что плашка «применить» обязана быть видна и без
 * нажатий: перезапуск приложения, потеря процесса или правка конфига руками
 * оставляют файл новым, а работающую сессию — на старой модели.
 *
 * Сверяемся не с корневым `model` из `/api/config` (сервер читает его раз при
 * старте, и он всегда отстаёт), а с моделью самой активной сессии — это ровно
 * та, на которой пользователь говорит с агентом. Если сессии ещё нет (лента не
 * разобралась после запуска) или сервер не ответил, действие не предлагается:
 * нечего применять и нечем проверять.
 */
internal suspend fun currentChoiceState(
    context: Context,
    routing: ModelRouting,
): ChoiceResult =
    withContext(Dispatchers.IO) {
        when {
            !ModelRoutingPrefs.isEnabled(context) -> ChoiceResult(pending = false, message = "")
            ChatCache.sessionId == null -> ChoiceResult(pending = false, message = NO_SESSION_TEXT)
            else -> {
                // Сверяются оба признака, как и после нажатия: модель активной
                // сессии И живой конфиг. Иначе сервер, не перечитавший конфиг
                // после чужой правки, считался бы «всё применено», а лёгкие и
                // служебные роли так и остались бы старыми.
                val session = sessionState(routing)
                val roles = rolesOutcome(routing)
                val text = listOf(session.message, roles.message).filter(String::isNotBlank).joinToString("; ")
                ChoiceResult(pending = session.pending || roles.pending, message = text)
            }
        }
    }

/**
 * Сверка по ответу живого сервера. Молчащий сервер — не повод звать рестарт:
 * про него скажет [rolesOutcome], и повторяться двумя одинаковыми строками
 * незачем.
 */
private fun sessionState(routing: ModelRouting): ChoiceResult {
    val sessionId = ChatCache.sessionId
    val served = sessionId?.let { SessionModel.current(OpencodeServerService.state.value.port, it) }
    return when {
        served == null -> ChoiceResult(pending = false, message = "")
        SessionModel.matches(served, routing.heavy) -> ChoiceResult(pending = false, message = "")
        else -> ChoiceResult(pending = true, message = STALE_TEXT)
    }
}

/**
 * Мягкий рестарт serve с проверкой результата — запасное действие плашки
 * «применить» на случай, когда смена модели на лету не прошла.
 *
 * Проверяем по обоим признакам сразу: сервер подхватил новый конфиг (этим
 * перезапуск и нужен — конфиг он читает только на старте) И активная сессия
 * работает на выбранной модели. Раньше проверялся только конфиг, и плашка
 * снималась с сессией, оставшейся на старой модели.
 */
internal suspend fun restartAndVerify(
    context: Context,
    routing: ModelRouting,
): ChoiceResult =
    withContext(Dispatchers.IO) {
        OpencodeServerService.restart(context)
        val configTaken = RoutingReadiness.awaitServed(OpencodeServerService.state.value.port, routing)
        val sessionAligned = configTaken && alignActiveSession(routing.heavy)
        when {
            !configTaken -> ChoiceResult(pending = true, message = RESTART_FAILED_TEXT)
            !sessionAligned -> ChoiceResult(pending = true, message = SWITCH_FAILED_TEXT)
            else -> ChoiceResult(pending = false, message = "$RESTARTED_TEXT: ${ModelCatalog.labelOf(routing.heavy)}")
        }
    }

private fun appliedText(routing: ModelRouting): String = "$APPLIED_TEXT: ${ModelCatalog.labelOf(routing.heavy)}"

/** Выключено управление — записанный блок остаётся, сообщаем ровно это. */
private const val DISABLED_TEXT = "выключено — записанный блок оставлен как есть"

/** Правка не тяжёлой роли, а живой сервер уже обслуживает новую: применено. */
private const val KEPT_TEXT = "выбор сохранён"

/** Лёгкие/служебные роли живут только в конфиге — до рестарта агента не применятся. */
private const val ROLES_PENDING_TEXT = "лёгкие и служебные роли сервер прочитает только при перезапуске — нажми «применить»"

/** Конфиг не наш — патчить нечего, и рестарт тут не поможет. */
private const val FAILED_TEXT =
    "выбор сохранён, но конфиг пропатчить не вышло — форма файла не наша; перезапусти приложение руками"

/** Смена на лету не прошла — остаётся запасной путь через рестарт. */
private const val SWITCH_FAILED_TEXT =
    "конфиг записан, но текущую сессию переключить не вышло — нажми «применить» (перезапуск агента)"

/** Модель активной сессии разошлась с выбором. */
private const val STALE_TEXT = "текущая сессия ещё на старой модели — нажми «применить»"

/** Лента ещё не разобралась после запуска — сверять не с чем, и применять нечего. */
private const val NO_SESSION_TEXT = "сессия ещё не открыта — выбор применён к ней при первом сообщении"

/** Сервер не ответил: рестарт не поможет, пока он не поднимется. */
private const val NO_ANSWER_TEXT = "агент не отвечает — проверь его состояние в диагностике"

/** Сервер принял смену модели. */
private const val APPLIED_TEXT = "применено"

/** Рестарт прошёл и сессия встала на выбранную модель. */
private const val RESTARTED_TEXT = "применено (агент перезапущен)"

/** Агент после рестарта не подхватил конфиг. */
private const val RESTART_FAILED_TEXT = "агент не подхватил новый конфиг — нажми «применить» ещё раз"
