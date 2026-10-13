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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencode.mobile.stt.SttModel
import org.opencode.mobile.stt.SttModels

// Цвета и отступы — константами по правилам репозитория: detekt считает
// литерал цвета магическим числом, а не только его второе появление.
private const val PICK_BULLET_ARGB = 0xFFFF6D00L
private const val PICK_TEXT_ARGB = 0xFFE6E6EL
private const val PICK_DIM_ARGB = 0xFF90A4AEL
private const val PICK_OK_ARGB = 0xFF7BD88FL
private const val PICK_MISSING_ARGB = 0xFFFF6F5AL
private const val PICK_BG_ARGB = 0xFF1C1C1CL
private val PICK_CORNER = 8.dp
private const val PICK_LEFT_PAD = 20
private const val PICK_GAP = 2

/**
 * Выбор модели распознавания для движка whisper.cpp.
 *
 * Правило, которое важнее всего: **модель, которой нет на диске, выбрать
 * нельзя.** Раньше выбора модели не было вовсе — только движок, — и если
 * файл не скачан, нажатие на микрофон приводило к ошибке в чате уже после
 * записи. Теперь такая модель показана, но тусклая и без реакции на нажатие,
 * а рядом написано, куда идти.
 *
 * Готовность моделей считается в IO-ветке: [SttModel.isReady] хеширует файл
 * целиком (141 МБ у base, 1,5 ГБ у turbo), и вызов в теле композабла повесил бы
 * панель настроек на секунды. Здесь поэтому стоит LaunchedEffect, а не чтение
 * при построении.
 */
@Composable
internal fun SttModelPicker(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var readyIds by remember { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(Unit) {
        readyIds =
            withContext(Dispatchers.IO) {
                SttModels.all
                    .filter { it.isReady(context) }
                    .map { it.id }
                    .toSet()
            }
    }

    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp)
            .background(Color(PICK_BG_ARGB), RoundedCornerShape(PICK_CORNER))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text("Модель (Whisper.cpp):", color = Color(PICK_DIM_ARGB), fontSize = 12.sp)
        for (model in SttModels.all) {
            ModelRow(
                model = model,
                ready = model.id in readyIds,
                selected = model.id == selected,
                onSelect = { onSelect(model.id) },
            )
        }
    }
}

/**
 * Строка модели. Кликабельна только когда файл уже на диске: иначе выбор
 * «сработает», а распознавание упадёт с «модель не скачана» посреди записи.
 */
@Composable
private fun ModelRow(
    model: SttModel,
    ready: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = ready) { onSelect() }
            .padding(start = PICK_LEFT_PAD.dp, top = PICK_GAP.dp, bottom = PICK_GAP.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selected) "● " else "○ ",
                color = Color(PICK_BULLET_ARGB),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                model.id,
                color = if (ready) Color(PICK_TEXT_ARGB) else Color(PICK_MISSING_ARGB),
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                "  " + model.sizeLabel,
                color = Color(PICK_DIM_ARGB),
                fontSize = 10.sp,
            )
            Text(
                if (ready) "  ✔ скачана" else "  ✘ не скачана",
                color = if (ready) Color(PICK_OK_ARGB) else Color(PICK_MISSING_ARGB),
                fontSize = 10.sp,
            )
        }
        Text(
            model.note + if (ready) "" else " — сначала скачай: Настройки -> Диагностика",
            color = Color(PICK_DIM_ARGB),
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}
