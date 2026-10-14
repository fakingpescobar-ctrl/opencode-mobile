package org.opencode.mobile.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencode.mobile.server.ModelRouting
import org.opencode.mobile.server.ModelRoutingPrefs
import org.opencode.mobile.server.OpencodeRuntime
import org.opencode.mobile.server.OpencodeServerService
import org.opencode.mobile.server.RoutingApply
import org.opencode.mobile.server.RoutingReadiness

/**
 * Итог нажатия на плашку: что показать пользователю и надо ли ещё что-то делать.
 *
 * `pending` — единственный признак, по которому карточка решает, показывать ли
 * плашку «применить». Он вычисляется не по факту записи файла (файл записать мы
 * могли, а агент продолжает читать старый конфиг до рестарта), а по тому, что
 * сервер обслуживает прямо сейчас. Раньше признаком служил префикс текста статуса
 * — и любая правка формулировки ломала применение молча.
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
 */
internal suspend fun commitRoutingChoice(
    context: Context,
    enabled: Boolean,
    routing: ModelRouting,
    onSaved: (Boolean) -> Unit,
): ChoiceResult =
    withContext(Dispatchers.IO) {
        if (!enabled) {
            ModelRoutingPrefs.disable(context)
            onSaved(false)
            ChoiceResult(pending = false, message = "выключено — записанный блок оставлен как есть")
        } else {
            ModelRoutingPrefs.save(context, routing)
            onSaved(true)
            settleChoice(context, routing)
        }
    }

/**
 * Записали выбор — теперь решаем, считать ли дело сделанным.
 *
 * Единственный источник правды здесь — ответ сервера. Если он уже обслуживает
 * нужное расписание, плашку «применить» показывать незачем: рестарт рвёт
 * стриминг текущей сессии, и требовать его без нужды — тот же грех, что и
 * рвать его молча.
 */
private fun settleChoice(
    context: Context,
    routing: ModelRouting,
): ChoiceResult {
    val applied = OpencodeRuntime.applyModelRouting(context)
    val served = RoutingReadiness.servedAgents(OpencodeServerService.state.value.port)
    if (served != null && RoutingReadiness.routingServed(served, routing)) {
        return ChoiceResult(pending = false, message = appliedMessage(served, routing))
    }
    // Плашку «применить» предлагаем только там, где рестарт что-то изменит.
    // При FAILED записи конфиг не пропатчен вовсе: повторный рестарт повторит
    // тот же отказ по 40 попыток и ничего не даст — ровно тот путь, от которого
    // текст выше просит отойти к ручной правке.
    return ChoiceResult(pending = applied != RoutingApply.FAILED, message = pendingMessage(applied, served))
}

/**
 * «Применено» — плюс оговорка, если служебные роли уехали в сторону.
 *
 * Их модели выводит сервер из `small_model`, проверить которую напрямую нечем
 * (в ответе `/api/config` её нет). Поэтому расхождение показываем текстом, но
 * плашку «применить» не возвращаем: повторный рестарт тут не поможет, а
 * требовать его — значит вечно обещать пользователю то, что не сработает.
 */
private fun appliedMessage(
    served: Map<String, String>,
    routing: ModelRouting,
): String =
    if (RoutingReadiness.smallRoleMismatch(served, routing)) {
        "$APPLIED_TEXT; служебные роли уехали с $SMALL_HINT"
    } else {
        APPLIED_TEXT
    }

/**
 * Состояние карточки при открытии, а не по нажатию.
 *
 * Отдельная проверка, потому что плашка «применить» обязана быть видна и без
 * нажатий: перезапуск приложения, потеря процесса или правка конфига руками —
 * всё это оставляет файл новым, а агент старый. Если такую рассинхронизацию
 * показывать только после клика, карточка врала бы «всё применено» именно в тот
 * момент, когда это неправда.
 */
internal suspend fun currentChoiceState(
    context: Context,
    routing: ModelRouting,
): ChoiceResult =
    withContext(Dispatchers.IO) {
        val served = RoutingReadiness.servedAgents(OpencodeServerService.state.value.port)
        val inSync = !ModelRoutingPrefs.isEnabled(context) ||
            (served != null && RoutingReadiness.routingServed(served, routing))
        if (inSync) {
            ChoiceResult(pending = false, message = "")
        } else {
            ChoiceResult(pending = true, message = "$PENDING_TEXT сервер подхватит после рестарта")
        }
    }

/**
 * Мягкий рестарт serve с проверкой результата — то, что делает плашка
 * «применить». Блокирующий опрос живого сервера уходит в IO, а повторный
 * вход во время уже идущего рестарта отсекается флагом вызывающей стороны:
 * два конкурентных рестарта рвали бы стриминг сессии дважды и опрашивали бы
 * порт, который к тому моменту мог уже смениться.
 */
internal suspend fun restartAndVerify(
    context: Context,
    routing: ModelRouting,
): ChoiceResult =
    withContext(Dispatchers.IO) {
        OpencodeServerService.restart(context)
        if (RoutingReadiness.awaitServed(OpencodeServerService.state.value.port, routing)) {
            ChoiceResult(pending = false, message = "применено — агент перезапущен на новых моделях")
        } else {
            ChoiceResult(pending = true, message = "агент не подхватил новый конфиг — нажми «применить» ещё раз")
        }
    }

/**
 * Что именно не сходится — по результату записи и по живости сервера.
 *
 * Ветки разные, потому что действия у них разные: «файл записан» лечится
 * рестартом, «конфиг не патчится» — только руками пользователя (форма файла не
 * наша), а «сервер не ответил» рестартом не лечится вовсе: сначала он должен
 * подняться, и требовать «применить» при молчащем сервере бессмысленно.
 */
private fun pendingMessage(
    applied: RoutingApply,
    served: Map<String, String>?,
): String =
    when {
        applied == RoutingApply.FAILED ->
            "выбор сохранён, но конфиг пропатчить не вышло — форма файла не наша; перезапусти приложение руками"
        served == null ->
            "агент сейчас не отвечает — дождись, пока он поднимется, и нажми «применить»"
        applied == RoutingApply.UNCHANGED ->
            "в конфиге уже так, но агент всё ещё читает старый — нажми «применить»"
        else -> "$PENDING_TEXT сервер подхватит после рестарта"
    }

/** Статус, когда файл новый, а сервер ещё читает старый. */
private const val PENDING_TEXT = "записано в конфиг; агент"

/** Статус, когда сервер обслуживает нужное расписание. */
private const val APPLIED_TEXT = "применено — агент уже работает на этих моделях"

/** Подсказка в статусе про разъехавшиеся служебные роли. */
private const val SMALL_HINT = "small_model (title, summary)"
