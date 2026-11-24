package org.opencode.mobile.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opencode.mobile.server.AskCard
import org.opencode.mobile.ui.AskCardView

/**
 * Что показывает поле ввода: черновик, состояние остановки, голосовой ввод и
 * блокирующий вопрос сервера.
 */
internal data class ChatInputState(
    val draft: String,
    val stopping: Boolean,
    val listening: Boolean,
    val speechError: String?,
    val whisperBusy: Boolean,
    /** Уровни полоски спектра: новый отсчёт слева, старые гаснут справа. */
    val levels: List<Float> = emptyList(),
    val ask: AskCard?,
    val askBusy: Boolean,
    val askError: String?,
)

/**
 * Куда поле ввода отдаёт нажатия: черновик, отправка и остановка.
 */
internal class ChatInputActions(
    val onDraftChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
)

/** Ответ на блокирующий вопрос сервера: текст или отказ. */
internal class AskActions(
    val onReply: (String?) -> Unit,
    val onReject: () -> Unit,
)

/** Удержание микрофона: просьба о разрешении, начало и конец записи. */
internal class VoiceHoldActions(
    val onRequestMic: () -> Unit,
    val onStart: () -> Unit,
    val onStop: () -> Unit,
)

// Нижняя панель чата: ОДИН тёмный контейнер с текстом, спектром и микрофоном
// или отправкой внутри. Снаружи только Stop — прерывание генерации, а не ввод.
//
// Ширина экрана 363 dp, корневой padding 12 dp → в строке 339 dp. Stop (38 dp)
// и зазор 8 dp оставляют 293 dp на поле. Внутри поля: текст ~190 dp, спектр
// 56 dp во время записи, круглая кнопка 38 dp.
@Composable
internal fun ChatInput(
    state: ChatInputState,
    actions: ChatInputActions,
    ask: AskActions,
    voice: VoiceHoldActions,
) {
    // pointerInput(Unit) и clickable держат лямбду первого кадра. Через
    // rememberUpdatedState нажатие попадает в текущие действия.
    val act by rememberUpdatedState(actions)
    val askAct by rememberUpdatedState(ask)
    if (state.listening || state.speechError != null || state.whisperBusy) {
        VoiceStatusText(state)
    }
    state.ask?.let { card ->
        Box(modifier = Modifier.padding(top = 8.dp)) {
            AskCardView(
                card = card,
                busy = state.askBusy,
                error = state.askError,
                onReply = { m -> askAct.onReply(m) },
                onReject = { askAct.onReject() },
            )
        }
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DraftRow(
            draft = state.draft,
            listening = state.listening,
            levels = state.levels,
            onDraftChange = { act.onDraftChange(it) },
            onSend = { act.onSend() },
            voice = voice,
        )
        StopButton(
            stopping = state.stopping,
            onStop = { act.onStop() },
        )
    }
}

