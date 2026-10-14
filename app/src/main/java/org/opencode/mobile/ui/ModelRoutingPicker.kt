package org.opencode.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.opencode.mobile.server.ModelCatalog
import org.opencode.mobile.server.ModelRouting
import org.opencode.mobile.server.ModelRoutingPrefs

/** Роль агента, за которой закрепляется модель. Список ролей конечен и известен. */
private enum class RoutingRole(
    val chip: String,
    val hint: String,
) {
    HEAVY("ТЯЖЁЛЫЕ", "build, plan — сложные многошаговые задачи"),
    LIGHT("ЛЁГКИЕ", "explore, general — поиск и вспомогательные шаги"),
    SMALL("СЛУЖЕБНЫЕ", "title, summary, compaction — короткие генерации"),
}

/**
 * Карточка маршрутизации моделей по ролям. Графически — ровно карточка
 * Moltbook: та же подложка, та же обводка, тот же заголовок с разрядкой и те же
 * плашечки-чипы вместо списка с галочками. Различие только в предмете: не
 * «что случилось с журналом», а «какая модель у каких агентов opencode».
 *
 * Выбор применяется СРАЗУ по нажатию плашечки — отдельной кнопки «Сохранить»
 * нет намеренно: лишний шаг между решением и результатом в настройке, которую
 * открывают раз в пару месяцев, только мешает. Запись конфига синхронная
 * (чтение + tmp/rename + читающая проверка), поэтому она уходит в IO — на кадре
 * это фриз. Дальше — мягкий рестарт serve, потому что конфиг opencode
 * перечитывается только при старте процесса; он остаётся отдельным явным
 * действием, и успех его проверяется по живому серверу, а не по факту записи.
 *
 * Выключение не стирает уже записанный блок: оно лишь перестаёт им управлять,
 * чтобы правка конфига вручную не пропадала из-под пользователя.
 */
@Composable
internal fun ModelRoutingPicker(
    modifier: Modifier = Modifier,
    onSaved: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(ModelRoutingPrefs.isEnabled(context)) }
    var routing by remember { mutableStateOf(ModelRoutingPrefs.current(context)) }
    var role by remember { mutableStateOf(RoutingRole.HEAVY) }
    var status by remember { mutableStateOf("") }
    // true = конфиг на диске новый, но сервер ещё читает старый. Сервер
    // подхватит новое только после рестарта, а рестарт нельзя делать без
    // спроса: он рвёт стриминг текущей сессии. Поэтому это явное действие.
    var pending by remember { mutableStateOf(false) }
    // Флаг идущего рестарта: плашка остаётся на экране всё время опроса
    // сервера, и без этого флага два така подряд поднимали бы второй рестарт
    // поверх первого.
    var applying by remember { mutableStateOf(false) }

    // Рассинхрон может появиться не только из нажатий: перезапуск приложения,
