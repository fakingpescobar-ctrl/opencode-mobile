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
import androidx.compose.material.icons.filled.Stop
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

// Нижняя строка по референсам из /sdcard/Documents/reference lowpanel.
// Два режима, переключаются САМОЙ ЛЕВОЙ кнопкой в поле:
//   голос: [⌨][Подпись][синий спектр]  (+) (крупный Mic)
//   текст: [Mic][BasicTextField][синий спектр] (+) (Send/Stop)
// При удержании крупной кнопки чат скрывается и открывается полноэкранный
// живой спектр на «переливающемся» голубом градиенте (02-18-17-47).
// Геометрия снята пиксельно: поле ~146 dp, синий блок ~80 dp, кнопки 34 dp.
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

    // Полноэкранный режим записи — отдельным окном, поверх чата и шапки.
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

// ---------- Режимы поля ----------

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
                .height(44.dp)
                .background(Color(0xFF242424), RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIcon(
            icon = Icons.Filled.Keyboard,
            description = "Переключить на текстовый ввод",
            tint = Color(0xFF7BA6F8),
            onClick = onTextMode,
            modifier = Modifier.padding(start = 6.dp),
        )
        Text(
            "Удерживай микрофон",
            color = Color(0xFFE8E8E8),
            fontSize = 13.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        SpectrumBlock(levels = levels, listening = listening)
    }
    Spacer(Modifier.width(8.dp))
    RoundIcon(
        icon = Icons.Filled.Add,
        description = "Прикрепить изображение или файл",
        tint = Color(0xFFB8B8B8),
        onClick = onAttach,
    )
    Spacer(Modifier.width(8.dp))
    HoldMicButton(voice = voice)
}

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
                .height(44.dp)
                .background(Color(0xFF242424), RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIcon(
            icon = Icons.Filled.Mic,
            description = "Переключить на голосовой ввод",
            tint = Color(0xFF7BA6F8),
            onClick = onVoiceMode,
            modifier = Modifier.padding(start = 6.dp),
        )
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            textStyle = TextStyle(color = Color(0xFFF0F0F0), fontSize = 15.sp),
            cursorBrush = SolidColor(Color(0xFF7BA6F8)),
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
                            "Напиши сообщение…",
                            color = Color(0xFFADADAD),
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
        SpectrumBlock(levels = levels, listening = listening)
    }
    Spacer(Modifier.width(8.dp))
    RoundIcon(
        icon = Icons.Filled.Add,
        description = "Прикрепить изображение или файл",
        tint = Color(0xFFB8B8B8),
        onClick = onAttach,
    )
    Spacer(Modifier.width(8.dp))
    SendStopButton(draft = draft, stopping = stopping, onSend = onSend, onStop = onStop)
}

// ---------- Общие элементы ----------

@Composable
private fun SpectrumBlock(
    levels: List<Float>,
    listening: Boolean,
) {
    Box(
        modifier =
            Modifier
                .width(80.dp)
                .height(44.dp)
                .background(Color(0xFF33426B), RoundedCornerShape(0.dp, 10.dp, 10.dp, 0.dp))
                .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        SpectrumCanvas(levels = levels, listening = listening, barCount = 14, color = Color(0xFF7BA6F8))
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
        val barW = (slot * 0.5f).coerceAtLeast(2f)
        for (i in 0 until barCount) {
            val raw = levels.getOrNull(i) ?: 0f
            val v = if (listening) raw.coerceIn(0f, 1f) else 0.12f
            val h = (3.dp.toPx() + v * (size.height - 3.dp.toPx())).coerceAtLeast(3.dp.toPx())
            val x = i * slot + (slot - barW) / 2f
            val y = (size.height - h) / 2f
            val tint = if (v > 0.62f) Color(0xFFFFFFFF) else color
            drawRoundRect(
                color = tint,
                topLeft = Offset(x, y),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

@Composable
private fun RoundIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .size(34.dp)
                .clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.Transparent,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.fillMaxSize().padding(7.dp),
        )
    }
}

@Composable
private fun HoldMicButton(voice: VoiceHoldActions) {
    val context = LocalContext.current
    val mic by rememberUpdatedState(voice.onRequestMic)
    val start by rememberUpdatedState(voice.onStart)
    val stop by rememberUpdatedState(voice.onStop)
    Surface(
        modifier =
            Modifier
                .size(44.dp)
                .border(1.dp, Color(0xFF3A4C72), CircleShape)
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
        color = Color(0xFF2C3E63),
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = "Голосовое сообщение (удерживай для записи)",
            tint = Color(0xFFFFFFFF),
            modifier = Modifier.fillMaxSize().padding(11.dp),
        )
    }
}

@Composable
private fun SendStopButton(
    draft: String,
    stopping: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val send by rememberUpdatedState(onSend)
    val stop by rememberUpdatedState(onStop)
    val sending = draft.isNotEmpty()
    Surface(
        modifier =
            Modifier
                .size(34.dp)
                .border(1.dp, Color(0xFF333333), CircleShape)
                .clickable(enabled = !stopping || sending) {
                    if (sending) send() else stop()
                },
        shape = CircleShape,
        color = Color(0xFF242424),
    ) {
        Icon(
            imageVector = if (sending) Icons.Filled.Send else Icons.Filled.Stop,
            contentDescription = if (sending) "Отправить сообщение" else "Прервать генерацию",
            tint = if (sending) Color(0xFF7BA6F8) else Color(0xFFFF6F5A),
            modifier = Modifier.fillMaxSize().padding(8.dp),
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
        RoundIcon(icon = Icons.Filled.Close, description = "Закрыть", tint = Color(0xFFB8B8B8), onClick = onClose)
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
            Icon(icon, contentDescription = label, tint = Color(0xFF7BA6F8), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color(0xFFE8E8E8), fontSize = 13.sp)
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
                .background(Color(0xFF0E0E10)),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // Замер с 02-18-17-47: сверху почти чёрный (#0F0F11), внизу яркий
                // голубой (#276ED8), по центру — «дышащее» пятно.
                drawRect(
                    brush =
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    Color(0xFF0F0F11),
                                    Color(0xFF12274A),
                                    Color(0xFF1A4B98),
                                    Color(0xFF276ED8),
                                ),
                        ),
                )
                drawRect(
                    brush =
                        Brush.radialGradient(
                            colors =
                                listOf(
                                    Color(0xFF4EA8FF).copy(alpha = 0.18f + phase * 0.16f),
                                    Color(0xFF2C7BE5).copy(alpha = 0.08f),
                                    Color.Transparent,
                                ),
                            center = Offset(size.width * 0.5f, size.height * (0.62f + phase * 0.06f)),
                            radius = size.minDimension * 1.05f,
                        ),
                )
                // Живой спектр по настоящему PCM из AudioRecorder.level().
                val bars = 28
                val slot = size.width / (bars + 4)
                val barW = (slot * 0.42f).coerceAtLeast(3.dp.toPx())
                for (i in 0 until bars) {
                    val raw = levels.getOrNull(i % levels.size.coerceAtLeast(1)) ?: 0f
                    val v = raw.coerceIn(0f, 1f)
                    val h = (8.dp.toPx() + v * size.height * 0.34f).coerceAtLeast(8.dp.toPx())
                    val x = (i + 2) * slot + (slot - barW) / 2f
                    val y = size.height * 0.56f - h / 2f
                    val alpha = 0.35f + 0.5f * v
                    drawRoundRect(
                        color = Color(0xFFBFE2FF).copy(alpha = alpha),
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
                Text(
                    "Слушаю… говорите",
                    color = Color(0xFFE8F3FF),
                    fontSize = 16.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
