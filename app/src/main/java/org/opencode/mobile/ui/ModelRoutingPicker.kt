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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencode.mobile.server.ModelCatalog
import org.opencode.mobile.server.ModelRouting
import org.opencode.mobile.server.ModelRoutingPrefs
import org.opencode.mobile.server.OpencodeRuntime

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
 * это фриз.
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

    fun commit(
        next: ModelRouting,
        on: Boolean,
    ) {
        routing = next
        enabled = on
        scope.launch {
            status =
                withContext(Dispatchers.IO) {
                    apply(context, on, next, onSaved)
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
                onClick = { commit(routing, !enabled) },
            )
        }

        Spacer(Modifier.height(6.dp))
        DividerLine()
        Spacer(Modifier.height(6.dp))

        Caption("РОЛЬ АГЕНТА · нажатием плашки выбираем, кому какая модель")
        RoleChips(role) { role = it }
        Caption(role.hint, top = 3)

        Spacer(Modifier.height(6.dp))
        ModelChips(
            current = modelOf(routing, role),
            onPick = { commit(setModel(routing, role, it), true) },
        )

        val note = ModelCatalog.all.firstOrNull { it.id == modelOf(routing, role) }?.note ?: ""
        RoutingFooter(note, status)
    }
}

/**
 * Подпись под сеткой: что умеет выбранная модель и что сделало последнее
 * нажатие. Пустой статус — не «всё хорошо молча», а напоминание, что конфиг
 * opencode перечитывает при старте агента.
 */
@Composable
private fun RoutingFooter(
    note: String,
    status: String,
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
    onSelect: (RoutingRole) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
        RoutingRole.entries.forEach { role ->
            RoutingChip(
                text = role.chip,
                selected = role == selected,
                onClick = { onSelect(role) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Сетка моделей по две в ряду: подписи вида «Nemotron 3.5 Lightning» влезают
 *  в половину карточки, а id («muse-spark-1.3-contributor-free») — нет. */
@Composable
private fun ModelChips(
    current: String,
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
                .clickable { onClick() }
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

/**
 * Пишем выбор в prefs и сразу патчим управляемый конфиг — это те ключи
 * `small_model` и `agent.<id>.model`, которые конфиг-менеджер намеренно не трогает.
 * Функция блокирующая (диск), поэтому зовётся только из withContext(Dispatchers.IO).
 */
private fun apply(
    context: android.content.Context,
    enabled: Boolean,
    routing: ModelRouting,
    onSaved: (Boolean) -> Unit,
): String {
    if (!enabled) {
        ModelRoutingPrefs.disable(context)
        onSaved(false)
        return "выключено — записанный блок оставлен как есть"
    }
    ModelRoutingPrefs.save(context, routing)
    val applied = OpencodeRuntime.applyModelRouting(context)
    onSaved(true)
    return if (applied) "записано в конфиг" else "сохранено; конфиг подхватится при следующем старте агента"
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

/** Подпись под карточкой до первого нажатия: конфиг opencode перечитывает при
 *  старте агента, и без этой оговорки «применилось» читается как «уже применено». */
private const val APPLY_HINT = "перечитывается при старте агента — надёжнее открыть новую сессию"
