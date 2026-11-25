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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

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
        0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
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

    editingKey?.let { key ->
        ColorDialog(
            key = key, h = tempH, s = tempS, v = tempV,
            onH = { tempH = it }, onS = { tempS = it }, onV = { tempV = it },
            onDismiss = { editingKey = null },
            onConfirm = {
                onColorChange(key, hsvToColor(tempH, tempS, tempV).toArgb())
                onPalettePersist(); editingKey = null
            },
        )
    }

    Surface(color = MoltCard, shape = RoundedCornerShape(10.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(MoltAccent))
                Spacer(Modifier.width(6.dp))
                Text(
                    "НАСТРОЙКИ ПОЛОС",
                    color = MoltAccent, fontSize = TINY, lineHeight = TINY_LINE,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "СБРОС",
                    color = Color(0xFFFF8A3D), fontSize = TINY, lineHeight = TINY_LINE,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onResetDefaults() }.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            ShapeBlock(
                title = "Контекст", accent = Color(0xFFFF8A3D), shape = ctxShape,
                onChange = onCtxShapeChange, onPersist = onCtxShapePersist,
            )
            ShapeBlock(
                title = "Zen-квота", accent = Color(0xFFB388FF), shape = zenShape,
                onChange = onZenShapeChange, onPersist = onZenShapePersist,
            )

            CompactSlider("Зазор полос", barGap.toFloat(), 0f..6f, 5, { onBarGapChange(it.toInt()) }, onBarGapPersist, "$barGap dp", MoltIdle)

            Text("ЦВЕТА", color = MoltIdle, fontSize = TINY, lineHeight = TINY_LINE, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            GaugeColorKey.entries.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    pair.forEach { key ->
                        ColorChip(
                            key = key, color = Color(palette.getColor(key)),
                            modifier = Modifier.weight(1f),
                            onPick = { c ->
                                val hsv = Color(c).toHsv(); tempH = hsv.h; tempS = hsv.s; tempV = hsv.v; editingKey = key
                            },
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ShapeBlock(
    title: String, accent: Color, shape: GaugeShape,
    onChange: (GaugeShape) -> Unit, onPersist: () -> Unit,
) {
    Surface(color = MoltChipOff, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(5.dp))
                Text(title.uppercase(), color = accent, fontSize = TINY, lineHeight = TINY_LINE, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            CompactSlider("Высота", shape.heightDp.toFloat(), 6f..20f, 13, { onChange(shape.copy(heightDp = it.toInt())) }, onPersist, "${shape.heightDp}", accent)
            CompactSlider("Сегменты", shape.cells.toFloat(), 40f..120f, 79, { onChange(shape.copy(cells = it.toInt())) }, onPersist, "${shape.cells}", accent)
            CompactSlider("Зазор", shape.cellGapDp, 0f..2.5f, 24, { onChange(shape.copy(cellGapDp = it)) }, onPersist, String.format("%.2f", shape.cellGapDp), accent)
            CompactSlider("Наклон", shape.lean, -0.40f..0.40f, 79, { onChange(shape.copy(lean = it)) }, onPersist, String.format("%.2f", shape.lean), accent)
        }
    }
}

@Composable
private fun CompactSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    onChange: (Float) -> Unit, onDone: () -> Unit, display: String, accent: Color,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MoltValue, fontSize = TINY, lineHeight = TINY_LINE, modifier = Modifier.width(62.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps,
            onValueChangeFinished = onDone,
            modifier = Modifier.weight(1f).height(28.dp),
            colors = SliderDefaults.colors(
                thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Color(0xFF2A3344),
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
            ),
        )
        Text(display, color = accent, fontSize = TINY, lineHeight = TINY_LINE, modifier = Modifier.width(34.dp), maxLines = 1)
    }
}

@Composable
private fun ColorChip(key: GaugeColorKey, color: Color, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MoltChipOff)
            .clickable { onPick(color.toArgb()) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color).border(1.dp, Color(0x55FFFFFF), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(key.title, color = MoltValue, fontSize = TINY, lineHeight = TINY_LINE, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ColorDialog(
    key: GaugeColorKey, h: Float, s: Float, v: Float,
    onH: (Float) -> Unit, onS: (Float) -> Unit, onV: (Float) -> Unit,
    onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = MoltCard,
        title = { Text(key.title, color = MoltValue, fontSize = SMALL, lineHeight = SMALL_LINE, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(6.dp)).background(hsvToColor(h, s, v)).border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(6.dp)))
                DialogSlider("Оттенок", h, 0f..360f, onH, Color(0xFFB388FF))
                DialogSlider("Насыщенность", s, 0f..1f, onS, Color(0xFFFF8A3D))
                DialogSlider("Яркость", v, 0f..1f, onV, MoltIdle)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Ок", color = MoltAccent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = MoltIdle) } },
    )
}

@Composable
private fun DialogSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, accent: Color) {
    Text(label, color = MoltIdle, fontSize = TINY, lineHeight = TINY_LINE)
    Slider(
        value = value, onValueChange = onChange, valueRange = range,
        modifier = Modifier.height(28.dp),
        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Color(0xFF2A3344), activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
    )
}
