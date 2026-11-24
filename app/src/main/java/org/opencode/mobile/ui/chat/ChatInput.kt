package org.opencode.mobile.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
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

// Нижняя строка по референсу из /sdcard/Documents/reference lowpanel:
//   [ тёмное поле ввода ][ синий блок спектра ]   (микрофон) (отправка/Stop)
// Геометрия снята пиксельно с 1272x530 и 1272x1472 при density 3.5 px/dp:
//   поле ~146 dp, синий блок ~75 dp вплотную к полю, две круглые кнопки ~34 dp.
// Спектр рисуется на реальном PCM; в покое — низкая ровная линия, при удержании
// микрофона столбики растут и «бегут». Контракт state/actions не менялся.
@Composable
internal fun ChatInput(
    state: ChatInputState,
    actions: ChatInputActions,
    ask: AskActions,
    voice: VoiceHoldActions,
) {
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
        InputWithSpectrum(
            draft = state.draft,
            listening = state.listening,
            levels = state.levels,
            onDraftChange = { act.onDraftChange(it) },
            onSend = { act.onSend() },
        )
        Spacer(Modifier.width(16.dp))
        VoiceButton(voice = voice)
        Spacer(Modifier.width(8.dp))
        SendStopButton(
            draft = state.draft,
            stopping = state.stopping,
            onSend = { act.onSend() },
            onStop = { act.onStop() },
        )
    }
}

// Всё, что в референсе выглядит как одна строка: тёмное поле и синий спектр
// вплотную друг к другу, с общим скруглением по краям.
@Composable
private fun RowScope.InputWithSpectrum(
    draft: String,
    listening: Boolean,
    levels: List<Float>,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .weight(1f)
                .height(44.dp)
                .background(Color(0xFF242424), RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f).padding(start = 10.dp, end = 4.dp),
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
}

// Синий блок справа от поля. В покое — ровная линия, при удержании голоса —
// столбики по реальному уровню микрофона. Никаких случайных чисел.
@Composable
private fun SpectrumBlock(
    levels: List<Float>,
    listening: Boolean,
) {
    Box(
        modifier =
            Modifier
                .width(75.dp)
                .height(44.dp)
                .background(
                    Color(0xFF33426B),
                    RoundedCornerShape(0.dp, 10.dp, 10.dp, 0.dp),
                )
                .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val count = 14
            val slot = size.width / count
            val barW = (slot * 0.5f).coerceAtLeast(2f)
            for (i in 0 until count) {
                val raw = levels.getOrNull(i) ?: 0f
                val v = if (listening) raw.coerceIn(0f, 1f) else 0.12f
                val h = (3.dp.toPx() + v * (size.height - 3.dp.toPx())).coerceAtLeast(3.dp.toPx())
                val x = i * slot + (slot - barW) / 2f
                val y = (size.height - h) / 2f
                val tint = if (v > 0.62f) Color(0xFFFFFFFF) else Color(0xFF7BA6F8)
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(x, y),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                )
            }
        }
    }
}

// Строка состояния голосового ввода. Защита от переноса обязательна на 363 dp.
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

// Левая круглая кнопка: удержание = запись голоса. Разрешения просим один раз;
// после выдачи запись не стартует сама — пользователь удерживает кнопку.
@Composable
private fun VoiceButton(voice: VoiceHoldActions) {
    val context = LocalContext.current
    val mic by rememberUpdatedState(voice.onRequestMic)
    val start by rememberUpdatedState(voice.onStart)
    val stop by rememberUpdatedState(voice.onStop)
    Surface(
        modifier =
            Modifier
                .size(34.dp)
                .border(1.dp, Color(0xFF333333), CircleShape)
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
        color = Color(0xFF242424),
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = "Голосовой ввод (удерживай для записи)",
            tint = Color(0xFFFFFFFF),
            modifier = Modifier.fillMaxSize().padding(8.dp),
        )
    }
}

// Правая круглая кнопка: при черновике отправляет, в покое/генерации — Stop.
// Так на строке ровно две кнопки, как на скриншотах.
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
