package org.opencode.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
private val DiagGlow = Color(0x33FF8A3D)

@Composable
internal fun SttModelPicker(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var readyIds by remember { mutableStateOf(emptySet<String>()) }

    val visibleModels = remember { SttModels.all.filterNot { it.hiddenFromPicker } }
    LaunchedEffect(Unit) {
        readyIds = withContext(Dispatchers.IO) {
            visibleModels.filter { it.isReady(context) }.map { it.id }.toSet()
        }
    }

    val intro = rememberInfiniteTransition(label = "stt-picker-intro")
    val glow by intro.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
        label = "stt-picker-glow",
    )

    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1B1B1B), DiagBg, Color(0xFF141414)),
                ),
            )
            .border(1.dp, DiagBorder.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
            .drawBehind {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, DiagAccent.copy(alpha = glow * 0.18f), Color.Transparent),
                    ),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                )
            }
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(DiagAccent.copy(alpha = glow + 0.2f)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Модели распознавания",
                color = DiagText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            StatusTag(
                visibleModels.count { it.id in readyIds }.toString() + " готовы",
                DiagOk,
            )
        }
        Text(
            "Стиль диагностики · рекомендованные/рабочие модели",
            color = DiagDim,
            fontSize = 10.sp,
        )
        visibleModels.forEachIndexed { index, model ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(220, delayMillis = (index * 35).coerceAtMost(280))) +
                    slideInVertically(tween(220, delayMillis = (index * 35).coerceAtMost(280))) { it / 4 },
            ) {
                ModelRow(
                    model = model,
                    ready = model.id in readyIds,
                    selected = model.id == selected,
                    onSelect = { onSelect(model.id) },
                )
            }
        }
        Text(
            "Скачивание/удаление и скрытые экспериментальные модели — в Диагностике.",
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
    val borderColor by animateColorAsState(
        when {
            selected -> DiagAccent
            enabled -> Color(0xFF4A4A4A)
            else -> DiagBorder
        },
        label = "stt-border-" + model.id,
    )
    val bgColor by animateColorAsState(
        if (selected) DiagCardSelected else DiagCard,
        label = "stt-bg-" + model.id,
    )
    val pulse = rememberInfiniteTransition(label = "stt-pulse-" + model.id)
    val dotAlpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (selected) 900 else 1400, easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "stt-dot-" + model.id,
    )
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
            .graphicsLayer { alpha = if (enabled || selected) 1f else 0.92f }
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) {
                    Brush.linearGradient(listOf(Color(0xFF2A211A), DiagCardSelected, Color(0xFF201F1F)))
                } else {
                    Brush.linearGradient(listOf(bgColor, bgColor))
                },
            )
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .drawBehind {
                if (selected) {
                    drawRoundRect(
                        color = DiagGlow.copy(alpha = 0.18f),
                        cornerRadius = CornerRadius(10.dp.toPx()),
                    )
                }
            }
            .clickable(enabled = enabled) { onSelect() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        when {
                            selected -> DiagAccent.copy(alpha = dotAlpha)
                            ready && model.runnable -> DiagOk.copy(alpha = dotAlpha)
                            else -> Color(0xFF555555)
                        },
                    )
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
        val hint = model.note
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
