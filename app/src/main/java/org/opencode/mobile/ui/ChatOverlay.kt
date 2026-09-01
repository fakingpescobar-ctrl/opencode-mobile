package org.opencode.mobile.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.opencode.mobile.R
import org.opencode.mobile.stt.ModelDownloader
import org.opencode.mobile.stt.WhisperTranscribeService
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private const val MAX_SHOWN = 120

private data class ChatMsg(val role: String, val text: String)

private data class ChatQuestion(val id: String, val text: String, val options: List<String>)

private data class ChatSnapshot(
    val messages: List<ChatMsg>,
    val label: String,
    val activeId: String?,
    val question: ChatQuestion? = null,
    val thinking: Boolean = false,
    val modelName: String = "Модель"
)

/**
 * Полноэкранный чат поверх WebView. WebView (SPA opencode 1.18.25) не рендерит ленту
 * в WebView-окружении — поэтому чат реализован здесь, нативно: опрос локального
 * сервера каждые 2с + отправка через POST /session/{id}/message.
 * Поле ввода внизу, лента наверху, клавиатура не перекрывает поле (imePadding).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatOverlay(modifier: Modifier = Modifier, serverPort: Int = 4096) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var snapshot by remember { mutableStateOf<ChatSnapshot?>(null) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var knownMsgs by remember { mutableIntStateOf(0) }
    var userScrolledUp by remember { mutableStateOf(false) }

    // Настройка шрифта ответов модели: хранится в SharedPreferences, меняется на лету.
    val prefs = remember { context.getSharedPreferences("chat_overlay", Context.MODE_PRIVATE) }
    var modelFontKey by remember { mutableStateOf(prefs.getString("model_font_key", "mono") ?: "mono") }
    var showFontPicker by remember { mutableStateOf(false) }
    val modelFont = remember(modelFontKey) { fontFor(modelFontKey) }

    // Настройка цвета ответов модели: hex-строка без '#', меняется на лету через цветовой пикер.
    var modelColorHex by remember { mutableStateOf(prefs.getString("model_color", "D97706") ?: "D97706") }
    var showColorPicker by remember { mutableStateOf(false) }
    val modelColor = remember(modelColorHex) { parseHexColor(modelColorHex) }

    fun setModelColor(hex: String) {
        modelColorHex = hex
        prefs.edit().putString("model_color", hex).apply()
    }

    // Голосовой ввод: системный распознаватель (SpeechRecognizer). Удержание кнопки — запись, отпускание — распознавание и отправка.
    val speechRecognizer = remember { ContextCompatSpeechRecognizer(context) }
    var listening by remember { mutableStateOf(false) }
    var speechError by remember { mutableStateOf<String?>(null) }
    var whisperBusy by remember { mutableStateOf(false) }
    // Настройки голоса: движок распознавания ("system" | "whisper").
    var sttEngine by remember { mutableStateOf(prefs.getString("stt_engine", "system") ?: "system") }
    // Модель whisper: "base" (assets, вшита) | "turbo" (скачивается 574MB в filesDir).
    var sttModel by remember { mutableStateOf(prefs.getString("stt_model", "base") ?: "base") }
    // Прогресс скачивания turbo: null = не качаем, иначе Int (0..100) + статус.
    var turboDownloadPct by remember { mutableStateOf<Int?>(null) }
    var turboDownloadMsg by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var whisperRecorder: AudioRecorder? = null
    fun setSttModel(m: String) {
        sttModel = m
        prefs.edit().putString("stt_model", m).apply()
    }

    fun answerQuestion(q: ChatQuestion, text: String) {
        val sessionId = snapshot?.activeId ?: return
        if (sending) return
        sending = true
        userScrolledUp = false
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (sessionId != null) postAnswer(serverPort, sessionId, q.id, listOf(text)) else false
            }
            sending = false
            if (ok) {
                draft = ""
                keyboard?.hide()
                focusManager.clearFocus()
                snapshot = snapshot?.let { it.copy(question = null, messages = it.messages + ChatMsg("user", text)) }
                scrollToBottomFull(listState, (snapshot?.messages?.size ?: 0) - 1)
            }
        }
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        val sessionId = snapshot?.activeId
        val pending = snapshot?.question
        if (pending != null) {
            // Модель ждёт ответа на вопрос — обычный POST не продвинет сессию.
            answerQuestion(pending, text)
            return
        }
        sending = true
        userScrolledUp = false
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val id = sessionId ?: createSession(serverPort)
                if (id != null) postMessage(serverPort, id, text) else false
            }
            sending = false
            if (ok) {
                draft = ""
                keyboard?.hide()
                focusManager.clearFocus()
                // Оптимистично: мгновенно показываем своё сообщение в ленте.
                snapshot = snapshot?.let { it.copy(messages = it.messages + ChatMsg("user", text)) }
                scrollToBottomFull(listState, (snapshot?.messages?.size ?: 0) - 1)
            }
        }
    }

    val voiceListener = remember {
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                listening = false
                speechError = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "не расслышал, попробуй ещё"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "слишком тихо"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "нет доступа к микрофону"
                    else -> "ошибка распознавания ($error)"
                }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                if (!best.isNullOrEmpty()) {
                    speechError = null
                    draft = best
                    send()
                } else {
                    speechError = "не расслышал, попробуй ещё"
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    fun startVoice() {
        speechError = null
        Log.d("VOICE", "startVoice: engine=$sttEngine")
        if (sttEngine == "whisper" || sttEngine == "ncnn") {
            // Локальный движок: ncnn (CPU) или whisper.cpp (CPU) — запись PCM16 16кГц в буфер.
            try {
                val ar = AudioRecorder()
                ar.start()
                whisperRecorder = ar
                listening = true
                Log.d("VOICE", "запись начата")
            } catch (e: Throwable) {
                Log.e("VOICE", "не удалось начать запись", e)
                speechError = "не удалось начать запись голоса"
            }
            return
        }
        val sr = speechRecognizer ?: run { speechError = "распознавание речи недоступно на устройстве"; return }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        sr.setRecognitionListener(voiceListener)
        try {
            sr.startListening(intent)
            listening = true
        } catch (_: Exception) {
            speechError = "не удалось запустить распознавание"
        }
    }

    fun stopVoice() {
        if (sttEngine == "whisper" || sttEngine == "ncnn") {
            val ar = whisperRecorder ?: return
            whisperRecorder = null
            listening = false
            Log.d("VOICE", "stopVoice: останавливаю запись")
            var samples = try { ar.stop() } catch (e: Throwable) {
                Log.e("VOICE", "ar.stop упал", e)
                FloatArray(0)
            }
            Log.d("VOICE", "сэмплов: ${samples.size}")
            // Нормализация уровня: OPPO пишет речь очень тихо (RMS ~0.05),
            // whisper на таком сигнале деградирует. Тянем peak к 0.85.
            run {
                var peak = 0f
                for (s in samples) if (kotlin.math.abs(s) > peak) peak = kotlin.math.abs(s)
                if (peak > 0.01f && peak < 0.6f) {
                    val gain = 0.85f / peak
                    for (i in samples.indices) samples[i] = (samples[i] * gain).coerceIn(-1f, 1f)
                    Log.d("VOICE", "нормализация: peak=$peak -> gain=${"%.1f".format(gain)}")
                } else {
                    Log.d("VOICE", "без нормализации: peak=$peak")
                }
            }
            // Паддинг: whisper стабильно врёт на начале коротких клипов (<3с).
            // 0.25с тишины в начало + добиваем конец нулями до 3с.
            if (samples.size >= 1600) {
                val padStart = 4000
                val targetLen = maxOf(samples.size + padStart, 48000 + padStart)
                val padded = FloatArray(targetLen)
                System.arraycopy(samples, 0, padded, padStart, samples.size)
                Log.d("VOICE", "паддинг: ${samples.size} → $targetLen (+$padStart в начало)")
                samples = padded
            }
            // Диагностика качества аудио: RMS по 0.5с чанкам + дамп PCM.
            run {
                val sb = StringBuilder()
                var i = 0
                while (i < samples.size) {
                    val end = minOf(i + 8000, samples.size)
                    var sum = 0.0
                    for (j in i until end) sum += samples[j].toDouble() * samples[j]
                    sb.append(String.format("%.3f ", kotlin.math.sqrt(sum / (end - i))))
                    i = end
                }
                Log.d("VOICE", "RMS по 0.5с: $sb")
                try {
                    val f = java.io.File(context.cacheDir, "rec.pcm")
                    val bb = java.nio.ByteBuffer.allocate(samples.size * 2)
                    for (s in samples) bb.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                    f.writeBytes(bb.array())
                    Log.d("VOICE", "дамп: ${f.absolutePath} (${f.length()} байт)")
                } catch (_: Exception) {}
            }
            whisperBusy = true
            scope.launch {
                if (samples.size < 1600) { // короче 0.1 секунды — явно не речь
                    speechError = "слишком короткая запись"
                    whisperBusy = false
                    Log.d("VOICE", "слишком короткая запись")
                    return@launch
                }
                speechError = null
                Log.d("VOICE", "запускаю распознавание через foreground-сервис...")
                val text = try {
                    WhisperTranscribeService.transcribe(
                        context, samples, model = sttModel,
                        engine = if (sttEngine == "ncnn") WhisperTranscribeService.ENGINE_NCNN
                                 else WhisperTranscribeService.ENGINE_WHISPER
                    )
                } catch (e: Throwable) {
                    Log.e("VOICE", "сервис распознавания упал", e)
                    "ОШИБКА WHISPER: ${e.message}"
                }
                Log.d("VOICE", "распознано: '${text.take(80)}'")
                whisperBusy = false
                if (!text.isNullOrBlank() && !text.startsWith("ОШИБКА")) {
                    speechError = null
                    draft = text
                    send()
                } else {
                    speechError = if (text.startsWith("ОШИБКА")) text else "не распознал голос, попробуй ещё"
                }
            }
            return
        }
        speechRecognizer?.stopListening()
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else speechError = "нет доступа к микрофону"
    }

    // Диагностика: синтез фразу системным TTS → прогон через наш whisper.
    // Чистое распознавание = модель и пайплайн ок, проблема в микрофоне/записи.
    var ttsTestRunning by remember { mutableStateOf(false) }
    fun runTtsTest() {
        if (ttsTestRunning) return
        ttsTestRunning = true
        Log.d("VOICE", "TTS-тест: инициализация синтеза...")
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.e("VOICE", "TTS-тест: движок недоступен")
                ttsTestRunning = false
                return@TextToSpeech
            }
            val file = File(context.cacheDir, "tts_test.wav")
            tts?.language = Locale("ru")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onDone(id: String?) {
                    if (id != "tts_test") return
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            try {
                                val samples = readWavPcm16(file)
                                Log.d("VOICE", "TTS-тест: ${samples.size} сэмплов → whisper ($sttModel)")
                                WhisperTranscribeService.transcribe(
                                    context, samples, model = sttModel,
                                    engine = if (sttEngine == "ncnn") WhisperTranscribeService.ENGINE_NCNN
                                             else WhisperTranscribeService.ENGINE_WHISPER
                                )
                            } catch (e: Throwable) {
                                Log.e("VOICE", "TTS-тест упал", e)
                                "ОШИБКА TTS-ТЕСТА: ${e.message}"
                            }
                        }
                        Log.d("VOICE", "TTS-тест РЕЗУЛЬТАТ: '$text'")
                        tts?.shutdown()
                        ttsTestRunning = false
                    }
                }
                override fun onError(id: String?) {
                    Log.e("VOICE", "TTS-тест: ошибка синтеза")
                    tts?.shutdown()
                    ttsTestRunning = false
                }
                override fun onStart(id: String?) {}
            })
            tts?.synthesizeToFile("Расскажи анекдот про цыгана.", null, file, "tts_test")
        }
    }

    // Скачивание large-v3-turbo (574MB) на устройство. Прогресс обновляется
    // в панели настроек; по завершении — переключаем STT на turbo.
    fun startTurboDownload(context: Context) {
        if (turboDownloadPct != null) return // уже качаем
        turboDownloadPct = 0
        turboDownloadMsg = null
        scope.launch {
            try {
                val file = ModelDownloader.download(context) { done, total ->
                    turboDownloadPct = if (total > 0) ((done * 100) / total).toInt() else 0
                }
                Log.d("VOICE", "turbo скачана: ${file.absolutePath}")
                turboDownloadPct = 100
                setSttModel("turbo")
            } catch (e: Throwable) {
                Log.e("VOICE", "скачивание turbo упало", e)
                turboDownloadMsg = "Ошибка скачивания: ${e.message}"
                turboDownloadPct = null
            }
        }
    }

    // Автопрокрутка вниз: после отправки и при новом ответе/думании,
    // но НЕ когда юзер сам ушёл читать историю вверх.
    LaunchedEffect(snapshot?.messages?.size, snapshot?.thinking) {
        delay(90) // дождаться рекомпозиции LazyColumn
        val snap = snapshot ?: return@LaunchedEffect
        val n = snap.messages.size
        if (n > 0 && !userScrolledUp) {
            val target = if (snap.thinking) n else n - 1
            scrollToBottomFull(listState, target)
        }
    }

    // Отслеживаем, ушёл ли юзер от низа списка вручную.
    LaunchedEffect(listState) {
        var prevIdx = -1
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx ->
                if (idx < 0) return@collect
                val total = snapshot?.messages?.size ?: 0
                if (idx >= total - 1) {
                    userScrolledUp = false // юзер на самом низу — автоскролл можно
                } else if (prevIdx >= 0 && idx < prevIdx) {
                    userScrolledUp = true // юзер сам листает вверх — не дёргать
                }
                prevIdx = idx
            }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val snap = fetchChatSnapshot(serverPort)
            if (snap != null) {
                val completed = snap.messages.count { it.role == "assistant" && it.text.isNotBlank() }
                snapshot = snap
                if (completed > knownMsgs) {
                    knownMsgs = completed
                    vibrate(context)
                } else if (knownMsgs == 0 && snap.messages.isNotEmpty()) {
                    knownMsgs = completed
                }
            }
            delay(2000)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        color = Color(0xFF101010)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Чат — " + (snapshot?.label ?: "подключение…"),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFE6E6E6),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (sending) {
                    Text("отправка…", color = Color(0xFF8A8A8A), fontSize = 12.sp)
                }
                Box(
                    Modifier
                        .padding(start = 8.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
                                    Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF)
                                )
                            )
                        )
                        .border(1.dp, if (showColorPicker) Color.White else Color(0xFF555555), RoundedCornerShape(4.dp))
                        .clickable { showColorPicker = !showColorPicker }
                )
                Text(
                    "Aa",
                    color = if (showFontPicker) modelColor else Color(0xFF8A8A8A),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable { showFontPicker = !showFontPicker }
                )
                Box(
                    Modifier
                        .padding(start = 8.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (showSettings) Color(0xFF3A3A3A) else Color.Transparent)
                        .clickable { showSettings = !showSettings }
                ) {
                    Canvas(Modifier.size(22.dp).padding(2.dp)) {
                        // Шестерёнка: 8 зубцов + окружность
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val white = Color(0xFFBDBDBD)
                        for (i in 0 until 8) {
                            val a = Math.toRadians(i * 45.0 - 90.0)
                            val dx = kotlin.math.cos(a).toFloat()
                            val dy = kotlin.math.sin(a).toFloat()
                            drawLine(
                                white,
                                Offset(c.x + dx * 4.5.dp.toPx(), c.y + dy * 4.5.dp.toPx()),
                                Offset(c.x + dx * 8.dp.toPx(), c.y + dy * 8.dp.toPx()),
                                strokeWidth = 2.6.dp.toPx()
                            )
                        }
                        drawCircle(white, radius = 5.dp.toPx(), center = c)
                        drawCircle(Color(0xFF101010), radius = 2.dp.toPx(), center = c)
                    }
                }
            }
            // Цветовой пикер для ответов модели: квадрат-градиент (X — оттенок, Y — яркость), тап/драг точкой.
            if (showColorPicker) {
                val pickSize = with(LocalDensity.current) { 300.dp.toPx() }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp)
                        .background(Color(0xFF1C1C1C), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("Цвет ответов модели (тап/тяни точку):", color = Color(0xFF8A8A8A), fontSize = 11.sp)
                    Canvas(
                        Modifier
                            .padding(top = 6.dp)
                            .size(300.dp)
                            .pointerInput(Unit) {
                                detectTapGestures { off ->
                                    val h = (off.x / pickSize).coerceIn(0f, 1f) * 360f
                                    val v = 1f - (off.y / pickSize).coerceIn(0f, 1f)
                                    setModelColor("%06X".format(android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, v)) and 0xFFFFFF))
                                }
                            }
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { off ->
                                        val h = (off.x / pickSize).coerceIn(0f, 1f) * 360f
                                        val v = 1f - (off.y / pickSize).coerceIn(0f, 1f)
                                        setModelColor("%06X".format(android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, v)) and 0xFFFFFF))
                                    },
                                    onDrag = { change, _ ->
                                        val h = (change.position.x / pickSize).coerceIn(0f, 1f) * 360f
                                        val v = 1f - (change.position.y / pickSize).coerceIn(0f, 1f)
                                        setModelColor("%06X".format(android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, v)) and 0xFFFFFF))
                                    }
                                )
                            }
                    ) {
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
                                    Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000)
                                ),
                                startX = 0f, endX = size.width
                            )
                        )
                        drawRect(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0f), Color.Black),
                                startY = 0f, endY = size.height
                            )
                        )
                        val hsv = FloatArray(3)
                        android.graphics.Color.colorToHSV(modelColor.toArgb(), hsv)
                        val mark = Offset(hsv[0] / 360f * size.width, (1f - hsv[2]) * size.height)
                        drawCircle(Color.Black, radius = 10.dp.toPx(), center = mark, style = Stroke(width = 1.dp.toPx()))
                        drawCircle(Color.White, radius = 10.dp.toPx(), center = mark, style = Stroke(width = 2.dp.toPx()))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(modelColor)
                                .border(1.dp, Color.White, CircleShape)
                        )
                        Text("#$modelColorHex", color = Color(0xFFBDBDBD), fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
            // Панель настроек: движок голосового распознавания + ключ Whisper.
            if (showSettings) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp)
                        .background(Color(0xFF1C1C1C), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("Голосовое распознавание:", color = Color(0xFFBDBDBD), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                sttEngine = "system"
                                prefs.edit().putString("stt_engine", "system").apply()
                            }
                            .padding(vertical = 6.dp)
                    ) {
                        Text(if (sttEngine == "system") "● " else "○ ", color = Color(0xFFFF6D00), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Системный Android (Google)", color = Color(0xFFE6E6E6), fontSize = 13.sp)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                sttEngine = "whisper"
                                prefs.edit().putString("stt_engine", "whisper").apply()
                            }
                            .padding(vertical = 6.dp)
                    ) {
                        Text(if (sttEngine == "whisper") "● " else "○ ", color = Color(0xFFFF6D00), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Whisper (локально)", color = Color(0xFFE6E6E6), fontSize = 13.sp)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                sttEngine = "ncnn"
                                prefs.edit().putString("stt_engine", "ncnn").apply()
                            }
                            .padding(vertical = 6.dp)
                    ) {
                        Text(if (sttEngine == "ncnn") "● " else "○ ", color = Color(0xFFFF6D00), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("NCNN (CPU/NEON fp16 · KV-cache)", color = Color(0xFFE6E6E6), fontSize = 13.sp)
                    }
                    Text("Модель распознавания:", color = Color(0xFFBDBDBD), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { setSttModel("base") }
                            .padding(vertical = 4.dp)
                    ) {
                        Text(if (sttModel == "base") "● " else "○ ", color = Color(0xFFFF6D00), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("base (141 МБ, быстро, вшита в приложение)", color = Color(0xFFE6E6E6), fontSize = 12.sp)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (ModelDownloader.turboReady(context) || turboDownloadPct != null) {
                                    setSttModel("turbo")
                                } else {
                                    startTurboDownload(context)
                                }
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Text(if (sttModel == "turbo") "● " else "○ ", color = Color(0xFFFF6D00), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        val turboLabel = when {
                            turboDownloadPct != null -> "large-v3-turbo… $turboDownloadPct%"
                            ModelDownloader.turboReady(context) -> "large-v3-turbo (574 МБ, точнее на быстрой речи)"
                            else -> "large-v3-turbo (574 МБ) — нажми, чтобы скачать"
                        }
                        Text(turboLabel, color = Color(0xFFE6E6E6), fontSize = 12.sp)
                    }
                    turboDownloadMsg?.let {
                        Text(it, color = Color(0xFFE05A5A), fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    Text(
                        if (ttsTestRunning) "TTS-тест: синтезирую и распознаю…" else "Диагностика: синтез → распознавание (см. лог VOICE)",
                        color = Color(0xFF5A8DEE),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clickable(enabled = !ttsTestRunning) { runTtsTest() }
                    )
                }
            }
            val msgs = snapshot?.messages ?: emptyList()
            LaunchedEffect(msgs.size, snapshot?.thinking) {
                android.util.Log.d("ChatOverlay", "RENDER msgs=${msgs.size} thinking=${snapshot?.thinking}")
            }
            // Палитра шрифтов (настройка): тап по варианту — мгновенно применяется и сохраняется.
            if (showFontPicker) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp)
                        .background(Color(0xFF1C1C1C), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("Шрифт ответов модели (тап — применить):", color = Color(0xFF8A8A8A), fontSize = 11.sp)
                    fontEntries.forEach { (key, name, ff) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    modelFontKey = key
                                    prefs.edit().putString("model_font_key", key).apply()
                                    showFontPicker = false
                                }
                                .padding(vertical = 6.dp)
                        ) {
                            Text(
                                if (key == modelFontKey) "● " else "○ ",
                                color = modelColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                name,
                                color = Color(0xFF9E9E9E),
                                fontSize = 10.sp,
                                modifier = Modifier.width(88.dp)
                            )
                            Text(
                                "Привет, я модель!",
                                color = modelColor,
                                fontFamily = ff,
                                fontSize = 16.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            if (msgs.isEmpty()) {
                Text(
                    "Сообщений пока нет — напиши в поле внизу.",
                    color = Color(0xFF8A8A8A),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Позиционный ключ (без кастомного key). Кастомный key из контента
                    // крашил LazyColumn (Key "…was already used") при дубликатах сообщений
                    // (например, дважды отправленная команда «Стой»). Лента append-only,
                    // добавление в конец не трогает существующие позиции — позиционный
                    // ключ безопасен и полностью устраняет этот краш.
                    items(msgs) { m ->
                        MessageRow(m, snapshot?.modelName ?: "Модель", modelFont, modelColor)
                    }
                    if (snapshot?.thinking == true) {
                        item(key = "thinking") {
                            ThinkingRow()
                        }
                    }
                }
            }
            snapshot?.question?.let { q ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(Color(0xFF1E2B1E), RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        "Модель спрашивает:",
                        color = Color(0xFF7BD88F),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        q.text.ifBlank { "…" },
                        color = Color(0xFFEDEDED),
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    if (q.options.isEmpty()) {
                        Text(
                            "Напиши ответ в поле и нажми →",
                            color = Color(0xFF8A8A8A),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    } else {
                        q.options.forEach { label ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                                    .clickable { answerQuestion(q, label) },
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF24401F)
                            ) {
                                Text(
                                    label,
                                    color = Color(0xFFE6E6E6),
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                )
                            }
                        }
                        Text(
                            "…или напиши свой ответ в поле ↓",
                            color = Color(0xFF8A8A8A),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
            if (listening || speechError != null || whisperBusy) {
                Text(
                    when {
                        listening -> "Слушаю… отпусти кнопку — текст уйдёт в чат"
                        whisperBusy -> "Распознаю голос…"
                        else -> "Распознавание: $speechError"
                    },
                    color = if (listening) Color(0xFFFF6F5A) else Color(0xFF8A8A8A),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF1C1C1C), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    textStyle = TextStyle(color = Color(0xFFF0F0F0), fontSize = 15.sp),
                    cursorBrush = SolidColor(Color(0xFF7BA6F8)),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) {
                                Text("Напиши сообщение…", color = Color(0xFF777777), fontSize = 15.sp)
                            }
                            inner()
                        }
                    }
                )
                Surface(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(46.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    } else {
                                        startVoice()
                                    }
                                    try { awaitRelease() } finally { stopVoice() }
                                }
                            )
                        },
                    shape = CircleShape,
                    color = if (listening) Color(0xFFB71C1C) else Color(0xFF252525)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(24.dp)) {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val white = Color.White
                            // Чашка микрофона
                            drawRoundRect(
                                white,
                                topLeft = Offset(cx - 5.dp.toPx(), cy - 11.dp.toPx()),
                                size = Size(10.dp.toPx(), 15.dp.toPx()),
                                cornerRadius = CornerRadius(5.dp.toPx())
                            )
                            // Дуга-скобка
                            drawArc(
                                white,
                                startAngle = 180f,
                                sweepAngle = 180f,
                                useCenter = false,
                                topLeft = Offset(cx - 9.dp.toPx(), cy + 1.dp.toPx()),
                                size = Size(18.dp.toPx(), 13.dp.toPx()),
                                style = Stroke(width = 2.2.dp.toPx())
                            )
                            // Ножка и стойка
                            drawLine(white, Offset(cx, cy + 4.dp.toPx()), Offset(cx, cy + 11.dp.toPx()), strokeWidth = 2.2.dp.toPx())
                            drawLine(white, Offset(cx - 7.dp.toPx(), cy + 14.dp.toPx()), Offset(cx + 7.dp.toPx(), cy + 14.dp.toPx()), strokeWidth = 2.2.dp.toPx())
                        }
                    }
                }
                Surface(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(46.dp)
                        .clickable { send() },
                    shape = CircleShape,
                    color = if (sending) Color(0xFF3A3A3A) else Color(0xFF2E5E8E)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("→", color = Color.White, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingRow() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val bars = listOf(
        transition.animateFloat(
            initialValue = 0.35f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(380, delayMillis = 0), RepeatMode.Reverse),
            label = "bar0"
        ),
        transition.animateFloat(
            initialValue = 0.35f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(380, delayMillis = 130), RepeatMode.Reverse),
            label = "bar1"
        ),
        transition.animateFloat(
            initialValue = 0.35f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(380, delayMillis = 260), RepeatMode.Reverse),
            label = "bar2"
        )
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp)
    ) {
        bars.forEach { h ->
            Box(
                Modifier
                    .padding(horizontal = 1.5.dp)
                    .size(width = 5.dp, height = 16.dp * h.value)
                    .background(Color(0xFF7BD88F), RoundedCornerShape(2.dp))
            )
        }
        Spacer(Modifier.width(10.dp))
        Text("Модель думает…", color = Color(0xFF8A8A8A), fontSize = 13.sp)
    }
}

// «big-pickle» → «Big Pickle»; пусто/нет — «Модель»
private fun prettyModel(id: String?): String {
    if (id.isNullOrBlank()) return "Модель"
    val words = id.trim()
        .split('-', '_', '.', '/', ':')
        .filter { it.isNotBlank() }
    if (words.isEmpty()) return "Модель"
    return words.joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercaseChar() } }
}

// Доступные шрифты ответов модели: (ключ сохранения, имя в палитре, FontFamily)
private val fontEntries = listOf(
    Triple("mono", "Моноширинный", FontFamily.Monospace),
    Triple("jbm", "JetBrains Mono", FontFamily(Font(R.font.jbm))),
    Triple("play", "Play", FontFamily(Font(R.font.play))),
    Triple("lobster", "Lobster", FontFamily(Font(R.font.lobster))),
    Triple("vt323", "VT323", FontFamily(Font(R.font.vt323))),
    Triple("caveat", "Caveat", FontFamily(Font(R.font.caveat))),
    Triple("montserrat", "Montserrat", FontFamily(Font(R.font.montserrat))),
    Triple("russo", "Russo One", FontFamily(Font(R.font.russo_one))),
    Triple("neucha", "Neucha", FontFamily(Font(R.font.neucha))),
    Triple("badscript", "Bad Script", FontFamily(Font(R.font.bad_script))),
    Triple("raleway", "Raleway", FontFamily(Font(R.font.raleway))),
    Triple("rubik", "Rubik", FontFamily(Font(R.font.rubik))),
    Triple("exo2", "Exo 2", FontFamily(Font(R.font.exo2))),
    Triple("ptsans", "PT Sans", FontFamily(Font(R.font.ptsans))),
    Triple("ptserif", "PT Serif", FontFamily(Font(R.font.ptserif))),
    Triple("dancing", "Dancing Script", FontFamily(Font(R.font.dancing))),
    Triple("comfortaa", "Comfortaa", FontFamily(Font(R.font.comfortaa))),
    Triple("kurale", "Kurale", FontFamily(Font(R.font.kurale))),
    Triple("pangolin", "Pangolin", FontFamily(Font(R.font.pangolin))),
    Triple("cormorant", "Cormorant", FontFamily(Font(R.font.cormorant)))
)

private fun fontFor(key: String): FontFamily =
    fontEntries.firstOrNull { it.first == key }?.third ?: FontFamily.Monospace

private fun parseHexColor(hex: String): Color = try {
    Color(("FF$hex").toLong(16))
} catch (_: Exception) {
    Color(0xFFD97706)
}

// Создаёт системный распознаватель речи, если он доступен на устройстве.
private fun ContextCompatSpeechRecognizer(context: Context): SpeechRecognizer? =
    if (SpeechRecognizer.isRecognitionAvailable(context)) {
        SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
    } else {
        null
    }

/** Чтение PCM16 WAV → FloatArray 16кГц моно (микс каналов средним + ресемпл линейной интерполяцией). */
private fun readWavPcm16(f: File, targetRate: Int = 16000): FloatArray {
    val b = f.readBytes()
    fun le16(o: Int) = ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)
    fun le32(o: Int) = ((b[o + 3].toInt() and 0xFF) shl 24) or ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)
    if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE")
        throw RuntimeException("не WAV-файл")
    var pos = 12
    var channels = 1; var rate = 16000; var bits = 16
    var dataStart = -1; var dataLen = 0
    while (pos + 8 <= b.size) {
        val id = String(b, pos, 4, Charsets.US_ASCII)
        val len = le32(pos + 4)
        if (id == "fmt ") {
            channels = le16(pos + 10)
            rate = le32(pos + 12)
            bits = le16(pos + 22)
        } else if (id == "data") {
            dataStart = pos + 8; dataLen = len
        }
        pos += 8 + len + (len and 1)
    }
    if (dataStart < 0 || channels < 1 || bits != 16) throw RuntimeException("WAV: нет data/не PCM16 (bits=$bits)")
    val n = (dataLen / (2 * channels)).coerceAtMost((b.size - dataStart) / (2 * channels))
    val mono = FloatArray(n) { i ->
        var acc = 0
        for (c in 0 until channels) acc += le16(dataStart + (i * channels + c) * 2).toShort().toInt()
        (acc / channels) / 32768f
    }
    if (rate == targetRate) return mono
    // линейный ресемпл
    val ratio = rate.toDouble() / targetRate
    val outLen = (n / ratio).toInt()
    return FloatArray(outLen) { i ->
        val src = i * ratio
        val i0 = src.toInt(); val i1 = (i0 + 1).coerceAtMost(n - 1)
        val frac = (src - i0).toFloat()
        mono[i0] * (1 - frac) + mono[i1] * frac
    }
}