// Строка состояния голосового ввода: «слушаю», «распознаю», причина отказа.
// Обязательные softWrap/maxLines/overflow: узкий экран, иначе строка удваивается.
@Composable
private fun VoiceStatusText(state: ChatInputState) {
    Text(
        when {
            state.listening -> "Слушаю… отпусти кнопку — текст уйдёт в чат"
            state.whisperBusy -> "Распознаю голос…"
            else -> "Распознавание: ${state.speechError}"
        },
        color = if (state.listening) Color(0xFFFF6F5A) else Color(0xFF8A8A8A),
        fontSize = 12.sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

// Полоска спектра: 14 столбиков внутри общего поля, высота каждого — уровень
// звука в момент опроса. Уровни приходят из реального PCM (AudioRecorder.level()
// или MicLevelMeter.level()), случайных значений нет: тишина — низкие столбики,
// речь — высокие. Новый отсчёт слева, предыдущие гаснут и уходят вправо.
@Composable
private fun SpectrumBar(
    levels: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 24.dp,
    barCount: Int = 14,
) {
    Canvas(modifier = modifier.height(height)) {
        val shown = levels.take(barCount)
        val n = shown.size.coerceAtLeast(1)
        val slot = size.width / n
        val barW = minOf(slot * 0.55f, 5.dp.toPx()).coerceAtLeast(2f)
        for (i in 0 until n) {
            val v = shown[i].coerceIn(0f, 1f)
            val h = (3.dp.toPx() + v * (size.height - 3.dp.toPx())).coerceAtLeast(3.dp.toPx())
            val x = i * slot + (slot - barW) / 2f
            val y = (size.height - h) / 2f
            val tint = if (v > 0.62f) Color(0xFFFF6F5A) else Color(0xFF7BA6F8)
            drawRoundRect(
                color = tint,
                topLeft = Offset(x, y),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

// Комбинированное поле: текст слева, во время удержания — живой спектр, справа
// микрофон (пока черновик пуст или идёт запись) либо отправка (если текст есть).
@Composable
private fun RowScope.DraftRow(
    draft: String,
    listening: Boolean,
    levels: List<Float>,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    voice: VoiceHoldActions,
) {
    Row(
        modifier =
            Modifier
                .weight(1f)
                .background(Color(0xFF1C1C1C), RoundedCornerShape(12.dp))
                .padding(horizontal = 5.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f).padding(end = 4.dp),
            textStyle = TextStyle(color = Color(0xFFF0F0F0), fontSize = 15.sp),
            cursorBrush = SolidColor(Color(0xFF7BA6F8)),
            maxLines = 4,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            decorationBox = { inner ->
                Box {
                    if (draft.isEmpty()) {
                        Text(
                            "Напиши сообщение…",
                            color = Color(0xFF777777),
                            fontSize = 15.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
        // Полоска появляется ровно на время удержания микрофона — это и есть
        // «реализация при удержании голосового ввода» с третьего скриншота.
        if (listening) {
            SpectrumBar(
                levels = levels,
                modifier = Modifier.width(56.dp).padding(end = 4.dp),
            )
        }
        if (draft.isEmpty() || listening) {
            MicButton(listening = listening, voice = voice)
        } else {
            SendButton(onSend = onSend)
        }
    }
}

// Микрофон внутри поля: нажал и держишь — запись, отпустил — текст уйдёт в чат.
// Если разрешения нет, просим его и НЕ вызываем stop(): рекордера ещё нет, а
// launcher сам запустит запись после выдачи разрешения (см. ChatOverlay).
@Composable
private fun MicButton(
    listening: Boolean,
    voice: VoiceHoldActions,
) {
    val context = LocalContext.current
    val mic by rememberUpdatedState(voice.onRequestMic)
    val start by rememberUpdatedState(voice.onStart)
    val stop by rememberUpdatedState(voice.onStop)
    Surface(
        modifier =
            Modifier
                .size(38.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                mic()
                            } else {
                                start()
                                try {
                                    awaitRelease()
                                } finally {
                                    stop()
                                }
                            }
                        },
                    )
                },
        shape = CircleShape,
        color = if (listening) Color(0xFFB71C1C) else Color(0xFF252525),
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = "Голосовой ввод (удерживай для записи)",
            tint = if (listening) Color.White else Color(0xFFADADAD),
            modifier = Modifier.fillMaxSize().padding(9.dp),
        )
    }
}

// Отправка занимает то же место внутри поля, что и микрофон: как только появился
// черновик, кнопка меняется, и поле остаётся единым элементом.
@Composable
private fun SendButton(onSend: () -> Unit) {
    val send by rememberUpdatedState(onSend)
    Surface(
        modifier = Modifier.size(38.dp).clickable { send() },
        shape = CircleShape,
        color = Color(0xFF7BA6F8),
    ) {
        Icon(
            imageVector = Icons.Filled.Send,
            contentDescription = "Отправить сообщение",
            tint = Color(0xFF101418),
            modifier = Modifier.fillMaxSize().padding(9.dp),
        )
    }
}

// Stop снаружи поля: прерывание текущей генерации, а не ввод текста или голоса.
@Composable
private fun StopButton(
    stopping: Boolean,
    onStop: () -> Unit,
) {
    val stop by rememberUpdatedState(onStop)
    Surface(
        modifier =
            Modifier
                .padding(start = 8.dp)
                .size(38.dp)
                .clickable(enabled = !stopping) { stop() },
        shape = CircleShape,
        color = if (stopping) Color(0xFF3A3A3A) else Color(0xFF9E1C1C),
    ) {
        Icon(
            imageVector = Icons.Filled.Stop,
            contentDescription = "Прервать генерацию",
            tint = Color.White,
            modifier = Modifier.fillMaxSize().padding(8.dp),
        )
    }
}
