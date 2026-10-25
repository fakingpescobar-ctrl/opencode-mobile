package org.opencode.mobile.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.opencode.mobile.ui.GaugePalette
import org.opencode.mobile.ui.GaugeShape
import org.opencode.mobile.ui.McpInfo
import org.opencode.mobile.ui.ZenQuota
import org.opencode.mobile.ui.chat.CONTEXT_LIMIT

// testTag-метки строк шапки: якоря для проверки геометрии по дампу uiautomator.
private const val TAG_CTX_ROW = "ctx_row"
private const val TAG_ZEN_ROW = "zen_row"

// Пороги цвета полосы расхода Zen: с 60% жёлтый («прибереги квоту»), с 90% красный
// («почти всё»). Ровно как у шкалы заполнения контекста.
private const val ZEN_WARN_RATIO = 0.6f
private const val ZEN_DANGER_RATIO = 0.9f

/**
 * Статус связи с сервером + пинг: «Online 42ms» зелёным, «Offline» красным.
 *
 * Что именно показывает. Сервер opencode НЕ умеет отдавать остаток квоты — в его
 * API нет ни одного эндпоинта про usage/limits/billing (проверено по схеме /doc).
 * Поэтому честный сигнал здесь один: прошёл ли последний запрос и что на него
 * ответили. «Online» = сервер ответил и не отказал в квоте; «Offline» = либо не
 * ответил вовсе, либо ответил отказом (reason/message провайдера — квота или ретрай).
 *
 * Почему `rejected` отдельным флагом, а не через `reachable`: отказ по квоте — это
 * УСПЕШНЫй HTTP-ответ с телом про отказ. Сервер доступен, но квоты нет — и по одному
 * факту «досстучались» это не отличить от успеха. Без отдельного флага плашка горела
 * бы «Online» ровно тогда, когда модель уже не отвечает.
 *
 * @param reachable null → состояние неизвестно (ни разу не достучались). Показываем
 *   серый «Online?» вместо красного «Offline»: врать об отказе хуже, чем не знать.
 * @param rejected сервер ответил отказом по квоте/ретраю (текст — в плашке под лентой).
 * @param pingMs задержка последнего запроса, мс. Показываем только когда есть чему
 *   верить: при reachable == null замер обрывается и цифра бессмысленна.
 */
