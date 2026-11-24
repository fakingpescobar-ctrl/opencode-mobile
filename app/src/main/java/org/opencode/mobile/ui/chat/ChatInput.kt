package org.opencode.mobile.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.PressGestureScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.window.Popup
import java.io.File
import kotlinx.coroutines.delay
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
) = ChatInputVariantRouter(state, actions, ask, voice)

@Composable
internal fun ChatInput80(
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
        VoiceStatusText80(state)
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
private fun VoiceStatusText80(state: ChatInputState) {
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
            SendButton80(onSend = onSend)
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
private fun SendButton80(onSend: () -> Unit) {
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

// По прямому указанию Макса (24.11, после apk-85): голосовой режим — ОДНА
// сплошная скруглённая ячейка #242424 с микрофоном и подписью Hold to speak.
// Удержание любого места полоски запускает распознавание; справа от ячейки —
// одна квадратная кнопка клавиатуры, переключающая на текстовый ввод.
// Никакого спектра, кружков + и микрофона в голосовом баре больше нет.
// Высота 28 dp и радиус 10 dp — как в ChatInputDefaults (ChatSessionBottomBar.kt:249).
private val BarHeight = 28.dp
private val KeySize = 32.dp
private val VoiceBar = Color(0xFF242424)
private val TextBar = Color(0xFF3C444C)
private val SpectrumFg = Color(0xFF6799FE)
private val SendBlue = Color(0xFF345C84)
private val IconWhite = Color(0xFFF8F8F8)

@Composable
internal fun ChatInput86(
    state: ChatInputState,
    actions: ChatInputActions,
    ask: AskActions,
    voice: VoiceHoldActions,
) {
    val act by rememberUpdatedState(actions)
    val askAct by rememberUpdatedState(ask)
    var textMode by remember { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }

    VoiceRecordingOverlay(
        listening = state.listening,
        levels = state.levels,
        onCancel = { voice.onStop() },
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        if (state.listening || state.speechError != null || state.whisperBusy) {
            VoiceStatusText86(state)
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
        if (attachOpen) {
            AttachmentPanel(onPick = { attachOpen = false }, onClose = { attachOpen = false })
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (textMode) {
                TextModeField(
                    draft = state.draft,
                    stopping = state.stopping,
                    levels = state.levels,
                    listening = state.listening,
                    onDraftChange = { act.onDraftChange(it) },
                    onSend = { act.onSend() },
                    onStop = { act.onStop() },
                    onVoiceMode = { textMode = false },
                    onAttach = { attachOpen = !attachOpen },
                )
            } else {
                VoiceModeField(
                    voice = voice,
                    onTextMode = { textMode = true },
                )
            }
        }
    }
}

// ---------- Голосовой режим ----------

@Composable
private fun RowScope.VoiceModeField(
    voice: VoiceHoldActions,
    onTextMode: () -> Unit,
) {
    val context = LocalContext.current
    val requestMic by rememberUpdatedState(voice.onRequestMic)
    val start by rememberUpdatedState(voice.onStart)
    val stop by rememberUpdatedState(voice.onStop)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Одна сплошная ячейка: удержание любого её места = голосовой ввод.
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(BarHeight)
                    .background(VoiceBar, RoundedCornerShape(10.dp))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                holdToSpeak(
                                    context = context,
                                    requestMic = { requestMic() },
                                    start = { start() },
                                    stop = { stop() },
                                )
                            },
                        )
                    }
                    .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = "Удерживай полоску для голосового ввода",
                    tint = IconWhite,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Hold to speak",
                    color = IconWhite,
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        KeyboardSquare(onClick = onTextMode)
    }
}

/** Квадратная кнопка справа от голосовой полоски: текстовый ввод. */
@Composable
private fun KeyboardSquare(onClick: () -> Unit) {
    Surface(
        modifier =
            Modifier
                .size(KeySize)
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = VoiceBar,
    ) {
        Icon(
            imageVector = Icons.Filled.Keyboard,
            contentDescription = "Переключить на текстовый ввод",
            tint = IconWhite,
            modifier = Modifier.fillMaxSize().padding(7.dp),
        )
    }
}

