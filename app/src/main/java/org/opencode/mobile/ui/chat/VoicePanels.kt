package org.opencode.mobile.ui.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

// Голосовые панели шапки: распознавание (Mic) и озвучка. Вынесены из ChatPanels,
// потому что настроек у голоса вдвое больше, чем у цвета или гейджа, и держать
// их в одном файле с остальными панелями значило держать файл на 600 строк.
//
// Всё, что панель меняет снаружи себя, лежит в объектах настроек (voice, tts,
// elevenKey): сами они в состояние экрана не пишут.

// Панель выбора голосового движка (system | whisper). Отдельная от GaugePanel:
// GaugeSettings отвечает только за полосы гейджа. Открывается иконкой Mic.
@Composable
internal fun SttPanel(voice: VoiceSettings) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp)
            .background(Color(0xFF1C1C1C), RoundedCornerShape(12.dp))
            .testTag(TAG_PANEL_STT)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            "Голосовое распознавание:",
            color = Color(0xFFBDBDBD),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        SttEngineRow(
            label = "Системный Android (Google)",
            selected = voice.sttEngine == VoiceEngine.SYSTEM,
        ) { voice.chooseEngine(VoiceEngine.SYSTEM) }
        // Модель НЕ подставляется при смене движка: её выбирает человек списком
        // ниже. Раньше движок молча затирал модель на base, и выбрать turbo было
        // невозможно — настройка «движок» затирала настройку «модель».
        SttEngineRow(
            label = "Локальные модели (whisper.cpp / sherpa-onnx)",
            selected = voice.sttEngine == VoiceEngine.WHISPER,
        ) { voice.chooseEngine(VoiceEngine.WHISPER) }
        // Список моделей — только для локального движка.
        if (voice.sttEngine == VoiceEngine.WHISPER) {
            SttModelPicker(
                selected = voice.whisperModelId,
                onSelect = { picked -> voice.chooseWhisperModel(picked) },
            )
        }
    }
}

// Строка выбора движка: кружок состояния и подпись. Две строки отличались только
// движком и текстом, поэтому общая функция, а не копия на каждый движок.
@Composable
private fun SttEngineRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(vertical = 6.dp),
    ) {
        Text(
            if (selected) "● " else "○ ",
            color = Color(0xFFFF6D00),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(label, color = Color(0xFFE6E6E6), fontSize = 13.sp)
    }
}

// Панель озвучки. Живёт по тем же правилам, что STT: настройка в prefs,
// переключение — сразу, без рестарта чата.
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
            .background(Color(0xFF1C1C1C), RoundedCornerShape(12.dp))
            .testTag(TAG_PANEL_TTS)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            "Озвучка ответов:",
            color = Color(0xFFBDBDBD),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        TtsRadioRow("Читать вслух", ttsOn) {
            tts.setEnabled(!ttsOn)
        }
        if (!ttsOn) {
            Text(
                if (TtsConfig.read(context).modelInstalled(context)) {
                    "Supertonic offline: модель на месте, 2 потока, sid ${tts.config.sid}"
                } else {
                    "Модель Supertonic не скачана — скачай её в Настройки -> Диагностика"
                },
                color = Color(0xFF90A4AE),
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp),
            )
        }
        if (ttsOn) {
            TtsEngineSection(tts, ttsFallback)
            if (tts.config.engine.isCloud) {
                ElevenKeySection(tts, elevenKey)
            }
            // Локальный sid к облаку отношения не имеет: там голос выбирается
            // Voice ID выше. Показывать 1..10 при выбранном ElevenLabs — значит
            // предлагать юзеру переключатель, который ничего не делает.
            if (!tts.config.engine.isCloud) {
                TtsVoiceSection(tts)
            }
            TtsDumpSection(context, tts)
        }
    }
}

