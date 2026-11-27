package org.opencode.mobile.ui.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opencode.mobile.tts.TtsConfig
import org.opencode.mobile.tts.TtsEngine
import org.opencode.mobile.tts.TtsNarrator
import org.opencode.mobile.ui.SttModelPicker
import kotlin.random.Random

// Голосовые панели шапки: STT под Mic, TTS под динамиком. Разнесены по разным
// иконкам, чтобы выбор движка распознавания и настройка озвучки не мешались.

private val PanelBg = Brush.verticalGradient(listOf(Color(0xFF1A1F2B), Color(0xFF141821)))
private val CardBg = Color(0xFF202838)
private val CardActiveBg = Color(0xFF26324A)
private val Accent = Color(0xFFFF8A3D)
private val AccentSoft = Color(0x33FF8A3D)
private val TextMain = Color(0xFFEAF0FF)
private val TextDim = Color(0xFF93A0B8)

private val TTS_PREVIEW_PHRASES = listOf(
    "Это мой голос.",
    "Это мой новый голос.",
    "Привет, так я звучу.",
    "Проверка голоса, как слышно?",
    "Вот так будет звучать ответ.",
)

@Composable
internal fun SttPanel(voice: VoiceSettings) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(PanelBg)
            .border(1.dp, Color(0xFF2A3550), RoundedCornerShape(16.dp))
            .testTag(TAG_PANEL_STT)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            "РАСПОЗНАВАНИЕ РЕЧИ",
            color = Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        Text(
            "Выбери движок STT. Для русского приоритет — GigaAM.",
            color = TextDim,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SttEngineCard(
                title = "Системный",
                subtitle = "Android/Google",
                selected = voice.sttEngine == VoiceEngine.SYSTEM,
                modifier = Modifier.weight(1f),
            ) { voice.chooseEngine(VoiceEngine.SYSTEM) }
            SttEngineCard(
                title = "Локальный",
                subtitle = "Whisper/Sherpa",
                selected = voice.sttEngine == VoiceEngine.WHISPER,
                modifier = Modifier.weight(1f),
            ) { voice.chooseEngine(VoiceEngine.WHISPER) }
        }

        if (voice.sttEngine == VoiceEngine.WHISPER) {
            Spacer(Modifier.height(10.dp))
            Text("МОДЕЛЬ", color = TextDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Text(
                "GigaAM v3 E2E RNNT — рекомендуемый RU-first. Скачивание в Диагностике.",
                color = Color(0xFF9FC5A6),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
            )
            SttModelPicker(
                selected = voice.whisperModelId,
                onSelect = { picked -> voice.chooseWhisperModel(picked) },
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "Системный движок использует распознавание Android. Локальные модели не нужны.",
                color = TextDim,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun SttEngineCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    val border = if (selected) Accent else Color(0xFF33405C)
    val bg = if (selected) CardActiveBg else CardBg
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(if (selected) 1.5.dp else 1.dp, border, RoundedCornerShape(14.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (selected) Accent else Color(0xFF55627A)),
            )
            Spacer(Modifier.width(6.dp))
            Text(title, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(subtitle, color = TextDim, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
internal fun TtsPanel(
    context: Context,
    tts: TtsSettings,
    ttsOn: Boolean,
    ttsFallback: String?,
    elevenKey: ElevenKeySettings,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(PanelBg)
            .border(1.dp, Color(0xFF2A3550), RoundedCornerShape(16.dp))
            .testTag(TAG_PANEL_TTS)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "ОЗВУЧКА ОТВЕТОВ",
                    color = Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                )
                Text(
                    if (ttsOn) "TTS включён" else "TTS выключен",
                    color = TextDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            ToggleChip(active = ttsOn, label = if (ttsOn) "ON" else "OFF") {
                tts.setEnabled(!ttsOn)
            }
        }

        if (!ttsOn) {
            Text(
                if (TtsConfig.read(context).modelInstalled(context)) {
                    "Supertonic offline готов, sid ${tts.config.sid + 1}."
                } else {
                    "Модель Supertonic не скачана — скачай в Диагностике."
                },
                color = TextDim,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            return
        }

        Spacer(Modifier.height(10.dp))
        TtsEngineSection(tts, ttsFallback)
        if (tts.config.engine.isCloud) {
            ElevenKeySection(tts, elevenKey)
        } else {
            TtsVoiceSection(tts, context)
        }
        TtsDumpSection(context, tts)
    }
}

@Composable
private fun ToggleChip(active: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) AccentSoft else Color(0xFF222B3A))
            .border(1.dp, if (active) Accent else Color(0xFF39465F), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (active) Accent else TextDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun TtsEngineSection(tts: TtsSettings, ttsFallback: String?) {
    Text("ДВИЖОК", color = TextDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    Spacer(Modifier.height(6.dp))
    TtsEngineCard(
        "Supertonic (offline, русский)",
        "Локально, без сети",
        selected = tts.config.engine == TtsEngine.Sherpa,
    ) { tts.chooseEngine(TtsEngine.Sherpa) }
    Spacer(Modifier.height(6.dp))
    TtsEngineCard(
        "ElevenLabs (облако)",
        "Нужен API-ключ и VPN из РФ",
        selected = tts.config.engine == TtsEngine.ElevenLabs,
    ) { tts.chooseEngine(TtsEngine.ElevenLabs) }
    ttsFallback?.let { reason ->
        Text(
            reason,
            color = Color(TTS_FALLBACK_NOTICE_COLOR),
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
        )
    }
}

@Composable
private fun TtsEngineCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) CardActiveBg else CardBg)
            .border(1.dp, if (selected) Accent else Color(0xFF33405C), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Text(
            if (selected) "●" else "○",
            color = if (selected) Accent else TextDim,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextDim, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ElevenKeySection(tts: TtsSettings, elevenKey: ElevenKeySettings) {
    Spacer(Modifier.height(10.dp))
    Text(
        "api.elevenlabs.io из РФ закрыт — нужен VPN. Без сети фраза уйдёт в локальный движок.",
        color = TextDim,
        fontSize = 10.sp,
    )
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ElevenKeyField(elevenKey)
        ElevenVoiceField(tts)
    }
}

@Composable
private fun ElevenKeyField(elevenKey: ElevenKeySettings) {
    Text(
        when {
            !elevenKey.hasKey -> "API-ключ не задан"
            else -> "API-ключ сохранён " + (elevenKey.preview ?: "(зашифрован)")
        },
        color = if (elevenKey.hasKey) ElevenKeyPalette.HintOk else ElevenKeyPalette.HintMissing,
        fontSize = 11.sp,
    )
    BasicTextField(
        value = elevenKey.draft,
        onValueChange = elevenKey::edit,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1C), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        textStyle = TextStyle(color = Color(0xFFF0F0F0), fontSize = 13.sp),
        cursorBrush = SolidColor(Color(0xFF7BA6F8)),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        decorationBox = { inner ->
            Box {
                if (elevenKey.draft.isEmpty()) {
                    Text("sk_…", color = Color(0xFF777777), fontSize = 13.sp)
                }
                inner()
            }
        },
    )
    ElevenKeyActions(elevenKey)
}

@Composable
private fun ElevenKeyActions(elevenKey: ElevenKeySettings) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Сохранить",
            color = if (elevenKey.draft.isBlank()) ElevenKeyPalette.SaveIdle else ElevenKeyPalette.SaveReady,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clickable(enabled = elevenKey.draft.isNotBlank()) { elevenKey.save() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        if (elevenKey.hasKey) {
            Text(
                "Удалить",
                color = ElevenKeyPalette.Danger,
                fontSize = 12.sp,
                modifier = Modifier.clickable { elevenKey.remove() }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
    elevenKey.error?.let { why ->
        Text(
            "не сохранено: $why",
            color = ElevenKeyPalette.Danger,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun ElevenVoiceField(tts: TtsSettings) {
    Text("Voice ID:", color = TextDim, fontSize = 11.sp)
    BasicTextField(
        value = tts.config.elevenVoice,
        onValueChange = { value -> tts.update { it.copy(elevenVoice = value) } },
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1C), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        textStyle = TextStyle(color = Color(0xFFF0F0F0), fontSize = 12.sp),
        cursorBrush = SolidColor(Color(0xFF7BA6F8)),
        singleLine = true,
    )
    Text("Модель: ${tts.config.elevenModel}", color = TextDim, fontSize = 11.sp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TtsVoiceSection(tts: TtsSettings, context: Context) {
    Spacer(Modifier.height(10.dp))
    Text("ГОЛОС", color = TextDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    Text(
        "Нажми на голос — сразу услышишь превью.",
        color = TextDim,
        fontSize = 10.sp,
        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (sid in 0..TtsConfig.MAX_SID) {
            val active = tts.config.sid == sid
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) Accent else Color(0xFF232C3D))
                    .border(1.dp, if (active) Accent else Color(0xFF34415A), RoundedCornerShape(10.dp))
                    .clickable {
                        tts.update { it.copy(sid = sid) }
                        TtsNarrator.stop()
                        val phrase = TTS_PREVIEW_PHRASES[Random.nextInt(TTS_PREVIEW_PHRASES.size)]
                        TtsNarrator.speakPreview(context, phrase)
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    "${sid + 1}",
                    color = if (active) Color(0xFF151515) else TextMain,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun TtsDumpSection(context: Context, tts: TtsSettings) {
    Spacer(Modifier.height(10.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                tts.update { it.copy(dumpPcm = !it.dumpPcm) }
                TtsNarrator.applyDumpPref(context)
                TtsNarrator.stop()
            }
            .padding(vertical = 6.dp),
    ) {
        Text(if (tts.config.dumpPcm) "● " else "○ ", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("Дамп PCM (диагностика)", color = TextMain, fontSize = 13.sp)
    }
    if (tts.config.dumpPcm) {
        Text(
            "Пишет files/tts-dump.pcm. Забрать: adb exec-out run-as org.opencode.mobile.debug cat files/tts-dump.pcm > dump.pcm",
            color = TextDim,
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 20.dp, top = 2.dp),
        )
    }
}

private const val TAG_PANEL_STT = "panel_stt"
private const val TAG_PANEL_TTS = "panel_tts"
private const val TTS_FALLBACK_NOTICE_COLOR = 0xFFFFB74D

@Suppress("MagicNumber")
private object ElevenKeyPalette {
    val HintOk = Color(0xFF8A8A8A)
    val HintMissing = Color(0xFFE6A23C)
    val SaveIdle = Color(0xFF5A5A5A)
    val SaveReady = Color(0xFF7BA6F8)
    val Danger = Color(0xFFE57373)
}
