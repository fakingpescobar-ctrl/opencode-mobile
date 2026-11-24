@file:Suppress("MagicNumber", "LongMethod", "TooManyFunctions")

package org.opencode.mobile.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sin

private const val SAMPLE_MS = 700L
private const val HISTORY_POINTS = 48
private const val CPU_CORES = 8

private val CORE_COLORS =
    listOf(
        Color(0xFF00E5FF),
        Color(0xFF7BD88F),
        Color(0xFFFFB74D),
        Color(0xFFFF6F5A),
        Color(0xFFB388FF),
        Color(0xFF4DD0E1),
        Color(0xFFFFD54F),
        Color(0xFFF06292),
    )

private val GPU_PATHS =
    listOf(
        "/sys/class/kgsl/kgsl-3d0/gpubusy",
        "/sys/devices/platform/kgsl-3d0.0/kgsl/kgsl-3d0/gpubusy",
        "/sys/class/drm/card0/device/gpu_busy_percent",
        "/sys/devices/platform/mali-t760/gpubusy",
    )

data class PerformanceSnapshot(
    val cpu: Int,
    val gpu: Int?,
    val ram: Int,
    val cacheMb: Long,
    val cores: List<Int>,
    val history: List<FloatArray>,
)

/**
 * Сэмплер локальных метрик. /proc/stat на Android недоступен untrusted_app,
 * поэтому загрузка ядер берётся как активность по частоте (scaling_cur_freq
 * между min и max) — это честно подписано в панели как «частота», а не «% CPU».
 * GPU читается из gpubusy (busy/total), RAM/кэш — из /proc/meminfo.
 */
object PerformanceMonitor {
    private val _state =
        MutableStateFlow(
            PerformanceSnapshot(0, null, 0, 0L, List(CPU_CORES) { 0 }, emptyList()),
        )
    val state: StateFlow<PerformanceSnapshot> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var gpuPrev: LongArray? = null
    private val history = ArrayList<FloatArray>()

