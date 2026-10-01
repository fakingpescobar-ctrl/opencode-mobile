package org.opencode.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    Column(
        modifier = modifier
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
                    { c -> onColorChange(key, c) },
                    { onPalettePersist() }
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
