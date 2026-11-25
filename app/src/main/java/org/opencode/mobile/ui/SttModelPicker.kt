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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencode.mobile.stt.SttModel
import org.opencode.mobile.stt.SttModels
import androidx.compose.ui.platform.LocalContext

private val AnsiBg = Color(0xFF0D0F14)
private val AnsiCard = Color(0xFF171B24)
private val AnsiCardSelected = Color(0xFF1E1528)
private val AnsiBorder = Color(0xFF343B4A)
private val AnsiPurple = Color(0xFFB388FF)
private val AnsiPurpleDim = Color(0xFF6C4AB6)
private val AnsiOrange = Color(0xFFFF8A3D)
private val AnsiText = Color(0xFFE6EAF2)
private val AnsiDim = Color(0xFF8A93A6)
private val AnsiOk = Color(0xFF7BD88F)
private val AnsiBad = Color(0xFFFF6F5A)

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
                SttModels.all.filter { it.isReady(context) }.map { it.id }.toSet()
            }
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF121018), AnsiBg)))
            .border(1.dp, Color(0xFF2B2338), RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("STT MODELS", color = AnsiPurple, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Spacer(Modifier.weight(1f))
            Text("ANSI", color = AnsiOrange, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        }
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

@Composable
private fun ModelRow(
    model: SttModel,
    ready: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val enabled = ready && model.runnable
    val border = when {
        selected -> AnsiOrange
        enabled -> AnsiPurpleDim
        else -> AnsiBorder
    }
    val bg = if (selected) AnsiCardSelected else AnsiCard
    val statusColor = if (ready && model.runnable) AnsiOk else AnsiBad
    val statusText = when {
        !model.runnable -> "UNSUPPORTED"
        ready -> "READY"
        model.manual -> "MANUAL"
        else -> "NOT DOWNLOADED"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(if (selected) 1.5.dp else 1.dp, border, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onSelect() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (selected) AnsiOrange else Color(0xFF3A4252))
                    .border(1.dp, if (selected) AnsiOrange else Color(0xFF596276), RoundedCornerShape(3.dp))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                model.id,
                color = if (enabled) AnsiText else Color(0xFFB0B7C5),
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (model.recommendedForRu) {
                Tag("RU", AnsiPurple)
                Spacer(Modifier.width(6.dp))
            }
            Tag(statusText, statusColor)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model.sizeLabel, color = AnsiOrange, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(
                if (selected) "SELECTED" else if (enabled) "TAP TO SELECT" else "DOWNLOAD IN DIAGNOSTICS",
                color = if (selected) AnsiPurple else AnsiDim,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        val hint = when {
            !model.runnable -> "Нужна сборка APK с поддержкой этой модели."
            ready -> model.note
            model.manual -> model.note + " — ставится вручную (adb push)."
            else -> model.note + " — сначала скачай: Диагностика."
        }
        if (hint.isNotBlank()) {
            Text(hint, color = AnsiDim, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}