// Запись голоса для локального Whisper.
// Пишем 48000 Гц (нативная частота телефона) и ресемплим в 16к СВОИМ FIR-фильтром:
// встроенный ресемплер OPPO сыпет паразитные пики 2.5/4/6/7.5 кГц, от которых
// whisper путает слова. Стерео: L/R — два микрофона, берём более громкий.
private class AudioRecorder(private val sampleRate: Int = 16000) {
    private val captureRate = 48000 // частота захвата (нативная)
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val raw = mutableListOf<Short>() // interleaved (L,R,L,R…) если стерео
    private var channels = 1

    private fun buildRecorder(fmt: Int, bufSize: Int): AudioRecord? {
        // UNPROCESSED (сырой тракт): MIC-тракт OPPO замусорен артефактами
        // ресемплинга (паразитные пики 2.5/4/7.5 кГц у Найквиста) — whisper
        // на таком сигнале путает слова. Если UNPROCESSED не поддержан — MIC.
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, // телефонный тракт с AGC: самый громкий и чистый
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC
        )
        for (src in sources) {
            try {
                val r = AudioRecord(src, captureRate, fmt, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d("VOICE", "recorder source=$src (${if (src == MediaRecorder.AudioSource.UNPROCESSED) "UNPROCESSED" else "MIC"})")
                    return r
                }
                r.release()
            } catch (_: Throwable) { /* пробуем следующий source */ }
        }
        return null
    }

    fun start() {
        val enc = AudioFormat.ENCODING_PCM_16BIT
        var fmt = AudioFormat.CHANNEL_IN_STEREO
        var minBuf = AudioRecord.getMinBufferSize(captureRate, fmt, enc)
        var r = if (minBuf > 0) buildRecorder(fmt, maxOf(minBuf * 4, 8192)) else null
        if (r != null) channels = 2 else {
            fmt = AudioFormat.CHANNEL_IN_MONO
            minBuf = AudioRecord.getMinBufferSize(captureRate, fmt, enc)
            r = buildRecorder(fmt, maxOf(minBuf * 4, 8192)) ?: throw RuntimeException("микрофон не инициализирован")
            channels = 1
        }
        val bufSize = maxOf(minBuf * 4, 8192)
        recorder = r
        raw.clear()
        running = true
        r.startRecording()
        Log.d("VOICE", "recorder: channels=$channels, ${captureRate}Hz")
        thread = thread(name = "whisper-record") {
            val buf = ShortArray(bufSize / 2)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) synchronized(raw) { for (i in 0 until n) raw.add(buf[i]) }
            }
        }
    }

    fun stop(): FloatArray {
        running = false
        thread?.join(1500)
        val r = recorder ?: return FloatArray(0)
        try { r.stop() } catch (_: Exception) {}
        r.release()
        recorder = null
        val data = synchronized(raw) { raw.toShortArray() }
        if (data.isEmpty()) return FloatArray(0)
        var l: FloatArray
        var rr: FloatArray
        if (channels == 2 && data.size >= 2) {
            val n = data.size / 2
            l = resample4to1(FloatArray(n) { data[it * 2] / 32768f })
            rr = resample4to1(FloatArray(n) { data[it * 2 + 1] / 32768f })
        } else {
            l = resample4to1(FloatArray(data.size) { data[it] / 32768f })
            rr = FloatArray(0)
        }
        // High-pass 150 Гц: убираем сетевой фон 100 Гц.
        l = highPass(l)
        if (rr.isNotEmpty()) rr = highPass(rr)
        val rmsL = rms(l); val rmsR = if (rr.isNotEmpty()) rms(rr) else -1.0
        val best = if (rmsL >= rmsR) l else rr
        Log.d("VOICE", "48к→16к: rmsL=${"%.3f".format(rmsL)} rmsR=${"%.3f".format(rmsR)} → беру ${if (rmsL >= rmsR) "L" else "R"}")
        return best
    }

    /** Качественный ресемпл 48000→16000: FIR low-pass (Хэмминг, 63 тапа, срез 7.4к) + децимация 4:1. */
    private fun resample4to1(x: FloatArray): FloatArray {
        val taps = 63
        val cut = 7400.0 / 48000.0
        val h = DoubleArray(taps) { i ->
            val n = i - (taps - 1) / 2.0
            val sinc = if (n == 0.0) 2 * cut else kotlin.math.sin(2 * Math.PI * cut * n) / (Math.PI * n)
            sinc * (0.54 - 0.46 * kotlin.math.cos(2 * Math.PI * i / (taps - 1)))
        }
        val hSum = h.sum()
        for (i in h.indices) h[i] /= hSum
        val half = (taps - 1) / 2
        val outLen = x.size / 4
        val out = FloatArray(outLen)
        for (k in 0 until outLen) {
            var acc = 0.0
            val base = k * 4
            for (t in 0 until taps) {
                val idx = base - half + t
                if (idx in x.indices) acc += h[t] * x[idx]
            }
            out[k] = acc.toFloat()
        }
        return out
    }

    /** High-pass 150 Гц (биквад RBJ, Q=0.707) — срез сетевого фона 100 Гц. */
    private fun highPass(x: FloatArray, fc: Double = 150.0, q: Double = 0.707): FloatArray {
        val w0 = 2.0 * Math.PI * fc / sampleRate
        val cosw = Math.cos(w0); val sinw = Math.sin(w0)
        val alpha = sinw / (2.0 * q)
        val b0 = (1.0 + cosw) / 2.0; val b1 = -(1.0 + cosw); val b2 = (1.0 + cosw) / 2.0
        val a0 = 1.0 + alpha; val a1 = -2.0 * cosw; val a2 = 1.0 - alpha
        val out = FloatArray(x.size)
        var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
        for (i in x.indices) {
            val y = ((b0 / a0) * x[i] + (b1 / a0) * x1 + (b2 / a0) * x2 - (a1 / a0) * y1 - (a2 / a0) * y2).toFloat()
            x2 = x1; x1 = x[i]; y2 = y1; y1 = y
            out[i] = y
        }
        return out
    }

    private fun rms(x: FloatArray): Double {
        var s = 0.0
        for (v in x) s += v.toDouble() * v
        return kotlin.math.sqrt(s / x.size)
    }
}