// правка конфига руками, убитый процесс. Поэтому при открытии карточки
    // спрашиваем сервер, а не полагаемся на остаточное состояние remember.
    // Ответ приходит асинхронно, поэтому сверяем его со снимком выбора: пока
    // пользователь не нажал ещё одну плашку, снимок совпадает и результат
    // применяется. Разошлись — ответ прошлого состояния, выкидываем его,
    // иначе он снимет плашку «применить» у только что записанного выбора.
    LaunchedEffect(Unit) {
        val snapshot = routing
        val state = currentChoiceState(context, snapshot)
        if (routing != snapshot) return@LaunchedEffect
        pending = state.pending
        if (state.message.isNotEmpty()) status = state.message
    }

    fun commit(
        next: ModelRouting,
        on: Boolean,
    ) {
        // Правка модели посреди опроса рестарта ломает сверку: завершившийся
        // applyNow пришёл бы со старым routing и объявил «применено» для модели,
        // которую сервер ещё не обслуживает. Поэтому на время рестарта карточка
        // не принимает выбор — кнопки просто не жмутся.
        if (applying) return
        routing = next
        enabled = on
        scope.launch {
            val result =
                runCatching { commitRoutingChoice(context, on, next, onSaved) }
                    .getOrElse { ChoiceResult(pending = false, message = FAILED_TEXT) }
            if (routing != next) return@launch
            status = result.message
            pending = result.pending
        }
    }

    /**
     * Рестарт с проверкой результата.
     *
     * Не «попросили — считаем, что сработало»: после рестарта спрашиваем живой
     * сервер и только по его ответу снимаем плашку. Слепой рестарт оставлял бы
     * карточку врёт (или, наоборот, прятал неудачу), а плашка «применить» на
     * неудаче должна остаться — иначе пользователь решает, что всё применено.
     */
    fun applyNow() {
        if (applying) return
        applying = true
        status = RESTARTING
        // Снимок, а не текущее значение: applyNow обязан сверяться с тем
        // выбором, ради которого рестарт и поднимался.
        val target = routing
        scope.launch {
            // finally, а не присваивание после: исключение рестарта (например,
            // запрет startForegroundService на фоне) иначе навсегда оставило бы
            // плашку в состоянии «перезапускаю» с отключённым нажатием.
            try {
                // Рестарт и опрос — с сетью и сервисами, а uncaught-исключение
                // из корутины в rememberCoroutineScope валит приложение. Отказ
                // показываем текстом и снимаем блокировку, чтобы карточка
                // осталась в рабочем состоянии.
                val result =
                    runCatching { restartAndVerify(context, target) }
                        .getOrElse { ChoiceResult(pending = true, message = FAILED_TEXT) }
                if (routing == target) {
                    status = result.message
                    pending = result.pending
                }
            } finally {
                applying = false
            }
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MoltCard)
            .border(1.dp, MoltAccent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        RoutingHeader(enabled, !applying) { commit(routing, !enabled) }

        Spacer(Modifier.height(6.dp))
        DividerLine()
        Spacer(Modifier.height(6.dp))

        Caption("РОЛЬ АГЕНТА · нажатием плашки выбираем, кому какая модель")
        RoleChips(role, !applying) { role = it }
        Caption(role.hint, top = 3)

        Spacer(Modifier.height(6.dp))
        ModelChips(
            current = modelOf(routing, role),
            enabled = !applying,
            onPick = { commit(setModel(routing, role, it), true) },
        )

        val note = ModelCatalog.all.firstOrNull { it.id == modelOf(routing, role) }?.note ?: ""
        RoutingFooter(note, status, pending, applying, ::applyNow)
    }
}

/**
 * Шапка карточки: название плюс тумблер «ВКЛ/ВЫКЛ».
 *
 * Вынесена отдельно от [ModelRoutingPicker], чтобы та не разрослась за счёт
 * разметки: её длина упирается в лимит detekt, а именно её логика (сверка
 * снимков и блокировка на время рестарта) нужна читаемой целиком.
 */
@Composable
private fun RoutingHeader(
    enabled: Boolean,
    togglable: Boolean,
    onToggle: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "МОДЕЛИ АГЕНТА",
            color = MoltAccent,
            fontSize = SMALL,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            lineHeight = SMALL_LINE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        RoutingChip(
            text = if (enabled) "ВКЛ" else "ВЫКЛ",
            selected = enabled,
            onClick = onToggle,
            enabled = togglable,
        )
    }
}

/**
 * Подвал карточки: что умеет выбранная модель, что сделало последнее нажатие и
 * — когда сервер ещё не подтвердил новое расписание — плашка «ПРИМЕНИТЬ».
 * Отдельное действие, а не автоматика на каждый тап: рестарт обрывает стриминг
 * текущей сессии, и молча рвать его без спроса нельзя. Плашка не прячется, пока
 * сервер реально не подтвердит новые модели (см. [RoutingReadiness.awaitServed]).
 */