/** Нажал — начали запись, отпустил — остановили и отправили распознанное. */
private suspend fun PressGestureScope.holdToSpeak(
    context: Context,
    requestMic: () -> Unit,
    start: () -> Unit,
    stop: () -> Unit,
) {
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        requestMic()
        return
    }
    start()
    try {
        awaitRelease()
    } finally {
        stop()
    }
}

// ---------- Текстовый режим ----------

@Composable
private fun RowScope.TextModeField(
    draft: String,
    stopping: Boolean,
    levels: List<Float>,
    listening: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoiceMode: () -> Unit,
    onAttach: () -> Unit,
) {
    val send by rememberUpdatedState(onSend)
    Row(
        modifier =
            Modifier
                .weight(1f)
                .height(BarHeight)
                .background(TextBar, RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HoldMicButton(voice = null, onClick = onVoiceMode)
        Spacer(Modifier.width(4.dp))
        CircleIcon(
            icon = Icons.Filled.Add,
            description = "Прикрепить изображение или файл",
            tint = IconWhite,
            onClick = onAttach,
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            textStyle = TextStyle(color = IconWhite, fontSize = 14.sp),
            cursorBrush = SolidColor(IconWhite),
            maxLines = 4,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
            keyboardActions = KeyboardActions(onSend = { send() }),
            decorationBox = { inner ->
                Box {
                    if (draft.isEmpty()) {
                        Text(
                            "Type a message",
                            color = Color(0xFFDCDCDC),
                            fontSize = 14.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
        SendButton86(draft = draft, stopping = stopping, onSend = onSend, onStop = onStop)
    }
}

@Composable
private fun SendButton86(
    draft: String,
    stopping: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val sending = draft.isNotEmpty()
    Surface(
        modifier =
            Modifier
                .width(49.dp)
                .height(BarHeight)
                .clickable(enabled = !stopping || sending) {
                    if (sending) onSend() else onStop()
                },
        shape = RoundedCornerShape(6.dp),
        color = SendBlue,
    ) {
        Icon(
            imageVector = if (sending) Icons.Filled.Send else Icons.Filled.Close,
            contentDescription = if (sending) "Отправить сообщение" else "Прервать генерацию",
            tint = IconWhite,
            modifier = Modifier.fillMaxSize().padding(9.dp),
        )
    }
}

// ---------- Общие элементы ----------

@Composable
private fun CircleIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .size(24.dp)
                .border(1.dp, Color(0x66FFFFFF), CircleShape)
                .clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.Transparent,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.fillMaxSize().padding(5.dp),
        )
    }
}

@Composable
private fun HoldMicButton(
    voice: VoiceHoldActions?,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val mic by rememberUpdatedState(voice?.onRequestMic ?: {})
    val start by rememberUpdatedState(voice?.onStart ?: {})
    val stop by rememberUpdatedState(voice?.onStop ?: {})
    val tap by rememberUpdatedState(onClick)
    val modifier =
        Modifier
            .size(24.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap?.invoke() },
                    onPress = {
                        if (voice == null) return@detectTapGestures
                        holdToSpeak(
                            context = context,
                            requestMic = { mic() },
                            start = { start() },
                            stop = { stop() },
                        )
                    },
                )
            }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = Color.Transparent,
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = "Голосовое сообщение (удерживай для записи)",
            tint = IconWhite,
            modifier = Modifier.fillMaxSize().padding(5.dp),
        )
    }
}

@Composable
private fun VoiceStatusText86(state: ChatInputState) {
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

// ---------- Вложения ----------

@Composable
private fun AttachmentPanel(
    onPick: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(Color(0xFF1C1C1C), RoundedCornerShape(12.dp))
                .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AttachmentOption(Icons.Filled.Image, "Изображение", onPick)
        Spacer(Modifier.width(12.dp))
        AttachmentOption(Icons.Filled.InsertDriveFile, "Файл", onPick)
        Spacer(Modifier.weight(1f))
        CircleIcon(icon = Icons.Filled.Close, description = "Закрыть", tint = IconWhite, onClick = onClose)
    }
}

