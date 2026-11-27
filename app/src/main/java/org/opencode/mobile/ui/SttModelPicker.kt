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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencode.mobile.stt.SttModel
import org.opencode.mobile.stt.SttModels

private val DiagBg = Color(0xFF171717)
private val DiagCard = Color(0xFF1F1F1F)
private val DiagCardSelected = Color(0xFF242424)
private val DiagBorder = Color(0xFF333333)
private val DiagAccent = Color(0xFFFF8A3D)
private val DiagText = Color(0xFFE6E6E6)
private val DiagDim = Color(0xFF90A4AE)
private val DiagOk = Color(0xFF7BD88F)
private val DiagWarn = Color(0xFFFFC107)
private val DiagBad = Color(0xFFFF6F5A)

@Composable
internal fun SttModelPicker(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var readyIds by remember { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(Unit) {
        readyIds = withContext(Dispatchers.IO) {
            SttModels.all.filter { it.isReady(context) }.map { it.id }.toSet()
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(DiagBg)
            .border(1.dp, DiagBorder, RoundedCornerShape(10.dp))
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Модели распознавания",
            color = DiagDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        for (model in SttModels.all) {
            ModelRow(
                model = model,
                ready = model.id in readyIds,
                selected = model.id == selected,
                onSelect = { onSelect(model.id) },
            )
        }
        Text(
            "Скачивание/удаление — в Диагностике. Неподключённые runtime нельзя выбрать.",
            color = DiagDim,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun ModelRow(
    model: SttModel,
    ready: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val enabled = ready && model.runnable
    val border = when {
        selected -> DiagAccent
        enabled -> Color(0xFF4A4A4A)
        else -> DiagBorder
    }
    val statusText = when {
        !model.runnable -> "runtime не подключён"
        ready -> "готова"
        model.manual -> "ручная установка"
        else -> "не скачана"
    }
    val statusColor = when {
        ready && model.runnable -> DiagOk
        !model.runnable -> DiagWarn
        model.manual -> DiagDim
        else -> DiagBad
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) DiagCardSelected else DiagCard)
            .border(if (selected) 1.5.dp else 1.dp, border, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onSelect() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (selected) DiagAccent else Color(0xFF555555))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                model.id,
                color = if (enabled || selected) DiagText else Color(0xFFB0B0B0),
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (model.recommendedForRu) {
                StatusTag("RU", Color(0xFFFFD54F))
                Spacer(Modifier.width(6.dp))
            }
            StatusTag(statusText, statusColor)
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model.sizeLabel, color = DiagAccent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(
                if (selected) "выбрана" else if (enabled) "можно выбрать" else "выбор недоступен",
                color = if (selected) DiagAccent else DiagDim,
                fontSize = 10.sp,
            )
        }
        val hint = when {
            !model.runnable -> model.note
            ready -> model.note
            model.manual -> model.note
            else -> model.note
        }
        if (hint.isNotBlank()) {
            Text(hint, color = DiagDim, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun StatusTag(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, color = color, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}