@Composable
private fun MessageRow(m: ChatMsg, modelName: String = "Модель", modelFont: FontFamily = FontFamily.Monospace, modelColor: Color = Color(0xFFD97706)) {
    val isModel = m.role == "assistant"
    val roleColor = when (m.role) {
        "user" -> Color(0xFF8AB4F8)
        "assistant" -> Color(0xFF9C27B0)
        else -> Color(0xFFB0B0B0)
    }
    val roleLabel = when (m.role) {
        "user" -> "Ты"
        "assistant" -> modelName
        else -> "Система"
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            roleLabel,
            color = roleColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            m.text.ifBlank { "… генерируется …" },
            color = if (isModel) modelColor else Color(0xFFEDEDED),
            fontFamily = if (isModel) modelFont else FontFamily.Default,
            fontSize = 14.sp,
            lineHeight = 19.sp
        )
    }
}

private suspend fun fetchChatSnapshot(port: Int?): ChatSnapshot? = withContext(Dispatchers.IO) {
    val p = port ?: return@withContext null
    try {
        val sessionsRaw = get("http://127.0.0.1:$p/session") ?: return@withContext null
        val sessions = JSONArray(sessionsRaw)
        var bestId: String? = null
        var bestTs = -1L
        var bestModelId = ""
        for (i in 0 until sessions.length()) {
            val s = sessions.getJSONObject(i)
            val t = s.optJSONObject("time")?.optLong("updated") ?: -1L
            if (t > bestTs) {
                bestTs = t
                bestId = s.optString("id", null)
                bestModelId = s.optJSONObject("model")?.optString("id", "") ?: ""
            }
        }
        if (bestId == null) return@withContext ChatSnapshot(emptyList(), "нет сессий", null)
        val label = titleOf(sessions, bestId)
        val msgRaw = get("http://127.0.0.1:$p/session/$bestId/message") ?: return@withContext ChatSnapshot(emptyList(), label, bestId)
        val arr = JSONArray(msgRaw)
        val out = ArrayList<ChatMsg>(arr.length())
        for (i in 0 until arr.length()) {
            val msg = arr.getJSONObject(i)
            val info = msg.optJSONObject("info") ?: continue
            val role = info.optString("role", "system")
            val parts = msg.optJSONArray("parts") ?: continue
            val sb = StringBuilder()
            for (ph in 0 until parts.length()) {
                val part = parts.getJSONObject(ph)
                if (part.optString("type") == "text") {
                    val t = part.optString("text", "")
                    if (sb.isNotEmpty() && t.isNotEmpty()) sb.append("\n")
                    sb.append(t)
                }
            }
            out.add(ChatMsg(role, sb.toString()))
        }
        if (out.isNotEmpty()) {
            val lastMsg = out[out.size - 1]
            if (lastMsg.role == "assistant" && lastMsg.text.isBlank()) {
                out.removeAt(out.size - 1)
            }
        }
        val take = if (out.size > MAX_SHOWN) out.subList(out.size - MAX_SHOWN, out.size) else out
        val q = questionOf(p, bestId)
        val last = out.lastOrNull()
        val thinking = q == null && when {
            last == null -> false
            last.role == "user" -> true
            last.role == "assistant" && last.text.isBlank() -> true
            else -> false
        }
        val snap = ChatSnapshot(take, "$label · ${out.size} сообщ.", bestId, q, thinking, prettyModel(bestModelId))
        android.util.Log.d("ChatOverlay", "FETCH out=${out.size} take=${take.size} thinking=$thinking q=${q != null} label=$label model=$bestModelId")
        snap
    } catch (e: Exception) {
        if (e is InterruptedException) throw e
        null
    }
}

