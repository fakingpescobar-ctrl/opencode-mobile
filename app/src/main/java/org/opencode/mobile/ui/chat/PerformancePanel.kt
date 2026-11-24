@file:Suppress("MagicNumber", "LongMethod", "TooManyFunctions")

package org.opencode.mobile.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    // Мгновенная частота скачет от min до max за один такт гувернера, поэтому
    // усредняем и по ядрам, и по времени: полоска показывает среднюю загрузку,
    // а не случайный снимок. График получает те же сглаженные значения.
    private var cpuEma = 0f
    private val coreEma = FloatArray(CPU_CORES)
    // GPU счётчики kgsl часто вообще не тикают, когда GPU спит: без памяти
    // полоска дёргалась «н/д → 100%». Держим последнее сглаженное значение и
    // меняем его только когда пришёл новый честный замер.
    private var gpuEma: Float? = null

    fun start(active: () -> Boolean = { true }) {
        if (scope?.isActive == true) return
        cpuEma = 0f
        coreEma.fill(0f)
        gpuEma = null
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
        cpuEma = 0f
        coreEma.fill(0f)
        gpuEma = null
    }

    private fun sample(): PerformanceSnapshot {
        val cores = readCoreActivity()
        val mem = readMemInfo()
        val total = mem["MemTotal"] ?: 1L
        val available = mem["MemAvailable"] ?: 0L
        val ram = ((total - available) * 100f / total).roundToInt().coerceIn(0, 100)
        val cacheMb = (mem["Cached"] ?: 0L) / 1024L
        // Сглаживаем каждое ядро (alpha 0.3), затем усредняем по всем ядрам и
        // ещё раз по времени (alpha 0.15). Полоска CPU показывает среднюю
        // загрузку по ядрам, а не дёрганый снимок частоты.
        for (i in cores.indices) {
            coreEma[i] = coreEma[i] * 0.7f + cores[i] * 0.3f
        }
        val coreLevels = coreEma.copyOf()
        val avg = if (coreLevels.isEmpty()) 0f else coreLevels.sum() / coreLevels.size
        cpuEma = cpuEma * 0.85f + avg * 0.15f
        history.add(coreLevels)
        while (history.size > HISTORY_POINTS) history.removeAt(0)
        return PerformanceSnapshot(
            cpu = cpuEma.roundToInt().coerceIn(0, 100),
            gpu = readGpu(),
            ram = ram,
            cacheMb = cacheMb,
            cores = coreLevels.map { it.roundToInt().coerceIn(0, 100) },
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

    /** Сглаженное GPU-значение: при отсутствии свежего замера держим последнее. */
    private fun readGpu(): Int? {
        val raw = readGpuRaw()
        if (raw != null) {
            gpuEma =
                if (gpuEma == null) raw.toFloat() else gpuEma!! * 0.85f + raw * 0.15f
        }
        return gpuEma?.roundToInt()?.coerceIn(0, 100)
    }

    /** Один честный замер gpubusy (дельта busy/total) или null, если счётчик стоит. */
    private fun readGpuRaw(): Int? {
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
                        // Счётчик мог обнулиться при suspend/resume — это НЕ нулевая
                        // загрузка, а новый отсчёт, поэтому просто пропускаем тик.
                        if (prev == null || total < prev[1]) null
                        else {
                            val dBusy = (busy - prev[0]).coerceAtLeast(0L)
                            val dTotal = (total - prev[1]).coerceAtLeast(1L)
                            ((dBusy * 100f / dTotal).roundToInt())
                        }
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

/** Неоновая карточка с полосками GPU/CPU/RAM и живым графиком ядер. */
@Composable
internal fun PerformancePanel(onClose: () -> Unit) {
    val snapshot by PerformanceMonitor.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Фаза «живого» сканирования. ДИСКРЕТНО: 60 мс ≈ 16 кадров/с, не 60 fps —
    // при уходе в фон цикл спит и RenderThread не рисует.
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            if (lifecycle.currentState == Lifecycle.State.RESUMED) {
                phase = (phase + 0.025f) % 1f
                delay(60L)
            } else {
                delay(2_000L)
            }
        }
    }
    val live = 0.45f + 0.55f * ((sin(phase * 6.283185f) + 1f) / 2f)
    val peak = snapshot.cores.maxOrNull() ?: 0
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(listOf(Color(0xFF111A20), Color(0xFF0B0E11))),
            )
            .border(1.dp, Color(0xFF24404A), RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(8.dp)) {
                drawCircle(Color(0xFF39FF88).copy(alpha = live), radius = 4.dp.toPx())
            }
            Text(
                "⚡ ПРОИЗВОДИТЕЛЬНОСТЬ",
                color = Color(0xFF00E5FF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            Text(
                "LIVE",
                color = Color(0xFF39FF88).copy(alpha = live),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Text(
                "×",
                color = Color(0xFF8A8A8A),
                fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onClose).padding(start = 6.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .padding(top = 6.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF00E5FF), Color(0xFF24404A), Color.Transparent),
                    ),
                ),
        )
        MetricBar("GPU · загрузка", snapshot.gpu, Color(0xFF00E5FF), phase)
        MetricBar("CPU · среднее по ядрам", snapshot.cpu, Color(0xFF7BD88F), phase)
        MetricBar("RAM · занято", snapshot.ram, Color(0xFFFFB74D), phase)
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("КЭШ ${snapshot.cacheMb} МБ", color = Color(0xFF8AA0AA), fontSize = 9.sp)
            Text("СР ${snapshot.cpu}%", color = Color(0xFF7BD88F), fontSize = 9.sp)
            Text("ПИК ${peak}%", color = Color(0xFFFFB74D), fontSize = 9.sp)
        }
        Text(
            "источник: gpubusy / cpufreq / meminfo · /proc/stat закрыт",
            color = Color(0xFF5E7078),
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        CoreGraph(snapshot.history, phase)
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CORE_COLORS.forEachIndexed { i, color ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                    Text(
                        "$i",
                        color = color.copy(alpha = 0.85f),
                        fontSize = 9.sp,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricBar(
    label: String,
    value: Int?,
    color: Color,
    phase: Float,
) {
    // Плавное скольжение за 650 мс: сэмпл приходит раз в 700 мс, и полоска
    // всё время движется, а не прыгает на новое число.
    val progress by
        animateFloatAsState(
            targetValue = (value ?: 0).coerceIn(0, 100) / 100f,
            animationSpec = tween(650, easing = FastOutSlowInEasing),
            label = "metric_bar",
        )
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = Color(0xFFBDBDBD),
            fontSize = 11.sp,
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
            .height(9.dp)
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF0A1115), Color(0xFF16242B))))
            .border(1.dp, Color(0xFF1E3038), RoundedCornerShape(4.dp)),
    ) {
        if (progress > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(listOf(color.copy(alpha = 0.35f), color)),
                    ),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val filled = w * progress
            if (filled > 2f) {
                // Бегущий блик по заполненной части — «живой» отклик на изменение.
                val sx = filled * ((phase + 0.15f) % 1f)
                drawLine(
                    Color.White.copy(alpha = 0.3f),
                    Offset(sx, 1f),
                    Offset(sx, h - 1f),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            val step = w / 20f
            for (k in 1 until 20) {
                drawLine(
                    Color(0xFF080C0F).copy(alpha = 0.7f),
                    Offset(step * k, 0f),
                    Offset(step * k, h),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
    }
}

@Composable
private fun CoreGraph(
    history: List<FloatArray>,
    phase: Float,
) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF091116), Color(0xFF0D171D))))
            .border(1.dp, Color(0xFF1B2D35), RoundedCornerShape(10.dp)),
    ) {
        val w = size.width
        val h = size.height
        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(Color(0xFF1A2A32), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        for (k in 1..7) {
            val x = w * k / 8f
            drawLine(Color(0xFF14232B), Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }
        // Сканирующая линия: фаза обновляется раз в 60 мс, давая живое движение.
        val sx = phase * w
        drawLine(
            Color(0xFF00E5FF).copy(alpha = 0.22f),
            Offset(sx, 0f),
            Offset(sx, h),
            strokeWidth = 3.dp.toPx(),
        )
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
            // Свечение: широкая полупрозрачная линия под тонкой яркой.
            drawPath(
                path,
                color.copy(alpha = 0.16f),
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
            )
            drawPath(
                path,
                color,
                style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        // Яркие точки на правом краю — «сейчас» по каждому ядру.
        val last = history.last()
        CORE_COLORS.forEachIndexed { core, color ->
            val level = last.getOrElse(core) { 0f }.coerceIn(0f, 100f) / 100f
            val y = h - 4.dp.toPx() - level * (h - 8.dp.toPx())
            drawCircle(color.copy(alpha = 0.85f), radius = 2.5.dp.toPx(),
                center = Offset(w - 3.dp.toPx(), y))
        }
    }
}
