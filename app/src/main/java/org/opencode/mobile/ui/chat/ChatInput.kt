package org.opencode.mobile.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
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
    val ask: AskCard?,
    val askBusy: Boolean,
    val askError: String?,
)

/**
 * Куда поле ввода отдаёт нажатия: черновик, отправка и остановка.
 *
 * Ответ на вопрос сервера и голосовые действия — отдельными объектами: у них своя
 * жизнь (карточка вопроса приходит с сервера, микрофон держит палец), и вместе с
 * полем они давали восемь параметров, в которых ничего не читалось.
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

// Нижний блок чата: состояние голосового ввода, блокирующий вопрос, поле с
// микрофоном внутри и кнопка Stop. Всё, что не лента и не шапка, — здесь.
//
// Микрофон внутри поля, а не рядом: три круга по 46 dp съедали 162 dp из 339, и на
// текст оставалось меньше половины ширины экрана.
@Composable
internal fun ChatInput(
    state: ChatInputState,
    actions: ChatInputActions,
    ask: AskActions,
    voice: VoiceHoldActions,
) {
    // pointerInput(Unit) и clickable держат лямбду первого кадра. Через
    // rememberUpdatedState нажатие попадает в текущие действия, а не в те, что были
    // при входе на экран.
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
            onDraftChange = { act.onDraftChange(it) },
            onSend = { act.onSend() },
            voice = voice,
        )
        // Кнопка Stop: ВСЕГДА видна рядом с микрофоном. Прерывает текущую генерацию
        // модели; если модель не думает — abort просто не сработает.
        Surface(
            modifier =
                Modifier
                    .padding(start = 8.dp)
                    .size(38.dp)
                    .clickable(enabled = !state.stopping) { act.onStop() },
            shape = CircleShape,
            color = if (state.stopping) Color(0xFF3A3A3A) else Color(0xFF9E1C1C),
        ) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = "Прервать генерацию",
                tint = Color.White,
                modifier = Modifier.fillMaxSize().padding(8.dp),
            )
        }
    }
}

// Строка состояния голосового ввода: «слушаю», «распознаю», причина отказа.
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
        modifier = Modifier.padding(top = 6.dp),
    )
}

// Поле ввода с микрофоном внутри: сам текст, подсказка и микрофон, который уходит,
// как только пользователь начал печатать. Пока идёт запись (listening) он остаётся —
// иначе нечем остановить её.
@Composable
private fun RowScope.DraftRow(
    draft: String,
    listening: Boolean,
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
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 4.dp),
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
                        )
                    }
                    inner()
                }
            },
        )
        AnimatedVisibility(
            visible = draft.isEmpty() || listening,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            MicButton(listening = listening, voice = voice)
        }
    }
}

// Микрофон: запись идёт, пока палец держит кнопку. Разрешение спрашивается в
// момент нажатия, а не при старте экрана: до первого нажатия оно не нужно.
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
                            }
                            try {
                                awaitRelease()
                            } finally {
                                stop()
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
            tint = if (listening) Color.White else Color(0xFFE0E0E0),
            modifier = Modifier.fillMaxSize().padding(8.dp),
        )
    }
}
