package org.opencode.mobile.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
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
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.opencode.mobile.R
import org.opencode.mobile.server.LocalOpenCodeClient
import org.opencode.mobile.server.MemoryMcp
import org.opencode.mobile.server.OpenCodePermissionApi
import org.opencode.mobile.server.OpenCodePermissionRequest
import org.opencode.mobile.server.PermissionDecision
import org.opencode.mobile.server.YnisonMcp
import org.opencode.mobile.stt.NcnnModelValidator
import org.opencode.mobile.stt.WhisperTranscribeService
import org.opencode.mobile.tts.TtsConfig
import org.opencode.mobile.tts.TtsEngine
import org.opencode.mobile.tts.TtsNarrator
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

private const val MAX_SHOWN = 120

// Контекст-лимит активной модели (входные токены) — порог, при котором opencode
// начнёт компакт. big-pickle (opencode free) имеет окно 200000 токенов.
// Если сменить модель с другим окном — поправить здесь. Круговой индикатор в
// шапке чата показывает current/limit зрительно.
private const val CONTEXT_LIMIT = 200_000L

// Идентификаторы выпадающих панелей шапки — ключи для [ChatOverlay] переключателя
// toggleHeaderPanel. Строками, а не enum: ключ нужен ровно в двух местах
// (объявление и клик по иконке), а enum здесь стоил бы лишней печати.
private const val PANEL_MCP = "mcp"
private const val PANEL_COLOR = "color"
private const val PANEL_FONT = "font"
private const val PANEL_SETTINGS = "settings"
private const val PANEL_STT = "stt"

// testTag-метки для проверки геометрии по дампу uiautomator (resource-id).
// Читать их в коде не нужно - это якоря для инструментов, а не логика.
// Сегменты полос сознательно НЕ помечены: 90 ячеек на гейдж дали бы 180 узлов
// ради арифметики, которая восстанавливается из ширины ряда и cellGap.
private const val TAG_CTX_GAUGE = "ctx_gauge"
private const val TAG_ZEN_METER = "zen_meter"
private const val TAG_CTX_ROW = "ctx_row"
private const val TAG_ZEN_ROW = "zen_row"
private const val TAG_PANEL_MCP = "panel_mcp"
private const val TAG_PANEL_COLOR = "panel_color"
private const val TAG_PANEL_FONT = "panel_font"
private const val TAG_PANEL_SETTINGS = "panel_settings"
private const val TAG_PANEL_STT = "panel_stt"
private const val TAG_PANEL_TTS = "panel_tts"

// Наносекунды в миллисекундах: замер пинга отдаёт наносекунды, в UI нужны мс.
private const val NS_PER_MS = 1_000_000L

// Пороги цвета полосы расхода Zen: с 60% жёлтый («прибереги квоту»), с 90% красный
// («почти всё»). Ровно как у шкалы заполнения контекста.
private const val ZEN_WARN_RATIO = 0.6f
private const val ZEN_DANGER_RATIO = 0.9f

// Миллисекунд в минуте для паузы между обходами сессий.
private const val MS_PER_MINUTE = 60_000L

// Геометрия и цвета полос расхода живут в GaugeStyle.kt и меняются пользователем
// из панели шестерёнки в шапке. Констант GAUGE_* здесь больше нет намеренно:
// именно из-за того, что они были константами, любую правку приходилось делать
// через меня и пересборку APK.

// Вариант B: если модель "думает" (последнее — user, или assistant с пустым текстом)
// дольше этого времени без какого-либо прогресса в сессии — считаем зависание
// и снимаем вечный индикатор «… генерируется …». 120_000 = 2 минуты паузы.
private const val STALL_TIMEOUT_MS = 120_000L

// «Пустой» открытый assistant-шаг (нет ни activity, ни текста, ни выполняемого тула) —
// модель явно работает (предикт/ранний tool), но молчит БЕЗ частей. Провайдер может
// держать такую паузу перед первым токеном/тулом достаточно долго (замер ~25-30с на
// big-pickle перед bash-диагностикой), поэтому таймаут НЕ ставим слишком маленьким,
// иначе помечаем «Нет ответа» при реально работающей модели. 90с = 1.5 мин паузы.
private const val STALL_EMPTY_MS = 90_000L

// Мёртвый ход: сервер принял сообщение, но ассистент не создал НИ ОДНОГО шага.
// Утверждение сильнее, чем «долго думает», поэтому и пород жёстче: сразу после
// отправки assistant-часть появляется не мгновенно (очередь у провайдера, холодный
// старт), и 90с тут давали бы ложное «не ответила» на живой сессии. 180с = 3 мин.
private const val STALL_DEAD_MS = 180_000L

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
    val fresh = LocalOpenCodeClient.get(port, "/mcp")
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
)

private object ChatCache {
    @Volatile var sessionId: String? = null

    @Volatile var rawHash: Int = 0

    @Volatile var result: ChatParseResult? = null
}

private data class PermissionCacheEntry(
    val sessionId: String,
    val raw: String,
    val request: OpenCodePermissionRequest?,
)

private object PermissionCache {
    private var entry: PermissionCacheEntry? = null
    private var generation = 0L

    fun get(
        port: Int,
        sessionId: String,
    ): OpenCodePermissionRequest? {
        val (cached, startedAt) =
            synchronized(this) {
                if (entry?.sessionId != sessionId) {
                    entry = null
                    generation++
                }
                entry to generation
            }
        val raw = LocalOpenCodeClient.get(port, "/permission")
        return synchronized(this) {
            // Reply/session switch happened while GET was in flight. Its response describes
            // already-mutated state and must not resurrect a permission card that was cleared.
            if (generation != startedAt) {
                null
            } else if (raw == null) {
                cached?.request
            } else if (cached?.raw == raw) {
                cached.request
            } else {
                val request = OpenCodePermissionApi.pendingForSession(raw, sessionId)
                entry = PermissionCacheEntry(sessionId, raw, request)
                request
            }
        }
    }

    fun clear(sessionId: String) {
        synchronized(this) {
            if (entry?.sessionId == sessionId) entry = null
            generation++
        }
    }

    fun clearAll() {
        synchronized(this) {
            entry = null
            generation++
        }
    }
}

internal data class ChatMsg(
    val role: String,
    val text: String,
)

internal data class ChatQuestion(
    val id: String,
    val text: String,
    val options: List<String>,
)

// Один вызов инструмента модели (tool) для live-чипа «что делает сейчас».
internal data class ChatTool(
    val name: String,
    val detail: String,
)

// Отдельный MCP-сервер: имя + статус ("connected" / "disconnected" / ...).
// tools — сколько инструментов отдаёт сервер; null, если это не наш локальный
// сервер памяти (или память не поднята) и посчитать нечем.
internal data class McpInfo(
    val name: String,
    val status: String,
    val tools: Int? = null,
)

internal data class ChatSnapshot(
    val messages: List<ChatMsg>,
    val label: String,
    val activeId: String?,
    val question: ChatQuestion? = null,
    val thinking: Boolean = false,
    val modelName: String = "Модель",
    val stalled: Boolean = false,
    val stalledDead: Boolean = false,
    val liveTool: ChatTool? = null,
    // Заполненность контекста (входные токены сессии). Контекст-лимит модели
    // (порог компакта) задаётся константой CONTEXT_LIMIT — берётся из модели.
    val contextTokens: Long = 0L,
    // Ответ сервера на последний запрос, мс. Показывается цифрами рядом со
    // статусом в шапке: «Online 42ms». Замер бесплатный — берётся вокруг запроса,
    // который и так делается каждый тик, отдельного запроса не добавляем.
    val pingMs: Long = 0L,
    // Израсходовано запросов Zen (opencode/big-pickle) за текущие UTC-сутки.
    // Считается из ленты — см. ZenQuota. Показывается полосой в шапке.
    // живёт в Compose-состоянии шапки. Дублировать его здесь означало бы держать
    // две копии одного числа, которые разъедутся при неудачном обходе сессий.
    // Отвечает ли сервер вообще. null — ещё ни разу не удалось достучаться, то
    // есть состояние неизвестно, и показывать «Offline» было бы враньём.
    val reachable: Boolean? = null,
    // MCP-серверы: (подключено, всего). Для индикатора «mcp N» в шапке — зелёный
    // если есть хотя бы один подключённый, красный если 0.
    val mcpConnected: Int = 0,
    val mcpTotal: Int = 0,
    // Полный список MCP-серверов (имя + статус) для выпадающего списка по тапу.
    val mcpServers: List<McpInfo> = emptyList(),
    val permission: OpenCodePermissionRequest? = null,
    // Причина, по которой сервер сам не может продолжить ход (квота/ретрай).
    val notice: ChatNotice? = null,
)