// Движок озвучки: что читать вслух и какой синтез выбран. Причина, по которой
// выбранный движок молчит (ttsFallback), показывается здесь же: юзер иначе
// слышит тишину и не знает, что фраза ушла в локальный движок.
@Composable
private fun TtsEngineSection(
    tts: TtsSettings,
    ttsFallback: String?,
) {
    TtsRadioRow("Supertonic (offline, русский)", tts.config.engine == TtsEngine.Sherpa) {
        tts.chooseEngine(TtsEngine.Sherpa)
    }
    TtsRadioRow("ElevenLabs (облако, нужен VPN)", tts.config.engine == TtsEngine.ElevenLabs) {
        tts.chooseEngine(TtsEngine.ElevenLabs)
    }
    // Что СЕЙЧАС реально озвучит, если выбранный движок не поднялся.
    // Без этой строки юзер выбирает облако, слышит тишину и гадает
    // почему: причина была только в Log.w и в UI не попадала.
    // Текст приходит из ttsFallback - состояния, которое
    // LaunchedEffect выше подтягивает из TtsNarrator. Считать его
    // здесь нельзя: панель перерисовывается на каждый кадр, а
    // File.length() по модели - это I/O на main thread.
    // Локальная копия: ttsFallback - делегированное свойство, и Kotlin
    // не smart-cast'ит такие (значение может измениться между
    // проверкой и использованием).
    val notice = ttsFallback
    if (notice != null) {
        Text(
            notice,
            color = Color(TTS_FALLBACK_NOTICE_COLOR),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 20.dp, top = 2.dp),
        )
    }
}