private fun titleOf(sessions: JSONArray, id: String): String {
    for (i in 0 until sessions.length()) {
        val s = sessions.getJSONObject(i)
        if (s.optString("id") == id) {
            val t = s.optString("title", "")
            return if (t.isBlank()) "сессия" else t
        }
    }
    return "сессия"
}

private fun questionOf(port: Int, sessionId: String): ChatQuestion? {
    try {
        val raw = get("http://127.0.0.1:$port/api/session/$sessionId/question") ?: return null
        val data = JSONObject(raw).optJSONArray("data") ?: return null
        if (data.length() == 0) return null
        val q = data.getJSONObject(0)
        val opts = q.optJSONArray("options") ?: JSONArray()
        val labels = ArrayList<String>(opts.length())
        for (i in 0 until opts.length()) {
            labels.add(opts.getJSONObject(i).optString("label", ""))
        }
        return ChatQuestion(q.optString("id", ""), q.optString("text", ""), labels)
    } catch (_: Exception) {
        return null
    }
}

private fun postAnswer(port: Int, sessionId: String, questionId: String, labels: List<String>): Boolean {
    try {
        val conn = (URL("http://127.0.0.1:$port/api/session/$sessionId/question/$questionId/reply").openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.connectTimeout = 2000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val answers = JSONArray()
        val one = JSONArray()
        one.put(labels.firstOrNull() ?: "")
        answers.put(one)
        val body = JSONObject().put("answers", answers).toString()
        conn.outputStream.use { it.write(body.toByteArray()) }
        thread(isDaemon = true) {
            try {
                conn.responseCode
            } catch (_: Exception) {
            } finally {
                conn.disconnect()
            }
        }
        return true
    } catch (_: Exception) {
        return false
    }
}

private suspend fun scrollToBottomFull(state: LazyListState, target: Int) {
    if (target < 0) return
    state.scrollToItem(target)
    // scrollToItem ставит элемент началом видимой области; длинное сообщение
    // обрезается снизу. Дожимаем в цикле до низа последнего видимого элемента.
    repeat(4) {
        val info = state.layoutInfo
        val items = info.visibleItemsInfo
        if (items.isEmpty()) return
        val last = items.last()
        val overflow = (last.offset + last.size) - info.viewportEndOffset
        if (overflow <= 0) return
        state.scrollBy(overflow.toFloat())
    }
}

private fun vibrate(context: Context) {
    try {
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(90)
        }
    } catch (_: Exception) {
    }
}