@Composable
private fun AttachmentOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF2A2A2A),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = label, tint = SpectrumFg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = IconWhite, fontSize = 13.sp)
        }
    }
}

// ---------- Полноэкранный живой спектр при удержании ----------

@Composable
private fun VoiceRecordingOverlay(
    listening: Boolean,
    levels: List<Float>,
    onCancel: () -> Unit,
) {
    if (!listening) return
    val infinite = rememberInfiniteTransition()
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 2600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
    )
    Popup(alignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0F0F0F)),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // Замер 02-18-17-47: чёрный верх, голубое «жидкое» пятно по центру
                // и сплошной переливающийся градиент #265cb0 → #276dd3 внизу.
                drawRect(
                    brush =
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    Color(0xFF0F0F0F),
                                    Color(0xFF12284C),
                                    Color(0xFF1A4B98),
                                    Color(0xFF276DD3),
                                ),
                        ),
                )
                drawRect(
                    brush =
                        Brush.radialGradient(
                            colors =
                                listOf(
                                    Color(0xFF3E8BEE).copy(alpha = 0.3f + phase * 0.18f),
                                    Color(0xFF1A4B98).copy(alpha = 0.14f),
                                    Color.Transparent,
                                ),
                            center = Offset(size.width * 0.5f, size.height * (0.3f + phase * 0.04f)),
                            radius = size.minDimension * 1.05f,
                        ),
                )
                // Живой спектр по настоящему PCM; у DeepSeek 46 столбиков.
                val bars = 46
                val slot = size.width / (bars + 4)
                val barW = (slot * 0.42f).coerceAtLeast(3.dp.toPx())
                for (i in 0 until bars) {
                    val raw = levels.getOrNull(i % levels.size.coerceAtLeast(1)) ?: 0f
                    val v = raw.coerceIn(0f, 1f)
                    val h = (8.dp.toPx() + v * size.height * 0.12f).coerceAtLeast(8.dp.toPx())
                    val x = (i + 2) * slot + (slot - barW) / 2f
                    val y = size.height * 0.97f - h
                    drawRoundRect(
                        color = Color(0xFFB4C9EA).copy(alpha = 0.4f + 0.5f * v),
                        topLeft = Offset(x, y),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 116.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(32.dp).clickable(onClick = onCancel),
                    shape = CircleShape,
                    color = Color(0x33FFFFFF),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Отменить запись",
                        tint = Color.White,
                        modifier = Modifier.fillMaxSize().padding(7.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Запись…",
                        color = Color(0xFFE8F3FF),
                        fontSize = 16.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Проведи вверх, чтобы отменить",
                        color = Color(0xFFBBD6F5),
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}


// ---------- Переключатель вариантов интерфейса без пересборки APK ----------
// Агент меняет файл ui-variant.txt в приватном внешнем хранилище приложения:
//   apk-80 (по умолчанию) — единое поле ввода образца apk-80;
//   apk-86               — одна голосовая полоска + квадратная клавиатура.
// Композит опрашивает файл раз в секунду, поэтому смена видна без перезапуска
// приложения и без новой сборки/установки APK.
private fun readUiVariant(context: Context): String {
    val file = File(context.getExternalFilesDir(null), "ui-variant.txt")
    val raw = runCatching { file.readText().trim() }.getOrDefault("apk-80")
    return if (raw.contains("86") || raw.contains("strip") || raw.contains("keyboard")) {
        "apk-86"
    } else {
        "apk-80"
    }
}

@Composable
private fun rememberUiVariant(): String {
    val context = LocalContext.current
    var variant by remember { mutableStateOf(readUiVariant(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val next = readUiVariant(context)
            if (next != variant) variant = next
        }
    }
    return variant
}

@Composable
internal fun ChatInputVariantRouter(
    state: ChatInputState,
    actions: ChatInputActions,
    ask: AskActions,
    voice: VoiceHoldActions,
) {
    when (rememberUiVariant()) {
        "apk-86" -> ChatInput86(state, actions, ask, voice)
        else -> ChatInput80(state, actions, ask, voice)
    }
}