@Composable
private fun RoutingFooter(
    note: String,
    status: String,
    pending: Boolean,
    applying: Boolean,
    onApply: () -> Unit,
) {
    if (note.isNotEmpty()) {
        Text(
            text = note,
            color = MoltIdle,
            fontSize = TINY,
            lineHeight = TINY_LINE,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (pending) {
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoutingChip(
                text = if (applying) "ПЕРЕЗАПУСКАЮ АГЕНТА…" else "ПРИМЕНИТЬ · ПЕРЕЗАПУСТИТЬ АГЕНТА",
                selected = true,
                onClick = onApply,
                enabled = !applying,
            )
        }
    }
    Text(
        text = status.ifEmpty { APPLY_HINT },
        color = if (status.isEmpty()) MoltIdle else MoltValue,
        fontSize = TINY,
        lineHeight = TINY_LINE,
        modifier = Modifier.padding(top = 5.dp),
    )
}

/** Три роли плашками в ряд: ширина делится поровну, поэтому карточка тянется
 *  на любой экран, а не подгоняется под 319 dp, как в шапке чата. */
@Composable
private fun RoleChips(
    selected: RoutingRole,
    enabled: Boolean,
    onSelect: (RoutingRole) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
        RoutingRole.entries.forEach { role ->
            RoutingChip(
                text = role.chip,
                selected = role == selected,
                onClick = { onSelect(role) },
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
        }
    }
}

/** Сетка моделей по две в ряду: подписи вида «Nemotron 3.5 Lightning» влезают
 *  в половину карточки, а id («muse-spark-1.3-contributor-free») — нет. */
@Composable
private fun ModelChips(
    current: String,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
        ModelCatalog.all.chunked(MODELS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
                row.forEach { model ->
                    RoutingChip(
                        text = model.label,
                        selected = model.id == current,
                        onClick = { onPick(model.id) },
                        modifier = Modifier.weight(1f),
                        enabled = enabled,
                    )
                }
                repeat(MODELS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Плашечка. Ровно чип из MoltbookIntervalChip: та же скруглённая подложка, та же
 * обводка только у выбранного, жирный акцентный текст вместо серого.
 */
@Composable
private fun RoutingChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // Цвет обводки — отдельно, иначе цепочка модификаторов разъезжается на две
    // строки и ktlint требует переносить точку в конец предыдущей, ломая порядок
    // рисования (см. MoltbookIntervalChip).
    val borderColor = if (selected) MoltAccent.copy(alpha = 0.7f) else Color.Transparent
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) MoltAccent.copy(alpha = 0.14f) else MoltChipOff)
                .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                .clickable(enabled = enabled) { onClick() }
                .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) MoltAccent else MoltIdle,
            fontSize = BODY,
            lineHeight = BODY_LINE,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Caption(
    text: String,
    top: Int = 5,
) {
    Text(
        text = text,
        color = MoltIdle,
        fontSize = TINY,
        letterSpacing = 0.5.sp,
        lineHeight = TINY_LINE,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = top.dp),
    )
}

@Composable
private fun DividerLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MoltAccent.copy(alpha = 0.15f)))
}

private fun modelOf(
    routing: ModelRouting,
    role: RoutingRole,
): String =
    when (role) {
        RoutingRole.HEAVY -> routing.heavy
        RoutingRole.LIGHT -> routing.light
        RoutingRole.SMALL -> routing.small
    }

private fun setModel(
    routing: ModelRouting,
    role: RoutingRole,
    id: String,
): ModelRouting =
    when (role) {
        RoutingRole.HEAVY -> routing.copy(heavy = id)
        RoutingRole.LIGHT -> routing.copy(light = id)
        RoutingRole.SMALL -> routing.copy(small = id)
    }

/** Моделей в ряду: подпись самой длинной (Nemotron 3.5 Lightning) влезает в
 *  половину карточки, в третьей колонке — обрезалась бы многоточием. */
private const val MODELS_PER_ROW = 2

/** Зазор между плашками — тот же, что у чипов периодичности Moltbook. */
private val CHIP_GAP = 6.dp

/** Подпись под карточкой до первого нажатия: без неё карточка выглядит так,
 *  будто выбор уже действует, хотя конфиг ещё не патчен. */
private const val APPLY_HINT = "выбор пишется сразу; чтобы сервер его увидел — нажми «применить»"

/** Статус на время рестарта: плашка «применить» уже нажата, ждём новый процесс. */
private const val RESTARTING = "перезапускаю агента…"

/** Отказ записи или рестарта: показываем вместо падения приложения. */
private const val FAILED_TEXT = "не получилось сохранить — попробуй ещё раз"
