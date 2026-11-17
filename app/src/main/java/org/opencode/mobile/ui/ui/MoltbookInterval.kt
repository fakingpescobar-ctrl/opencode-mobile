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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opencode.mobile.social.MoltbookScheduler

/**
 * Выбор периодичности будильника Moltbook.
 *
 * Живёт отдельно от сводки: это настройка поведения, а не отчёт о состоянии.
 * С 17.11 все пять вариантов встают в один ряд, а не в два: на 323 dp секции
 * чип 58 dp читается, и лишний ряд высоты больше не нужен. Акцент выбранного
 * чипа — общий зелёный действий, а не оранжевый, который остался заголовкам.
 *
 * Ряды собираем вручную из [Row], а не через FlowRow: FlowRow в этой версии
 * Compose помечен ExperimentalLayoutApi, и тащить opt-in ради пяти кнопок нечем.
 *
 * Значение перечитывается из prefs ПОСЛЕ [MoltbookScheduler.setUserIntervalMinutes],
 * а не берётся из нажатого чипа: prefs — единственный источник правды, там же
 * мусор из ручной правки сводится к 0, и показывать юзеру нужно ровно то, что
 * агент потом реально применит.
 */
@Composable
internal fun MoltbookIntervalPicker() {
    val context = LocalContext.current
    var chosen by remember { mutableIntStateOf(MoltbookScheduler.userIntervalMinutes(context)) }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            text = "ПЕРИОД · 0 = авто, паузу выбирает агент",
            color = MoltIdle,
            fontSize = TINY,
            letterSpacing = 0.5.sp,
            lineHeight = TINY_LINE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Column(verticalArrangement = Arrangement.spacedBy(INTERVAL_CHIP_GAP)) {
            MoltbookScheduler.INTERVAL_CHOICES_MINUTES.chunked(INTERVAL_CHIPS_PER_ROW).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(INTERVAL_CHIP_GAP)) {
                    row.forEach { minutes ->
                        MoltbookIntervalChip(
                            minutes = minutes,
                            selected = minutes == chosen,
                            onClick = {
                                MoltbookScheduler.setUserIntervalMinutes(context, minutes)
                                chosen = MoltbookScheduler.userIntervalMinutes(context)
                            },
                            modifier = Modifier.width(INTERVAL_CHIP_WIDTH),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoltbookIntervalChip(
    minutes: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Цвет обводки — отдельно, иначе цепочка модификаторов разъезжается на две строки
    // и ktlint требует переносить точку в конец предыдущей, ломая порядок рисования.
    val accent = DiagTone.OK.color()
    val borderColor = if (selected) accent.copy(alpha = 0.5f) else Color.Transparent
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) accent.copy(alpha = 0.12f) else MoltChipOff)
                .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                .clickable { onClick() }
                .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = intervalLabel(minutes),
            color = if (selected) accent else MoltIdle,
            fontSize = SMALL,
            lineHeight = SMALL_LINE,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Подпись варианта периодичности. Ноль показываем как «авто», а не «0 мин»:
 * это не «каждую нулевую минуту», а «агент сам выбирает паузу» — и юзер должен
 * видеть разницу, а не гадать, что список начинается с нуля.
 */
internal fun intervalLabel(minutes: Int): String =
    when {
        minutes <= 0 -> "авто"
        minutes % MINUTES_PER_HOUR == 0 -> "${minutes / MINUTES_PER_HOUR} ч"
        else -> "$minutes мин"
    }

/** Час в часе: подпись ведёт тайм пояситой не минутами. */
private const val MINUTES_PER_HOUR = 60

/** Чипов в ряду: все пять вариантов одновременно, без второго ряда. */
internal const val INTERVAL_CHIPS_PER_ROW: Int = 5

/** Ширина одного чипа: 5 × 58 + 4 × 4 = 306 dp при ~323 доступных в секции. */
private val INTERVAL_CHIP_WIDTH = 58.dp

/** Зазор между чипами. */
private val INTERVAL_CHIP_GAP = 4.dp
