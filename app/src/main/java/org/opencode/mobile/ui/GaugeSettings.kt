package org.opencode.mobile.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

private val BgTop = Color(0xFF141018)
private val BgBottom = Color(0xFF0B0D12)
private val CardBg = Color(0xFF171C26)
private val CardBg2 = Color(0xFF111722)
private val BorderDim = Color(0xFF2E3648)
private val NeonPurple = Color(0xFFB388FF)
private val NeonOrange = Color(0xFFFF8A3D)
private val TextMain = Color(0xFFEAF0FF)
private val TextDim = Color(0xFF909AB0)

private data class Hsv(val h: Float, val s: Float, val v: Float)

private fun Color.toHsv(): Hsv {
    val r = red; val g = green; val b = blue
    val maxC = max(r, max(g, b)); val minC = min(r, min(g, b)); val delta = maxC - minC
    val h = when {
        delta <= 0f -> 0f
        maxC == r -> ((g - b) / delta % 6f)
        maxC == g -> ((b - r) / delta + 2f)
        else -> ((r - g) / delta + 4f)
    } * 60f
    val hue = if (h < 0f) h + 360f else h
    val sat = if (maxC <= 0f) 0f else delta / maxC
    return Hsv(hue, sat, maxC)
}

private fun hsvToColor(h: Float, s: Float, v: Float): Color {
    val c = v * s
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = v - c
    val (r1, g1, b1) = when ((h / 60f).toInt().coerceIn(0, 5)) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color((r1 + m).coerceIn(0f, 1f), (g1 + m).coerceIn(0f, 1f), (b1 + m).coerceIn(0f, 1f), 1f)
}

