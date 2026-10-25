package org.opencode.mobile.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opencode.mobile.ui.ChatMsg
import org.opencode.mobile.ui.TypewriterText

/** Отдельная роль для сообщения об ошибке — иначе она уезжает в «Система». */
private const val ERROR_ROLE_ARGB = 0xFFE05B5B

/**
 * Текст реплики.
 *
 * [onTyped] == null — печатать не надо (своя реплика, служебная строка, уже
 * напечатанный ответ). Непустой — текст проявляется по буквам, и колбэк
 * вызывается один раз, когда печать дошла до конца.
 */
@Composable
private fun MessageText(
    m: ChatMsg,
    isModel: Boolean,
    modelFont: FontFamily,
    modelColor: Color,
    onTyped: (() -> Unit)?,
) {
    val style =
        TextStyle(
            color = if (isModel) modelColor else Color(0xFFEDEDED),
            fontFamily = if (isModel) modelFont else FontFamily.Default,
            fontSize = 14.sp,
            lineHeight = 19.sp,
        )
    // Один и тот же composable в обоих случаях: смена ветки означала бы, что при
    // гашении ключа поддерево печати выбрасывается из композиции, а вместе с ним
    // и remember с накопленным прогрессом. Здесь меняется только флаг.
    TypewriterText(
        text = m.text,
        animate = onTyped != null,
        style = style,
        key = typedKeyOf(m),
        onTyped = { onTyped?.invoke() },
    )
}

@Composable
internal fun MessageRow(
    m: ChatMsg,
    modelName: String = "Модель",
    modelFont: FontFamily = FontFamily.Monospace,
    modelColor: Color = Color(0xFFD97706),
    onTyped: (() -> Unit)? = null,
) {
    val isModel = m.role == "assistant"
    val roleColor =
        when (m.role) {
            "user" -> Color(0xFF8AB4F8)
            "assistant" -> Color(0xFF9C27B0)
            "error" -> Color(ERROR_ROLE_ARGB)
            else -> Color(0xFFB0B0B0)
        }
    val roleLabel =
        when (m.role) {
            "user" -> "Ты"
            "assistant" -> modelName
            // Отдельная роль, а не «Система»: ход, у которого осталась только
            // причина отказа, показываем как отказ, иначе он читается как
            // служебная реплика приложения.
            "error" -> "Ошибка"
            else -> "Система"
        }
    Column(Modifier.fillMaxWidth()) {
        Text(
            roleLabel,
            color = roleColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        if (m.text.isNotBlank()) {
            MessageText(
                m = m,
                isModel = isModel,
                modelFont = modelFont,
                modelColor = modelColor,
                onTyped = onTyped,
            )
        }
        // Причина отказа лежит отдельно от текста, поэтому и рисуется отдельно:
        // под репликой модели её не спутать с самим ответом.
        if (m.notice != null) {
            SelectionContainer {
                Text(
                    m.notice,
                    color = Color(ERROR_ROLE_ARGB),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Ключ реплики для анимации печати.
 *
 * Ключ — идентификатор сообщения от сервера. Индекс в ленте для этого не годится:
 * лента обрезается до MAX_SHOWN, при каждом обновлении индексы прыгают, и уже
 * напечатанная реплика начинает печататься заново (жалоба пользователя 14.10).
 * Длина текста тоже не годится по той же причине — она меняется при дописывании.
 * У клиентских реплик (своё сообщение) идентификатора нет, поэтому fallback —
 * роль и длина: две такие реплики подряд дадут разные ключи, лишь одинаковые
 * тексты совпадут, и второй просто не напечатается.
 */
internal fun typedKeyOf(m: ChatMsg): String = m.id.ifBlank { "${m.role}:${m.text.length}" }

/** Ряд-переключатель в стиле панели STT: активен или нет. */
@Composable
internal fun TtsRadioRow(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
    ) {
        Text(
            if (active) "● " else "○ ",
            color = Color(0xFFFF6D00),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(label, color = Color(0xFFE6E6E6), fontSize = 13.sp)
    }
}
