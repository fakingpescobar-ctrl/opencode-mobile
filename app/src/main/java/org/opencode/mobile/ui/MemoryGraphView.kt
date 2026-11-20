@file:Suppress("MagicNumber")

package org.opencode.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opencode.mobile.server.MemoryWire
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Интерактивная карта графа памяти: псевдо-3D пространство на обычном Canvas.
 *
 * Почему не настоящий 3D-движок: в APK нельзя тащить внешние зависимости, а
 * граф у нас — десятки узлов. Сферическая проекция с вращением жестом даёт
 * всё, что нужно человеку: видно скопления, связи и можно подтянуть узел
 * ближе. Позиции детерминированы по id: при следующем открытии карта не
 * «прыгает», а остаётся узнаваемой.
 */
@Composable
@Suppress("LongMethod")
internal fun MemoryGraphView(edges: List<MemoryWire.Edge>) {
    var rotX by remember { mutableStateOf(0.28f) }
    var rotY by remember { mutableStateOf(0f) }
    var zoom by remember { mutableStateOf(1f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember { mutableStateOf<String?>(null) }

    val nodes = remember(edges) {
        val set = LinkedHashSet<String>()
        for (e in edges) { set.add(e.from); set.add(e.to) }
        set.toList().sorted()
    }
    val points = remember(nodes, size, rotX, rotY, zoom) {
        projectAll(nodes, size, rotX, rotY, zoom)
    }

    fun hit(offset: Offset): String? =
        points.minByOrNull { (it.offset - offset).getDistanceSquared() }
            ?.takeIf { (it.offset - offset).getDistanceSquared() <= HIT_RADIUS * HIT_RADIUS }
            ?.id

    Column(Modifier.fillMaxWidth()) {
        InfoRow("Узлов", nodes.size.toString() + " · связей " + edges.size.toString())
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(GRAPH_HEIGHT)
                .onSizeChanged { size = it }
                .pointerInput(Unit) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        rotY += amount.x / ROTATE_DIV
                        rotX = (rotX - amount.y / ROTATE_DIV).coerceIn(-MAX_TILT, MAX_TILT)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { offset -> selected = hit(offset) })
                },
        ) {
            if (size.width <= 0 || size.height <= 0) return@Canvas
            for (e in edges) {
                val a = points.firstOrNull { it.id == e.from } ?: continue
                val b = points.firstOrNull { it.id == e.to } ?: continue
                drawLine(
                    color = relationColor(e.relation),
                    start = a.offset,
                    end = b.offset,
                    strokeWidth = 1.4.dp.toPx(),
                    alpha = 0.55f,
                )
            }
            for (p in points.sortedByDescending { it.depth }) {
                val r = if (p.id == selected) SELECTED_RADIUS.dp.toPx() else NODE_RADIUS.dp.toPx()
                drawCircle(color = Color(0xFF0E1116), radius = r + 2.dp.toPx(), center = p.offset)
                drawCircle(
                    color = if (p.id == selected) Color(0xFFFFC107) else nodeColor(p.id),
                    radius = r,
                    center = p.offset,
                    alpha = (0.55f + 0.45f / p.depth).coerceIn(0.35f, 1f),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { zoom = (zoom - ZOOM_STEP).coerceIn(MIN_ZOOM, MAX_ZOOM) }) {
                Text("−")
            }
            TextButton(onClick = { zoom = (zoom + ZOOM_STEP).coerceIn(MIN_ZOOM, MAX_ZOOM) }) {
                Text("+")
            }
            TextButton(onClick = { rotX = 0.28f; rotY = 0f; zoom = 1f; selected = null }) {
                Text("Сброс")
            }
        }
        val node = selected
        if (node != null) {
            val links = edges.filter { it.from == node || it.to == node }
            InfoRow("Выбран", node, Color(0xFFFFC107))
            InfoRow(
                "Связи",
                links.joinToString { e ->
                    (if (e.from == node) "→ " + e.to else "← " + e.from) + " [" + e.relation + "]"
                }.ifEmpty { "нет" },
            )
        } else {
            Text(
                "Тяните пальцем — вращение; тапните шарик — его связи.",
                color = Color(0xFF8A8A8A),
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class Projected(
    val id: String,
    val offset: Offset,
    val depth: Float,
)

/** Детерминированная точка на единичной сфере по hash(id). */
private fun spherePoint(id: String): Triple<Float, Float, Float> {
    val h = (id.hashCode().toLong() and 0x7fff_ffffL)
    val u = (h % 1000) / 999f
    val v = ((h / 1000) % 1000) / 999f
    val theta = u * 2f * Math.PI.toFloat()
    val phi = acos((2f * v - 1f).coerceIn(-1f, 1f))
    return Triple(
        sin(phi) * cos(theta),
        cos(phi),
        sin(phi) * sin(theta),
    )
}

private fun projectAll(
    nodes: List<String>,
    size: IntSize,
    rotX: Float,
    rotY: Float,
    zoom: Float,
): List<Projected> {
    if (size.width <= 0 || size.height <= 0) return emptyList()
    val cy = cos(rotY); val sy = sin(rotY)
    val cx = cos(rotX); val sx = sin(rotX)
    val focal = min(size.width, size.height) * 0.40f * zoom
    return nodes.map { id ->
        val (x0, y0, z0) = spherePoint(id)
        val x1 = x0 * cy - z0 * sy
        val z1 = x0 * sy + z0 * cy
        val y2 = y0 * cx - z1 * sx
        val z2 = y0 * sx + z1 * cx
        val depth = CAMERA + z2
        val f = focal / depth
        Projected(
            id = id,
            offset = Offset(size.width / 2f + x1 * f, size.height / 2f + y2 * f),
            depth = depth.coerceAtLeast(0.1f),
        )
    }
}

private val PALETTE =
    listOf(
        Color(0xFF7BD88F), Color(0xFF6FA8FF), Color(0xFFFFC107),
        Color(0xFFFF6F5A), Color(0xFFC792EA), Color(0xFF77D8D0),
    )

private fun nodeColor(id: String): Color =
    PALETTE[(id.hashCode().toLong() and 0x7fff_ffffL).toInt() % PALETTE.size]

private fun relationColor(relation: String): Color = when {
    relation.contains("depend", true) -> Color(0xFFFF6F5A)
    relation.contains("evidence", true) -> Color(0xFF6FA8FF)
    relation.contains("identity", true) || relation.contains("architect", true) -> Color(0xFF7BD88F)
    else -> Color(0xFF8A8A8A)
}

private fun Offset.getDistanceSquared(): Float = x * x + y * y
private operator fun Offset.minus(other: Offset): Offset = Offset(x - other.x, y - other.y)

private const val GRAPH_HEIGHT_DP = 260
private val GRAPH_HEIGHT = GRAPH_HEIGHT_DP.dp
private const val HIT_RADIUS = 44f
private const val ROTATE_DIV = 180f
private const val MAX_TILT = 1.35f
private const val NODE_RADIUS = 6
private const val SELECTED_RADIUS = 9
private const val CAMERA = 3.2f
private const val ZOOM_STEP = 0.2f
private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 2.4f
