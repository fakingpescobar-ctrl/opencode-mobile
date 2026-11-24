package org.opencode.mobile.ui.chat

import android.Manifest
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
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
    /** Уровни спектра: новый отсчёт слева, старые гаснут справа. */
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

// Панель снята пиксель-в-пиксель с DeepSeek 2.9.0 (скриншоты 02-19-50-40 и
// 02-19-56-80 из /sdcard/Documents/reference lowpanel):
//   голос: сплошной скруглённый бар #242424, [микрофон] [Hold to speak]
//          [синий блок #273041 с живым спектром из 46 столбиков] [+] [клавиатура]
//   текст: бар #3c444c, [микрофон] [поле] [синяя кнопка отправки #345c84]
// Высота всех элементов 30 dp, иконки 24 dp, синий блок 80 dp — все числа
// переведены из пикселей референса при плотности 3.5 px/dp.
// 28 dp и радиус 10 dp — константы из ChatInputDefaults (ChatSessionBottomBar.kt:249).
private val BarHeight = 28.dp
private val VoiceBar = Color(0xFF242424)
private val TextBar = Color(0xFF3C444C)
private val SpectrumBg = Color(0xFF273041)
private val SpectrumFg = Color(0xFF6799FE)
private val SendBlue = Color(0xFF345C84)
private val IconWhite = Color(0xFFF8F8F8)

@Composable
internal fun ChatInput(
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
                    levels = state.levels,
                    listening = state.listening,
                    voice = voice,
                    onTextMode = { textMode = true },
                    onAttach = { attachOpen = !attachOpen },
                )
            }
        }
    }
}

// ---------- Голосовой режим ----------

@Composable
private fun RowScope.VoiceModeField(
    levels: List<Float>,
    listening: Boolean,
    voice: VoiceHoldActions,
    onTextMode: () -> Unit,
    onAttach: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .weight(1f)
                .height(BarHeight)
                .background(VoiceBar, RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HoldMicButton(voice = voice, onClick = onTextMode)
        Spacer(Modifier.width(4.dp))
        Text(
            "Hold to speak",
            color = IconWhite,
            fontSize = 12.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        Spacer(Modifier.width(4.dp))
        SpectrumBlock(levels = levels, listening = listening)
        Spacer(Modifier.width(12.dp))
        CircleIcon(
            icon = Icons.Filled.Add,
            description = "Прикрепить изображение или файл",
            tint = IconWhite,
            onClick = onAttach,
        )
        Spacer(Modifier.width(8.dp))
        CircleIcon(
            icon = Icons.Filled.Keyboard,
            description = "Переключить на текстовый ввод",
            tint = IconWhite,
            onClick = onTextMode,
        )
    }
}

@Composable
private fun SpectrumBlock(
    levels: List<Float>,
    listening: Boolean,
) {
    Box(
        modifier =
            Modifier
                .width(80.dp)
                .height(BarHeight)
                .background(SpectrumBg, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        SpectrumCanvas(levels = levels, listening = listening, barCount = 46, color = SpectrumFg)
    }
}

@Composable
private fun SpectrumCanvas(
    levels: List<Float>,
    listening: Boolean,
    barCount: Int,
    color: Color,
) {
    Canvas(Modifier.fillMaxSize()) {
        val slot = size.width / barCount
        val barW = (slot * 0.55f).coerceAtLeast(1.5f)
        for (i in 0 until barCount) {
            val raw = levels.getOrNull(i % levels.size.coerceAtLeast(1)) ?: 0f
            val v = if (listening) raw.coerceIn(0f, 1f) else 0.18f
            val h = (2.dp.toPx() + v * (size.height - 2.dp.toPx())).coerceAtLeast(2.dp.toPx())
            val x = i * slot + (slot - barW) / 2f
            val y = (size.height - h) / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
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
        SendButton(draft = draft, stopping = stopping, onSend = onSend, onStop = onStop)
    }
}

@Composable
private fun SendButton(
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
