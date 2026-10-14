package org.opencode.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencode.mobile.server.ModelCatalog
import org.opencode.mobile.server.ModelRouting
import org.opencode.mobile.server.ModelRoutingPrefs
import org.opencode.mobile.server.OpencodeRuntime

// Цвета/отступы — константами: detekt считает литерал цвета магическим числом
// уже при первом появлении, как и в SttModelPicker.
private const val MRP_BG_ARGB = 0xFF1C1C1CL
private const val MRP_TEXT_ARGB = 0xFFE6E6EL
private const val MRP_DIM_ARGB = 0xFF90A4AEL
private const val MRP_ACCENT_ARGB = 0xFFFF6D00L
private const val MRP_OK_ARGB = 0xFF7BD88FL
private val MRP_CORNER = 8.dp
private const val MRP_LEFT_PAD = 20
private const val MRP_GAP = 2

/** Роль агента, за которой закрепляется модель. Список ролей конечен и известен. */
private enum class RoutingRole(
    val title: String,
    val hint: String,
) {
    HEAVY("Тяжёлые (build, plan)", "сложные многошаговые задачи"),
    LIGHT("Лёгкие (explore, general)", "поиск и вспомогательные шаги"),
    SMALL("Служебные (заголовок, сводка)", "короткие авто-генерации"),
}

/**
 * Выбор модели под роль агента. Аналог [SttModelPicker], но предмет другой:
 * не «какой движок распознавания», а «какая модель у каких агентов opencode».
 *
 * Пишем прямо в управляемый конфиг (`ModelRoutingPrefs.save` +
 * `OpencodeRuntime.applyModelRouting`) — это те ключи `small_model` и
 * `agent.<id>.model`, которые конфиг-менеджер намеренно не трогает, пока мы их
 * не завели через [RoutingPlan].
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

    Column(
        modifier
            .fillMaxWidth()
            .background(Color(MRP_BG_ARGB), RoundedCornerShape(MRP_CORNER))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text("Маршрутизация моделей по ролям", color = Color(MRP_TEXT_ARGB), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        ToggleRow(enabled) { enabled = !enabled }

        for (r in RoutingRole.entries) {
            RoleRow(
                role = r,
                active = r == role,
                modelId = modelOf(routing, r),
                onClick = { role = r },
            )
        }

        Text("Модель для выбранной роли:", color = Color(MRP_DIM_ARGB), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        for (model in ModelCatalog.all) {
            ModelRow(
                id = model.id,
                label = model.label,
                note = model.note,
                selected = model.id == modelOf(routing, role),
                onClick = {
                    routing = setModel(routing, role, model.id)
                    if (!enabled) enabled = true
                },
            )
        }

        // Запись конфига — синхронный дисковый I/O (чтение, tmp+rename, читающая
        // проверка). На кадре это фриз, поэтому уводим в IO, как SttModelPicker.
        SaveRow(enabled, status) {
            scope.launch {
                val msg = withContext(Dispatchers.IO) { save(context, enabled, routing, onSaved) }
                status = msg
            }
        }
    }
}

/** Сохраняет выбор и пытается применить его к конфигу немедленно. */
private fun save(
    context: android.content.Context,
    enabled: Boolean,
    routing: ModelRouting,
    onSaved: (Boolean) -> Unit,
): String {
    if (enabled) {
        ModelRoutingPrefs.save(context, routing)
        val applied = OpencodeRuntime.applyModelRouting(context)
        onSaved(enabled)
        return if (applied) "Сохранено и записано в конфиг" else "Сохранено; конфиг подхватится при следующем старте агента"
    }
    ModelRoutingPrefs.disable(context)
    onSaved(enabled)
    return "Выключено — записанный блок оставлен как есть"
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

@Composable
private fun ToggleRow(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(top = MRP_GAP.dp, bottom = MRP_GAP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (enabled) "● " else "○ ",
            color = Color(if (enabled) MRP_OK_ARGB else MRP_DIM_ARGB),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            if (enabled) "включена" else "выключена",
            color = Color(MRP_TEXT_ARGB),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun RoleRow(
    role: RoutingRole,
    active: Boolean,
    modelId: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(start = MRP_LEFT_PAD.dp, top = MRP_GAP.dp, bottom = MRP_GAP.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (active) "◆ " else "◇ ",
                color = Color(MRP_ACCENT_ARGB),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                role.title,
                color = Color(MRP_TEXT_ARGB),
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            )
        }
        Text(
            ModelCatalog.labelOf(modelId),
            color = Color(MRP_ACCENT_ARGB),
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 14.dp),
        )
        Text(
            role.hint,
            color = Color(MRP_DIM_ARGB),
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

@Composable
private fun ModelRow(
    id: String,
    label: String,
    note: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(start = MRP_LEFT_PAD.dp, top = MRP_GAP.dp, bottom = MRP_GAP.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selected) "● " else "○ ",
                color = Color(MRP_ACCENT_ARGB),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                id,
                color = Color(MRP_TEXT_ARGB),
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                "  " + label,
                color = Color(MRP_DIM_ARGB),
                fontSize = 10.sp,
            )
        }
        if (note.isNotEmpty()) {
            Text(
                note,
                color = Color(MRP_DIM_ARGB),
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
    }
}

@Composable
private fun SaveRow(
    enabled: Boolean,
    status: String,
    onSave: () -> Unit,
) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(
            if (enabled) "✔ Сохранить маршрутизацию" else "✔ Сохранить (маршрутизация выключена)",
            color = Color(MRP_OK_ARGB),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { onSave() },
        )
        if (status.isNotEmpty()) {
            Text(status, color = Color(MRP_DIM_ARGB), fontSize = 10.sp)
        }
        Text(
            "opencode перечитывает конфиг при старте агента — надёжнее всего после сохранения открыть новую сессию.",
            color = Color(MRP_DIM_ARGB),
            fontSize = 10.sp,
        )
    }
}
