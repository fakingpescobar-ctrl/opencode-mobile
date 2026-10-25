@file:Suppress("MatchingDeclarationName") // файл назван по ленте (ChatFeed), а не по её
// состоянию: главное здесь — composable, ChatFeedState только его вход.

package org.opencode.mobile.ui.chat

import android.content.Context
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.opencode.mobile.ui.ChatMsg
import org.opencode.mobile.ui.ChatNotice
import org.opencode.mobile.ui.ChatSnapshot
import org.opencode.mobile.ui.EmptyChatArt

/**
 * Всё, что лента берёт из состояния экрана.
 *
 * Отдельным объектом, а не шестью параметрами: список сообщений, снимок сессии и
 * оформление меняются вместе, и по отдельности они ничего не значат.
 */
internal data class ChatFeedState(
    val messages: List<ChatMsg>,
    val snapshot: ChatSnapshot?,
    val noticeLatch: ChatNotice?,
    val modelFont: FontFamily,
    val modelColor: Color,
    val typingKey: String?,
    /**
     * Единственное, чем лента пишет наружу: гасит ключ печати после того, как
     * последняя реплика напечаталась. Ключ живёт в экране, потому что переживает
     * пересборку ленты при каждом опросе сервера.
     */
    val onTyped: (String) -> Unit,
)

// Лента чата: пустая картинка, сообщения, строка «думаю», живой чип инструмента
// и плашка отказа. Всё, что растёт вниз и скроллится, — здесь; шапка, поле ввода
// и панели живут снаружи и не перерисовываются от новых сообщений.
//
// modifier приходит от вызывающего, потому что weight(1f) задаётся в родительской
// Column: без гибкого ребёнка колонка сжимается до содержимого и поле ввода
// уезжает вверх — так и было в новой или очищенной сессии.
@Composable
internal fun ChatFeed(
    state: ChatFeedState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onAbortDead: (String) -> Unit = {},
    onResetDead: (String) -> Unit = {},
) {
    val context = LocalContext.current
    // The empty-state art fades out on the first message instead of blinking off.
    // It is driven here rather than inside the branch that draws it, because a
    // branch flip cannot be animated.
    val emptyAlpha by animateFloatAsState(
        targetValue = if (state.messages.isEmpty()) 1f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "empty-art",
    )
    LaunchedEffect(state.messages.size, state.snapshot?.thinking, state.snapshot?.liveTool) {
        Log.d(
            "ChatOverlay",
            "RENDER msgs=${state.messages.size} thinking=${state.snapshot?.thinking} " +
                "live=${state.snapshot?.liveTool?.name}",
        )
    }
    // The octopus is composed even while the feed has messages, so it can fade out
    // instead of blinking off. It sits under the feed.
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        EmptyChatArt(alpha = emptyAlpha)
        if (state.messages.isNotEmpty()) {
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                feedItems(state, context, onAbortDead, onResetDead)
            }
        }
    }
}

// Строки ленты. Вынесено из ChatFeed, потому что всё это — один LazyListScope:
// сам composable остаётся про картинку и скролл, а состав ленты читается отдельно.
private fun LazyListScope.feedItems(
    state: ChatFeedState,
    context: Context,
    onAbortDead: (String) -> Unit,
    onResetDead: (String) -> Unit,
) {
    val msgs = state.messages
    // Позиционный ключ (без кастомного key). Кастомный key из контента
    // крашил LazyColumn (Key "…was already used") при дубликатах сообщений
    // (например, дважды отправленная команда «Стой»). Лента append-only,
    // добавление в конец не трогает существующие позиции — позиционный
    // ключ безопасен и полностью устраняет этот краш.
    itemsIndexed(msgs) { idx, m ->
        // Печать — только ПОСЛЕДНЕЙ реплике модели и только один
        // раз на её текст. Ключ гасится в onTyped: иначе каждая
        // пересборка ленты (опрос сервера каждые доли секунды)
        // начинала бы печать заново, а возврат в чат
        // перепечатывал бы всю историю.
        // Условие «последняя» обязательно: без него печаталась бы
        // каждая реплика, ключ которой ещё не погашен, то есть
        // вся лента целиком при каждом новом ответе.
        val last = idx == msgs.lastIndex
        // Пустой ответ модели (шаг с одними вызовами
        // инструментов) печатать нечего: MessageText для
        // него вообще не вызывается, значит и гасить ключ
        // некому — иначе typingKey навсегда остался бы на
        // предыдущем ответе.
        val printable = last && m.role == "assistant" && m.text.isNotBlank()
        MessageRow(
            m,
            state.snapshot?.modelName ?: "Модель",
            state.modelFont,
            state.modelColor,
            onTyped =
                if (printable && state.typingKey != typedKeyOf(m)) {
                    { state.onTyped(typedKeyOf(m)) }
                } else {
                    null
                },
        )
    }
    if (state.snapshot?.thinking == true) {
        val snap = requireNotNull(state.snapshot)
        item(key = if (snap.stalled) "stalled" else "thinking") {
            when {
                snap.stalledDead ->
                    DeadRow(
                        onAbort = { snap.activeId?.let(onAbortDead) },
                        onReset = { snap.activeId?.let(onResetDead) },
                    )
                snap.stalled -> StalledRow()
                else -> ThinkingRow()
            }
        }
        // Живой чип «какой тул выполняет модель» — поверх индикатора думания.
        snap.liveTool?.let { t ->
            item(key = "livetool_${t.name}_${t.detail.hashCode()}") {
                LiveToolRow(t)
            }
        }
    }
    // Причина отказа сервера (квота/ретрай) — всегда последняя строка,
    // чтобы её не съедал автоскролл вниз и она читалась как вывод.
    // Рендерим защёлку: плашка должна висеть, пока сервер держит
    // ту же причину, даже если конкретный тик опроса её не увидел.
    (state.noticeLatch ?: state.snapshot?.notice)?.let { n ->
        item(key = "notice_${n.title}_${n.attempt}") {
            NoticeRow(n, context)
        }
    }
}
