package org.opencode.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

private data class Hsv(
    val h: Float,
    val s: Float,
    val v: Float,
)

private fun Color.toHsv(): Hsv {
    val r = red
    val g = green
    val b = blue
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC
    val h =
        when {
            delta <= 0f -> 0f
            maxC == r -> ((g - b) / delta % 6f)
            maxC == g -> ((b - r) / delta + 2f)
            else -> ((r - g) / delta + 4f)
        } * 60f
    val hue = if (h < 0f) h + 360f else h
    val sat = if (maxC <= 0f) 0f else delta / maxC
    val value = maxC
    return Hsv(hue, sat, value)
}

private fun hsvToColor(
    h: Float,
    s: Float,
    v: Float,
): Color {
    val c = v * s
    val x = c * (1f - ((h / 60f) % 2f - 1f).coerceIn(-1f, 1f).let { if (it < 0f) -it else it })
    val m = v - c
    val (r1, g1, b1) =
        when ((h / 60f).toInt()) {
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

    editingKey?.let { key ->
        val currentArgb = palette.getColor(key)
        AlertDialog(
            onDismissRequest = { editingKey = null },
            title = { Text("Color - ${key.name}") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(hsvToColor(tempH, tempS, tempV))
                                .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(8.dp)),
                    )
                    Text("Hue", color = Color(0xFFBDBDBD), fontSize = 12.sp)
                    Slider(value = tempH, onValueChange = { tempH = it }, valueRange = 0f..360f, steps = 359)
                    Text("Saturation", color = Color(0xFFBDBDBD), fontSize = 12.sp)
                    Slider(value = tempS, onValueChange = { tempS = it }, valueRange = 0f..1f)
                    Text("Value", color = Color(0xFFBDBDBD), fontSize = 12.sp)
                    Slider(value = tempV, onValueChange = { tempV = it }, valueRange = 0f..1f)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val argb = hsvToColor(tempH, tempS, tempV).toArgb()
                        onColorChange(key, argb)
                        onPalettePersist()
                        editingKey = null
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { editingKey = null }) { Text("Cancel") }
            },
        )
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .background(Color(0xFF1C1C1C), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = "Gauge", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        SectionTitle("Context")
        ShapeSliderRow(
            "Height",
            ctxShape.heightDp.toFloat(),
            6f..20f,
            13,
            { v -> onCtxShapeChange(ctxShape.copy(heightDp = v.toInt())) },
            onCtxShapePersist,
            ctxShape.heightDp.toString()
        )
        ShapeSliderRow(
            "Cells",
            ctxShape.cells.toFloat(),
            40f..120f,
            79,
            { v -> onCtxShapeChange(ctxShape.copy(cells = v.toInt())) },
            onCtxShapePersist,
            ctxShape.cells.toString()
        )
        ShapeSliderRow(
            "CellGap",
            ctxShape.cellGapDp,
            0f..2.5f,
            24,
            { v -> onCtxShapeChange(ctxShape.copy(cellGapDp = v)) },
            onCtxShapePersist,
            String.format("%.2f", ctxShape.cellGapDp)
        )
        ShapeSliderRow(
            "Lean",
            ctxShape.lean,
            -0.40f..0.40f,
            79,
            { v -> onCtxShapeChange(ctxShape.copy(lean = v)) },
            onCtxShapePersist,
            String.format("%.2f", ctxShape.lean)
        )
        SectionTitle("Zen")
        ShapeSliderRow(
            "Height",
            zenShape.heightDp.toFloat(),
            6f..20f,
            13,
            { v -> onZenShapeChange(zenShape.copy(heightDp = v.toInt())) },
            onZenShapePersist,
            zenShape.heightDp.toString()
        )
        ShapeSliderRow(
            "Cells",
            zenShape.cells.toFloat(),
            40f..120f,
            79,
            { v -> onZenShapeChange(zenShape.copy(cells = v.toInt())) },
            onZenShapePersist,
            zenShape.cells.toString()
        )
        ShapeSliderRow(
            "CellGap",
            zenShape.cellGapDp,
            0f..2.5f,
            24,
            { v -> onZenShapeChange(zenShape.copy(cellGapDp = v)) },
            onZenShapePersist,
            String.format("%.2f", zenShape.cellGapDp)
        )
        ShapeSliderRow(
            "Lean",
            zenShape.lean,
            -0.40f..0.40f,
            79,
            { v -> onZenShapeChange(zenShape.copy(lean = v)) },
            onZenShapePersist,
            String.format("%.2f", zenShape.lean)
        )
        SectionTitle("Gap")
        ShapeSliderRow("Gap", barGap.toFloat(), 0f..6f, 11, { v -> onBarGapChange(v.toInt()) }, onBarGapPersist, barGap.toString())
        SectionTitle("Colors")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GaugeColorKey.entries.forEach { key ->
                ColorRow(
                    key.name,
                    Color(palette.getColor(key)),
                    onPick = { c ->
                        editingKey = key
                        val hsv = Color(c).toHsv()
                        tempH = hsv.h
                        tempS = hsv.s
                        tempV = hsv.v
                    },
                    onPickFinished = { },
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onResetDefaults) {
                Text("Reset", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color(0xFFBDBDBD), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun ShapeSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    display: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color(0xFFE6E6E6), fontSize = 12.sp)
            Text(display, color = Color(0xFF9E9E9E), fontSize = 12.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished
        )
    }
}

@Composable
private fun ColorRow(
    label: String,
    color: Color,
    onPick: (Int) -> Unit,
    onPickFinished: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xFFE6E6E6), fontSize = 12.sp)
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).background(color).border(1.dp, Color(0x66FFFFFF), CircleShape).clickable {
                onPick(color.value.toInt())
                onPickFinished()
            }
        )
    }
}