private fun createSession(port: Int): String? {
    val conn = (URL("http://127.0.0.1:$port/session").openConnection() as HttpURLConnection)
    try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 2000
        conn.readTimeout = 4000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write("{}".toByteArray()) }
        if (conn.responseCode != 200) return null
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(body).optString("id", null)
    } catch (_: Exception) {
        return null
    } finally {
        conn.disconnect()
    }
}

private fun postMessage(port: Int, sessionId: String, text: String): Boolean {
    try {
        val conn = (URL("http://127.0.0.1:$port/session/$sessionId/message").openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.connectTimeout = 2000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val body = "{\"parts\":[{\"type\":\"text\",\"text\":${JSONObject.quote(text)}}]}"
        conn.outputStream.use { it.write(body.toByteArray()) }
        // opencode отвечает на этот POST только после завершения генерации.
        // Не блокируем UI и НЕ рвём соединение: фоновый поток дочитает ответ —
        // так сервер считает запрос завершённым и гарантированно запишет сообщение.
        thread(isDaemon = true) {
            try {
                conn.responseCode
            } catch (_: Exception) {
            } finally {
                conn.disconnect()
            }
        }
        return true
    } catch (_: Exception) {
        return false
    }
}

private fun get(url: String): String? {
    val conn = (URL(url).openConnection() as HttpURLConnection)
    try {
        conn.connectTimeout = 1500
        conn.readTimeout = 3000
        conn.requestMethod = "GET"
        if (conn.responseCode != 200) return null
        return conn.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        return null
    } finally {
        conn.disconnect()
    }
}