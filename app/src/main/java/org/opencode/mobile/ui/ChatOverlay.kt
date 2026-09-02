package org.opencode.mobile.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.opencode.mobile.R
import org.opencode.mobile.stt.ModelDownloader
import org.opencode.mobile.stt.WhisperTranscribeService
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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

// Контекст-лимит активной модели (входные токены) — порог, при котором opencode
// начнёт компакт. big-pickle (opencode free) имеет окно 200000 токенов.
// Если сменить модель с другим окном — поправить здесь. Круговой индикатор в
// шапке чата показывает current/limit зрительно.
private const val CONTEXT_LIMIT = 200_000L

// Число сегментов (наклонных кубиков) индикатора контекста в шапке чата.
// 30 — очень много мелких кубиков на всю ширину строки.
private const val SEGMENTS = 30

// Вариант B: если модель "думает" (последнее — user, или assistant с пустым текстом)
// дольше этого времени без какого-либо прогресса в сессии — считаем зависание
// и снимаем вечный индикатор «… генерируется …». 120_000 = 2 минуты паузы.
private const val STALL_TIMEOUT_MS = 120_000L

// Интервал опроса serve. КРАЙНЕ ВАЖНО для скорости появления ответа: serve пишет
// полный ответ мгновенно, но приложение узнаёт о нём только на следующем поллинге.
// Раз поллинг стоит 2_000мс — ответ «задерживался» на 0..2с (в среднем ~1с), что
// ощущалось как «рендер через 1.5-2с». Снижено до 400мс: ответ появляется почти
// сразу (≤400мс). Сам запрос лёгкий (~30-70мс), частая опрашивание безопасна.
// При 400мс добавляем CDelta-поллинг: snapshot ставится только при реальных
// изменениях, чтобы не реконсилить LazyColumn на каждый тик.
private const val POLL_INTERVAL_MS = 400L

// Адаптивный поллинг: в простое (лента статична, нет думания, никто не отвечает)
// serve-опрос растягивается до POLL_IDLE_MS, чтобы не создавать 3 TCP-соединения
// каждые 400мс вхолостую (жрёт ~30% CPU UI в idle). Как только детектим активность
// (thinking=true | новый user-part | сменился liveTool/question) — мгновенно
// возвращаемся к быстрому 400мс, чтобы отклик на ответ модели не проседал.
// Idle-интервал → ввод: стоит 900мс (≈1.1 поллинга/с вместо 2.5).
private const val POLL_IDLE_MS = 900L

// Сколько последовательных «стабильных» поллингов нужно, чтобы перейти в idle.
// Небольшое значение, чтобы не дёргаться на единичных флапс (скролл не влияет —
// поллинг не зависит от видимости). Только лента + флаги.
private const val STABLE_POLL_ROUNDS = 3

// Лёгкий кэш MCP-статуса: /mcp меняется редко (только подключение/отключение
// серверов), но считывается каждый поллинг (~2.5 раза/с). Чтобы убрать этот
// HTTP-запрос из большинства опросов — кэшируем сырой JSON на короткое время.
// При просрочке фонем его на следующем поллинге. Индикатор «N MCP» обновится
// с задержкой ≤3с — некритично.
private const val MCP_CACHE_MS = 3_000L
private object McpCache {
    @Volatile var raw: String? = null
    @Volatile var at: Long = 0L
}
// Вернёт сырой JSON MCP из кэша, если он свежий (<MCP_CACHE_MS), иначе загрузит.
private fun getMcpCached(port: Int): String? {
    val now = System.currentTimeMillis()
    val cached = McpCache.raw
    if (cached != null && now - McpCache.at < MCP_CACHE_MS) return cached
    val fresh = get("http://127.0.0.1:$port/mcp")
    if (fresh != null) {
        McpCache.raw = fresh
        McpCache.at = now
    }
    return fresh
}

// Инкрементальный кэш ленты: самая дорогая операция поллинга — пересоздание
// списка ChatMsg + вычисление thinking/liveTool/ctxTokens из сырого JSON /message.
// При 400мс поллинге это происходит ~2.5 раза в секунду даже когда лента НЕ
// меняется (нет ответа, нет думания). Кэшируем результат парсинга, привязанный
// к (sessionId, hashCode(сырой /message)): если хэш тот же — лента битово
// идентична, переиспользуем готовые объекты и НЕ парсим снова. serverRevs держит
// отдельно, потому что заголовок/метки могут меняться независимо от ленты.
private data class ChatParseResult(
    val messages: List<ChatMsg>,
    val question: ChatQuestion?,
    val thinking: Boolean,
    val liveTool: ChatTool?,
    val contextTokens: Long,
    val hasActivity: List<Boolean>,
    val hasFinish: List<Boolean>,
    val lastTool: ChatTool?
)
private object ChatCache {
    @Volatile var sessionId: String? = null
    @Volatile var rawHash: Int = 0
    @Volatile var result: ChatParseResult? = null
}