@Composable
internal fun QuotaBadge(
    reachable: Boolean?,
    pingMs: Long,
    rejected: Boolean,
    modifier: Modifier = Modifier,
) {
    val offline = reachable == false || rejected
    val unknown = reachable == null
    val color =
        when {
            offline -> Color(0xFFE53935)
            unknown -> Color(0xFF9E9E9E)
            else -> Color(0xFF7BD88F)
        }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = if (offline) "Offline" else "Online",
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            // ОДНА строка любой ценой: бейдж живёт в weight(1f)-слоте шапки, и при
            // нехватке ширины Compose переносил «Online 69ms» на вторую строку —
            // хедер вздваивался по высоте. Лучше обрезать хвост, чем ломать вёрстку.
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
        // Пинг цифрами — только при подтверждённом контакте. На неизвестном
        // состоянии показывать «—ms» значило бы рисовать число, которого нет.
        val pingSuffix =
            when {
                reachable == null -> ""
                else -> " ${pingMs}ms"
            }
        if (pingSuffix.isNotEmpty()) {
            Text(
                text = pingSuffix,
                color = Color(0xFF8A8A8A),
                fontSize = 11.sp,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/**
 * Расход дневной Zen-квоты — та же полоса-кубики, что индикатор контекста,
 * строкой ниже него.
 *
 * Ровно та же функция [GaugeBars], тот же цвет кубиков для «свободного места»,
 * та же высота. Различается только цвет заливки: у контекста он зелёно-жёлто-
 * красный по мере заполнения, у квоты — голубой, пока есть запас, и жёлто-красный
 * на последних процентах (там опасно уже не «много занято», а «скоро кончится»).
 *
 * Ни цифр, ни процентов, ни подписи: сколько именно занято, полоса показывает
 * сама. Держать рядом ещё и текст значило бы дублировать одно и то же дважды и
 * забирать высоту у чата.
 *
 * Пока точного замера не было (`exact = false`), полоса пустая: неизвестное
 * значение нельзя рисовать как «почти пусто», иначе 2000 потраченных запросов
 * выглядели бы как 0.
 */
@Suppress("MagicNumber")
@Composable
internal fun ZenMeter(
    used: Int,
    exact: Boolean,
    shape: GaugeShape,
    palette: GaugePalette,
    modifier: Modifier = Modifier,
) {
    val ratio = ZenQuota.ratio(used, ZenQuota.DAILY_LIMIT).coerceIn(0f, 1f)
    val active =
        when {
            ratio >= ZEN_DANGER_RATIO -> Color(palette.zenDanger)
            ratio >= ZEN_WARN_RATIO -> Color(palette.zenWarn)
            else -> Color(palette.zenCalm)
        }
    GaugeBars(
        ratio = if (exact) ratio else 0f,
        colors = GaugeColors(active, Color(palette.track)),
        shape = shape,
        rowTag = TAG_ZEN_ROW,
        modifier = modifier.height(shape.height).fillMaxWidth(),
    )
}

/**
 * Индикатор заполнения контекста (в шапке чата) — горизонтальная ПОЛОСКА из
 * наклонных кубиков (ромбов). Показывает `filled` (входные токены сессии)
 * относительно `limit` (контекст-лимит модели, CONTEXT_LIMIT). Заполненные
 * кубики окрашены цветом прогресса, который меняется по мере приближения к
 * компакту: зелёный (<50%) → жёлтый (50-80%) → красный (>80%). Пустые кубики
 * — тёмные. Зрительно видно, сколько контекста накоплено и когда скоро будет
 * компакт. Полоска занимает всю доступную ширину (weight 1f).
 */
@Suppress("MagicNumber")
@Composable
internal fun ContextGauge(
    filled: Long,
    limit: Long,
    shape: GaugeShape,
    palette: GaugePalette,
    modifier: Modifier = Modifier,
) {
    val ratio = if (limit <= 0) 0f else (filled.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
    val active =
        when {
            ratio < 0.50f -> Color(palette.ctxLow)
            ratio < 0.80f -> Color(palette.ctxMid)
            else -> Color(palette.ctxHigh)
        }
    GaugeBars(
        ratio = ratio,
        colors = GaugeColors(active, Color(palette.track)),
        shape = shape,
        rowTag = TAG_CTX_ROW,
        modifier = modifier.height(shape.height).fillMaxWidth(),
    )
}

/**
 * Общая полоса-кубики для индикаторов расхода.
 *
 * Её используют и контекст, и Zen-квота. Раньше каждый рисовал кубики сам, и они
 * разошлись: у квоты ромбики вышли наклонены в другую сторону и вдвое ниже. Общая
 * функция сделана, чтобы они больше не могли стать разными по сюжету.
 *
 * Форма — наклонная лента: параллелограмм, у которого верхнее ребро сдвинуто
 * вправо на `lean = GAUGE_LEAN * height`. Поверх заливки лента нарезана щелями
 * на [GAUGE_CELLS] ячеек, щели идут параллельно наклону.
 *
 * Почему лента, а не кубики: ячейки должны быть узкими (90 штук на всю
 * ширину), а наклон — заметным. У кубика наклон задаётся сдвигом вершины на
 * величину порядка его ширины, поэтому 90 узких ячеек плюс сильный наклон
 * вместе невозможны — ячейки либо слипаются в гладкую полосу без делений, либо
 * наклон получается нулевым. Лента решает это: наклон идёт по всей длине, а
 * ячейки остаются видимыми.
 */

/**
 * Активная заливка и подложка индикатора одной парой.
 *
 * Два цвета всегда приходят вместе — по одному полосу не нарисовать, — а держать
 * их отдельными параметрами раздувало список аргументов [GaugeBars] до шести.
 */
private data class GaugeColors(
    val active: Color,
    val track: Color,
)

@Composable
private fun GaugeBars(
    ratio: Float,
    colors: GaugeColors,
    shape: GaugeShape,
    rowTag: String,
    modifier: Modifier = Modifier,
) {
    // Щели рисуются цветом фона шапки, поэтому выглядят как пустота между
    // ячейками, а не как линии поверх полосы.
    val backdrop = Color(0xFF101010)
    val fill = ratio.coerceIn(0f, 1f)
    val cells = shape.cells
    val leanRatio = shape.lean
    val gapDp = shape.cellGapDp
    // Canvas сам по себе не создаёт узла доступности, поэтому ряду нужен
    // явный testTag - иначе в дампе не видно, где полоса и какова её высота.
    Canvas(modifier.testTag(rowTag)) {
        val h = size.height
        val lean = h * leanRatio
        // Лента вписана в холст по диагонали: нижний левый угол у левого края,
        // верхний правый — у правого, поэтому ничего не обрезается.
        val bandW = (size.width - lean).coerceAtLeast(1f)
        drawPath(leanBand(0f, bandW, lean, h), colors.track)
        if (fill > 0f) {
            drawPath(leanBand(0f, bandW * fill, lean, h), colors.active)
        }
        val step = bandW / cells
        val gap = gapDp.dp.toPx()
        if (gap > 0f && gap < step * 0.5f) {
            for (k in 1 until cells) {
                val x = step * k
                drawLine(
                    color = backdrop,
                    start = Offset(x, h),
                    end = Offset(x + lean, 0f),
                    strokeWidth = gap,
                )
            }
        }
    }
}

/** Параллелограмм-лента: низ от [left] до [left] + [width], верх сдвинут на [lean]. */
private fun leanBand(
    left: Float,
    width: Float,
    lean: Float,
    height: Float,
) = Path().apply {
    moveTo(left, height)
    lineTo(left + width, height)
    lineTo(left + width + lean, 0f)
    lineTo(left + lean, 0f)
    close()
}

/**
 * Неоновый индикатор MCP-серверов: «N MCP» + мигающая точка-светодиод.
 * Зелёный — все подключённые серверы работают (connected==total>0);
 * красный — какой-то не работает или их нет. Стилистика — неон: яркий
 * цвет, мягкое свечение вокруг точки (shadowBlur), точка плавно мигает.
 */
@Composable
internal fun MCPIndicator(
    connected: Int,
    total: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    // Все работают: есть серверы, и все подключённые дошли до connected.
    val allOk = total > 0 && connected == total
    val neon = if (allOk) Color(0xFF39FF88) else Color(0xFFFF3B3B)
    val hasServers = total > 0
    // Мягкое «дыхание» точки через ДИСКРЕТНЫЙ таймер, а не через infiniteTransition.
    // infiniteTransition тикал каждый кадр (60fps) = RenderThread постоянно занят.
    // Здесь alpha обновляется ~16 раз/с (delay 60мс) циклом, давая плавный пульс,
    // но массивно дешевле. ИТОГОВАЯ защита CPU — тройная:
    //  1) без MCP (total==0) — цикл вообще не запускается, точка статична;
    //  2) цикл дискретный (не 60fps);
    //  3) в фоне (lifecycle паузы) — эффект спит, не тикает.
    var blink by remember { mutableFloatStateOf(if (hasServers) 0.5f else 0.45f) }
    if (hasServers) {
        val lc = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(total) {
            if (lc.currentState != Lifecycle.State.RESUMED) {
                // При старте в фоне — стоим, пока не вернёмся на передний план.
                // Возобновляем по перезаходу (эффект перезапустится на рекомпозиции).
                return@LaunchedEffect
            }
            // Пульс 0.35 → 1.0 → 0.35 по синусу. ДИСКРЕТНО: апдейт раз в 120мс
            // (≈8 тиков за цикл ~1с). Это «дышащий» пульс — плавный на глаз, но
            // в ~7 раз дешевле 60fps-infiniteTransition (RenderThread рисует Canvas
            // только при смене alpha). Пауза в фоне — ниже.
            var t = 0.0
            while (true) {
                // Если Activity ушла в фон — перестаём тикать (экономия батареи).
                if (lc.currentState != Lifecycle.State.RESUMED) {
                    blink = 0.5f
                    delay(2_000L)
                    continue
                }
                val a = 0.35f + 0.65f * ((kotlin.math.sin(t) + 1.0) / 2.0).toFloat()
                blink = a
                t += 0.785 // ~0.785 рад/тик → период волны ≈ 8 тиков ≈ 0.96с
                if (t > kotlin.math.PI * 2.0) t -= kotlin.math.PI * 2.0
                delay(120)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clickable { onClick() }) {
        // Аккуратная мигающая точка-светодиод (без ореола/свечения).
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(22.dp)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val rDot = 4.dp.toPx()
                drawCircle(neon.copy(alpha = blink), radius = rDot, center = c)
            }
        }
        Spacer(Modifier.width(4.dp))
        // «N MCP» — сначала число, потом слово; надпись ВСЕГДА бирюзовая (яркий неон),
        // не зависит от статуса. Статус показывает только точка-светодиод.
        Text(
            "$connected MCP",
            color = Color(0xFF00E5FF),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            softWrap = false,
            maxLines = 1,
        )
    }
}

/**
 * Выпадающий список MCP-серверов (по тапу на индикатор MCP в шапке). Для каждого
 * имени — точка-светодиод: зелёная (работает, status=="connected") или красная
 * (не работает / отключён), плюс голубое число = сколько инструментов отдаёт
 * сервер (если сервер наш локальный и ответил tools/list). Пусто — «нет MCP».
 */
@Composable
internal fun McpServerList(
    servers: List<McpInfo>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(Color(0xFF161616), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            "MCP-серверы",
            color = Color(0xFF00E5FF),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(6.dp))
        if (servers.isEmpty()) {
            Text("Нет подключённых MCP", color = Color(0xFF8A8A8A), fontSize = 12.sp)
        } else {
            servers.forEach { srv ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Точка статуса СТАТИЧНАЯ (без rememberInfiniteTransition) — как в
                    // MCPIndicator. Статус передаёт цвет, а не мигание: не грузим
                    // RenderThread постоянно, даже при открытом списке серверов.
                    val ok = srv.status == "connected"
                    val dotColor = if (ok) Color(0xFF39FF88) else Color(0xFFFF3B3B)
                    Canvas(Modifier.size(12.dp)) {
                        drawCircle(dotColor.copy(alpha = 0.9f), radius = size.minDimension / 2f)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        srv.name,
                        color = Color(0xFFE6E6E6),
                        fontSize = 13.sp,
                    )
                    // Сколько инструментов отдаёт сервер — считает сам сервер
                    // (MemoryMcp.toolCounts), для чужих серверов вывода нет.
                    srv.tools?.let { count ->
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "$count",
                            color = Color(0xFF00E5FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (ok) "работает" else "не работает",
                        color = if (ok) Color(0xFF39FF88) else Color(0xFFFF3B3B),
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(5.dp))
            }
        }
    }
}
