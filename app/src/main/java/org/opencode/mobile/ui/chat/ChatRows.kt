package org.opencode.mobile.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opencode.mobile.ui.ChatNotice
import org.opencode.mobile.ui.ChatTool

@Composable
internal fun ThinkingRow() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val bars =
        listOf(
            transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(380, delayMillis = 0), RepeatMode.Reverse),
                label = "bar0",
            ),
            transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(380, delayMillis = 130), RepeatMode.Reverse),
                label = "bar1",
            ),
            transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(380, delayMillis = 260), RepeatMode.Reverse),
                label = "bar2",
            ),
        )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        bars.forEach { h ->
            Box(
                Modifier
                    .padding(horizontal = 1.5.dp)
                    .size(width = 5.dp, height = 16.dp * h.value)
                    .background(Color(0xFF7BD88F), RoundedCornerShape(2.dp)),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text("Модель думает…", color = Color(0xFF8A8A8A), fontSize = 13.sp)
    }
}

@Composable
internal fun LiveToolRow(tool: ChatTool) {
    // Живой чип: какой инструмент модель вызывает ПРЯМО СЕЙЧАС (пока работает).
    val transition = rememberInfiniteTransition(label = "liveTool")
    val pulse by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(460, delayMillis = 0), RepeatMode.Reverse),
        label = "pulse",
    )
    // Иконка по типу инструмента.
    val (icon, accent) =
        when (tool.name) {
            "websearch", "webfetch", "context7" -> "🔍" to Color(0xFF5B9BD5)
            "bash", "shell" -> "🛠" to Color(0xFFD97706)
            "read", "grep", "glob" -> "📄" to Color(0xFF7BD88F)
            "write", "edit" -> "✏️" to Color(0xFFB48AD9)
            else -> "⚙️" to Color(0xFF9AA5B1)
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .padding(vertical = 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = 0.12f * pulse))
                .border(width = 1.dp, color = accent.copy(alpha = 0.5f), shape = RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        // Пульсирующая точка «активно».
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer { alpha = pulse }
                .background(accent, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$icon ${tool.name}",
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        if (tool.detail.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(
                tool.detail,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Палитра плашки причины отказа: тёмно-коричневый фон (не красный — это не
 * ошибка приложения, а отказ провайдера), янтарный акцент и приглушённый вторичный.
 */
@Suppress("MagicNumber")
private object NoticePalette {
    val bg = Color(0xFF2A1F1A)
    val accent = Color(0xFFFFC107)
    val dim = Color(0xFF9A8C7A)
}

/**
 * Плашка причины, по которой сервер не двигает ход (исчерпанная квота провайдера,
 * ретрай после ошибки). Показывается вместо безликого «зависло»: у пользователя
 * должно быть конкретное действие, а не гипотеза про сеть.
 */
@Composable
internal fun NoticeRow(
    notice: ChatNotice,
    context: Context,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(NoticePalette.bg, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(NoticePalette.accent, RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                notice.title,
                color = NoticePalette.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            if (notice.attempt > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "попытка ${notice.attempt}",
                    color = NoticePalette.dim,
                    fontSize = 11.sp,
                )
            }
        }
        Text(
            notice.message,
            color = Color(0xFFEDEDED),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        val link = notice.actionLink
        val label = notice.actionLabel
        if (!link.isNullOrBlank() && !label.isNullOrBlank()) {
            Text(
                label,
                color = Color(0xFF7BD88F),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier =
                    Modifier
                        .padding(top = 6.dp)
                        .clickable {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                            } catch (_: Exception) {
                                // Без браузера — просто молча оставляем текст.
                            }
                        },
            )
        }
    }
}

@Composable
internal fun StalledRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(Color(0xFFE25822), RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Нет ответа (зависло) — проверь сеть/провайдера",
            color = Color(0xFFE25822),
            fontSize = 13.sp,
        )
    }
}

/**
 * Плашка МЁРТВОГО хода: сервер принял запрос, но ассистент не создал ни одного
 * шага — обычно он уже ответил ошибкой (неизвестная модель, 500 провайдера) или
 * списал квоту в ретрае. Отличать от «долго думает» принципиально: там модель
 * работает и надо ждать, тут она не вернётся сама и ждать бессмысленно.
 *
 * Само по себе «текст + иди жди» — это тупик: ход не завершится никогда, и
 * единственный выход (сменить сессию) надо ещё и догадаться. Поэтому здесь два
 * действия, и они неравнозначны по цене:
 *   [onAbort] — «прервать ход»: abort через сервер, история остаётся целой.
 *   [onReset] — «начать заново»: удалить сессию-зомби и создать новую.
 * Удаление необратимо, поэтому второй путь открывается только через диалог
 * подтверждения (см. resetDeadSession).
 */
@Composable
internal fun DeadRow(
    onAbort: () -> Unit,
    onReset: () -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(NoticePalette.accent, RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Модель не ответила — запрос не прошёл",
                color = NoticePalette.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            "Сервер принял сообщение, но шага ответа не создал. Проверь квоту " +
                "провайдера и отправь сообщение ещё раз.",
            color = Color(0xFFEDEDED),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 2.dp),
        )

        // Два выхода из тупика, цена разная и это видно по цвету: зелёный —
        // бесплатный (abort), красный — необратимый (удаление сессии).
        Row(
            Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Прервать ход",
                color = Color(0xFF7BD88F),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onAbort),
            )
            Text(
                "·",
                color = NoticePalette.dim,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text(
                "Начать заново",
                color = Color(0xFFFF6F5A),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onReset),
            )
        }
    }
}