@Composable
fun GaugeSettings(
    ctxShape: GaugeShape,
    onCtxShapeChange: (GaugeShape) -> Unit,
    onCtxShapePersist: () -> Unit,
    zenShape: GaugeShape,
    onZenShapeChange: (GaugeShape) -> Unit,
    onZenShapePersist: () -> Unit,
    palette: GaugePalette,
    onColorChange: (GaugeColorKey, Int) -> Unit,
    onPalettePersist: () -> Unit,
    barGap: Int,
    onBarGapChange: (Int) -> Unit,
    onBarGapPersist: () -> Unit,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingKey by remember { mutableStateOf<GaugeColorKey?>(null) }
    var tempH by remember { mutableFloatStateOf(0f) }
    var tempS by remember { mutableFloatStateOf(1f) }
    var tempV by remember { mutableFloatStateOf(1f) }

    val pulse = rememberInfiniteTransition(label = "gauge")
    val glow by pulse.animateFloat(
        initialValue = 0.35f, targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
        label = "glow"
    )
    val scan by pulse.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "scan"
    )

    editingKey?.let { key ->
        ColorDialog(
            key = key,
            h = tempH, s = tempS, v = tempV,
            onH = { tempH = it }, onS = { tempS = it }, onV = { tempV = it },
            onDismiss = { editingKey = null },
            onConfirm = {
                onColorChange(key, hsvToColor(tempH, tempS, tempV).toArgb())
                onPalettePersist()
                editingKey = null
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
            .border(1.dp, Color(0xFF332846), RoundedCornerShape(16.dp))
            .drawBehind {
                val y = size.height * scan
                drawLine(Color(0x22B388FF), Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
            }
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Header(glow)
        NeonSection("КОНТЕКСТ", NeonOrange) {
            ShapeSlider("Высота", ctxShape.heightDp.toFloat(), 6f..20f, 13, { onCtxShapeChange(ctxShape.copy(heightDp = it.toInt())) }, onCtxShapePersist, "${ctxShape.heightDp} dp", NeonOrange)
            ShapeSlider("Сегменты", ctxShape.cells.toFloat(), 40f..120f, 79, { onCtxShapeChange(ctxShape.copy(cells = it.toInt())) }, onCtxShapePersist, "${ctxShape.cells}", NeonOrange)
            ShapeSlider("Зазор сегм.", ctxShape.cellGapDp, 0f..2.5f, 24, { onCtxShapeChange(ctxShape.copy(cellGapDp = it)) }, onCtxShapePersist, String.format("%.2f", ctxShape.cellGapDp), NeonOrange)
            ShapeSlider("Наклон", ctxShape.lean, -0.40f..0.40f, 79, { onCtxShapeChange(ctxShape.copy(lean = it)) }, onCtxShapePersist, String.format("%.2f", ctxShape.lean), NeonOrange)
        }
        NeonSection("ZEN-КВОТА", NeonPurple) {
            ShapeSlider("Высота", zenShape.heightDp.toFloat(), 6f..20f, 13, { onZenShapeChange(zenShape.copy(heightDp = it.toInt())) }, onZenShapePersist, "${zenShape.heightDp} dp", NeonPurple)
            ShapeSlider("Сегменты", zenShape.cells.toFloat(), 40f..120f, 79, { onZenShapeChange(zenShape.copy(cells = it.toInt())) }, onZenShapePersist, "${zenShape.cells}", NeonPurple)
            ShapeSlider("Зазор сегм.", zenShape.cellGapDp, 0f..2.5f, 24, { onZenShapeChange(zenShape.copy(cellGapDp = it)) }, onZenShapePersist, String.format("%.2f", zenShape.cellGapDp), NeonPurple)
            ShapeSlider("Наклон", zenShape.lean, -0.40f..0.40f, 79, { onZenShapeChange(zenShape.copy(lean = it)) }, onZenShapePersist, String.format("%.2f", zenShape.lean), NeonPurple)
        }
        NeonSection("ОБЩЕЕ", Color(0xFF9AA6BF)) {
            ShapeSlider("Зазор полос", barGap.toFloat(), 0f..6f, 5, { onBarGapChange(it.toInt()) }, onBarGapPersist, "$barGap dp", Color(0xFF9AA6BF))
        }
        NeonSection("ЦВЕТА", NeonOrange) {
            GaugeColorKey.entries.forEach { key ->
                ColorRow(key.title, Color(palette.getColor(key))) { c ->
                    val hsv = Color(c).toHsv()
                    tempH = hsv.h; tempS = hsv.s; tempV = hsv.v
                    editingKey = key
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF231A14))
                    .border(1.dp, NeonOrange.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .clickable { onResetDefaults() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("СБРОС", color = NeonOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun Header(glow: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(NeonOrange.copy(alpha = glow))
                .border(1.dp, NeonPurple.copy(alpha = 0.7f), CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("НАСТРОЙКИ ПОЛОС", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text("Контекст · Zen-квота · геометрия · цвета", color = TextDim, fontSize = 10.sp)
        }
        Text("HUD", color = NeonPurple.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NeonSection(title: String, accent: Color, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(CardBg, CardBg2)))
            .border(1.dp, BorderDim, RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(accent))
            Spacer(Modifier.width(8.dp))
            Text(title, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        }
        content()
    }
}

@Composable
private fun ShapeSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    onChange: (Float) -> Unit, onDone: () -> Unit, display: String, accent: Color,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextMain, fontSize = 12.sp)
            Text(display, color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps,
            onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(
                thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Color(0xFF2A3344),
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun ColorRow(label: String, color: Color, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(color.toArgb()) }.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextMain, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(String.format("#%06X", color.toArgb() and 0xFFFFFF), color = TextDim, fontSize = 10.sp)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(color)
                    .border(1.dp, Color(0x66FFFFFF), CircleShape)
                    .border(2.dp, color.copy(alpha = 0.25f), CircleShape)
            )
        }
    }
}

@Composable
private fun ColorDialog(
    key: GaugeColorKey, h: Float, s: Float, v: Float,
    onH: (Float) -> Unit, onS: (Float) -> Unit, onV: (Float) -> Unit,
    onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF151A24),
        title = { Text("Цвет: ${key.title}", color = TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp))
                        .background(hsvToColor(h, s, v))
                        .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(10.dp))
                )
                DialogSlider("Оттенок", h, 0f..360f, 359, onH, NeonPurple)
                DialogSlider("Насыщенность", s, 0f..1f, 0, onS, NeonOrange)
                DialogSlider("Яркость", v, 0f..1f, 0, onV, Color(0xFF9AA6BF))
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Применить", color = NeonOrange) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = TextDim) } },
    )
}

@Composable
private fun DialogSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit, accent: Color) {
    Text(label, color = TextDim, fontSize = 11.sp)
    Slider(
        value = value, onValueChange = onChange, valueRange = range, steps = steps,
        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Color(0xFF2A3344), activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
    )
}