private data class ChatMsg(val role: String, val text: String)

private data class ChatQuestion(val id: String, val text: String, val options: List<String>)

// Один вызов инструмента модели (tool) для live-чипа «что делает сейчас».
private data class ChatTool(
    val name: String,
    val detail: String
)

// Отдельный MCP-сервер: имя + статус ("connected" / "disconnected" / ...).
private data class McpInfo(
    val name: String,
    val status: String
)

private data class ChatSnapshot(
    val messages: List<ChatMsg>,
    val label: String,
    val activeId: String?,
    val question: ChatQuestion? = null,
    val thinking: Boolean = false,
    val modelName: String = "Модель",
    val stalled: Boolean = false,
    val liveTool: ChatTool? = null,
    // Заполненность контекста (входные токены сессии). Контекст-лимит модели
    // (порог компакта) задаётся константой CONTEXT_LIMIT — берётся из модели.
    val contextTokens: Long = 0L,
    // MCP-серверы: (подключено, всего). Для индикатора «mcp N» в шапке — зелёный
    // если есть хотя бы один подключённый, красный если 0.
    val mcpConnected: Int = 0,
    val mcpTotal: Int = 0,
    // Полный список MCP-серверов (имя + статус) для выпадающего списка по тапу.
    val mcpServers: List<McpInfo> = emptyList()
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState = androidx.compose.runtime.remember { lifecycleOwner.lifecycle }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var snapshot by remember { mutableStateOf<ChatSnapshot?>(null) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var knownMsgs by remember { mutableIntStateOf(0) }
    // ИНДЕКС последнего завершённого ответа, на который уже сработала вибрация.
    // Отделён от knownMsgs (фиксирует появление в сессии), чтобы звук играл
    // СТРОГО когда ответ реально стал видимым на экране (см. LaunchedEffect(lastResp)).
    var knownRendered by remember { mutableIntStateOf(-1) }
    // Флаг «базовая линия зафиксирована»: истина после первого поллинга.
    // Нужен чтобы первый реальный ответ (в т.ч. в пустой сессии) корректно
    // вибрировал, а старые ответы при старте — нет.
    var baselineDone by remember { mutableStateOf(false) }
    // Счётчик размера ленты на прошлой автопрокрутке. Если лента ВЫРОСЛА
    // (появилось новое сообщение — от юзера или модели) — принудительно
    // спускаемся к низу, даже если юзер перед этим листал вверх (userScrolledUp).
    var prevMsgCount by remember { mutableIntStateOf(0) }
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
    // Прогресс скачивания base (тоже lazy с этого релиза).
    var baseDownloadPct by remember { mutableStateOf<Int?>(null) }
    var baseDownloadMsg by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    // Выпадающий список MCP-серверов (открывается тапом по индикатору MCP).
    var showMcpList by remember { mutableStateOf(false) }
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

    fun stopGen() {
        val sessionId = snapshot?.activeId ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { abortSession(serverPort, sessionId) }
            if (ok) {
                vibrate(context)
            }
            // thinking сбросится сам на следующем поллинге (2с): abort завершит
            // стрим, и fetchChatSnapshot увидит step-finish → thinking=false.
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
                val file = ModelDownloader.downloadTurbo(context) { done, total ->
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

    // Скачивание base (141MB) на устройство — теперь тоже по требованию
    // (вынесена из assets в lazy), как и turbo.
    fun startBaseDownload(context: Context) {
        if (baseDownloadPct != null) return // уже качаем
        baseDownloadPct = 0
        baseDownloadMsg = null
        scope.launch {
            try {
                val file = ModelDownloader.downloadBase(context) { done, total ->
                    baseDownloadPct = if (total > 0) ((done * 100) / total).toInt() else 0
                }
                Log.d("VOICE", "base скачана: ${file.absolutePath}")
                baseDownloadPct = 100
                setSttModel("base")
            } catch (e: Throwable) {
                Log.e("VOICE", "скачивание base упало", e)
                baseDownloadMsg = "Ошибка скачивания: ${e.message}"
                baseDownloadPct = null
            }
        }
    }

    // Автопрокрутка вниз: после отправки и при новом ответе/думании.
    // Если юзер сам листал вверх, автопрокрутка СТАРОЙ ленты не дёргает его,
    // НО когда приходит НОВОЕ сообщение (лента выросла) — принудительно
    // спускаемся к низу, чтобы последнее сообщение всегда было видно.
    LaunchedEffect(snapshot?.messages?.size, snapshot?.thinking) {
        delay(90) // дождаться рекомпозиции LazyColumn — здесь новый ответ УЖЕ отрисован
        val snap = snapshot ?: return@LaunchedEffect
        val n = snap.messages.size
        val newMsg = n > prevMsgCount // появилось новое сообщение (свой вопрос или ответ модели)
        if (n > 0 && (!userScrolledUp || newMsg)) {
            val target = if (snap.thinking) n else n - 1
            scrollToBottomFull(listState, target)
        }
        if (newMsg) userScrolledUp = false // новая порция контента — снимаем блокировку
        prevMsgCount = n
    }

    // Вибрация при ПОЯВЛЕНИИ нового завершённого ответа.
    // ПРИВЯЗАНА К ФАКТИЧЕСКОЙ ОТРИСОВКЕ: срабатывает только когда последний
    // assistant-ответ реально стал видимым в viewport LazyColumn. Это исключает
    // рассинхрон «звук раньше, текст позже»: даже если рендер медленный
    // (композиция/измерение заняли время), вибрируем строго после показа текста.
    // knownRendered — индекс последнего, на который уже «вибрировали» (высчитывается
    // как количество завершённых ответов на момент последней вибрации).
    val lastResp = snapshot?.messages?.indexOfLast { it.role == "assistant" && it.text.isNotBlank() }
    LaunchedEffect(lastResp) {
        val idx = lastResp ?: return@LaunchedEffect
        if (idx < 0) return@LaunchedEffect
        if (idx <= knownRendered) return@LaunchedEffect // старый/уже обработанный ответ — не вибрируем
        // Ждём, пока этот элемент реально окажется в видимой области (скоррлировали).
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.index == idx } }
            .filter { it }
            .first()
        knownRendered = idx
        vibrate(context)
        playNotificationSound(context)
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
        // Вариант B: клиентский таймаут стрима. Как только видим "думает" —
        // фиксируем момент System.currentTimeMillis(); если за STALL_TIMEOUT_MS
        // сессия так и не сдвинулась вперёд (нет нового ассистент-part и нет
        // нового user-part), помечаем stalled -> UI снимает вечный индикатор
        // и показывает «Нет ответа». Метка сбрасывается при любом прогрессе.
        var stallSince = 0L
        // Адаптивный поллинг: счётчик «стабильных» итераций. Растёт, пока лента
        // статична и не думается; по достижении STABLE_POLL_ROUNDS поллинг
        // растягивается до POLL_IDLE_MS. При малейшем прогрессе сбрасывается → 400мс.
        var stableRounds = 0
        while (true) {
            // Экономия батареи/CPU в фоне: если Activity не видна (на заднем плане),
            // обновлять UI бессмысленно. Пропускаем поллинг и спим длинным квантом.
            // При возврате в foreground первый же тик подхватит актуальный snapshot
            // (задержка ≤2с — незаметно, т.к. экран просыпается). Процесс живой,
            // сервер НЕ трогается — это лишь пауза обновления скрытого чата.
            if (lifecycleState.currentState != Lifecycle.State.RESUMED) {
                delay(2_000L)
                stableRounds = 0
                continue
            }
            val snap = fetchChatSnapshot(serverPort)
            var changed = false
            if (snap != null) {
                val now = System.currentTimeMillis()
                val completed = snap.messages.count { it.role == "assistant" && it.text.isNotBlank() }
                val stalled = if (!snap.thinking) {
                    stallSince = 0L
                    false
                } else {
                    if (stallSince == 0L) stallSince = now
                    val el = now - stallSince
                    android.util.Log.d("ChatOverlay", "STALL check thinking=true since=${el}ms")
                    if (el >= STALL_TIMEOUT_MS) true else false
                }
                val final = if (stalled) snap.copy(stalled = true) else snap
                // Дельта-поллинг: ставим snapshot в UI только если содержимое реально
                // изменилось (messages + thinking + stalled одинаковы — пропускаем).
                // При частом поллинге (400мс) это не даёт Compose реконсилить всю
                // ленту без необходимости, сохраняя рендер максимально дешёвым.
                val old = snapshot
                changed = old == null ||
                    old.messages != final.messages ||
                    old.thinking != final.thinking ||
                    old.stalled != final.stalled ||
                    old.liveTool != final.liveTool ||
                    old.question != final.question
                if (changed) {
                    snapshot = final
                }
                if (completed > knownMsgs) {
                    // Ответ появился в сессии (модель завершила, `completed` считает
                    // ассистентов с текстом). ЗВУК НЕ играем здесь: snapshot ещё не
                    // отрисован — иначе вибрация опережала бы рендер сообщения на
                    // 1-2с (Compose-композиция + скролл отстают от записи данных).
                    // Вибрация перенесена в отдельный LaunchedEffect, привязанный
                    // к фактической видимости ответа. Здесь только фиксируем факт.
                    knownMsgs = completed
                } else if (knownMsgs == 0 && snap.messages.isNotEmpty()) {
                    knownMsgs = completed
                }
                // Базовая линия на ПЕРВОМ поллинге приложения: фиксируем ИНДЕКС
                // последнего завершённого ответа как «уже известный», чтобы старые
                // ответы (если сессия не пуста в момент открытия чата) НЕ вызывали
                // вибрацию. Выполняется один раз. Последующие новые ответы
                // (idx > knownRendered) завибрируют строго после отрисовки.
                if (!baselineDone) {
                    baselineDone = true
                    knownRendered = snap.messages.indexOfLast { it.role == "assistant" && it.text.isNotBlank() }
                }
            }

            // Адаптивный интервал: активность (thinking / реальное изменение ленты) →
            // быстрая опрашивание 400мс. Стабильность → плавно растягиваем к idle 900мс.
            val active = snap != null && (snap.thinking || changed)
            if (active) {
                stableRounds = 0
            } else {
                stableRounds++
            }
            delay(if (stableRounds >= STABLE_POLL_ROUNDS) POLL_IDLE_MS else POLL_INTERVAL_MS)
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
                // Круговой индикатор заполнения контекста. Показывает, сколько уже
                // накоплено входных токенов сессии относительно лимита модели
                // (CONTEXT_LIMIT). Зелёный → жёлтый → красный по мере приближения
                // к компакту; внутри — процент заполнения.
                ContextGauge(
                    filled = snapshot?.contextTokens ?: 0L,
                    limit = CONTEXT_LIMIT,
                    modifier = Modifier.weight(1f)
                )
                // Индикатор MCP-серверов (НЕОНОВЫЙ): «N MCP» + мигающая точка.
                // Зелёный — все N подключённых серверов работают; красный — какой-то
                // из них не работает (или их нет вовсе). Стоит ЛЕВЕЕ выбора цвета.
                MCPIndicator(
                    connected = snapshot?.mcpConnected ?: 0,
                    total = snapshot?.mcpTotal ?: 0,
                    onClick = { showMcpList = !showMcpList },
                    modifier = Modifier.padding(start = 8.dp)
                )
                // Цветовой пикер для ответов модели. ИКОНКА — готовая «капля»
                // (Material Icons: Icons.Filled.InvertColors) — узнаваемая капля,
                // тонируется ТЕКУЩИМ выбранным цветом ответов (modelColor).
                Icon(
                    imageVector = Icons.Filled.InvertColors,
                    contentDescription = "Цвет ответов модели",
                    tint = modelColor,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (showColorPicker) Color(0xFF3A3A3A) else Color.Transparent)
                        .clickable { showColorPicker = !showColorPicker }
                        .padding(3.dp)
                )
                Icon(
                    imageVector = Icons.Filled.TextFormat,
                    contentDescription = "Шрифт ответов модели",
                    tint = if (showFontPicker) modelColor else Color(0xFF8A8A8A),
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (showFontPicker) Color(0xFF3A3A3A) else Color.Transparent)
                        .clickable { showFontPicker = !showFontPicker }
                        .padding(3.dp)
                )
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Настройки голосового распознавания",
                    tint = if (showSettings) Color.White else Color(0xFFBDBDBD),
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (showSettings) Color(0xFF3A3A3A) else Color.Transparent)
                        .clickable { showSettings = !showSettings }
                        .padding(3.dp)
                )
            }
            // Выпадающий список подключённых MCP-серверов (тап по индикатору «N MCP»).
            // У каждого имени — мигающая точка: зелёная (работает) / красная (нет).
            if (showMcpList) {
                McpServerList(
                    servers = snapshot?.mcpServers ?: emptyList(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp)
                )
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
                            .clickable {
                                if (ModelDownloader.baseReady(context) || baseDownloadPct != null) {
                                    setSttModel("base")
                                } else {
                                    startBaseDownload(context)
                                }
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Text(if (sttModel == "base") "● " else "○ ", color = Color(0xFFFF6D00), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        val baseLabel = when {
                            baseDownloadPct != null -> "base… $baseDownloadPct%"
                            ModelDownloader.baseReady(context) -> "base (141 МБ, быстро)"
                            else -> "base (141 МБ) — нажми, чтобы скачать"
                        }
                        Text(baseLabel, color = Color(0xFFE6E6E6), fontSize = 12.sp)
                    }
                    baseDownloadMsg?.let {
                        Text(it, color = Color(0xFFE05A5A), fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
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
                        val snap = requireNotNull(snapshot)
                        item(key = if (snap.stalled) "stalled" else "thinking") {
                            if (snap.stalled) {
                                StalledRow()
                            } else {
                                ThinkingRow()
                            }
                        }
                        // Живой чип «какой тул выполняет модель» — поверх индикатора думания.
                        snap.liveTool?.let { t ->
                            item(key = "livetool_${t.name}_${t.detail.hashCode()}") {
                                LiveToolRow(t)
                            }
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
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "Голосовой ввод (удерживай для записи)",
                        tint = if (listening) Color.White else Color(0xFFE0E0E0),
                        modifier = Modifier.size(26.dp)
                    )
                }
                // Кнопка Stop: ВСЕГДА видна рядом с микрофоном.
                // Прерывает текущую генерацию модели (POST /session/{id}/abort).
                // Если модель не думает — abort просто не сработает, сессия не сломается.
                Surface(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(46.dp)
                        .clickable { stopGen() },
                    shape = CircleShape,
                    color = Color(0xFF9E1C1C)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "Прервать генерацию",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Surface(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(46.dp)
                        .clickable { send() },
                    shape = CircleShape,
                    color = if (sending) Color(0xFF3A3A3A) else Color(0xFF2E5E8E)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Отправить сообщение",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
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

@Composable
private fun LiveToolRow(tool: ChatTool) {
    // Живой чип: какой инструмент модель вызывает ПРЯМО СЕЙЧАС (пока работает).
    val transition = rememberInfiniteTransition(label = "liveTool")
    val pulse by transition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(460, delayMillis = 0), RepeatMode.Reverse),
        label = "pulse"
    )
    // Иконка по типу инструмента.
    val (icon, accent) = when (tool.name) {
        "websearch", "webfetch", "context7" -> "🔍" to Color(0xFF5B9BD5)
        "bash", "shell" -> "🛠" to Color(0xFFD97706)
        "read", "grep", "glob" -> "📄" to Color(0xFF7BD88F)
        "write", "edit" -> "✏️" to Color(0xFFB48AD9)
        else -> "⚙️" to Color(0xFF9AA5B1)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.12f * pulse))
            .border(width = 1.dp, color = accent.copy(alpha = 0.5f), shape = RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        // Пульсирующая точка «активно».
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer { alpha = pulse }
                .background(accent, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$icon ${tool.name}",
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        if (tool.detail.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(
                tool.detail,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StalledRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp)
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(Color(0xFFE25822), RoundedCornerShape(3.dp))
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Нет ответа (зависло) — проверь сеть/провайдера",
            color = Color(0xFFE25822), fontSize = 13.sp
        )
    }
}

/**
 * Индикатор заполнения контекста (в шапке чата) — горизонтальная ПОЛОСКА из
 * наклонных кубиков (ромбов). Показывает `filled` (входные токены сессии)
 * относительно `limit` (контекст-лимит модели, CONTEXT_LIMIT). Заполненные
 * кубики окрашены цветом прогресса, который меняется по мере приближения к
 * компакту: зелёный (<50%) → жёлтый (50-80%) → красный (>80%). Пустые кубики
 * — тёмные. Зрительно видно, сколько контекста накоплено и когда скоро будет
 * компакт. Полоска занимает всю доступную ширину (weight 1f).
 */
@Composable
private fun ContextGauge(filled: Long, limit: Long, modifier: Modifier = Modifier) {
    val ratio = if (limit <= 0) 0f else (filled.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
    // Цвет прогресса по мере заполнения: зелёный → жёлтый → красный.
    val g = Color(0xFF4CAF50)
    val y = Color(0xFFFFC107)
    val r = Color(0xFFE53935)
    val active = when {
        ratio < 0.50f -> g
        ratio < 0.80f -> y
        else -> r
    }
    val track = Color(0xFF242424)
    val filledCubes = (ratio * SEGMENTS).toInt().coerceIn(0, SEGMENTS)
    Canvas(modifier.height(40.dp).fillMaxWidth()) {
        val segW = size.width / SEGMENTS
        // Полуось по горизонтали (ширина кубика) и по вертикали (ВЫСОТА).
        // halfY ≈ 2× halfX — кубики вытянуты вверх вдвое, наклон вправо сохранён.
        val halfX = segW * 0.38f
        val halfY = halfX * 2f
        val skew = halfX * 0.55f
        for (i in 0 until SEGMENTS) {
            val cx = size.width * (i + 0.5f) / SEGMENTS
            val cy = size.height / 2f
            val color = if (i < filledCubes) active else track
            val path = Path().apply {
                moveTo(cx - halfX + skew, cy - halfY)   // верх-лево (сдвинут вправо)
                lineTo(cx + halfX + skew, cy - halfY)   // верх-право
                lineTo(cx + halfX, cy + halfY)          // низ-право
                lineTo(cx - halfX, cy + halfY)          // низ-лево
                close()
            }
            drawPath(path, color)
        }
    }
}

/**
 * Неоновый индикатор MCP-серверов: «N MCP» + мигающая точка-светодиод.
 * Зелёный — все подключённые серверы работают (connected==total>0);
 * красный — какой-то не работает или их нет. Стилистика — неон: яркий
 * цвет, мягкое свечение вокруг точки (shadowBlur), точка плавно мигает.
 */
@Composable
private fun MCPIndicator(connected: Int, total: Int, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    // Все работают: есть серверы, и все подключённые дошли до connected.
    val allOk = total > 0 && connected == total
    val neon = if (allOk) Color(0xFF39FF88) else Color(0xFFFF3B3B)
    val hasServers = total > 0
    // Мягкое «дыхание» точки через ДИСКРЕТНЫЙ таймер, а не через infiniteTransition.
    // infiniteTransition тикал каждый кадр (60fps) = RenderThread постоянно занят.
    // Здесь alpha обновляется ~16 раз/с (delay 60мс) циклом, давая плавный пульс,
    // но массивно дешевле. ИТОГОВАЯ защита CPU — тройная:
    //  1) без MCP (total==0) — цикл вообще не запускается, точка статична;
    //  2) цикл дискретный (не 60fps);
    //  3) в фоне (lifecycle паузы) — эффект спит, не тикает.
    var blink by remember { mutableFloatStateOf(if (hasServers) 0.5f else 0.45f) }
    if (hasServers) {
        val lc = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(total) {
            if (lc.currentState != Lifecycle.State.RESUMED) {
                // При старте в фоне — стоим, пока не вернёмся на передний план.
                // Возобновляем по перезаходу (эффект перезапустится на рекомпозиции).
                return@LaunchedEffect
            }
            // Пульс 0.35 → 1.0 → 0.35 по синусу. ДИСКРЕТНО: апдейт раз в 120мс
            // (≈8 тиков за цикл ~1с). Это «дышащий» пульс — плавный на глаз, но
            // в ~7 раз дешевле 60fps-infiniteTransition (RenderThread рисует Canvas
            // только при смене alpha). Пауза в фоне — ниже.
            var t = 0.0
            while (true) {
                // Если Activity ушла в фон — перестаём тикать (экономия батареи).
                if (lc.currentState != Lifecycle.State.RESUMED) {
                    blink = 0.5f
                    delay(2_000L)
                    continue
                }
                val a = 0.35f + 0.65f * ((kotlin.math.sin(t) + 1.0) / 2.0).toFloat()
                blink = a
                t += 0.785  // ~0.785 рад/тик → период волны ≈ 8 тиков ≈ 0.96с
                if (t > kotlin.math.PI * 2.0) t -= kotlin.math.PI * 2.0
                delay(120)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clickable { onClick() }) {
        // Аккуратная мигающая точка-светодиод (без ореола/свечения).
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(22.dp)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val rDot = 4.dp.toPx()
                drawCircle(neon.copy(alpha = blink), radius = rDot, center = c)
            }
        }
        Spacer(Modifier.width(4.dp))
        // «N MCP» — сначала число, потом слово; надпись ВСЕГДА бирюзовая (яркий неон),
        // не зависит от статуса. Статус показывает только точка-светодиод.
        Text(
            "${connected} MCP",
            color = Color(0xFF00E5FF),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            softWrap = false,
            maxLines = 1
        )
    }
}

/**
 * Выпадающий список MCP-серверов (по тапу на индикатор MCP в шапке). Для каждого
 * имени — мигающая точка-светодиод: зелёная (работает, status=="connected") или
 * красная (не работает / отключён). Если серверов нет — подпись «нет MCP».
 */
@Composable
private fun McpServerList(servers: List<McpInfo>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color(0xFF161616), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            "MCP-серверы",
            color = Color(0xFF00E5FF),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(6.dp))
        if (servers.isEmpty()) {
            Text("Нет подключённых MCP", color = Color(0xFF8A8A8A), fontSize = 12.sp)
        } else {
            servers.forEach { srv ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Точка статуса СТАТИЧНАЯ (без rememberInfiniteTransition) — как в
                    // MCPIndicator. Статус передаёт цвет, а не мигание: не грузим
                    // RenderThread постоянно, даже при открытом списке серверов.
                    val ok = srv.status == "connected"
                    val dotColor = if (ok) Color(0xFF39FF88) else Color(0xFFFF3B3B)
                    Canvas(Modifier.size(12.dp)) {
                        drawCircle(dotColor.copy(alpha = 0.9f), radius = size.minDimension / 2f)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        srv.name,
                        color = Color(0xFFE6E6E6),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (ok) "работает" else "не работает",
                        color = if (ok) Color(0xFF39FF88) else Color(0xFFFF3B3B),
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(5.dp))
            }
        }
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
        if (m.text.isNotBlank()) {
            // SelectionContainer — системное выделение текста длинным нажатием:
            // можно выделить любой кусок ответа и скопировать его отдельно.
            SelectionContainer {
                Text(
                    m.text,
                    color = if (isModel) modelColor else Color(0xFFEDEDED),
                    fontFamily = if (isModel) modelFont else FontFamily.Default,
                    fontSize = 14.sp,
                    lineHeight = 19.sp
                )
            }
        }
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
        // Вариант 2: /message (главный, тащит всю ленту) и /mcp стартуют ПАРАЛЛЕЛЬНО.
        // Оба — блокирующие get() на Dispatchers.IO; async даёт им работать одновременно,
        // а не последовательно (экономия ~11-58мс на поллинг в худшем случае).
        val msgDeferred = async { get("http://127.0.0.1:$p/session/$bestId/message") }
        // MCP-серверы: GET /mcp → Record<name, McpServer{name,enabled,status,...}> (иначе пустой {}).
        // Читаем из кэша (обновляется раз в MCP_CACHE_MS), чтобы не дёргать сервис каждый поллинг.
        // Подключёнными считаем тех, у кого status == "connected". Показываем «N MCP».
        var mcpConnected = 0
        var mcpTotal = 0
        val mcpServers = ArrayList<McpInfo>()
        try {
            val mcpRaw = getMcpCached(p)
            if (mcpRaw != null) {
                val trimmed = mcpRaw.trim()
                if (trimmed.startsWith("[")) {
                    val marr = JSONArray(trimmed)
                    mcpTotal = marr.length()
                    for (i in 0 until marr.length()) {
                        val ms = marr.optJSONObject(i)
                        val name = ms?.optString("name", "") ?: ""
                        val status = ms?.optString("status", "") ?: ""
                        if (status == "connected") mcpConnected++
                        if (name.isNotBlank()) mcpServers.add(McpInfo(name, status))
                    }
                } else if (trimmed.startsWith("{")) {
                    val mobj = JSONObject(trimmed)
                    val names = mobj.keys()
                    while (names.hasNext()) {
                        mcpTotal++
                        val key = names.next()
                        val ms = mobj.optJSONObject(key)
                        val status = ms?.optString("status", "") ?: ""
                        if (status == "connected") mcpConnected++
                        mcpServers.add(McpInfo(ms?.optString("name", "")?.takeIf { it.isNotBlank() } ?: key, status))
                    }
                }
            }
        } catch (_: Exception) { /* MCP недоступен — покажем 0 красным */ }
        val msgRaw = msgDeferred.await() ?: return@withContext ChatSnapshot(emptyList(), label, bestId)
        // Инкрементальный кэш: если за этой сессией тот же самый сырой JSON /message
        // (hash совпал) — лента и все производные (thinking/liveTool/ctxTokens/question)
        // гарантированно идентичны. Переиспользуем готовые объекты, НЕ пересоздавая
        // их: это убирает самое тяжёлое — полный JSON-парсинг и построение строк —
        // на каждый тик поллинга (2.5 раза/с), пока контент чата статичен.
        val rawHash = msgRaw.hashCode()
        val cached = ChatCache.result
        if (ChatCache.sessionId == bestId && ChatCache.rawHash == rawHash && cached != null) {
            val q = cached.question
            val thinking = cached.thinking
            val liveTool = cached.liveTool
            val take = cached.messages
            val snap = ChatSnapshot(take, "$label", bestId, q, thinking, prettyModel(bestModelId), liveTool = liveTool, contextTokens = cached.contextTokens, mcpConnected = mcpConnected, mcpTotal = mcpTotal, mcpServers = mcpServers)
            android.util.Log.d("ChatOverlay", "FETCH(cached) out=${take.size} thinking=$thinking q=${q != null} label=$label model=$bestModelId")
            return@withContext snap
        }
        val arr = JSONArray(msgRaw)
        val out = ArrayList<ChatMsg>(arr.length())
        // Параллельные out флаги: была ли у сообщения «активность» шага
        // (step-start/reasoning/tool) и был ли step-finish. Нужны, чтобы отличать
        // реально думающего assistant (активность есть, финиша нет) от оборванного
        // пустого шага после abort (parts=[], активности нет).
        val hasActivity = ArrayList<Boolean>(arr.length())
        val hasFinish = ArrayList<Boolean>(arr.length())
        // Последний инструмент, вызыванный моделью, в этой сессии (для live-чипа).
        var lastTool: ChatTool? = null
        // ЧЕСТНАЯ оценка активного контекста сессии: сумма символов всех текущих
        // частей (text + tool output/input + reasoning). `tokens.input` из /session
        // кумулятивный (включает уже компактированные хвосты), поэтому для индикатора
        // считаем именно активное окно: символы -> токены (≈ /4) + overhead (×1.15).
        var ctxChars = 0L
        for (i in 0 until arr.length()) {
            val msg = arr.getJSONObject(i)
            val info = msg.optJSONObject("info") ?: continue
            val role = info.optString("role", "system")
            val parts = msg.optJSONArray("parts") ?: continue
            val sb = StringBuilder()
            var hasText = false
            var finish = false
            var activity = false
            for (ph in 0 until parts.length()) {
                val part = parts.getJSONObject(ph)
                val type = part.optString("type", "")
                if (type == "text") {
                    hasText = true
                    val t = part.optString("text", "")
                    ctxChars += t.length
                    if (sb.isNotEmpty() && t.isNotEmpty()) sb.append("\n")
                    sb.append(t)
                } else if (type == "step-finish") {
                    finish = true
                } else if (type == "tool") {
                    activity = true
                    ctxChars += (part.optJSONObject("state")?.optString("output", "") ?: "").length
                    // Запомнить имя инструмента + краткое действие (команда/запрос).
                    val st = part.optJSONObject("state")
                    val input = st?.optJSONObject("input")
                    val title = st?.optString("title", "") ?: ""
                    val cmd = input?.optString("command", "") ?: ""
                    val qry = input?.optString("query", "") ?: ""
                    val det = when {
                        cmd.isNotBlank() -> cmd
                        qry.isNotBlank() -> qry
                        title.isNotBlank() -> title
                        else -> ""
                    }
                    lastTool = ChatTool(part.optString("tool", ""), det)
                } else if (type == "step-start" || type == "reasoning") {
                    activity = true
                    ctxChars += part.optString("text", "").length
                }
            }
            // Завершённый tool-only шаг (step-start->tool...->step-finish без text)
            // не должен отображаться как «… генерируется …» — это не зависание,
            // а просто шаг без текста. Фильтруем его из ленты. ДУМАЮЩИЙ assistant
            // (без step-finish) остаётся, чтобы UI показал «генерируется».
            if (role == "assistant" && !hasText && finish) continue
            out.add(ChatMsg(role, sb.toString()))
            hasActivity.add(activity)
            hasFinish.add(finish)
        }
        val lastIndex = out.size - 1
        fun hasActivityFor(idx: Int): Boolean = idx in hasActivity.indices && hasActivity[idx]
        fun hasFinishFor(idx: Int): Boolean = idx in hasFinish.indices && hasFinish[idx]
        val take = if (out.size > MAX_SHOWN) out.subList(out.size - MAX_SHOWN, out.size) else out
        val q = questionOf(p, bestId)
        val last = out.lastOrNull()
        // «Думает» = модель реально начала отвечать (есть шаг: step-start/reasoning/tool)
        // И НЕ завершилась (нет step-finish). После abort opencode добавляет ПУСТОЙ
        // assistant-шаг parts=[] (без step-start, без finish, без text) — такой НЕ
        // считается думающим: иначе UI вечно показывал бы «Модель думает» после Stop.
        val thinking = q == null && when {
            last == null -> false
            last.role == "user" -> true
            last.role == "assistant" && hasActivityFor(lastIndex) && !hasFinishFor(lastIndex) -> true
            else -> false
        }
        // Live-чип показываем только пока модель ещё работает (thinking). Когда она
        // закончила (дала финальный ответ) — lastTool не показываем как «текущее».
        val liveTool = if (thinking) lastTool else null
        // Токены оцениваем через суммарную длину активных частей сессии (ctxChars):
        // ≈ символов/4 (ok для кода/HTML/русского в среднем), плюс небольшой
        // оверхед на системный промпт/структуру (×1.15). Это и есть ТЕКУЩИЙ
        // активный контекст, а не кумулятивный tokens.input.
        val ctxTokens = (ctxChars / 4L * 115 / 100)
        // Записываем кэш ТОЛЬКО после успешного полного парсинга.
        ChatCache.sessionId = bestId
        ChatCache.rawHash = rawHash
        ChatCache.result = ChatParseResult(
            take, q, thinking, liveTool, ctxTokens,
            hasActivity, hasFinish, lastTool
        )
        val snap = ChatSnapshot(take, "$label", bestId, q, thinking, prettyModel(bestModelId), liveTool = liveTool, contextTokens = ctxTokens, mcpConnected = mcpConnected, mcpTotal = mcpTotal, mcpServers = mcpServers)
        android.util.Log.d("ChatOverlay", "FETCH parse out=${out.size} take=${take.size} thinking=$thinking q=${q != null} label=$label model=$bestModelId liveTool=${liveTool?.name}")
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

// Системный звук уведомления — тот самый тон, что юзер выбрал в настройках
// Android для нотификаций. Играет при появлении нового ответа, чтобы оповещение
// было слышным (не только вибрация). Ringtone.play() может блокировать — гоняем
// на Dispatchers.IO. Не создаём NotificationChannel: просто воспроизводим тон.
private fun playNotificationSound(context: Context) {
    try {
        val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (uri != null) {
            val rt = RingtoneManager.getRingtone(context.applicationContext, uri)
            if (rt != null) {
                if (Build.VERSION.SDK_INT >= 28) {
                    rt.audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                rt.play()
            }
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

/**
 * Прерывает текущую генерацию модели в сессии. opencode serve принимает
 * POST /session/{id}/abort (200 + "true"). Ответ приходит сразу, блокировать
 * нечего — это не долгий стрим.
 */
private fun abortSession(port: Int, sessionId: String): Boolean {
    return try {
        val conn = (URL("http://127.0.0.1:$port/session/$sessionId/abort").openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.connectTimeout = 2000
        conn.readTimeout = 3000
        val ok = conn.responseCode == 200
        conn.disconnect()
        ok
    } catch (_: Exception) {
        false
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