/**
 * Полноэкранный чат поверх WebView. WebView (SPA opencode 1.18.25) не рендерит ленту
 * в WebView-окружении — поэтому чат реализован здесь, нативно: опрос локального
 * сервера каждые 2с + отправка через POST /session/{id}/message.
 * Поле ввода внизу, лента наверху, клавиатура не перекрывает поле (imePadding).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ChatOverlay(
    modifier: Modifier = Modifier,
    serverPort: Int = 4096,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState = androidx.compose.runtime.remember { lifecycleOwner.lifecycle }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var snapshot by remember { mutableStateOf<ChatSnapshot?>(null) }
    // Защёлка на плашку причины. Retry/ошибка провайдера - УСТОЙЧИВОЕ состояние,
    // а не событие: сервер будет отдавать free_tier_limit и после любого TTL, пока
    // квота не восстановится. Поэтому держим плашку, пока идёт ход, и гасим только
    // когда opencode дошёл до успешного assistant-шага (thinking снят).
    var noticeLatch by remember { mutableStateOf<ChatNotice?>(null) }
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
    val modelFont = remember(modelFontKey) { fontFor(modelFontKey) }

    // Настройка цвета ответов модели: hex-строка без '#', меняется на лету через цветовой пикер.
    var modelColorHex by remember { mutableStateOf(prefs.getString("model_color", "D97706") ?: "D97706") }
    val modelColor = remember(modelColorHex) { parseHexColor(modelColorHex) }

    fun setModelColor(hex: String) {
        modelColorHex = hex
        prefs.edit().putString("model_color", hex).apply()
    }

    // ---- Настройки полос расхода (контекст и Zen-квота) ----
    // Читаются из того же prefs и живут в mutableStateOf, поэтому ползунок в
    // панели шестерёнки перерисовывает полосу в ту же секунду — без перезапуска.
    // В prefs пишем по завершении перетаскивания, а не на каждом кадре: запись
    // в файл на каждое движение ползунка — это лишний ввод-вывод в UI-потоке.
    var ctxShape by remember { mutableStateOf(prefs.readGaugeShape(CTX_PREFIX)) }
    var zenShape by remember { mutableStateOf(prefs.readGaugeShape(ZEN_PREFIX)) }
    var gaugePalette by remember { mutableStateOf(prefs.readGaugePalette()) }
    var gaugeBarGap by remember { mutableStateOf(prefs.readGaugeBarGap()) }

    fun persistCtxShape() = prefs.writeGaugeShape(CTX_PREFIX, ctxShape)

    fun persistZenShape() = prefs.writeGaugeShape(ZEN_PREFIX, zenShape)

    fun persistBarGap() = prefs.writeGaugeBarGap(gaugeBarGap)

    // Голосовой ввод: системный распознаватель (SpeechRecognizer). Удержание кнопки — запись, отпускание — распознавание и отправка.
    val speechRecognizer = remember { createSpeechRecognizer(context) }
    var listening by remember { mutableStateOf(false) }
    var speechError by remember { mutableStateOf<String?>(null) }
    var whisperBusy by remember { mutableStateOf(false) }
    // Настройки голоса: движок распознавания ("system" | "ncnn").
    // Миграция: движок whisper.cpp (ggml) убран из продукта — ранее сохранённый
    // "whisper" переключаем на ncnn (быстрый локальный путь по тестам).
    // Пишем prefs НЕ в композиции (UI-21: побочный эффект выполнялся бы на каждый
    // рекомпоз до первого обновления ключа) — один раз через LaunchedEffect.
    // Дефолт движка: явно сохранённая настройка выигрывает; иначе — ncnn, если
    // локальная модель готова (переустановка сбрасывает prefs: system-Google на
    // OPPO работает странно, а ncnn — единственный отлаженный путь).
    val defaultEngine =
        if (prefs.contains("stt_engine")) {
            prefs.getString("stt_engine", "system")!!
        } else if (NcnnModelValidator.checkTurbo(context).ok) {
            "ncnn"
        } else {
            "system"
        }
    var sttEngine by remember { mutableStateOf(defaultEngine) }
    // double-tap на "NCNN": показ подсказки о том, как работает двигатель (int8-энкодер и т.д.)
    var ncnnTipVisible by remember { mutableStateOf(false) }
    // Модель ncnn: "base" | "turbo" (выбор был в панели; ggml-модели убраны,
    // переключаться теперь негде). Дефолт — turbo: base-конверт даёт мусор
    // (петля декодера, никогда EOT), turbo-int8 — единственный рабочий путь.
    var sttModel by remember { mutableStateOf(prefs.getString("stt_model", "turbo") ?: "turbo") }
    LaunchedEffect(Unit) {
        if (sttEngine == "whisper") {
            prefs
                .edit()
                .putString("stt_engine", "ncnn")
                .putString("stt_model", "turbo")
                .apply()
            sttEngine = "ncnn"
            sttModel = "turbo"
        }
    }
    // Полноэкранная диагностика: состояние сервера, STT-модели, лог serve.
    // Оверлей рисуется ПОСЛЕДНИМ в корневом Surface — поверх чата и панелей.
    var showDiagnostics by remember { mutableStateOf(false) }

    // Озвучка ответов (§12.3/§12.5). Выключатель — здесь же, в панели STT.
    // Настройки живут в том же prefs "chat_overlay": tts_engine/tts_model/tts_sid.
    var ttsConfig by remember { mutableStateOf(TtsConfig.read(context)) }
    var ttsOn by remember { mutableStateOf(TtsConfig.read(context).isEnabled) }
    // Сколько user-реплик уже было озвучено: рост счётчика означает новый вопрос.
// -1 = «ещё не знаю, что было в ленте», первый снапшот только запомнит счётчик.
    var ttsSeenUserMsgs by remember { mutableIntStateOf(-1) }

    /**
     * Индекс assistant-сообщения, которое сейчас растёт и озвучивается.
     * -1 = в этом обороте ещё ничего не отдано нарратору.
     */
    var ttsSaidAssistants by remember { mutableIntStateOf(-1) }
    /** Сессия, для которой считается состояние озвучки выше. */
    var ttsSessionId by remember { mutableStateOf<String?>(null) }
    // Озвучка включается лишь после первого нового вопроса: иначе при запуске
    // приложение читает вслух последнее сообщение из истории.
    var ttsArmed by remember { mutableStateOf(false) }
    LaunchedEffect(ttsOn) {
        if (!ttsOn) TtsNarrator.stop()
    }
    // Панели шапки (список MCP, пикер цвета, пикер шрифта, настройки гейджа,
    // выбор STT) живут не здесь, а в activePanel выше — одним состоянием.
    // «Новая сессия» в полёте — иконка + подсвечивается зелёным (UI-19:
    // подсветка НЕ привязана к showMcpList — это два независимых состояния).
    var creatingSession by remember { mutableStateOf(false) }
    // Диалог подтверждения «очистить все сессии» (long-press на +).
    var confirmClearAll by remember { mutableStateOf(false) }

    // Подтверждение сброса зависшей сессии из DeadRow. Удаление необратимо,
    // поэтому отдельный диалог, а не кнопка в один тап.
    var confirmResetDead by remember { mutableStateOf(false) }
    var permissionRespondingId by remember { mutableStateOf<String?>(null) }
    var permissionError by remember { mutableStateOf<String?>(null) }
    var stopping by remember { mutableStateOf(false) }
    var whisperRecorder: AudioRecorder? = null
    val permissionActionBusy = permissionRespondingId != null || stopping

    // Активная выпадающая панель шапки; null — закрыты все. ОДНО состояние
    // вместо пяти флагов: панели взаимоисключающие СТРУКТУРНО, а не по
    // договорённости между обработчиками. Пять флагов требовали в каждом
    // клике перечислить четыре остальных, и договорённость уже дала баг:
    // Mic гасил Settings, а Settings не гасил Mic — обе панели открывались
    // одновременно и хедер вздваивался по высоте. Выводить признаки из одного
    // значения дешевле, чем каждый раз вспоминать остальные.
    var activePanel by remember { mutableStateOf<String?>(null) }

    fun toggleHeaderPanel(self: String) {
        activePanel = if (activePanel == self) null else self
    }

    // Закрытие панели по выбору внутри неё (например, применён шрифт).
    // Через сравнение, а не присваивание null: чужая панель закрываться не должна.
    fun closeHeaderPanel(self: String) {
        if (activePanel == self) activePanel = null
    }

    val showMcpList = activePanel == PANEL_MCP
    val showColorPicker = activePanel == PANEL_COLOR
    val showFontPicker = activePanel == PANEL_FONT
    val showSettings = activePanel == PANEL_SETTINGS
    val showSttSettings = activePanel == PANEL_STT

    fun answerQuestion(
        q: ChatQuestion,
        text: String,
    ) {
        val sessionId = snapshot?.activeId ?: return
        if (sending || snapshot?.permission != null) return
        sending = true
        userScrolledUp = false
        scope.launch {
            val ok =
                withContext(Dispatchers.IO) {
                    postAnswer(serverPort, sessionId, q.id, listOf(text))
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
        if (text.isEmpty() || sending || snapshot?.permission != null) return
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
            // Создание сессии идёт ВНУТРИ той же операции, что и отправка.
            // Раньше сессия создавалась, а отправка могла не уйти - и оставалась
            // сирота с одним session.created.1 и нулём сообщений (три такие
            // нашлись в базе при разборе). Молчание было худшей частью: у
            // пользователя сообщение выглядело отправленным, а текст оставался
            // в поле случайно.
            val (ok, createdHere) =
                withContext(Dispatchers.IO) {
                    if (sessionId != null) {
                        postMessage(serverPort, sessionId, text) to null
                    } else {
                        val fresh = createSession(serverPort)
                        when {
                            fresh == null -> false to null
                            postMessage(serverPort, fresh, text) -> true to null
                            else -> {
                                // postAsync отдаёт true сразу после записи тела, поэтому
                                // false означает «успешного ответа не было». Но сервер мог
                                // успеть сохранить сообщение и упасть уже после этого —
                                // проверено на живой сессии: неизвестная модель даёт
                                // HTTP 500, а user-сообщение уже лежит в базе. Стирать
                                // такую сессию нельзя, поэтому сначала спрашиваем сервер.
                                val saved = sessionHasMessages(serverPort, fresh)
                                if (!saved) deleteSession(serverPort, fresh)
                                false to fresh
                            }
                        }
                    }
                }
            sending = false
            if (ok) {
                draft = ""
                keyboard?.hide()
                focusManager.clearFocus()
                // Оптимистично: мгновенно показываем своё сообщение в ленте.
                snapshot = snapshot?.let { it.copy(messages = it.messages + ChatMsg("user", text)) }
                scrollToBottomFull(listState, (snapshot?.messages?.size ?: 0) - 1)
            } else {
                // Черновик намеренно остаётся: сообщение не ушло, повтор должен
                // быть возможен одним нажатием. Молчаливый отказ - это тот баг,
                // который чиним.
                val toast =
                    Toast.makeText(
                        context,
                        "Не отправилось: сообщение осталось в поле. Нажми ещё раз.",
                        Toast.LENGTH_LONG,
                    )
                toast.show()
                if (createdHere != null) {
                    android.util.Log.w("ChatOverlay", "откат новой сессии $createdHere после сбоя отправки")
                }
            }
        }
    }

    fun respondPermission(
        request: OpenCodePermissionRequest,
        decision: PermissionDecision,
    ) {
        if (permissionRespondingId != null || stopping) return
        permissionRespondingId = request.id
        permissionError = null
        scope.launch {
            val replied = withContext(Dispatchers.IO) {
                OpenCodePermissionApi.reply(serverPort, request.id, decision)
            }
            permissionRespondingId = null
            if (!replied) {
                permissionError = "Не удалось отправить ответ. Проверь сервер и повтори."
                return@launch
            }

            PermissionCache.clear(request.sessionId)
            snapshot =
                snapshot?.let { current ->
                    if (current.permission?.id == request.id) {
                        current.copy(permission = null, stalled = false)
                    } else {
                        current
                    }
                }
        }
    }

    fun stopGen() {
        if (permissionRespondingId != null || stopping) return
        val sessionId = snapshot?.activeId ?: return
        val pendingPermission = snapshot?.permission
        stopping = true
        scope.launch {
            val ok =
                try {
                    withContext(Dispatchers.IO) {
                        val rejected =
                            pendingPermission?.let {
                                OpenCodePermissionApi.reply(serverPort, it.id, PermissionDecision.REJECT)
                            } ?: false
                        val aborted = abortSession(serverPort, sessionId)
                        (pendingPermission != null && rejected) || aborted
                    }
                } finally {
                    stopping = false
                }
            if (ok) {
                PermissionCache.clear(sessionId)
                permissionError = null
                snapshot = snapshot?.copy(permission = null, stalled = false)
                vibrate(context)
            }
            // Стоп генерации = стоп озвучки: иначе голос продолжал бы читать
            // уже отменённый ответ (очередь синтеза опустошается, игра гасится).
            TtsNarrator.stop()
            // thinking сбросится сам на следующем поллинге: abort завершит
            // стрим, и fetchChatSnapshot увидит step-finish → thinking=false.
        }
    }

    // Восстановление после зависшего хода (DeadRow), дешёвый порядок:
    //  1) abort через сервер — останавливает генерацию, история остаётся целой;
    //  2) если abort не помог — удалить сессию и создать новую.
    // Порядок не переставлен специально: abort стоит копейки и сохраняет переписку,
    // а удаление необратимо и может снести 161 сообщение. Поэтому шаг 2 только
    // из явного диалога и только если шаг 1 не вывел нас из висящего состояния.
    fun recoverDeadTurn(sid: String) {
        scope.launch {
            val stopped =
                withContext(Dispatchers.IO) {
                    // Код ответа бесполезен: abort на несуществующей сессии отдаёт
                    // 200 + HTML (SPA-fallback на любой путь), то есть «успех» не
                    // значит ничего. Судим по результату: был ли ход в
                    // /session/status до abort и уехал ли после.
                    val before = sessionRunning(serverPort, sid)
                    abortSession(serverPort, sid)
                    abortResolvedTurn(before, sessionRunning(serverPort, sid))
                }
            ChatCache.result = null
            if (!stopped) {
                // abort не помог — сервер, видимо, не отвечает на эту сессию.
                // Оставляем пользователю выбор: без удаления он зависнет навсегда,
                // а delete без спроса — потерять переписку без предупреждения.
                confirmResetDead = true
            }
        }
    }

    // Шаг 2: сессия-зомби не отдаёт даже abort. Сносим её и создаём свежую.
    // Вызывается только из диалога подтверждения.
    fun resetDeadSession(sid: String) {
        creatingSession = true
        scope.launch {
            val ok =
                withContext(Dispatchers.IO) {
                    abortSession(serverPort, sid)
                    deleteSession(serverPort, sid) &&
                        createSession(serverPort)?.let {
                            ChatCache.sessionId = null
                            ChatCache.rawHash = 0
                            true
                        } ?: false
                }
            // Сбрасываем кэш в любом случае: если удаление прошло, поллинг обязан
            // перечитать список и уйти на новую сессию, иначе вернёт удалённую.
            ChatCache.result = null
            PermissionCache.clearAll()
            creatingSession = false
            if (ok) vibrate(context)
        }
    }

    // «Начать новую сессию»: создаём пустую свежую сессию и ПЕРЕКЛЮЧАЕМСЯ на неё
    // (старые сессии остаются в списке). Жёсткая очистка ВСЕХ сессий — отдельно,
    // clearAllSessions (long-press на +) с диалогом подтверждения (UI-20).
    fun newSession() {
        creatingSession = true
        scope.launch {
            val created =
                withContext(Dispatchers.IO) {
                    val id = createSession(serverPort) // POST /session → fresh id
                    if (id != null) {
                        // Сбрасываем кэш ленты ТОЛЬКО при успехе: при отказе createSession
                        // поллинг продолжит прежнюю ленту без лишнего форс-перезапроса.
                        ChatCache.sessionId = null
                        ChatCache.rawHash = 0
                        ChatCache.result = null
                        PermissionCache.clearAll()
                    }
                    id != null
                }
            creatingSession = false
            if (created) vibrate(context)
            // snapshot сбросим на ближайшем поллинге (fetchChatSnapshot перевыберет bestId).
            ChatCache.result = null
        }
    }

    // «Очистить все сессии»: создаём свежую сессию и удаляем ВСЕ остальные
    // (включая зависшие/пустые). Используется как жёсткий «чистый старт», когда
    // модель залипла на огромном контексте и abort не пробивает сервер.
    // Вызывается только из диалога подтверждения (long-press на +).
    fun clearAllSessions() {
        creatingSession = true
        scope.launch {
            val created =
                withContext(Dispatchers.IO) {
                    val id = createSession(serverPort) // POST /session → fresh id
                    if (id != null) {
                        // Сбрасываем кэш ленты ТОЛЬКО при успехе: при отказе createSession
                        // поллинг продолжит прежнюю ленту без лишнего форс-перезапроса.
                        ChatCache.sessionId = null
                        ChatCache.rawHash = 0
                        ChatCache.result = null
                        PermissionCache.clearAll()
                        // Удаляем ВСЕ остальные сессии — чистый старт. DELETE сам по себе не
                        // обязан останавливать бегущую генерацию: модель может продолжать
                        // писать ответ в сессию, которую мы удаляем (CPU горит впустую, сервер
                        // «залипает»). Поэтому каждую умирающую сессию сначала глушим abort-ом
                        // (идемпотентен, безвреден для пустых/404) — независимо от того, была
                        // ли она активной на момент сброса.
                        val raw = LocalOpenCodeClient.get(serverPort, "/session") ?: "[]"
                        try {
                            val arr = org.json.JSONArray(raw)
                            for (i in 0 until arr.length()) {
                                val sid = arr.getJSONObject(i).optString("id").takeIf(String::isNotBlank) ?: continue
                                if (sid != id) {
                                    abortSession(serverPort, sid)
                                    LocalOpenCodeClient.delete(serverPort, "/session/$sid")
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                    id != null
                }
            creatingSession = false
            if (created) vibrate(context)
            // snapshot сбросим на ближайшем поллинге (fetchChatSnapshot перевыберет bestId).
            ChatCache.result = null
        }
    }

    val voiceListener =
        remember {
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listening = true
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    listening = false
                    speechError =
                        when (error) {
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

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?,
                ) {}
            }
        }

    fun startVoice() {
        speechError = null
        Log.d("VOICE", "startVoice: engine=$sttEngine")
        if (sttEngine == "whisper" || sttEngine == "ncnn") {
            // Локальный движок: ncnn (CPU) или whisper.cpp (CPU) — запись PCM16 16кГц в буфер.
            try {
                val ar = AudioRecorder(context.applicationContext)
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
        val sr =
            speechRecognizer ?: run {
                speechError = "распознавание речи недоступно на устройстве"
                return
            }
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
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
            var samples =
                try {
                    ar.stop()
                } catch (e: Throwable) {
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
                } catch (_: Exception) {
                }
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
                // ЭКСП-5: сегментный пайплайн — VAD отбрасывает тишину/шум
                // (нет галлюцинаций «Продолжение следует…»), длинные клипы
                // (>30с) режутся на высказывания (нет потери хвоста у ncnn).
                Log.d("VOICE", "запускаю сегментное распознавание через foreground-сервис...")
                val text =
                    try {
                        org.opencode.mobile.stt.ChunkedTranscriber.transcribe(
                            context,
                            samples,
                            model = sttModel,
                            engine =
                                if (sttEngine == "ncnn") {
                                    WhisperTranscribeService.ENGINE_NCNN
                                } else {
                                    WhisperTranscribeService.ENGINE_WHISPER
                                },
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

    val micPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
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
        tts =
            TextToSpeech(context.applicationContext) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    Log.e("VOICE", "TTS-тест: движок недоступен")
                    ttsTestRunning = false
                    return@TextToSpeech
                }
                val file = File(context.cacheDir, "tts_test.wav")
                tts?.language = Locale("ru")
                tts?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onDone(id: String?) {
                            if (id != "tts_test") return
                            scope.launch {
                                val text =
                                    withContext(Dispatchers.IO) {
                                        try {
                                            val samples = readWavPcm16(file)
                                            Log.d("VOICE", "TTS-тест: ${samples.size} сэмплов → whisper ($sttModel)")
                                            WhisperTranscribeService.transcribe(
                                                context,
                                                samples,
                                                model = sttModel,
                                                engine =
                                                    if (sttEngine == "ncnn") {
                                                        WhisperTranscribeService.ENGINE_NCNN
                                                    } else {
                                                        WhisperTranscribeService.ENGINE_WHISPER
                                                    },
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
                    },
                )
                tts?.synthesizeToFile("Расскажи анекдот про цыгана.", null, file, "tts_test")
            }
    }

    // Автопрокрутка вниз: после отправки и при новом ответе/думании.
    // Если юзер сам листал вверх, автопрокрутка СТАРОЙ ленты не дёргает его,
    // НО когда приходит НОВОЕ сообщение (лента выросла) — принудительно
    // спускаемся к низу, чтобы последнее сообщение всегда было видно.
    LaunchedEffect(snapshot?.messages?.size, snapshot?.thinking, snapshot?.liveTool) {
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
        // Отдельный счётчик для «пустого» открытого assistant-шага (нет ни одного part:
        // text пуст). Такой шаг может появиться сразу после вопроса / пока websearch
        // собирается, и обычно быстро обрастает activity. Если он висит дольше
        // STALL_EMPTY_MS — случаи abort-lag / зависший tool: снимаем спиннер раньше
        // общего 120с, чтобы юзер не видел вечного «Модель думает» после Stop.
        var emptyStallSince = 0L
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
// Счётчик Zen обновляем отдельным циклом раз в минуту (см. LaunchedEffect ниже).
// Здесь только читаем кэш: поллинг чата идёт 2.5 раза/с, обход сессий туда не втыкаем.
            val snap = fetchChatSnapshot(serverPort)
            var changed = false
            if (snap != null) {
                val now = System.currentTimeMillis()
                val completed = snap.messages.count { it.role == "assistant" && it.text.isNotBlank() }
                val old = snapshot
                if (old?.permission?.id != snap.permission?.id) {
                    permissionError = null
                }
                val madeProgress =
                    old != null &&
                        (
                            old.messages != snap.messages ||
                                old.liveTool != snap.liveTool ||
                                old.question != snap.question ||
                                old.permission != snap.permission ||
                                old.notice != snap.notice
                        )
                if (madeProgress || snap.permission != null) {
                    stallSince = 0L
                    emptyStallSince = 0L
                }
                var deadStall = false
                val stalled =
                    if (!snap.thinking) {
                        stallSince = 0L
                        emptyStallSince = 0L
                        false
                    } else {
                        // Пуст ли последний открытый assistant-шаг (модель не дала ни part)?
                        val lastMsg = snap.messages.lastOrNull()
                        // Ход МЁРТВ, если последнее сообщение — наше собственное user'ское:
                        // ассистент не создал ни одного шага. Проверено на живой сессии —
                        // сервер принял запрос, отдал HTTP 500 и оставил висящий user-part,
                        // поэтому /session/status пуст и плашка причины тут не поможет.
                        val deadThinking = lastMsg?.role == "user"
                        // «Пустой» = открыт assistant-шаг, никакого текста И никакого выполняемого
                        // тула (liveTool == null). Если модель реально гоняет websearch/тул,
                        // liveTool не null → идём в общий таймаут 120с (тул может работать долго).
                        val emptyThinking =
                            !deadThinking &&
                                lastMsg != null &&
                                lastMsg.role == "assistant" &&
                                lastMsg.text.isBlank() &&
                                snap.liveTool == null
                        var el: Long
                        deadStall = deadThinking
                        if (deadThinking || emptyThinking) {
                            if (emptyStallSince == 0L) emptyStallSince = now
                            el = now - emptyStallSince
                            val limit = if (deadThinking) STALL_DEAD_MS else STALL_EMPTY_MS
                            android.util.Log.d("ChatOverlay", "STALL dead=$deadThinking since=${el}ms")
                            if (el >= limit) true else false
                        } else {
                            emptyStallSince = 0L
                            if (stallSince == 0L) stallSince = now
                            el = now - stallSince
                            android.util.Log.d("ChatOverlay", "STALL check thinking=true since=${el}ms")
                            if (el >= STALL_TIMEOUT_MS) true else false
                        }
                    }
                val final =
                    if (stalled) {
                        snap.copy(stalled = true, stalledDead = deadStall)
                    } else {
                        snap
                    }
                // Дельта-поллинг: ставим snapshot в UI только если содержимое реально
                // изменилось (messages + thinking + stalled одинаковы — пропускаем).
                // При частом поллинге это не даёт Compose реконсилить всю
                // ленту без необходимости, сохраняя рендер максимально дешёвым.
                // Защёлка: сервер может моргнуть статусом на один тик, а причина у
                // пользователя должна висеть, пока он её не закрыл. Гасим только на
                // успешном шаге модели (thinking снят) - это и есть «проблема решена».
                // Сравниваем latch ДО присваивания: сравнение после всегда даёт
                // false и молча пропускало бы перерисовку в UI.
                val latchBefore = noticeLatch
                noticeLatch = nextNoticeLatch(latchBefore, final.notice, turnFinished = !final.thinking)
                changed = old == null ||
                    old.messages != final.messages ||
                    old.thinking != final.thinking ||
                    old.stalled != final.stalled ||
                    old.stalledDead != final.stalledDead ||
                    old.liveTool != final.liveTool ||
                    old.question != final.question ||
                    old.permission != final.permission ||
                    old.notice != final.notice ||
                    latchBefore != noticeLatch
                if (changed) {
                    snapshot = final
                    // Озвучка: раскладываем растущий текст ответа на предложения.
                    // Новый вопрос юзера — жёсткий сброс: очередь чистим, игра гасится.
                    val userCount = final.messages.count { it.role == "user" }
                    // Смена сессии — жёсткая граница озвучки. Гасим речь и НЕ
                    // вооружаемся: у новой сессии своя история, читать её вслух
                    // без запроса пользователя нельзя. Озвучка включится на первом
                    // новом вопросе уже внутри неё.
                    if (ttsSessionId != final.activeId) {
                        if (ttsSessionId != null) {
                            TtsNarrator.stop()
                            TtsNarrator.onNewResponse()
                        }
                        ttsSessionId = final.activeId
                        ttsArmed = false
                        ttsSeenUserMsgs = userCount
                        ttsSaidAssistants = -1
                    } else if (ttsSeenUserMsgs < 0) {
                        // Первый снапшот после запуска: запоминаем, что уже было в ленте,
                        // и НЕ озвучиваем старое. Иначе приложение при запуске читает
                        // вслух последний ответ из истории.
                        ttsSeenUserMsgs = userCount
                    } else if (userCount != ttsSeenUserMsgs) {
                        ttsSeenUserMsgs = userCount
                        ttsArmed = true
                        ttsSaidAssistants = -1
                        TtsNarrator.stop()
                        TtsNarrator.onNewResponse()
                    } else if (!ttsArmed && final.thinking) {
                        // Смена сессии и новый вопрос могут прийти ОДНИМ снапшотом
                        // (пользователь успевает отправить сообщение до следующего тика
                        // поллинга). Тогда счётчик вопросов уже включает новый вопрос, и
                        // ветка выше его не видит — первый ответ молча пропал бы. Ориентир:
                        // у живой переписки thinking=true, у истории он снят.
                        ttsArmed = true
                        ttsSaidAssistants = -1
                    }
                    if (ttsOn && ttsArmed) {
                        // Пустой текст — это начало thinking или tool call, а не откат
                        // ответа. Если such отдать в нарратор, он примет снапшот короче
                        // уже озвученного, сбросит чанкер и погасит речь на каждом
                        // таком шаге (в логе 6 сбросов на 6 шагов).
                        //
                        // Берём ВСЕ assistant-сообщения, а не только последнее: агент
                        // часто пишет несколько предложений подряд (рассуждение ->
                        // tool call -> новый текст), и `lastOrNull` молча ронял
                        // предыдущие. Каждое новое сообщение доигрывает остаток
                        // предыдущего, а не гасит его.
                        //
                        // Последнее (растущее) сообщение отдаём нарратору на КАЖДОМ
                        // снапшоте — именно по нему считается дельта. Оно летит даже
                        // когда ничего не изменилось: пока thinking=true, isFinal=false
                        // и чанкер держит текст до конца предложения; без повторной
                        // передачи этот текст никогда не доигран (регрессия a2e85b9 —
                        // озвучка молчала целиком).
                        //
                        // Срез ТОЛЬКО текущего оборота: сообщения после последнего
                        // user-реплики. Фильтр по всей ленте читал вслух историю
                        // сессии заново на каждом новом вопросе, а при переключении
                        // сессий — прочитанное из чужой переписки.
                        val lastUserIdx = final.messages.indexOfLast { it.role == "user" }
                        val assistants = final.messages
                            .drop(lastUserIdx + 1)
                            .filter { it.role == "assistant" && it.text.isNotEmpty() }
                        if (assistants.isNotEmpty()) {
                            val lastIdx = assistants.size - 1
                            if (lastIdx > ttsSaidAssistants) {
                                // Сообщение на позиции ttsSaidAssistants — то, что уже
                                // пошло в озвучку и расти больше не будет: доигрываем его
                                // остаток. -1 означает «ещё ни одного не начато», тогда
                                // доигрывать нечего, а все новые сообщения (включая
                                // ПЕРВОЕ, индекс 0) озвучиваются целиком.
                                if (ttsSaidAssistants >= 0) TtsNarrator.onNewAssistantMessage()
                                for (i in maxOf(ttsSaidAssistants + 1, 0) until lastIdx) {
                                    TtsNarrator.onAssistantText(context, assistants[i].text, isFinal = true)
                                }
                                ttsSaidAssistants = lastIdx
                            }
                            TtsNarrator.onAssistantText(
                                context,
                                assistants.last().text,
                                isFinal = !final.thinking,
                            )
                        }
                    }
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

    // Отдельный цикл счётчика Zen-расхода. Живёт отдельно от поллинга чата, потому
    // что обход сессий стоит HTTP-запросов, а поллинг идёт 2.5 раза/с. Раз в минуту.
    // Пишем результат в Compose-состояние напрямую: refresh возвращает null при
    // неудаче, и тогда оставляем показанное значение — обнулять нельзя, иначе
    // шкала дёргалась бы вниз при каждом сетевом сбое.
    // Цифры счётчика Zen и признак их свежести. Пока точного замера не было ни
    // разу — показываем `--`, а не ноль: неизвестное не должно выглядеть как
    // «квоты нет».
    var zenUsedState by remember { mutableStateOf(0) }
    var zenExactState by remember { mutableStateOf(false) }
    LaunchedEffect(serverPort) {
        while (lifecycleState.currentState == Lifecycle.State.RESUMED) {
            val fresh = withContext(Dispatchers.IO) { ZenQuota.refreshAcrossSessions(serverPort, System.currentTimeMillis()) }
            if (fresh != null) {
                zenUsedState = fresh
            }
            // Сбрасываем признак свежести при любой неудаче: иначе после первого
            // успеха он навсегда застрянет в true и старый замер будет выдаваться за
            // текущий. Саму цифру не трогаем — она остаётся последней известной.
            zenExactState = fresh != null
            delay(ZenQuota.REFRESH_MINUTES * MS_PER_MINUTE)
        }
    }

    Surface(
        modifier =
            modifier
                .fillMaxSize()
                .imePadding()
                // Проставляет testTag как resource-id в дереве доступности.
                // Без этой строки Modifier.testTag() не виден в дампе
                // uiautomator, и геометрию панелей/полос нечем проверять
                // автоматом. Экспериментальное, но единственное, что даёт
                // проверяемую геометрию: contentDescription озвучивался бы
                // TalkBack на каждом сегменте, а testTag не произносится.
                .semantics { testTagsAsResourceId = true },
        color = Color(0xFF101010),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Статус связи с сервером и пинг: «Online 42ms» зелёным, «Offline»
                // красным. Отдельная от ContextGauge строка — индикатор занятости
                // контекста уехал ниже, чтобы шапка не расползалась: раньше круг
                // занимал всю свободную ширину и отжимал индикаторы вправо.
                QuotaBadge(
                    reachable = snapshot?.reachable,
                    pingMs = snapshot?.pingMs ?: 0L,
                    rejected = snapshot?.notice != null,
                    modifier = Modifier.weight(1f),
                )
                // Индикатор MCP-серверов (НЕОНОВЫЙ): «N MCP» + мигающая точка.
                // Зелёный — все N подключённых серверов работают; красный — какой-то
                // из них не работает (или их нет вовсе). Стоит ЛЕВЕЕ выбора цвета.
                MCPIndicator(
                    connected = snapshot?.mcpConnected ?: 0,
                    total = snapshot?.mcpTotal ?: 0,
                    onClick = { toggleHeaderPanel(PANEL_MCP) },
                    modifier = Modifier.padding(start = 4.dp),
                )
                // Цветовой пикер для ответов модели. ИКОНКА — готовая «капля»
                // (Material Icons: Icons.Filled.InvertColors) — узнаваемая капля,
                // тонируется ТЕКУЩИМ выбранным цветом ответов (modelColor).
                Icon(
                    imageVector = Icons.Filled.InvertColors,
                    contentDescription = "Цвет ответов модели",
                    tint = modelColor,
                    modifier =
                        Modifier
                            .padding(start = 4.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (showColorPicker) Color(0xFF3A3A3A) else Color.Transparent)
                            .clickable { toggleHeaderPanel(PANEL_COLOR) }
                            .padding(3.dp),
                )
                Icon(
                    imageVector = Icons.Filled.TextFormat,
                    contentDescription = "Шрифт ответов модели",
                    tint = if (showFontPicker) modelColor else Color(0xFF8A8A8A),
                    modifier =
                        Modifier
                            .padding(start = 4.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (showFontPicker) Color(0xFF3A3A3A) else Color.Transparent)
                            .clickable { toggleHeaderPanel(PANEL_FONT) }
                            .padding(3.dp),
                )
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Настройки гейджа",
                    tint = if (showSettings) Color.White else Color(0xFFBDBDBD),
                    modifier =
                        Modifier
                            .padding(start = 4.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (showSettings) Color(0xFF3A3A3A) else Color.Transparent)
                            .clickable { toggleHeaderPanel(PANEL_SETTINGS) }
                            .padding(3.dp),
                )
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = "Настройки голосового ввода (STT)",
                    tint = if (showSttSettings) Color.White else Color(0xFFBDBDBD),
                    modifier =
                        Modifier
                            .padding(start = 4.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (showSttSettings) Color(0xFF3A3A3A) else Color.Transparent)
                            .clickable { toggleHeaderPanel(PANEL_STT) }
                            .padding(3.dp),
                )
                // Диагностика: состояние сервера, STT-модели и хвост лога serve.
                // Полноэкранный оверлей (DiagnosticsScreen), рисуется поверх всего.
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = "Диагностика",
                    tint = if (showDiagnostics) Color.White else Color(0xFFBDBDBD),
                    modifier =
                        Modifier
                            .padding(start = 4.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (showDiagnostics) Color(0xFF3A3A3A) else Color.Transparent)
                            .clickable {
                                // Прячем клавиатуру/фокус: корневой Surface чата сдвинут
                                // imePadding(), иначе полноэкранный оверлей «съехал» бы вверх,
                                // оставив полосу чата под клавиатурой.
                                keyboard?.hide()
                                focusManager.clearFocus()
                                showDiagnostics = !showDiagnostics
                            }.padding(3.dp),
                )
                // «Новая сессия»: тап — создать свежую (старые остаются в списке);
                // long-press — «очистить все сессии» (диалог подтверждения).
                // Подсветка по creatingSession (UI-19), а не showMcpList.
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Новая сессия (long-press — очистить все сессии)",
                    tint = if (creatingSession) Color(0xFF7BD88F) else Color(0xFFBDBDBD),
                    modifier =
                        Modifier
                            .padding(start = 4.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .combinedClickable(
                                onClick = { newSession() },
                                onLongClick = { confirmClearAll = true },
                            ).padding(3.dp),
                )
            }
            // Индикатор заполнения контекста — своей строкой ПОД шапкой со статусом.
            // Показывает, сколько накоплено входных токенов сессии относительно
            // лимита модели (CONTEXT_LIMIT). Зелёный → жёлтый → красный по мере
            // приближения к компакту; внутри — процент заполнения.
            Row(verticalAlignment = Alignment.CenterVertically) {
                ContextGauge(
                    filled = snapshot?.contextTokens ?: 0L,
                    limit = CONTEXT_LIMIT,
                    shape = ctxShape,
                    palette = gaugePalette,
                    modifier = Modifier.weight(1f).testTag(TAG_CTX_GAUGE),
                )
            }
            // Расход дневной Zen-квоты — ПОД индикатором контекста, отдельной
            // строкой и в его же стиле. В шапку он больше не влезает: там ряд
            // иконок, и любая полоса ломает выравнивание по центру. Зазор
            // настраивается пользователем, чтобы полосы читались как блок, но
            // не слипались.
            ZenMeter(
                used = zenUsedState,
                exact = zenExactState,
                shape = zenShape,
                palette = gaugePalette,
                modifier = Modifier.padding(top = gaugeBarGap.dp).testTag(TAG_ZEN_METER),
            )
            // Выпадающий список подключённых MCP-серверов (тап по индикатору «N MCP»).
            // У каждого имени — мигающая точка: зелёная (работает) / красная (нет).
            if (showMcpList) {
                McpServerList(
                    servers = snapshot?.mcpServers ?: emptyList(),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 2.dp)
                            .testTag(TAG_PANEL_MCP),
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
                        .testTag(TAG_PANEL_COLOR)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
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
                            }.pointerInput(Unit) {
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
                                    },
                                )
                            },
                    ) {
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFFFF0000),
                                    Color(0xFFFFFF00),
                                    Color(0xFF00FF00),
                                    Color(0xFF00FFFF),
                                    Color(0xFF0000FF),
                                    Color(0xFFFF00FF),
                                    Color(0xFFFF0000),
                                ),
                                startX = 0f,
                                endX = size.width,
                            ),
                        )
                        drawRect(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0f), Color.Black),
                                startY = 0f,
                                endY = size.height,
                            ),
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
                                .border(1.dp, Color.White, CircleShape),
                        )
                        Text("#$modelColorHex", color = Color(0xFFBDBDBD), fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
            // Панель настроек: движок голосового распознавания (system | ncnn).
            if (showSettings) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp)
                        .background(Color(0xFF1C1C1C), RoundedCornerShape(12.dp))
                        .testTag(TAG_PANEL_SETTINGS)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    GaugeSettings(
                        ctxShape = ctxShape,
                        onCtxShapeChange = { ctxShape = it },
                        onCtxShapePersist = { persistCtxShape() },
                        zenShape = zenShape,
                        onZenShapeChange = { zenShape = it },
                        onZenShapePersist = { persistZenShape() },
                        palette = gaugePalette,
                        onColorChange = { k, c ->
                            gaugePalette = gaugePalette.copyWith(k, c)
                            prefs.writeGaugeColor(k, c)
                        },
                        onPalettePersist = { /* цвета уже записаны по клику */ },
                        barGap = gaugeBarGap,
                        onBarGapChange = { gaugeBarGap = it },
                        onBarGapPersist = { persistBarGap() },
                        onResetDefaults = {
                            ctxShape = GaugeShape.DEFAULT
                            zenShape = GaugeShape.DEFAULT
                            gaugePalette = GaugePalette.DEFAULT
                            gaugeBarGap = GaugeBarGap.DEFAULT
                            persistCtxShape()
                            persistZenShape()
                            persistBarGap()
                            GaugeColorKey.entries.forEach { prefs.writeGaugeColor(it, it.argb(GaugePalette.DEFAULT)) }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (ttsTestRunning) "TTS-тест: синтезирую и распознаю…" else "Диагностика: синтез → распознавание (см. лог VOICE)",
                        color = Color(0xFF5A8DEE),
                        fontSize = 11.sp,
                        modifier =
                            Modifier
                                .padding(top = 6.dp)
                                .clickable(enabled = !ttsTestRunning) { runTtsTest() },
                    )
                }
            }
            // Панель выбора голосового движка (system | ncnn). Отдельная от showSettings:
            // GaugeSettings отвечает только за полосы гейджа. Открывается иконкой Mic в хедере.
            if (showSttSettings) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp)
                        .background(Color(0xFF1C1C1C), RoundedCornerShape(12.dp))
                        .testTag(TAG_PANEL_STT)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("Голосовое распознавание:", color = Color(0xFFBDBDBD), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    sttEngine = "system"
                                    prefs.edit().putString("stt_engine", "system").apply()
                                }.padding(vertical = 6.dp),
                    ) {
                        Text(
                            if (sttEngine ==
                                "system"
                            ) {
                                "● "
                            } else {
                                "○ "
                            },
                            color = Color(0xFFFF6D00),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("Системный Android (Google)", color = Color(0xFFE6E6E6), fontSize = 13.sp)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    enabled = true,
                                    onClick = {
                                        sttEngine = "ncnn"
                                        // Переключение на ncnn всегда подразумевает turbo (base битый)
                                        prefs
                                            .edit()
                                            .putString("stt_engine", "ncnn")
                                            .putString("stt_model", "turbo")
                                            .apply()
                                    },
                                    onDoubleClick = { ncnnTipVisible = !ncnnTipVisible },
                                ).padding(vertical = 6.dp),
                    ) {
                        Text(
                            if (sttEngine ==
                                "ncnn"
                            ) {
                                "● "
                            } else {
                                "○ "
                            },
                            color = Color(0xFFFF6D00),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("NCNN (CPU encoder int8)", color = Color(0xFFE6E6E6), fontSize = 13.sp)
                    }
                    if (ncnnTipVisible) {
                        Text(
                            "Как работает: int8-энкодер (блочная квантование, ~2× быстрее fp32) → fp16/32-декодер с KV-cache (шаг по токену ~64мс).",
                            color = Color(0xFF90A4AE),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 4.dp),
                        )
                    }
                }
            }
            // Панель озвучки. Открывается там же, где STT, и живёт по тем же правилам:
            // настройка в prefs, переключение — сразу, без рестарта чата.
            if (showSttSettings) {
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
                        ttsOn = !ttsOn
                        TtsNarrator.setEnabled(context, ttsOn)
                        if (ttsOn) ttsConfig = TtsConfig.read(context)
                    }
                    if (!ttsOn) {
                        Text(
                            if (TtsConfig.read(context).modelInstalled(context)) {
                                "Supertonic offline: модель на месте, 2 потока, sid ${ttsConfig.sid}"
                            } else {
                                "Модель Supertonic не скачана — пока доступен только системный голос"
                            },
                            color = Color(0xFF90A4AE),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(start = 20.dp, top = 2.dp),
                        )
                    }
                    if (ttsOn) {
                        TtsRadioRow("Supertonic (offline, русский)", ttsConfig.engine == TtsEngine.sherpa) {
                            ttsConfig = ttsConfig.copy(engine = TtsEngine.sherpa)
                            TtsConfig.save(context, ttsConfig)
                            TtsNarrator.stop()
                        }
                        TtsRadioRow("Системный Android", ttsConfig.engine == TtsEngine.system) {
                            ttsConfig = ttsConfig.copy(engine = TtsEngine.system)
                            TtsConfig.save(context, ttsConfig)
                            TtsNarrator.stop()
                        }
                        Text(
                            "Голос:",
                            color = Color(0xFF8A8A8A),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 20.dp, top = 4.dp),
                        )
                        /** Голоса в две строки по пять: на шапке телефона одна строка уже не помещалась. */
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            (0 until TtsConfig.MAX_SID + 1).chunked(5).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    row.forEach { sid ->
                                        val active = ttsConfig.sid == sid
                                        Text(
                                            "${sid + 1}",
                                            color = if (active) Color(0xFF1C1C1C) else Color(0xFFE6E6E6),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (active) Color(0xFFBDBDBD) else Color(0xFF2A2A2A))
                                                .clickable {
                                                    ttsConfig = ttsConfig.copy(sid = sid)
                                                    TtsConfig.save(context, ttsConfig)
                                                    TtsNarrator.stop()
                                                }.padding(horizontal = 12.dp, vertical = 5.dp),
                                        )
                                    }
                                }
                            }
                        }
                        // Диагностика озвучки: дамп сырого PCM в filesDir/tts-dump.pcm
                        // (float32, без заголовка). По нему видно длительность и паузы
                        // каждого фрагмента — то, чего не показывает лог. Нужен, чтобы
                        // проверять озвучку без возможности послушать.
                        TtsRadioRow("Дамп PCM (диагностика)", ttsConfig.dumpPcm) {
                            ttsConfig = ttsConfig.copy(dumpPcm = !ttsConfig.dumpPcm)
                            TtsConfig.save(context, ttsConfig)
                            // Применяем к живой озвучке сразу: dumpOut переживает смену
                            // голоса и движка, иначе файл писался бы до перезапуска.
                            TtsNarrator.applyDumpPref(context)
                            TtsNarrator.stop()
                        }
                        if (ttsConfig.dumpPcm) {
                            Text(
                                "Пишет files/tts-dump.pcm. Забрать: adb exec-out run-as " +
                                    "org.opencode.mobile.debug cat files/tts-dump.pcm > dump.pcm",
                                color = Color(0xFF90A4AE),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(start = 20.dp, top = 2.dp),
                            )
                        }
                    }
                }
            }
            val msgs = snapshot?.messages ?: emptyList()
            LaunchedEffect(msgs.size, snapshot?.thinking, snapshot?.liveTool) {
                android.util.Log.d(
                    "ChatOverlay",
                    "RENDER msgs=${msgs.size} thinking=${snapshot?.thinking} live=${snapshot?.liveTool?.name}",
                )
            }
            // Палитра шрифтов (настройка): тап по варианту — мгновенно применяется и сохраняется.
            if (showFontPicker) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp)
                        .background(Color(0xFF1C1C1C), RoundedCornerShape(12.dp))
                        .testTag(TAG_PANEL_FONT)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("Шрифт ответов модели (тап — применить):", color = Color(0xFF8A8A8A), fontSize = 11.sp)
                    fontEntries.forEach { (key, name, ff) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        modelFontKey = key
                                        prefs.edit().putString("model_font_key", key).apply()
                                        closeHeaderPanel(PANEL_FONT)
                                    }.padding(vertical = 6.dp),
                        ) {
                            Text(
                                if (key == modelFontKey) "● " else "○ ",
                                color = modelColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                name,
                                color = Color(0xFF9E9E9E),
                                fontSize = 10.sp,
                                modifier = Modifier.width(88.dp),
                            )
                            Text(
                                "Привет, я модель!",
                                color = modelColor,
                                fontFamily = ff,
                                fontSize = 16.sp,
                                maxLines = 1,
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
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
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
                            when {
                                snap.stalledDead ->
                                    DeadRow(
                                        onAbort = { snap.activeId?.let(::recoverDeadTurn) },
                                        onReset = { snap.activeId?.let { confirmResetDead = true } },
                                    )
                                snap.stalled -> StalledRow()
                                else -> ThinkingRow()
                            }
                        }
                        // Живой чип «какой тул выполняет модель» — поверх индикатора думания.
                        snap.liveTool?.let { t ->
                            item(key = "livetool_${t.name}_${t.detail.hashCode()}") {
                                LiveToolRow(t)
                            }
                        }
                    }
                    // Причина отказа сервера (квота/ретрай) — всегда последняя строка,
                    // чтобы её не съедал автоскролл вниз и она читалась как вывод.
                    // Рендерим защёлку: плашка должна висеть, пока сервер держит
                    // ту же причину, даже если конкретный тик опроса её не увидел.
                    (noticeLatch ?: snapshot?.notice)?.let { n ->
                        item(key = "notice_${n.title}_${n.attempt}") {
                            NoticeRow(n, context)
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
                        .padding(10.dp),
                ) {
                    Text(
                        "Модель спрашивает:",
                        color = Color(0xFF7BD88F),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        q.text.ifBlank { "…" },
                        color = Color(0xFFEDEDED),
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (q.options.isEmpty()) {
                        Text(
                            "Напиши ответ в поле и нажми →",
                            color = Color(0xFF8A8A8A),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    } else {
                        q.options.forEach { label ->
                            Surface(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp)
                                        .clickable { answerQuestion(q, label) },
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF24401F),
                            ) {
                                Text(
                                    label,
                                    color = Color(0xFFE6E6E6),
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                )
                            }
                        }
                        Text(
                            "…или напиши свой ответ в поле ↓",
                            color = Color(0xFF8A8A8A),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            snapshot?.permission?.let { request ->
                PermissionCard(
                    request = request,
                    responding = permissionActionBusy,
                    error = permissionError,
                    onDecision = { decision -> respondPermission(request, decision) },
                )
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
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                val permissionBlocked = snapshot?.permission != null
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier =
                        Modifier
                            .weight(1f)
                            .background(Color(0xFF1C1C1C), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    textStyle = TextStyle(color = Color(0xFFF0F0F0), fontSize = 15.sp),
                    cursorBrush = SolidColor(Color(0xFF7BA6F8)),
                    maxLines = 4,
                    enabled = !permissionBlocked,
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
                                    if (permissionBlocked) {
                                        "Сначала ответь на запрос разрешения"
                                    } else {
                                        "Напиши сообщение…"
                                    },
                                    color = Color(0xFF777777),
                                    fontSize = 15.sp,
                                )
                            }
                            inner()
                        }
                    },
                )
                Surface(
                    modifier =
                        Modifier
                            .padding(start = 8.dp)
                            .size(46.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                                            PackageManager.PERMISSION_GRANTED
                                        ) {
                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        } else {
                                            startVoice()
                                        }
                                        try {
                                            awaitRelease()
                                        } finally {
                                            stopVoice()
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
                        modifier = Modifier.size(26.dp),
                    )
                }
                // Кнопка Stop: ВСЕГДА видна рядом с микрофоном.
                // Прерывает текущую генерацию модели (POST /session/{id}/abort).
                // Если модель не думает — abort просто не сработает, сессия не сломается.
                Surface(
                    modifier =
                        Modifier
                            .padding(start = 8.dp)
                            .size(46.dp)
                            .clickable(enabled = !permissionActionBusy) { stopGen() },
                    shape = CircleShape,
                    color = if (permissionActionBusy) Color(0xFF3A3A3A) else Color(0xFF9E1C1C),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "Прервать генерацию",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Surface(
                    modifier =
                        Modifier
                            .padding(start = 8.dp)
                            .size(46.dp)
                            .clickable(enabled = !permissionBlocked) { send() },
                    shape = CircleShape,
                    color = if (sending || permissionBlocked) Color(0xFF3A3A3A) else Color(0xFF2E5E8E),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Отправить сообщение",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        // Экран диагностики — ПОСЛЕДНИМ в корневом Surface (поверх чата и панелей):
        // непрозрачный, полноэкранный, layout-алгоритм Surface-контейнера (Box)
        // кладёт его ПОВЕРХ Column чата даже без явного выравнивания.
        if (showDiagnostics) {
            DiagnosticsScreen(
                onClose = { showDiagnostics = false },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Подтверждение жёсткой очистки всех сессий (long-press на +): удаление
        // необратимое (включая активную сессию) — только через явный диалог.
        if (confirmClearAll) {
            AlertDialog(
                onDismissRequest = { confirmClearAll = false },
                containerColor = Color(0xFF1C1C1C),
                titleContentColor = Color(0xFFE6E6E6),
                textContentColor = Color(0xFFBDBDBD),
                title = { Text("Очистить все сессии?") },
                text = { Text("Будут удалены ВСЕ сессии, включая активную. История чата пропадёт безвозвратно.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClearAll = false
                        clearAllSessions()
                    }) { Text("Очистить", color = Color(0xFFFF6F5A)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClearAll = false }) { Text("Отмена", color = Color(0xFFBDBDBD)) }
                },
            )
        }

        // Подтверждение сброса зависшей сессии (шаг 2 в recoverDeadTurn).
        // Сессию-зомби не спасает даже abort, но в ней может быть вся переписка,
        // поэтому удаление — только через явный диалог.
        if (confirmResetDead) {
            AlertDialog(
                onDismissRequest = { confirmResetDead = false },
                containerColor = Color(0xFF1C1C1C),
                titleContentColor = Color(0xFFE6E6E6),
                textContentColor = Color(0xFFBDBDBD),
                title = { Text("Удалить зависшую сессию?") },
                text = {
                    Text(
                        "Сессия не отвечает даже на прерывание. Она будет удалена " +
                            "безвозвратно вместе с историей, и откроется новая пустая. " +
                            "Если переписка нужна — сначала попробуй «Прервать ход».",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmResetDead = false
                        snapshot?.activeId?.let(::resetDeadSession)
                    }) { Text("Удалить и начать заново", color = Color(0xFFFF6F5A)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmResetDead = false }) {
                        Text("Отмена", color = Color(0xFFBDBDBD))
                    }
                },
            )
        }
    }
}

@Composable
private fun ThinkingRow() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val bars =
        listOf(
            transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(380, delayMillis = 0), RepeatMode.Reverse),
                label = "bar0",
            ),
            transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(380, delayMillis = 130), RepeatMode.Reverse),
                label = "bar1",
            ),
            transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(380, delayMillis = 260), RepeatMode.Reverse),
                label = "bar2",
            ),
        )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        bars.forEach { h ->
            Box(
                Modifier
                    .padding(horizontal = 1.5.dp)
                    .size(width = 5.dp, height = 16.dp * h.value)
                    .background(Color(0xFF7BD88F), RoundedCornerShape(2.dp)),
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
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(460, delayMillis = 0), RepeatMode.Reverse),
        label = "pulse",
    )
    // Иконка по типу инструмента.
    val (icon, accent) =
        when (tool.name) {
            "websearch", "webfetch", "context7" -> "🔍" to Color(0xFF5B9BD5)
            "bash", "shell" -> "🛠" to Color(0xFFD97706)
            "read", "grep", "glob" -> "📄" to Color(0xFF7BD88F)
            "write", "edit" -> "✏️" to Color(0xFFB48AD9)
            else -> "⚙️" to Color(0xFF9AA5B1)
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .padding(vertical = 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = 0.12f * pulse))
                .border(width = 1.dp, color = accent.copy(alpha = 0.5f), shape = RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        // Пульсирующая точка «активно».
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer { alpha = pulse }
                .background(accent, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$icon ${tool.name}",
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        if (tool.detail.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(
                tool.detail,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Suppress("FunctionNaming", "LongMethod", "MagicNumber")
@Composable
private fun PermissionCard(
    request: OpenCodePermissionRequest,
    responding: Boolean,
    error: String?,
    onDecision: (PermissionDecision) -> Unit,
) {
    val accent = Color(0xFFF2A93B)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF241D12))
                .border(1.dp, accent.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            "Нужно разрешение",
            color = accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "OpenCode ждёт решения перед выполнением инструмента.",
            color = Color(0xFFE8DCC8),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (request.command.isNotBlank()) {
            Text(
                request.command,
                color = Color(0xFFF2E8D8),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            "Инструмент: ${request.permission}",
            color = Color(0xFFB9AA91),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 8.dp),
        )
        request.patterns.forEach { pattern ->
            Text(
                pattern,
                color = Color(0xFFF2E8D8),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (request.alwaysPatterns.isNotEmpty()) {
            Text(
                "Всегда разрешит: ${request.alwaysPatterns.joinToString(" · ")}",
                color = Color(0xFF9FB89A),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (error != null) {
            Text(
                error,
                color = Color(0xFFFF8A75),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = { onDecision(PermissionDecision.REJECT) },
                enabled = !responding,
                modifier = Modifier.weight(1f),
            ) {
                Text("Отклонить", color = Color(0xFFFF8A75), fontSize = 12.sp)
            }
            TextButton(
                onClick = { onDecision(PermissionDecision.ALLOW_ONCE) },
                enabled = !responding,
                modifier = Modifier.weight(1f),
            ) {
                Text("Разрешить", color = Color(0xFF9BD4A2), fontSize = 12.sp)
            }
            if (request.alwaysPatterns.isNotEmpty()) {
                TextButton(
                    onClick = { onDecision(PermissionDecision.ALLOW_ALWAYS) },
                    enabled = !responding,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Всегда", color = accent, fontSize = 12.sp)
                }
            }
        }
        if (responding) {
            Text(
                "Отправляю ответ…",
                color = Color(0xFF9E9E9E),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Палитра плашки причины отказа: тёмно-коричневый фон (не красный — это не
 * ошибка приложения, а отказ провайдера), янтарный акцент и приглушённый вторичный.
 */
@Suppress("MagicNumber")
private object NoticePalette {
    val bg = Color(0xFF2A1F1A)
    val accent = Color(0xFFFFC107)
    val dim = Color(0xFF9A8C7A)
}

/**
 * Плашка причины, по которой сервер не двигает ход (исчерпанная квота провайдера,
 * ретрай после ошибки). Показывается вместо безликого «зависло»: у пользователя
 * должно быть конкретное действие, а не гипотеза про сеть.
 */
@Composable
private fun NoticeRow(
    notice: ChatNotice,
    context: Context,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(NoticePalette.bg, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(NoticePalette.accent, RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                notice.title,
                color = NoticePalette.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            if (notice.attempt > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "попытка ${notice.attempt}",
                    color = NoticePalette.dim,
                    fontSize = 11.sp,
                )
            }
        }
        Text(
            notice.message,
            color = Color(0xFFEDEDED),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        val link = notice.actionLink
        val label = notice.actionLabel
        if (!link.isNullOrBlank() && !label.isNullOrBlank()) {
            Text(
                label,
                color = Color(0xFF7BD88F),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier =
                    Modifier
                        .padding(top = 6.dp)
                        .clickable {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                            } catch (_: Exception) {
                                // Без браузера — просто молча оставляем текст.
                            }
                        },
            )
        }
    }
}

@Composable
private fun StalledRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(Color(0xFFE25822), RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Нет ответа (зависло) — проверь сеть/провайдера",
            color = Color(0xFFE25822),
            fontSize = 13.sp,
        )
    }
}

/**
 * Плашка МЁРТВОГО хода: сервер принял запрос, но ассистент не создал ни одного
 * шага — обычно он уже ответил ошибкой (неизвестная модель, 500 провайдера) или
 * списал квоту в ретрае. Отличать от «долго думает» принципиально: там модель
 * работает и надо ждать, тут она не вернётся сама и ждать бессмысленно.
 *
 * Само по себе «текст + иди жди» — это тупик: ход не завершится никогда, и
 * единственный выход (сменить сессию) надо ещё и догадаться. Поэтому здесь два
 * действия, и они неравнозначны по цене:
 *   [onAbort] — «прервать ход»: abort через сервер, история остаётся целой.
 *   [onReset] — «начать заново»: удалить сессию-зомби и создать новую.
 * Удаление необратимо, поэтому второй путь открывается только через диалог
 * подтверждения (см. resetDeadSession).
 */
@Composable
private fun DeadRow(
    onAbort: () -> Unit,
    onReset: () -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(NoticePalette.accent, RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Модель не ответила — запрос не прошёл",
                color = NoticePalette.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            "Сервер принял сообщение, но шага ответа не создал. Проверь квоту " +
                "провайдера и отправь сообщение ещё раз.",
            color = Color(0xFFEDEDED),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 2.dp),
        )

        // Два выхода из тупика, цена разная и это видно по цвету: зелёный —
        // бесплатный (abort), красный — необратимый (удаление сессии).
        Row(
            Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Прервать ход",
                color = Color(0xFF7BD88F),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onAbort),
            )
            Text(
                "·",
                color = NoticePalette.dim,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text(
                "Начать заново",
                color = Color(0xFFFF6F5A),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onReset),
            )
        }
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

/**
 * Статус связи с сервером + пинг: «Online 42ms» зелёным, «Offline» красным.
 *
 * Что именно показывает. Сервер opencode НЕ умеет отдавать остаток квоты — в его
 * API нет ни одного эндпоинта про usage/limits/billing (проверено по схеме /doc).
 * Поэтому честный сигнал здесь один: прошёл ли последний запрос и что на него
 * ответили. «Online» = сервер ответил и не отказал в квоте; «Offline» = либо не
 * ответил вовсе, либо ответил отказом (reason/message провайдера — квота или ретрай).
 *
 * Почему `rejected` отдельным флагом, а не через `reachable`: отказ по квоте — это
 * УСПЕШНЫй HTTP-ответ с телом про отказ. Сервер доступен, но квоты нет — и по одному
 * факту «досстучались» это не отличить от успеха. Без отдельного флага плашка горела
 * бы «Online» ровно тогда, когда модель уже не отвечает.
 *
 * @param reachable null → состояние неизвестно (ни разу не достучались). Показываем
 *   серый «Online?» вместо красного «Offline»: врать об отказе хуже, чем не знать.
 * @param rejected сервер ответил отказом по квоте/ретраю (текст — в плашке под лентой).
 * @param pingMs задержка последнего запроса, мс. Показываем только когда есть чему
 *   верить: при reachable == null замер обрывается и цифра бессмысленна.
 */
@Composable
private fun QuotaBadge(
    reachable: Boolean?,
    pingMs: Long,
    rejected: Boolean,
    modifier: Modifier = Modifier,
) {
    val offline = reachable == false || rejected
    val unknown = reachable == null
    val color =
        when {
            offline -> Color(0xFFE53935)
            unknown -> Color(0xFF9E9E9E)
            else -> Color(0xFF7BD88F)
        }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = if (offline) "Offline" else "Online",
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            // ОДНА строка любой ценой: бейдж живёт в weight(1f)-слоте шапки, и при
            // нехватке ширины Compose переносил «Online 69ms» на вторую строку —
            // хедер вздваивался по высоте. Лучше обрезать хвост, чем ломать вёрстку.
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
        // Пинг цифрами — только при подтверждённом контакте. На неизвестном
        // состоянии показывать «—ms» значило бы рисовать число, которого нет.
        val pingSuffix =
            when {
                reachable == null -> ""
                else -> " ${pingMs}ms"
            }
        if (pingSuffix.isNotEmpty()) {
            Text(
                text = pingSuffix,
                color = Color(0xFF8A8A8A),
                fontSize = 11.sp,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/**
 * Расход дневной Zen-квоты — та же полоса-кубики, что индикатор контекста,
 * строкой ниже него.
 *
 * Ровно та же функция [GaugeBars], тот же цвет кубиков для «свободного места»,
 * та же высота. Различается только цвет заливки: у контекста он зелёно-жёлто-
 * красный по мере заполнения, у квоты — голубой, пока есть запас, и жёлто-красный
 * на последних процентах (там опасно уже не «много занято», а «скоро кончится»).
 *
 * Ни цифр, ни процентов, ни подписи: сколько именно занято, полоса показывает
 * сама. Держать рядом ещё и текст значило бы дублировать одно и то же дважды и
 * забирать высоту у чата.
 *
 * Пока точного замера не было (`exact = false`), полоса пустая: неизвестное
 * значение нельзя рисовать как «почти пусто», иначе 2000 потраченных запросов
 * выглядели бы как 0.
 */
@Suppress("MagicNumber")
@Composable
private fun ZenMeter(
    used: Int,
    exact: Boolean,
    shape: GaugeShape,
    palette: GaugePalette,
    modifier: Modifier = Modifier,
) {
    val ratio = ZenQuota.ratio(used, ZenQuota.DAILY_LIMIT).coerceIn(0f, 1f)
    val active =
        when {
            ratio >= ZEN_DANGER_RATIO -> Color(palette.zenDanger)
            ratio >= ZEN_WARN_RATIO -> Color(palette.zenWarn)
            else -> Color(palette.zenCalm)
        }
    GaugeBars(
        ratio = if (exact) ratio else 0f,
        active = active,
        track = Color(palette.track),
        shape = shape,
        rowTag = TAG_ZEN_ROW,
        modifier = modifier.height(shape.height).fillMaxWidth(),
    )
}

@Suppress("MagicNumber")
@Composable
private fun ContextGauge(
    filled: Long,
    limit: Long,
    shape: GaugeShape,
    palette: GaugePalette,
    modifier: Modifier = Modifier,
) {
    val ratio = if (limit <= 0) 0f else (filled.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
    val active =
        when {
            ratio < 0.50f -> Color(palette.ctxLow)
            ratio < 0.80f -> Color(palette.ctxMid)
            else -> Color(palette.ctxHigh)
        }
    GaugeBars(
        ratio = ratio,
        active = active,
        track = Color(palette.track),
        shape = shape,
        rowTag = TAG_CTX_ROW,
        modifier = modifier.height(shape.height).fillMaxWidth(),
    )
}

/**
 * Общая полоса-кубики для индикаторов расхода.
 *
 * Её используют и контекст, и Zen-квота. Раньше каждый рисовал кубики сам, и они
 * разошлись: у квоты ромбики вышли наклонены в другую сторону и вдвое ниже. Общая
 * функция сделана, чтобы они больше не могли стать разными по сюжету.
 *
 * Форма — наклонная лента: параллелограмм, у которого верхнее ребро сдвинуто
 * вправо на `lean = GAUGE_LEAN * height`. Поверх заливки лента нарезана щелями
 * на [GAUGE_CELLS] ячеек, щели идут параллельно наклону.
 *
 * Почему лента, а не кубики: ячейки должны быть узкими (90 штук на всю
 * ширину), а наклон — заметным. У кубика наклон задаётся сдвигом вершины на
 * величину порядка его ширины, поэтому 90 узких ячеек плюс сильный наклон
 * вместе невозможны — ячейки либо слипаются в гладкую полосу без делений, либо
 * наклон получается нулевым. Лента решает это: наклон идёт по всей длине, а
 * ячейки остаются видимыми.
 */
@Composable
private fun GaugeBars(
    ratio: Float,
    active: Color,
    track: Color,
    shape: GaugeShape,
    rowTag: String,
    modifier: Modifier = Modifier,
) {
    // Щели рисуются цветом фона шапки, поэтому выглядят как пустота между
    // ячейками, а не как линии поверх полосы.
    val backdrop = Color(0xFF101010)
    val fill = ratio.coerceIn(0f, 1f)
    val cells = shape.cells
    val leanRatio = shape.lean
    val gapDp = shape.cellGapDp
    // Canvas сам по себе не создаёт узла доступности, поэтому ряду нужен
    // явный testTag - иначе в дампе не видно, где полоса и какова её высота.
    Canvas(modifier.testTag(rowTag)) {
        val h = size.height
        val lean = h * leanRatio
        // Лента вписана в холст по диагонали: нижний левый угол у левого края,
        // верхний правый — у правого, поэтому ничего не обрезается.
        val bandW = (size.width - lean).coerceAtLeast(1f)
        drawPath(leanBand(0f, bandW, lean, h), track)
        if (fill > 0f) {
            drawPath(leanBand(0f, bandW * fill, lean, h), active)
        }
        val step = bandW / cells
        val gap = gapDp.dp.toPx()
        if (gap > 0f && gap < step * 0.5f) {
            for (k in 1 until cells) {
                val x = step * k
                drawLine(
                    color = backdrop,
                    start = Offset(x, h),
                    end = Offset(x + lean, 0f),
                    strokeWidth = gap,
                )
            }
        }
    }
}

/** Параллелограмм-лента: низ от [left] до [left] + [width], верх сдвинут на [lean]. */
private fun leanBand(
    left: Float,
    width: Float,
    lean: Float,
    height: Float,
) = Path().apply {
    moveTo(left, height)
    lineTo(left + width, height)
    lineTo(left + width + lean, 0f)
    lineTo(left + lean, 0f)
    close()
}

/**
 * Неоновый индикатор MCP-серверов: «N MCP» + мигающая точка-светодиод.
 * Зелёный — все подключённые серверы работают (connected==total>0);
 * красный — какой-то не работает или их нет. Стилистика — неон: яркий
 * цвет, мягкое свечение вокруг точки (shadowBlur), точка плавно мигает.
 */
@Composable
private fun MCPIndicator(
    connected: Int,
    total: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
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
                t += 0.785 // ~0.785 рад/тик → период волны ≈ 8 тиков ≈ 0.96с
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
            "$connected MCP",
            color = Color(0xFF00E5FF),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            softWrap = false,
            maxLines = 1,
        )
    }
}

/**
 * Выпадающий список MCP-серверов (по тапу на индикатор MCP в шапке). Для каждого
 * имени — точка-светодиод: зелёная (работает, status=="connected") или красная
 * (не работает / отключён), плюс голубое число = сколько инструментов отдаёт
 * сервер (если сервер наш локальный и ответил tools/list). Пусто — «нет MCP».
 */
@Composable
private fun McpServerList(
    servers: List<McpInfo>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(Color(0xFF161616), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            "MCP-серверы",
            color = Color(0xFF00E5FF),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
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
                    )
                    // Сколько инструментов отдаёт сервер — считает сам сервер
                    // (MemoryMcp.toolCounts), для чужих серверов вывода нет.
                    srv.tools?.let { count ->
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "$count",
                            color = Color(0xFF00E5FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (ok) "работает" else "не работает",
                        color = if (ok) Color(0xFF39FF88) else Color(0xFFFF3B3B),
                        fontSize = 11.sp,
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
    val words =
        id
            .trim()
            .split('-', '_', '.', '/', ':')
            .filter { it.isNotBlank() }
    if (words.isEmpty()) return "Модель"
    return words.joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercaseChar() } }
}

// Доступные шрифты ответов модели: (ключ сохранения, имя в палитре, FontFamily)
private val fontEntries =
    listOf(
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
        Triple("cormorant", "Cormorant", FontFamily(Font(R.font.cormorant))),
    )

private fun fontFor(key: String): FontFamily = fontEntries.firstOrNull { it.first == key }?.third ?: FontFamily.Monospace

private fun parseHexColor(hex: String): Color =
    try {
        Color(("FF$hex").toLong(16))
    } catch (_: Exception) {
        Color(0xFFD97706)
    }

// Создаёт системный распознаватель речи, если он доступен на устройстве.
private fun createSpeechRecognizer(context: Context): SpeechRecognizer? =
    if (SpeechRecognizer.isRecognitionAvailable(context)) {
        SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
    } else {
        null
    }

/** Чтение PCM16 WAV → FloatArray 16кГц моно (микс каналов средним + ресемпл линейной интерполяцией). */
private fun readWavPcm16(
    f: File,
    targetRate: Int = 16000,
): FloatArray {
    val b = f.readBytes()

    fun le16(o: Int) = ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)

    fun le32(o: Int) =
        ((b[o + 3].toInt() and 0xFF) shl 24) or ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)
    if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") {
        throw RuntimeException("не WAV-файл")
    }
    var pos = 12
    var channels = 1
    var rate = 16000
    var bits = 16
    var dataStart = -1
    var dataLen = 0
    while (pos + 8 <= b.size) {
        val id = String(b, pos, 4, Charsets.US_ASCII)
        val len = le32(pos + 4)
        if (id == "fmt ") {
            channels = le16(pos + 10)
            rate = le32(pos + 12)
            bits = le16(pos + 22)
        } else if (id == "data") {
            dataStart = pos + 8
            dataLen = len
        }
        pos += 8 + len + (len and 1)
    }
    if (dataStart < 0 || channels < 1 || bits != 16) throw RuntimeException("WAV: нет data/не PCM16 (bits=$bits)")
    val n = (dataLen / (2 * channels)).coerceAtMost((b.size - dataStart) / (2 * channels))
    val mono =
        FloatArray(n) { i ->
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
        val i0 = src.toInt()
        val i1 = (i0 + 1).coerceAtMost(n - 1)
        val frac = (src - i0).toFloat()
        mono[i0] * (1 - frac) + mono[i1] * frac
    }
}

// Запись голоса для локального Whisper.
// Пишем 48000 Гц (нативная частота телефона) и ресемплим в 16к СВОИМ FIR-фильтром:
// встроенный ресемплер OPPO сыпет паразитные пики 2.5/4/6/7.5 кГц, от которых
// whisper путает слова. Стерео: L/R — два микрофона, берём более громкий.
private class AudioRecorder(
    private val context: Context,
    private val sampleRate: Int = 16000,
) {
    private val captureRate = 48000 // частота захвата (нативная)
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile private var running = false
    private val raw = mutableListOf<Short>() // interleaved (L,R,L,R…) если стерео
    private var channels = 1

    @Suppress("ReturnCount")
    private fun buildRecorder(
        fmt: Int,
        bufSize: Int,
    ): AudioRecord? {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        // UNPROCESSED (сырой тракт): MIC-тракт OPPO замусорен артефактами
        // ресемплинга (паразитные пики 2.5/4/7.5 кГц у Найквиста) — whisper
        // на таком сигнале путает слова. Если UNPROCESSED не поддержан — MIC.
        val sources =
            listOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // телефонный тракт с AGC: самый громкий и чистый
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.MIC,
            )
        for (src in sources) {
            try {
                val r = AudioRecord(src, captureRate, fmt, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d("VOICE", "recorder source=$src (${if (src == MediaRecorder.AudioSource.UNPROCESSED) "UNPROCESSED" else "MIC"})")
                    return r
                }
                r.release()
            } catch (_: Throwable) {
                // пробуем следующий source
            }
        }
        return null
    }

    fun start() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission is not granted")
        }
        val enc = AudioFormat.ENCODING_PCM_16BIT
        var fmt = AudioFormat.CHANNEL_IN_STEREO
        var minBuf = AudioRecord.getMinBufferSize(captureRate, fmt, enc)
        var r = if (minBuf > 0) buildRecorder(fmt, maxOf(minBuf * 4, 8192)) else null
        if (r != null) {
            channels = 2
        } else {
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
        thread =
            thread(name = "whisper-record") {
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
        try {
            r.stop()
        } catch (_: Exception) {
        }
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
        val rmsL = rms(l)
        val rmsR = if (rr.isNotEmpty()) rms(rr) else -1.0
        val best = if (rmsL >= rmsR) l else rr
        Log.d("VOICE", "48к→16к: rmsL=${"%.3f".format(rmsL)} rmsR=${"%.3f".format(rmsR)} → беру ${if (rmsL >= rmsR) "L" else "R"}")
        return best
    }

    /** Качественный ресемпл 48000→16000: FIR low-pass (Хэмминг, 63 тапа, срез 7.4к) + децимация 4:1. */
    private fun resample4to1(x: FloatArray): FloatArray {
        val taps = 63
        val cut = 7400.0 / 48000.0
        val h =
            DoubleArray(taps) { i ->
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
    private fun highPass(
        x: FloatArray,
        fc: Double = 150.0,
        q: Double = 0.707,
    ): FloatArray {
        val w0 = 2.0 * Math.PI * fc / sampleRate
        val cosw = Math.cos(w0)
        val sinw = Math.sin(w0)
        val alpha = sinw / (2.0 * q)
        val b0 = (1.0 + cosw) / 2.0
        val b1 = -(1.0 + cosw)
        val b2 = (1.0 + cosw) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosw
        val a2 = 1.0 - alpha
        val out = FloatArray(x.size)
        var x1 = 0f
        var x2 = 0f
        var y1 = 0f
        var y2 = 0f
        for (i in x.indices) {
            val y = ((b0 / a0) * x[i] + (b1 / a0) * x1 + (b2 / a0) * x2 - (a1 / a0) * y1 - (a2 / a0) * y2).toFloat()
            x2 = x1
            x1 = x[i]
            y2 = y1
            y1 = y
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
private fun MessageRow(
    m: ChatMsg,
    modelName: String = "Модель",
    modelFont: FontFamily = FontFamily.Monospace,
    modelColor: Color = Color(0xFFD97706),
) {
    val isModel = m.role == "assistant"
    val roleColor =
        when (m.role) {
            "user" -> Color(0xFF8AB4F8)
            "assistant" -> Color(0xFF9C27B0)
            else -> Color(0xFFB0B0B0)
        }
    val roleLabel =
        when (m.role) {
            "user" -> "Ты"
            "assistant" -> modelName
            else -> "Система"
        }
    Column(Modifier.fillMaxWidth()) {
        Text(
            roleLabel,
            color = roleColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
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
                    lineHeight = 19.sp,
                )
            }
        }
    }
}

/**
 * Забирает статус хода у сервера. Разбор - в [noticeFromStatus], он чистый и
 * покрыт юнит-тестом; здесь только поход по сети.
 */
private suspend fun fetchNotice(
    port: Int,
    sessionId: String,
): ChatNotice? = noticeFromStatus(LocalOpenCodeClient.get(port, "/session/status"), sessionId)

private suspend fun fetchChatSnapshot(port: Int?): ChatSnapshot? =
    withContext(Dispatchers.IO) {
        val p = port ?: return@withContext null
        try {
            // Замер пинга: оборачиваем первый запрос, который и так уходит каждый
            // тик. Отдельный /health-запрос не делаем — это лишний трафик и лишняя
            // нагрузка на сервер ради цифры в шапке.
            val pingStart = System.nanoTime()
            val sessionsBody = LocalOpenCodeClient.get(p, "/session")
            val pingMs = (System.nanoTime() - pingStart) / NS_PER_MS
            if (sessionsBody == null) {
                // Сервер не ответил. Снапшот строить не из чего, но сам факт
                // «недоступен» важен шапке — возвращаем пустой снапшот с
                // reachable=false, чтобы в шапке горело Offline, а не вечный
                // спиннер. Иначе отказ сервера выглядит как «сообщение не отправляется».
                return@withContext ChatSnapshot(
                    messages = emptyList(),
                    label = "нет связи с сервером",
                    activeId = null,
                    pingMs = pingMs,
                    reachable = false,
                )
            }
            val sessionsRaw = sessionsBody
            val sessions = JSONArray(sessionsRaw)
            var bestId: String? = null
            var bestTs = -1L
            var bestModelId = ""
            for (i in 0 until sessions.length()) {
                val s = sessions.getJSONObject(i)
                val t = s.optJSONObject("time")?.optLong("updated") ?: -1L
                if (t > bestTs) {
                    bestTs = t
                    bestId = s.optString("id").takeIf(String::isNotBlank)
                    bestModelId = s.optJSONObject("model")?.optString("id", "") ?: ""
                }
            }
            if (bestId == null) {
                PermissionCache.clearAll()
                return@withContext ChatSnapshot(
                    emptyList(),
                    "нет сессий",
                    null,
                    pingMs = pingMs,
                    reachable = true,
                )
            }
            val activeId = bestId
            val label = titleOf(sessions, activeId)
            // /message (лента) и /permission стартуют параллельно: оба вызова
            // блокирующие, поэтому async убирает их последовательную задержку.
            val msgDeferred = async { LocalOpenCodeClient.get(p, "/session/$activeId/message") }
            val permissionDeferred = async { PermissionCache.get(p, activeId) }
            // Статус хода от сервера: квота/ретрай провайдера. Отдельным async,
            // чтобы не добавлять его задержку к ленте — оба вызова блокирующие.
            // Опрашиваем ТОЛЬКО пока ход незавершён: в покое статус не может дать
            // новое (проверено — на успешном ходе он и так пустой), а лишний запрос
            // на каждом тике поллинга это чистый оверхед. Решение берём по thinking
            // прошлого тика; на первом тике после старта кэша ещё нет, догоняем
            // следующим (разница — доли секунды, статус виден только как плашка).
            val noticeDeferred = async {
                if (ChatCache.result?.thinking == true) fetchNotice(p, activeId) else null
            }
            // MCP-серверы: GET /mcp → Record<name, McpServer{name,enabled,status,...}> (иначе пустой {}).
            // Читаем из кэша (обновляется раз в MCP_CACHE_MS), чтобы не дёргать сервис каждый поллинг.
            // Подключёнными считаем тех, у кого status == "connected". Показываем «N MCP».
            var mcpConnected = 0
            var mcpTotal = 0
            val mcpServers = ArrayList<McpInfo>()
            // Счётчики инструментов спрашиваем у наших локальных серверов (кэш 30с):
            // числа в коде разъехались бы с реальностью после обновления приложения.
            // Музыка жива только при подключённом Яндексе; нет токена — её toolCounts
            // вернёт пустую карту, и строка в списке просто не получит счётчик.
            val mcpToolCounts = MemoryMcp.toolCounts() + YnisonMcp.toolCounts()
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
                            if (name.isNotBlank()) mcpServers.add(McpInfo(name, status, mcpToolCounts[name]))
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
                            val name = ms?.optString("name", "")?.takeIf { it.isNotBlank() } ?: key
                            mcpServers.add(McpInfo(name, status, mcpToolCounts[name]))
                        }
                    }
                }
            } catch (_: Exception) {
                // MCP недоступен — покажем 0 красным
            }
            val permission = permissionDeferred.await()
            val notice = noticeDeferred.await()
            val msgRaw =
                msgDeferred.await()
                    ?: return@withContext ChatSnapshot(
                        emptyList(),
                        label,
                        activeId,
                        pingMs = pingMs,
                        reachable = true,
                        permission = permission,
                        notice = notice,
                    )
            // Инкрементальный кэш: если за этой сессией тот же самый сырой JSON /message
            // (hash совпал) — лента и все производные (thinking/liveTool/ctxTokens/question)
            // гарантированно идентичны. Переиспользуем готовые объекты, НЕ пересоздавая
            // их: это убирает самое тяжёлое — полный JSON-парсинг и построение строк —
            // на каждый тик поллинга (2.5 раза/с), пока контент чата статичен.
            val rawHash = msgRaw.hashCode()
            val cached = ChatCache.result
            if (ChatCache.sessionId == activeId && ChatCache.rawHash == rawHash && cached != null) {
                val q = cached.question
                val thinking = cached.thinking
                val liveTool = cached.liveTool
                val take = cached.messages
                val snap =
                    ChatSnapshot(
                        take,
                        "$label",
                        activeId,
                        q,
                        thinking,
                        prettyModel(bestModelId),
                        liveTool = liveTool,
                        contextTokens = cached.contextTokens,
                        // Пинг/доступность НЕ из кэша: это свойство текущего запроса,
                        // а не ленты. Из кэша тянуть нельзя — иначе в шапке застынет
                        // первое измерение и «Offline» не появится, когда сервер ляжет.
                        pingMs = pingMs,
                        reachable = true,
                        // Расход Zen из кэша ленты: если сырой JSON тот же, то и число
                        // завершённых ответов то же — пересчитывать незачем. Новый
                        // ответ меняет /message, значит меняет hash и кэш протухает.
                        mcpConnected = mcpConnected,
                        mcpTotal = mcpTotal,
                        mcpServers = mcpServers,
                        permission = permission,
                        notice = notice,
                    )
                android.util.Log.d(
                    "ChatOverlay",
                    "FETCH(cached) out=${take.size} thinking=$thinking q=${q != null} " +
                        "permission=${permission != null} notice=${notice != null} label=$label model=$bestModelId",
                )
                return@withContext snap
            }
            val arr = JSONArray(msgRaw)
            val out = ArrayList<ChatMsg>(arr.length())
            // Состояние последнего сырого сообщения нужно отдельно от видимых строк:
            // assistant с одним tool-чатом и step-finish скрывается из ленты ниже, но всё
            // равно завершает ответ. Без этих полей последней видимой строкой остался бы
            // user, и UI после Stop продолжал бы показывать «Модель думает…».
            var latestRole: String? = null
            var latestActivity = false
            var latestFinish = false
            var latestCompleted = false
            // Живой инструмент, вызываемый моделью в ТЕКУЩЕМ ответе. Накопительный по
            // assistant-шагам одного ответа (сбрасывается на новом user-сообщении), поэтому
            // чип НЕ моргает между tool-вызовами. На шагах с тулом запоминаем его; пустой
            // промежуточный шаг сохраняет предыдущий тул. Из UI показывается только пока
            // думает (thinking=true) — после завершения ответа гаснет.
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
                val messageCompleted =
                    info.optJSONObject("time")?.optLong("completed", 0L)?.let { it > 0L } == true
                val sb = StringBuilder()
                var hasText = false
                var finish = false
                var activity = false
                // Новый ВОПРОС (user-сообщение) — сбрасываем живой тул: начинается новый
                // ответ модели, чип должен отражать только инструменты ЭТОГО ответа.
                // На assistant-шагах НЕ сбрасываем: между tool-вызовами одного ответа есть
                // пустые промежуточные шаги, и live-чип не должен моргать (bash→пусто→bash).
                if (role == "user") {
                    lastTool = null
                }
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
                        val det =
                            when {
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
                latestRole = role
                latestActivity = activity
                latestFinish = finish
                latestCompleted = messageCompleted
                // Завершённый tool-only шаг (step-start->tool...->step-finish без text)
                // не должен отображаться как «… генерируется …» — это не зависание,
                // а просто шаг без текста. Фильтруем его из ленты. ДУМАЮЩИЙ assistant
                // (без step-finish) остаётся, чтобы UI показал «генерируется».
                if (role == "assistant" && !hasText && finish) continue
                out.add(ChatMsg(role, sb.toString()))
            }
            val take = if (out.size > MAX_SHOWN) out.subList(out.size - MAX_SHOWN, out.size) else out
            val q = questionOf(p, activeId)
            val last = out.lastOrNull()
            // «Думает» = последнее сырое сообщение открывает новый ответ. У завершённого
            // assistant проверяем даже скрытый tool-only шаг: после Stop opencode может
            // завершить его без step-finish, но с info.time.completed.
            val thinking =
                q == null &&
                    when (latestRole) {
                        null -> false
                        "user" -> true
                        "assistant" -> !latestFinish && !latestCompleted
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
            ChatCache.sessionId = activeId
            ChatCache.rawHash = rawHash
            ChatCache.result =
                ChatParseResult(
                    take,
                    q,
                    thinking,
                    liveTool,
                    ctxTokens,
                )
            val snap =
                ChatSnapshot(
                    take,
                    "$label",
                    activeId,
                    q,
                    thinking,
                    prettyModel(bestModelId),
                    liveTool = liveTool,
                    contextTokens = ctxTokens,
                    pingMs = pingMs,
                    reachable = true,
                    // Расход Zen — из БАЗЫ СЕРВЕРА, который обновляется раз в
                    // ZEN_REFRESH_MS. Лента тут не годится: она покрывает только
                    // активную сессию, а день мог начаться в нескольких сессиях.
                    mcpConnected = mcpConnected,
                    mcpTotal = mcpTotal,
                    mcpServers = mcpServers,
                    permission = permission,
                    notice = notice,
                )
            val lastDiag = last?.let { "role=${it.role} text='${it.text.take(30)}'" } ?: "null"
            android.util.Log.d(
                "ChatOverlay",
                "FETCH parse out=${out.size} take=${take.size} thinking=$thinking q=${q != null} " +
                    "permission=${permission != null} label=$label " +
                    "model=$bestModelId liveTool=${liveTool?.name} LAST=[$lastDiag] " +
                    "latestRole=$latestRole latestAct=$latestActivity latestFin=$latestFinish " +
                    "latestCompleted=$latestCompleted",
            )
            snap
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            null
        }
    }

private fun titleOf(
    sessions: JSONArray,
    id: String,
): String {
    for (i in 0 until sessions.length()) {
        val s = sessions.getJSONObject(i)
        if (s.optString("id") == id) {
            val t = s.optString("title", "")
            return if (t.isBlank()) "сессия" else t
        }
    }
    return "сессия"
}

private fun questionOf(
    port: Int,
    sessionId: String,
): ChatQuestion? {
    try {
        val raw = LocalOpenCodeClient.get(port, "/api/session/$sessionId/question") ?: return null
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

private fun postAnswer(
    port: Int,
    sessionId: String,
    questionId: String,
    labels: List<String>,
): Boolean {
    val answers = JSONArray()
    val one = JSONArray()
    one.put(labels.firstOrNull() ?: "")
    answers.put(one)
    val body = JSONObject().put("answers", answers).toString()
    return LocalOpenCodeClient.postAsync(port, "/api/session/$sessionId/question/$questionId/reply", body)
}

private suspend fun scrollToBottomFull(
    state: LazyListState,
    target: Int,
) {
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
                    rt.audioAttributes =
                        AudioAttributes
                            .Builder()
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
    val body = LocalOpenCodeClient.post(port, "/session", "{}") ?: return null
    return try {
        JSONObject(body).optString("id").takeIf(String::isNotBlank)
    } catch (_: Exception) {
        null
    }
}

/**
 * Прерывает текущую генерацию модели в сессии. opencode serve принимает
 * POST /session/{id}/abort (200 + "true"). Ответ приходит сразу, блокировать
 * нечего — это не долгий стрим.
 *
 * Осторожно, bool здесь обманчив: на несуществующей сессии тот же эндпоинт
 * отдаёт 200 + HTML (SPA-fallback на любой путь), то есть true бывает даже
 * когда ничего не прервалось. Проверять надо через [sessionRunning].
 */
private fun abortSession(
    port: Int,
    sessionId: String,
): Boolean = LocalOpenCodeClient.post(port, "/session/$sessionId/abort", "") != null

/**
 * Есть ли запись сессии в /session/status, то есть считает ли сервер её ход
 * незавершённым.
 *
 * Нужна потому, что по коду ответа abort узнать «прервалось ли» нельзя (см.
 * [abortSession]). Единственный честный признак — уехала ли запись из карты
 * статусов после abort.
 */
private fun sessionRunning(
    port: Int,
    sessionId: String,
): Boolean {
    val raw = LocalOpenCodeClient.get(port, "/session/status") ?: return false
    return try {
        JSONObject(raw).has(sessionId)
    } catch (_: Exception) {
        false
    }
}

/**
 * Откат только что созданной сессии. Нужен потому, что создание и отправка -
 * одна операция: если отправка не ушла, сессия остаётся сиротой (одно событие
 * session.created, ноль сообщений) и засоряет список сессий. На живом сервере
 * DELETE /session/{id} отвечает 200 и сессия исчезает.
 */
private fun deleteSession(
    port: Int,
    sessionId: String,
): Boolean = LocalOpenCodeClient.delete(port, "/session/$sessionId")

/**
 * Есть ли в сессии хоть одно сообщение. Разделяет «сервер не получил запрос»
 * (тогда сессию можно откатить) и «сервер записал, но ответил ошибкой»
 * (тогда откат съел бы чужое сообщение).
 */
private fun sessionHasMessages(
    port: Int,
    sessionId: String,
): Boolean =
    try {
        val raw = LocalOpenCodeClient.get(port, "/session/$sessionId/message")
        raw != null && JSONArray(raw).length() > 0
    } catch (_: Exception) {
        // Не смогли узнать — считаем, что сохранилось: на неопределённости не стираем.
        true
    }

private fun postMessage(
    port: Int,
    sessionId: String,
    text: String,
): Boolean {
    val body = "{\"parts\":[{\"type\":\"text\",\"text\":${JSONObject.quote(text)}}]}"
    return LocalOpenCodeClient.postAsync(port, "/session/$sessionId/message", body)
}

/** Ряд-переключатель в стиле панели STT: активен или нет. */
@Composable
private fun TtsRadioRow(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
    ) {
Text(
            if (active) "● " else "○ ",
            color = Color(0xFFFF6D00),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(label, color = Color(0xFFE6E6E6), fontSize = 13.sp)
    }
}