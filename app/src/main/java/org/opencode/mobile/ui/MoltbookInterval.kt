package org.opencode.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
 * Живёт отдельно от карточки [MoltbookPanel]: это настройка поведения, а не
 * отчёт о состоянии, и в карточке ей самое место — три чипа под сеткой чисел.
 *
 * Ряды собираем вручную из [Row], а не через FlowRow: FlowRow в этой версии
 * Compose помечен ExperimentalLayoutApi, и карточка в шапке — не то место, где
 * стоит тащить opt-in предупреждение ради пяти кнопок.
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
    Column(Modifier.padding(top = 6.dp)) {
        Text(
            text = "ПЕРИОД БУДИЛЬНИКА · 0 = авто, паузу выбирает агент",
            color = MoltIdle,
            fontSize = TINY,
            letterSpacing = 0.5.sp,
            lineHeight = TINY_LINE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
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
    val borderColor = if (selected) MoltAccent.copy(alpha = 0.7f) else Color.Transparent
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) MoltAccent.copy(alpha = 0.14f) else MoltChipOff)
                .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                .clickable { onClick() }
                .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = intervalLabel(minutes),
            color = if (selected) MoltAccent else MoltIdle,
            fontSize = BODY,
            lineHeight = BODY_LINE,
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

/** Чипов в ряду: 5 вариантов / 3 = два ряда, третий чип третьего ряда не бывает. */
internal const val INTERVAL_CHIPS_PER_ROW: Int = 3

/** Ширина одного чипа: (319 - 2 × 6) / 3 = 102 dp — текст «30 мин» влезает с запасом. */
private val INTERVAL_CHIP_WIDTH = 102.dp

/** Зазор между чипами: ряд из трёх даёт 3 × 102 + 2 × 6 = 318 dp при 319 доступных. */
private val INTERVAL_CHIP_GAP = 6.dp