// Ключ ElevenLabs и Voice ID. Показывается только для облачного движка:
// локальному голосу эти поля не нужны.
@Composable
private fun ElevenKeySection(
    tts: TtsSettings,
    elevenKey: ElevenKeySettings,
) {
    // Облако идёт в сеть, поэтому честно предупреждаем про VPN: из России
    // api.elevenlabs.io без него не отвечает, и юзер иначе гадает, почему
    // озвучка молчит.
    // Облако идёт в сеть, поэтому честно предупреждаем про VPN:
    // из России api.elevenlabs.io без него не отвечает, и юзер
    // иначе гадает, почему озвучка молчит.
    Text(
        "api.elevenlabs.io из РФ закрыт — нужен включённый VPN. Без сети " +
            "фраза уйдёт в локальный движок.",
        color = Color(0xFF90A4AE),
        fontSize = 10.sp,
        modifier = Modifier.padding(start = 20.dp, top = 2.dp),
    )
    Column(
        modifier = Modifier.padding(start = 20.dp, top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ElevenKeyField(elevenKey)
        ElevenVoiceField(tts)
    }
}

// Состояние ключа и поле ввода. Ключ маскируется: он даёт доступ к облачному
// синтезу за счёт юзера, и случайный скриншот не должен его показывать.
@Composable
private fun ElevenKeyField(elevenKey: ElevenKeySettings) {
    Text(
        when {
            !elevenKey.hasKey -> "API-ключ не задан"
            else ->
                "API-ключ сохранён " +
                    (elevenKey.preview ?: "(зашифрован)")
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
        // Маскируем: ключ — доступ к облачному синтезу за счёт
        // юзера, и подсветка под телефоном его не защитит,
        // а вот случайный скриншот или демо стрима — защитит.
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        decorationBox = { inner ->
            Box {
                if (elevenKey.draft.isEmpty()) {
                    Text(
                        "sk_…",
                        color = Color(0xFF777777),
                        fontSize = 13.sp,
                    )
                }
                inner()
            }
        },
    )
    ElevenKeyActions(elevenKey)
}

// Сохранить/удалить и причина отказа. Без причины юзер видит неподвижную
// кнопку и гадает, куда делся ключ.
@Composable
private fun ElevenKeyActions(elevenKey: ElevenKeySettings) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Сохранить",
            color = if (elevenKey.draft.isBlank()) ElevenKeyPalette.SaveIdle else ElevenKeyPalette.SaveReady,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clickable(enabled = elevenKey.draft.isNotBlank()) {
                    elevenKey.save()
                }.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        if (elevenKey.hasKey) {
            Text(
                "Удалить",
                color = ElevenKeyPalette.Danger,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable {
                        elevenKey.remove()
                    }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
    // Причина отказа от сохранения — иначе юзер видит
    // неподвижную кнопку и гадает, куда делся ключ.
    elevenKey.error?.let { why ->
        Text(
            "не сохранено: $why",
            color = ElevenKeyPalette.Danger,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// Voice ID и модель ElevenLabs. Модель только показывается: она задана
// в конфиге и меняется не из панели.
@Composable
private fun ElevenVoiceField(tts: TtsSettings) {
    Text(
        "Voice ID:",
        color = Color(0xFF8A8A8A),
        fontSize = 11.sp,
    )
    BasicTextField(
        value = tts.config.elevenVoice,
        onValueChange = { value ->
            tts.update { it.copy(elevenVoice = value) }
        },
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1C), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        textStyle = TextStyle(color = Color(0xFFF0F0F0), fontSize = 12.sp),
        cursorBrush = SolidColor(Color(0xFF7BA6F8)),
        singleLine = true,
    )
    Text(
        "Модель: ${tts.config.elevenModel}",
        color = Color(0xFF8A8A8A),
        fontSize = 11.sp,
    )
}

// Локальный голос (sid) сеткой. К облаку отношения не имеет: там голос —
// Voice ID выше.
@Composable
private fun TtsVoiceSection(tts: TtsSettings) {
    Text(
        "Голос:",
        color = Color(0xFF8A8A8A),
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 20.dp, top = 4.dp),
    )
    /** Голоса в две строки по пять: на шапке телефона одна строка уже не помещалась. */
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in (0..TtsConfig.MAX_SID).chunked(SID_CHUNK)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (sid in row) {
                    val active = tts.config.sid == sid
                    Text(
                        "${sid + 1}",
                        color = if (active) Color(0xFF1C1C1C) else Color(0xFFE6E6E6),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (active) Color(0xFFBDBDBD) else Color(0xFF2A2A2A),
                            ).clickable {
                                tts.update { it.copy(sid = sid) }
                                TtsNarrator.stop()
                            }.padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

// Дамп сырого PCM в filesDir/tts-dump.pcm (float32, без заголовка): по нему
// видно длительность и паузы каждого фрагмента — то, чего не показывает лог.
@Composable
private fun TtsDumpSection(
    context: Context,
    tts: TtsSettings,
) {
    // Диагностика озвучки: дамп сырого PCM в filesDir/tts-dump.pcm
    // (float32, без заголовка). По нему видно длительность и паузы
    // каждого фрагмента — то, чего не показывает лог. Нужен, чтобы
    // проверять озвучку без возможности послушать.
    TtsRadioRow("Дамп PCM (диагностика)", tts.config.dumpPcm) {
        tts.update { it.copy(dumpPcm = !it.dumpPcm) }
        // Применяем к живой озвучке сразу: dumpOut переживает смену
        // голоса и движка, иначе файл писался бы до перезапуска.
        TtsNarrator.applyDumpPref(context)
        TtsNarrator.stop()
    }
    if (tts.config.dumpPcm) {
        Text(
            "Пишет files/tts-dump.pcm. Забрать: adb exec-out run-as " +
                "org.opencode.mobile.debug cat files/tts-dump.pcm > dump.pcm",
            color = Color(0xFF90A4AE),
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 20.dp, top = 2.dp),
        )
    }
}

// testTag-метки голосовых панелей: якоря для проверки геометрии по дампу
// uiautomator (resource-id). В коде не читаются.
private const val TAG_PANEL_STT = "panel_stt"
private const val TAG_PANEL_TTS = "panel_tts"

// Голоса в сетке: пять на строку — на шапке телефона шесть уже не помещались.
private const val SID_CHUNK = 5

/** Оранжевый плашки «озвучка переехала на другой движок»: предупреждение, не ошибка. */
private const val TTS_FALLBACK_NOTICE_COLOR = 0xFFFFB74D

// Цвета панели ключа ElevenLabs. Вынесены именами, потому что «серый — есть ключ,
// оранжевый — ключа нет» и «красный — удалить/ошибка» это смысл, а не украшение:
// по цвету видно состояние, не читая текст.
@Suppress("MagicNumber")
private object ElevenKeyPalette {
    val HintOk = Color(0xFF8A8A8A)
    val HintMissing = Color(0xFFE6A23C)
    val SaveIdle = Color(0xFF5A5A5A)
    val SaveReady = Color(0xFF7BA6F8)
    val Danger = Color(0xFFE57373)
}
