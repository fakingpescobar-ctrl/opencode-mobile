package org.opencode.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opencode.mobile.social.MoltbookScheduler

/**
 * Выключатель будильника Moltbook в карточке — кнопка, а не ещё один период.
 *
 * Период и включённость — разные вопросы, и раньше в UI был только первый.
 * «Авто» (0) значит «паузу выбирает агент», то есть тик работает; выключить
 * автономию целиком было нечем, кроме «Остановить» в настройках Android,
 * которая гасит вместе с будильником и сам сервер. Юзеру нужен был ровно один
 * рубильник, и он здесь.
 *
 * Состояние читается из тех же prefs, что и планировщик, а не хранится в
 * композиции как истина: экран обязан показывать то, что реально применяется,
 * иначе после перезапуска кнопка говорила бы одно, а будильник делал другое.
 */
@Composable
internal fun rememberAlarmEnabled(): MutableState<Boolean> {
    val context = LocalContext.current
    return remember { mutableStateOf(MoltbookScheduler.isEnabled(context)) }
}

@Composable
internal fun MoltbookAlarmSwitch(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "БУДИЛЬНИК ТИКА",
                    color = MoltIdle,
                    fontSize = TINY,
                    letterSpacing = 0.5.sp,
                    lineHeight = TINY_LINE,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // Выключенное состояние обязано называть последствие, а не
                    // только факт: «выключен» без «тиков нет» читается как
                    // «кнопка нажата», а не как «агент больше не проснётся».
                    text = alarmStateText(enabled),
                    color = if (enabled) MoltValue else MoltIdle,
                    fontSize = SMALL,
                    lineHeight = SMALL_LINE,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            BoxedAlarmButton(enabled) {
                MoltbookScheduler.setEnabled(context, !enabled)
                // Перечитываем prefs, а не инвертируем нажатое: единственный
                // источник правды — то же место, откуда берёт решение будильник.
                onChange(MoltbookScheduler.isEnabled(context))
            }
        }
        if (!enabled) {
            Text(
                text = "тики не приходят; выбранный период ниже сохранится",
                color = MoltIdle,
                fontSize = TINY,
                lineHeight = TINY_LINE,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** Кнопка рубильника: подпись — действие, а не состояние, чтобы не читалась как статус. */
@Composable
private fun BoxedAlarmButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (enabled) MoltChipOff else MoltAccent.copy(alpha = 0.18f))
                .border(1.dp, MoltAccent.copy(alpha = if (enabled) 0.35f else 0.75f), RoundedCornerShape(6.dp))
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag(TAG_MOLTBOOK_ALARM_SWITCH),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = alarmActionText(enabled),
            color = if (enabled) MoltValue else MoltAccent,
            fontSize = BODY,
            lineHeight = BODY_LINE,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * Подпись состояния. Включённое не обещает конкретный период: его показывает
 * ряд чипов ниже, и дублировать число здесь значило бы завести второе место,
 * которое разъедется с первым при первом же изменении.
 */
internal fun alarmStateText(enabled: Boolean): String = if (enabled) "включён · тики идут" else "выключен · тиков нет"

/** Подпись кнопки — действие, которое произойдёт: «выключить», «включить». */
internal fun alarmActionText(enabled: Boolean): String = if (enabled) "выключить" else "включить"

const val TAG_MOLTBOOK_ALARM_SWITCH = "moltbook_alarm_switch"
