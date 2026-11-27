package org.opencode.mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencode.mobile.stt.SttModel
import org.opencode.mobile.stt.SttModels

private val Bg = Color(0xFF161616)
private val Card = Color(0xFF1E1E1E)
private val CardSel = Color(0xFF25211D)
private val Border = Color(0xFF303030)
private val Accent = Color(0xFFFF8A3D)
private val Text = Color(0xFFE8E8E8)
private val Dim = Color(0xFF8F9AA3)
private val Ok = Color(0xFF7BD88F)
private val Warn = Color(0xFFFFC107)
private val Bad = Color(0xFFFF6F5A)

@Composable
internal fun SttModelPicker(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var readyIds by remember { mutableStateOf(emptySet<String>()) }
    val catalogModels = remember { SttModels.all.filterNot { it.hiddenFromPicker } }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        readyIds = withContext(Dispatchers.IO) {
            catalogModels.filter { it.isReady(context) }.map { it.id }.toSet()
        }
        checked = true
    }
    val visibleModels = remember(readyIds, checked) { catalogModels.filter { it.id in readyIds && it.runnable } }

    val pulse = rememberInfiniteTransition(label = "stt-p")
    val glow by pulse.animateFloat(
        initialValue = 0.12f, targetValue = 0.28f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "stt-g",
    )

    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1A1A1A), Bg)))
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .drawBehind {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, Accent.copy(alpha = glow), Color.Transparent),
                    ),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                )
            }
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)) {
            Text("МОДЕЛЬ", color = Dim, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
            Spacer(Modifier.weight(1f))
            Text(
                if (checked) "${visibleModels.size} доступны" else "проверяю…",
                color = Ok.copy(alpha = 0.9f),
                fontSize = 9.sp,
            )
        }
        if (checked && visibleModels.isEmpty()) {
            Text(
                "Скачай модель в Диагностике — после скачивания она появится здесь.",
                color = Dim,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
            )
        }
        visibleModels.forEach { model ->
            val ready = model.id in readyIds
            val isSelected = model.id == selected
            ModelRow(
                model = model,
                ready = ready,
                selected = isSelected,
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
    val border by animateColorAsState(
        when {
            selected -> Accent
            enabled -> Color(0xFF3A3A3A)
            else -> Color(0xFF2C2C2C)
        },
        label = "b-${model.id}",
    )
    val bg by animateColorAsState(if (selected) CardSel else Card, label = "bg-${model.id}")
    val dotColor = when {
        selected -> Accent
        ready && model.runnable -> Ok
        !model.runnable -> Warn
        else -> Bad.copy(alpha = 0.7f)
    }
    val status = when {
        !model.runnable -> "нет runtime"
        ready -> "готова"
        model.manual -> "ручная"
        else -> "не скачана"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(if (selected) 1.2.dp else 1.dp, border, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onSelect() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(8.dp))
            Text(
                model.id,
                color = if (enabled || selected) Text else Color(0xFFA8A8A8),
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (model.recommendedForRu) {
                TinyTag("RU", Color(0xFFFFD54F))
                Spacer(Modifier.width(4.dp))
            }
            TinyTag(status, if (ready && model.runnable) Ok else Dim)
        }
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(model.sizeLabel, color = Accent.copy(alpha = 0.95f), fontSize = 9.sp)
            val hint = model.note.takeIf { it.isNotBlank() && (selected || model.recommendedForRu) }
            if (hint != null) {
                Text(" · ", color = Dim, fontSize = 9.sp)
                Text(
                    hint,
                    color = Dim,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TinyTag(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text, color = color, fontSize = 8.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