    fun start(active: () -> Boolean = { true }) {
        if (scope?.isActive == true) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { s ->
            s.launch {
                while (isActive) {
                    if (active()) {
                        val snap = sample()
                        _state.value = snap
                    }
                    delay(SAMPLE_MS)
                }
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        gpuPrev = null
    }

    private fun sample(): PerformanceSnapshot {
        val cores = readCoreActivity()
        val mem = readMemInfo()
        val total = mem["MemTotal"] ?: 1L
        val available = mem["MemAvailable"] ?: 0L
        val ram = ((total - available) * 100f / total).roundToInt().coerceIn(0, 100)
        val cacheMb = (mem["Cached"] ?: 0L) / 1024L
        val coreLevels = cores.map { it.toFloat() }.toFloatArray()
        history.add(coreLevels)
        while (history.size > HISTORY_POINTS) history.removeAt(0)
        return PerformanceSnapshot(
            cpu = if (cores.isEmpty()) 0 else cores.sum() / cores.size,
            gpu = readGpu(),
            ram = ram,
            cacheMb = cacheMb,
            cores = cores,
            history = history.map { it.copyOf() },
        )
    }

    private fun readCoreActivity(): List<Int> {
        val out = ArrayList<Int>()
        for (i in 0 until CPU_CORES) {
            val base = "/sys/devices/system/cpu/cpu$i/cpufreq/"
            val cur = readLong(base + "scaling_cur_freq") ?: 0L
            val min = readLong(base + "scaling_min_freq") ?: 0L
            val max = readLong(base + "cpuinfo_max_freq") ?: 0L
            val level =
                if (max > min) ((cur - min) * 100f / (max - min)).roundToInt() else 0
            out.add(level.coerceIn(0, 100))
        }
        return out
    }

    private fun readGpu(): Int? {
        for (path in GPU_PATHS) {
            val parts =
                try {
                    File(path).readText().trim().split(Regex("\\s+"))
                } catch (_: Exception) {
                    continue
                }
            val value =
                when (parts.size) {
                    1 -> parts[0].toLongOrNull()?.toInt()
                    2 -> {
                        val busy = parts[0].toLongOrNull() ?: continue
                        val total = parts[1].toLongOrNull() ?: continue
                        val prev = gpuPrev
                        gpuPrev = longArrayOf(busy, total)
                        if (prev == null || total <= prev[1]) null
                        else (((busy - prev[0]) * 100f / (total - prev[1])).roundToInt())
                    }
                    else -> null
                }
            if (value != null) return value.coerceIn(0, 100)
        }
        return null
    }

    private fun readMemInfo(): Map<String, Long> {
        val out = HashMap<String, Long>()
        try {
            File("/proc/meminfo").readLines().forEach { line ->
                val key = line.substringBefore(':').trim()
                val value = line.substringAfter(':').trim().split(Regex("\\s+"))[0].toLongOrNull()
                if (value != null) out[key] = value
            }
        } catch (_: Exception) {
            // MemInfo недоступен — показываем нули, а не падаем.
        }
        return out
    }

    private fun readLong(path: String): Long? =
        try {
            File(path).readText().trim().toLongOrNull()
        } catch (_: Exception) {
            null
        }
}

/** Маленькая светящаяся молния в шапке. Пульс дискретный (не 60 fps). */
@Composable
internal fun PerformanceChip(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot by PerformanceMonitor.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var pulse by remember { mutableFloatStateOf(0.5f) }
    LaunchedEffect(Unit) {
        var t = 0.0
        while (true) {
            if (lifecycle.currentState != Lifecycle.State.RESUMED) {
                pulse = 0.3f
                delay(2_000L)
                continue
            }
            val load = snapshot.cpu / 100f
            pulse = 0.3f + 0.7f * ((sin(t) + 1.0) / 2.0).toFloat() * (0.6f + 0.4f * load)
            t += 0.785
            delay(120)
        }
    }
    Box(
        modifier
            .size(20.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val tint = if (active) Color(0xFFFFF176) else Color(0xFFFFD54F)
            drawCircle(tint.copy(alpha = pulse * 0.45f), radius = 9.dp.toPx(), center = c)
        }
        Icon(
            Icons.Filled.FlashOn,
            contentDescription = "Производительность",
            tint = Color(0xFFFFF8E1),
            modifier = Modifier.size(15.dp),
        )
    }
}

/** Неоновая карточка с полосками GPU/CPU/RAM и живым графиком ядер. */
@Composable
internal fun PerformancePanel(onClose: () -> Unit) {
    val snapshot by PerformanceMonitor.state.collectAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF161616))
            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "⚡ ПРОИЗВОДИТЕЛЬНОСТЬ",
                color = Color(0xFF00E5FF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "×",
                color = Color(0xFF8A8A8A),
                fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 4.dp),
            )
        }
        MetricBar("GPU · загрузка", snapshot.gpu, Color(0xFF00E5FF))
        MetricBar("CPU · частота", snapshot.cpu, Color(0xFF7BD88F))
        MetricBar("RAM · занято", snapshot.ram, Color(0xFFFFB74D))
        Text(
            "Кэш: ${snapshot.cacheMb} МБ  ·  /proc/stat закрыт, частота ядер вместо %",
            color = Color(0xFF8A8A8A),
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        CoreGraph(snapshot.history)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            CORE_COLORS.forEachIndexed { i, color ->
                Text("$i", color = color, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun MetricBar(
    label: String,
    value: Int?,
    color: Color,
) {
    val progress by
        animateFloatAsState(
            targetValue = (value ?: 0).coerceIn(0, 100) / 100f,
            label = "metric_bar",
        )
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = Color(0xFFBDBDBD),
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (value == null) "н/д" else "$value%",
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFF2A2A2A)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress)
                .fillMaxHeight()
                .background(color),
        )
    }
}

@Composable
private fun CoreGraph(history: List<FloatArray>) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(88.dp)
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0E1013)),
    ) {
        val w = size.width
        val h = size.height
        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(Color(0xFF222222), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        if (history.size < 2) return@Canvas
        val stepX = w / (HISTORY_POINTS - 1)
        CORE_COLORS.forEachIndexed { core, color ->
            val path = Path()
            history.forEachIndexed { idx, sample ->
                val x = w - (history.size - 1 - idx) * stepX
                val level = sample.getOrElse(core) { 0f }.coerceIn(0f, 100f) / 100f
                val y = h - 4.dp.toPx() - level * (h - 8.dp.toPx())
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}
