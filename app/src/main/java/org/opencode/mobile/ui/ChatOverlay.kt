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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.opencode.mobile.R
import org.opencode.mobile.server.ApiPaths
import org.opencode.mobile.server.ApiShape
import org.opencode.mobile.server.AskCard
import org.opencode.mobile.server.LocalOpenCodeClient
import org.opencode.mobile.server.PendingAsk
import org.opencode.mobile.social.MoltbookScanClient
import org.opencode.mobile.stt.SttModels
import org.opencode.mobile.stt.WhisperTranscribeService
import org.opencode.mobile.tts.ElevenLabsSecret
import org.opencode.mobile.tts.TtsConfig
import org.opencode.mobile.tts.TtsEngine
import org.opencode.mobile.tts.TtsNarrator
import org.opencode.mobile.ui.chat.CONTEXT_LIMIT
import org.opencode.mobile.ui.chat.ChatCache
import org.opencode.mobile.ui.chat.RetryState
import org.opencode.mobile.ui.chat.StallState
import org.opencode.mobile.ui.chat.abortSession
import org.opencode.mobile.ui.chat.advanceRetry
import org.opencode.mobile.ui.chat.advanceStall
import org.opencode.mobile.ui.chat.answerPendingAsk
import org.opencode.mobile.ui.chat.createSession
import org.opencode.mobile.ui.chat.deleteSession
import org.opencode.mobile.ui.chat.fetchChatSnapshot
import org.opencode.mobile.ui.chat.nextStableRounds
import org.opencode.mobile.ui.chat.pollDelayMs
import org.opencode.mobile.ui.chat.postMessage
import org.opencode.mobile.ui.chat.pruneEmptySessions
import org.opencode.mobile.ui.chat.sessionHasMessages
import org.opencode.mobile.ui.chat.sessionRunning
import org.opencode.mobile.ui.chat.stopAndPurge
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

// Идентификаторы выпадающих панелей шапки — ключи для [ChatOverlay] переключателя
// toggleHeaderPanel. Строками, а не enum: ключ нужен ровно в двух местах
// (объявление и клик по иконке), а enum здесь стоил бы лишней печати.
private const val PANEL_MCP = "mcp"
private const val PANEL_MOLTBOOK = "moltbook"
private const val PANEL_COLOR = "color"
private const val PANEL_FONT = "font"
private const val PANEL_SETTINGS = "settings"
private const val PANEL_STT = "stt"

// Как часто открытая панель Moltbook перечитывает ledger. Быстрее 10 секунд —
// это долбить базу впустую, медленнее минуты — панель успевает соврать.
private const val MOLTBOOK_PANEL_REFRESH_MS = 10_000L

// Как часто панель просит плагин перечитать ленту. Раз в пять минут, а не на
// каждом чтении: скан ходит в молтбук за лентой, уведомлениями и тредами — это
// около девяти запросов, и десять секунд между ними это 54 запроса в минуту,
// за которые платформа вправе порезать. Пять минут — компромисс между «числа
// на карточке свежие» и «мы не долбим чужой сервис». Скан сам по себе умеет
// ждать до минуты, поэтому задержка сверху не копится.
private const val MOLTBOOK_SCAN_EVERY_MS = 5 * 60_000L

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

// Цвета панели ключа ElevenLabs. Вынесены именами, потому что «серый — есть ключ,
// оранжевый — ключа нет» и «красный — удалить/ошибка» это смысл, а не украшение:
// по цвету видно состояние, не читая текст. Сами литералы подавлены — цвет задаёт
// палитра тёмной темы, а не формула.
@Suppress("MagicNumber")
private object ElevenKeyPalette {
    val HintOk = Color(0xFF8A8A8A)
    val HintMissing = Color(0xFFE6A23C)
    val SaveIdle = Color(0xFF5A5A5A)
    val SaveReady = Color(0xFF7BA6F8)
    val Danger = Color(0xFFE57373)
}

// Голоса в сетке: пять на строку — на шапке телефона шесть уже не помещались.
private const val SID_CHUNK = 5

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

/** Оранжевый плашки «озвучка переехала на другой движок»: предупреждение, не ошибка. */
private const val TTS_FALLBACK_NOTICE_COLOR = 0xFFFFB74D

/** Отдельная роль для сообщения об ошибке — иначе она уезжает в «Система». */
private const val ERROR_ROLE_ARGB = 0xFFE05B5B

internal data class ChatMsg(
    val role: String,
    val text: String,
    /** причина отказа - показывается цветным, не попадает в озвучку и в счётчик. */
    val notice: String? = null,
    /**
     * Идентификатор сообщения от сервера. Пусто у реплик, добавленных на
     * стороне клиента (своё сообщение). Нужен устойчивой метке для анимации
     * печати: индекс в ленте не годится, она обрезается до MAX_SHOWN.
     */
    val id: String = "",
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
    val thinking: Boolean = false,
    val modelName: String = "Модель",
    val stalled: Boolean = false,
    val stalledDead: Boolean = false,
    val liveTool: ChatTool? = null,
    // Что сервер ждёт от пользователя: команда под «ask» или вопрос агента.
    // null — состояние неизвестно (сервер не ответил), пустой список — ждать
    // нечего. Различие обязательное: скрыть запрос значит оставить ход висеть.
    val ask: List<AskCard>? = null,
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
    // Причина, по которой сервер сам не может продолжить ход (квота/ретрай).
    val notice: ChatNotice? = null,
)

/**
 * Полноэкранный чат поверх WebView. WebView (SPA opencode) не рендерит ленту
 * в WebView-окружении — поэтому чат реализован здесь, нативно: опрос локального
 * сервера каждые 2с + отправка через POST /session/{id}/message.
 * Поле ввода внизу, лента наверху, клавиатура не перекрывает поле (imePadding).
 */
// Подавление осознанное: экран-монолит покрыт только ручным тестом на устройстве,
// и его тихая переделка без тестов опаснее, чем честная метка в коде.
// Разбиение на подкомпозблы по секциям (лента/ввод/панель MCP/диалоги) — отдельная задача.
@Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
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
    var stopping by remember { mutableStateOf(false) }
    var whisperRecorder: AudioRecorder? = null
    val listState = rememberLazyListState()
    var knownMsgs by remember { mutableIntStateOf(0) }
    // ИНДЕКС последнего завершённого ответа, на который уже сработала вибрация.
    // Отделён от knownMsgs (фиксирует появление в сессии), чтобы звук играл
    // СТРОГО когда ответ реально стал видимым на экране (см. LaunchedEffect(lastResp)).
    var knownRendered by remember { mutableIntStateOf(-1) }
    // Ключ ответа, который уже напечатан по буквам. null — печатать можно,
    // иначе тот же текст перепечатывался бы при каждой пересборке ленты.
    // Именно remember, а не rememberSaveable: при убийстве процесса Android
    // восстанавливает сохранённый Bundle, и тогда последний ответ не напечатался
    // бы вообще — хуже, чем лишняя перепечатка при пересоздании Activity.
    var typingKey by remember { mutableStateOf<String?>(null) }
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
    // Настройки голоса: движок распознавания ("system" | "whisper").
    //
    // ncnn-движок выпилен 14.10.2026 вместе с его моделью (1,2 ГБ): на этом
    // Adreno он падал в SIGSEGV на энкодере, а на CPU был вчетверо медленнее
    // whisper.cpp. Подробности и таблицы - docs/VOICE-REPORT.md. Сохранённое
    // значение "ncnn" мигрирует на "whisper", чтобы настройка не осталась
    // в несуществующем движке.
    val savedEngine = prefs.getString("stt_engine", "system")
    val defaultEngine = if (savedEngine == "ncnn") "whisper" else (savedEngine ?: "system")
    var sttEngine by remember { mutableStateOf(defaultEngine) }

    // Модель выбирается из каталога [SttModels]; в продукте она одна
    // (small), но ключ остаётся прежним: перевыбор в будущем не должен
    // ломать сохранённую настройку.
    //
    // Модель читается из prefs В МОМЕНТ записи, а не из remember. Копия в
    // remember замораживалась на момент запуска приложения: скачал turbo,
    // перевыбрал движок — а распознавание всё равно шло на base. Настройки на
    // диске, remember про них ничего не знает.

    /**
     * Модель whisper.cpp на момент записи — просто то, что выбрал человек.
     *
     * Здесь НЕТ проверки `spec.isReady(context)`, и это сделано намеренно:
     * `isReady` считает sha256 файла целиком (до 1,5 ГБ — это секунды
     * блокировки), а `sttModel()` дёргается прямо в колуторе записи, то есть
     * почти наверняка на главном потоке. Тот же расчёт уже есть в двух других
     * местах, оба в `Dispatchers.IO`: в пикере настроек и в диагностике.
     *
     * Что модель вообще скачана, гарантирует пикер: нескачанная строка
     * некликабельна. Если файл всё же пропал (уборка в диагностике, смена
     * карты) — ошибку даст сервис своим понятным текстом «скачай в Настройки ->
     * Диагностика», а не молчаливая подмена на другую модель, которую человек
     * не выбирал.
     */
    fun chosenWhisperModel(): String {
        val stored = prefs.getString("stt_model_whisper", SttModels.DEFAULT_ID) ?: SttModels.DEFAULT_ID
        return SttModels.byId(stored)?.id ?: SttModels.DEFAULT_ID
    }

    /** Модель для распознавания: движок один, значит и ключ один. */
    fun sttModel(): String = chosenWhisperModel()

    // Выбор модели в панели: состояние дублирует prefs, чтобы перерисовка была
    // мгновенной, а на диск пишется сразу — источник правды всё равно prefs.
    var whisperModelId by remember {
        mutableStateOf(prefs.getString("stt_model_whisper", SttModels.DEFAULT_ID) ?: SttModels.DEFAULT_ID)
    }
    // Движок больше не переключается принудительно: whisper.cpp собран
    // с GGML_VULKAN=ON под Adreno и выбирается пользователем.
    // Полноэкранная диагностика: состояние сервера, STT-модели, лог serve.
    // Оверлей рисуется ПОСЛЕДНИМ в корневом Surface — поверх чата и панелей.
    var showDiagnostics by remember { mutableStateOf(false) }

    // Озвучка ответов (§12.3/§12.5). Выключатель — здесь же, в панели STT.
    // Настройки живут в том же prefs "chat_overlay": tts_engine/tts_model/tts_sid.
    var ttsConfig by remember { mutableStateOf(TtsConfig.read(context)) }
    var ttsOn by remember { mutableStateOf(TtsConfig.read(context).isEnabled) }
// Причина отката движка, если озвучка пошла не тем голосом, что выбран.
    // Источник - поток TtsNarrator: движок грузится лениво на первом ответе, то
    // есть задолго после открытия панели, и опрос с потолком в несколько секунд
    // показывал бы причину отката через раз. collectAsState перерисовывает панель
    // и когда движок пересобрался сам (докачали модель, вставили ключ).
    val ttsFallback by TtsNarrator.fallbackReason.collectAsState()
    // Черновик ключа намеренно НЕ восстанавливается из хранилища: в поле
    // лежит только то, что юзер вводит прямо сейчас. Сам ключ читает
    // движок из ElevenLabsSecret, и в UI он не показывается никогда.
    var elevenKeyDraft by remember { mutableStateOf("") }
    var elevenHasKey by remember { mutableStateOf(ElevenLabsSecret.hasKey(context)) }
    // Причина, по которой последнее сохранение не вышло, или null. Само
    // ElevenLabsSecret вернёт её в SaveResult.Failed — здесь она просто
    // показывается под полем ввода ключа.
    var elevenKeyError by remember { mutableStateOf<String?>(null) }
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

    // Отвечаем на запроу, на которых сервер ждёт: команда под «ask» или вопрос
    // агента. Отдельное состояние от snapshot.ask нужно потому, что ответ —
    // действие с побочным эффектом: пока он уходит, карточку надо оставить на
    // экране, иначе ход повиснет с заблокированной кнопкой.
    var askBusy by remember { mutableStateOf(false) }
    var askError by remember { mutableStateOf<String?>(null) }
    // id запроса, чей ответ уже ушёл в очередь: повтор не должен слать его снова.
    var askAnswerSent by remember { mutableStateOf<String?>(null) }
    // Отметка привязана к конкретному запросу, поэтому её надо снимать при любой
    // смене карточки — и при уходе запроса из /permission, и при переходе в
    // другую сессию. Иначе после частичного провала (ответ ушёл в очередь, но
    // отклонить не удалось) тап «Разрешить» с новым текстом ушёл бы без текста:
    // answerPendingAsk решил бы, что этот ответ уже отправляли.
    val shownCard = snapshot?.ask?.firstOrNull()
    val shownAskId = shownCard?.ask?.id
    LaunchedEffect(shownAskId) {
        askAnswerSent = null
        askError = null
    }

    /**
     * Ответ на ждущий запрос. [message] обязателен для вопроса агента и не
     * нужен для команды. Карточку снимаем только по успеху: неуспешный ответ
     * иначе исчезнет с экрана вместе с единственным способом её закрыть.
     */
    fun answerAsk(
        ask: PendingAsk,
        decision: String,
        message: String? = null,
    ) {
        val sessionId = snapshot?.activeId ?: return
        if (askBusy) return
        askBusy = true
        askError = null
        val alreadySent = askAnswerSent == ask.id
        scope.launch {
            val outcome =
                withContext(Dispatchers.IO) {
                    answerPendingAsk(
                        serverPort,
                        sessionId,
                        ask,
                        decision,
                        if (alreadySent) null else message,
                    )
                }
            askBusy = false
            if (outcome.answered) askAnswerSent = ask.id
            when {
                !outcome.replied && alreadySent ->
                    askError = "Ответ ушёл в очередь, но запрос не закрыт. Нажми ещё раз — текст повторно не уйдёт."
                !outcome.replied -> askError = "Ответ не ушёл. Проверь сервер и повтори."
                else -> {
                    askAnswerSent = null
                    askError = null
                    userScrolledUp = false
                    scrollToBottomFull(listState, (snapshot?.messages?.size ?: 0) - 1)
                }
            }
        }
    }

    // Подтверждение сброса зависшей сессии из DeadRow. Удаление необратимо,
    // поэтому отдельный диалог, а не кнопка в один тап.
    var confirmResetDead by remember { mutableStateOf(false) }

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
    val showMoltbookPanel = activePanel == PANEL_MOLTBOOK
    val showColorPicker = activePanel == PANEL_COLOR
    val showFontPicker = activePanel == PANEL_FONT
    val showSettings = activePanel == PANEL_SETTINGS
    val showSttSettings = activePanel == PANEL_STT

    // Панель Moltbook: файл снимка, который переписывает плагин после каждого
    // своего вызова. Ни сети, ни ledger - читается файл, поэтому таймер здесь
    // один и на все случаи: раз открыли панель, так и перечитываем её, пока
    // открыта. Раньше был ещё отдельный ключ на «попытку сети при открытии»,
    // и он исчез вместе с сетью.
    var moltbookRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(showMoltbookPanel) {
        if (!showMoltbookPanel) return@LaunchedEffect
        moltbookRefresh++
        while (true) {
            delay(MOLTBOOK_PANEL_REFRESH_MS)
            moltbookRefresh++
        }
    }

    // Скан отдельно от чтения, и отдельным медленным кругом. Чтение файла дешёвое,
    // поэтому оно часто; скан ходит в ленту и стоит девяти запросов, поэтому его
    // пять минут. Один круг на оба означал бы либо протухшую карточку, либо
    // платформу, которая режет нас за 54 запроса в минуту.
    //
    // Первый скан - сразу, а не через пять минут: карточка, открытая на пустом
    // снимке, минуту показывала бы «нет данных» при живом плагине.
    // Скан идёт только пока панель открыта, и это замысел, а не недосмотр:
    // карточки нет - смотреть некуда, а девять запросов к молтбуку каждые пять
    // минут впустую копятся тем дольше, чем реже открывают панель.
    //
    // Эффект на `showMoltbookPanel`, поэтому закрытие панели обрывает скан на
    // середине, а не ждёт его конца: `requestScan` рвёт соединение по отмене.
    LaunchedEffect(showMoltbookPanel) {
        if (!showMoltbookPanel) return@LaunchedEffect
        moltbookRefresh++
        while (true) {
            MoltbookScanClient.requestScan(context)
            delay(MOLTBOOK_SCAN_EVERY_MS)
        }
    }
    val moltbookStats = rememberMoltbookState(moltbookRefresh)

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        val sessionId = snapshot?.activeId
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
                        val fresh = createSession(serverPort, context)
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

    fun stopGen() {
        if (stopping) return
        val sessionId = snapshot?.activeId ?: return
        stopping = true
        scope.launch {
            val ok =
                try {
                    withContext(Dispatchers.IO) { abortSession(serverPort, sessionId) }
                } finally {
                    stopping = false
                }
            if (ok) {
                vibrate(context)
            }
            // Стоп генерации = стоп озвучки: иначе голос продолжал бы читать
            // уже отменённый ответ (очередь синтеза опустошается, игра гасится).
            TtsNarrator.stop()
            // thinking сбросится сам на следующом поллинге: abort завершит
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
                        createSession(serverPort, context)?.let {
                            ChatCache.sessionId = null
                            ChatCache.rawHash = 0
                            true
                        } ?: false
                }
            // Сбрасываем кэш в любом случае: если удаление прошло, поллинг обязан
            // перечитать список и уйти на новую сессию, иначе вернёт удалённую.
            ChatCache.result = null
            creatingSession = false
            if (ok) vibrate(context)
        }
    }

    // «Начать новую сессию»: создаём пустую свежую сессию и ПЕРЕКЛЮЧАЕМСЯ на неё
    // (сессии с перепиской остаются в списке — это история диалогов). Жёсткая
    // очистка ВСЕГО — отдельно, clearAllSessions (long-press на +) с диалогом
    // подтверждения (UI-20).
    fun newSession() {
        creatingSession = true
        scope.launch {
            val created =
                withContext(Dispatchers.IO) {
                    val id = createSession(serverPort, context) // POST /session → fresh id
                    if (id != null) {
                        // Сбрасываем кэш ленты ТОЛЬКО при успехе: при отказе createSession
                        // поллинг продолжит прежнюю ленту без лишнего форс-перезапроса.
                        ChatCache.sessionId = null
                        ChatCache.rawHash = 0
                        ChatCache.result = null
                        // Подчищаем пустые сироты: новая сессия только что создана и
                        // тоже пуста, поэтому её id обязателен в keep.
                        pruneEmptySessions(serverPort, keep = setOf(id))
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
                    val id = createSession(serverPort, context) // POST /session → fresh id
                    if (id != null) {
                        // Сбрасываем кэш ленты ТОЛЬКО при успехе: при отказе createSession
                        // поллинг продолжит прежнюю ленту без лишнего форс-перезапроса.
                        ChatCache.sessionId = null
                        ChatCache.rawHash = 0
                        ChatCache.result = null
                        // Удаляем ВСЕ остальные сессии — чистый старт. DELETE сам по себе не
                        // обязан останавливать бегущую генерацию: модель может продолжать
                        // писать ответ в сессию, которую мы удаляем (CPU горит впустую, сервер
                        // «залипает»). Поэтому каждую умирающую сессию сначала глушим abort-ом
                        // (идемпотентен, безвреден для пустых/404) — независимо от того, была
                        // ли она активной на момент сброса.
                        val raw = LocalOpenCodeClient.get(serverPort, ApiPaths.SESSIONS)
                        try {
                            val arr = ApiShape.sessions(raw) ?: org.json.JSONArray()
                            for (i in 0 until arr.length()) {
                                val sid = arr.getJSONObject(i).optString("id").takeIf(String::isNotBlank) ?: continue
                                if (sid != id) {
                                    // Пока идёт цикл, сбой одного удаления не должен отменять
                                    // остальные: общий catch раньше проглатывал всё молча,
                                    // и пользователь видел «очистил», а половина сессий жила.
                                    try {
                                        stopAndPurge(serverPort, sid)
                                    } catch (e: Exception) {
                                        android.util.Log.w(
                                            "ChatOverlay",
                                            "PURGE failed session=$sid: ${e.message}",
                                        )
                                    }
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
        if (sttEngine == "whisper") {
            // Локальный движок: запись PCM16 16 кГц в буфер.
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
        if (sttEngine == "whisper") {
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
                // (>30 с) режутся на высказывания без потери хвоста.
                Log.d("VOICE", "запускаю сегментное распознавание через foreground-сервис...")
                val text =
                    try {
                        org.opencode.mobile.stt.ChunkedTranscriber.transcribe(
                            context,
                            samples,
                            model = sttModel(),
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
                                            Log.d("VOICE", "TTS-тест: ${samples.size} сэмплов → whisper (${sttModel()})")
                                            WhisperTranscribeService.transcribe(
                                                context,
                                                samples,
                                                model = sttModel(),
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
    val lastResp = snapshot?.messages?.indexOfLast { it.role == "assistant" && it.text.isNotBlank() && it.notice == null }
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
        // Детектор зависания (обычное думание, пустой шаг, мёртвый ход) и бюджет
        // повторов живут в ui/chat/ChatStallTracker.kt: чистые функции принимают
        // прошлое состояние и возвращают новое. Здесь только сами состояния.
        var stall = StallState()
        var retry = RetryState()
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
                val completed = snap.messages.count { it.role == "assistant" && it.text.isNotBlank() && it.notice == null }
                val old = snapshot
                val madeProgress =
                    old != null &&
                        (
                            old.messages != snap.messages ||
                                old.liveTool != snap.liveTool ||
                                old.notice != snap.notice
                        )
                val verdict = advanceStall(stall, madeProgress, snap, now)
                stall = verdict.state
                val final =
                    if (verdict.stalled) {
                        snap.copy(stalled = true, stalledDead = verdict.dead)
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
                // Переход защёлки (серверный retry → наша плашка «Ход остановлен»)
                // и бюджет повторов живут в ui/chat/ChatStallTracker.kt: здесь
                // только применяем решение.
                val latchBefore = noticeLatch
                val retryVerdict = advanceRetry(retry, final.notice, turnFinished = !final.thinking, now = now)
                retry = retryVerdict.state
                noticeLatch = retryVerdict.state.latch
                if (retryVerdict.abort) {
                    val sid = final.activeId
                    if (sid != null) {
                        android.util.Log.w(
                            "ChatOverlay",
                            "RETRY budget spent attempt=${retryVerdict.state.stop?.attempt} session=$sid, aborting",
                        )
                        // Сеть на главном потоке: этот вызов делает блокирующий
                        // HttpURLConnection, как и остальные abortSession в файле.
                        withContext(Dispatchers.IO) { abortSession(serverPort, sid) }
                    }
                }
                changed = old == null ||
                    old.messages != final.messages ||
                    old.thinking != final.thinking ||
                    old.stalled != final.stalled ||
                    old.stalledDead != final.stalledDead ||
                    old.liveTool != final.liveTool ||
                    old.notice != final.notice ||
                    // ask обязателен: запрос разрешения может прийти позже той же
                    // tool-части, что уже в ленте, и тогда ни лента, ни thinking, ни
                    // liveTool не меняются. Без этого пункта карточка не доедет до UI
                    // и ход останется висеть.
                    old.ask != final.ask ||
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
                        //
                        // Список НЕ фильтруется по наличию диагностики. Причина отказа
                        // лежит в отдельном поле notice, в text её нет, поэтому читать
                        // вслух нечего. А фильтр сдвигал бы индексы на ходу: пока ход
                        // стримится, error в нём ещё нет и сообщение в список попадает,
                        // а после проваза исчезает — хвост списка отъезжал назад, и
                        // последнее предложение начинало читаться заново с предыдущей
                        // реплики. Ошибок без своего текста в этот список не попадают
                        // вовсе: у них роль error.
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
                // База уведомлений двигается в ОБЕ стороны. Счётчик исключает
                // провалившиеся ходы (у них появляется диагностика), поэтому он может
                // уменьшиться: ход, который при стриминге шёл как обычный ответ, после
                // отказа провайдера перестаёт в нём учитываться. Если бы база только
                // росла, следующий настоящий ответ не дал бы completed > knownMsgs —
                // и уведомление пропало бы. ЗВУК НЕ играем здесь: snapshot ещё не
                // отрисован — иначе вибрация опережала бы рендер сообщения на 1-2с.
                // Вибрация живёт в отдельном LaunchedEffect, привязанном к фактической
                // видимости ответа; здесь только фиксируем факт.
                if (completed != knownMsgs && (completed > knownMsgs || snap.messages.isNotEmpty())) {
                    knownMsgs = completed
                }
                // Базовая линия на ПЕРВОМ поллинге приложения: фиксируем ИНДЕКС
                // последнего завершённого ответа как «уже известный», чтобы старые
                // ответы (если сессия не пуста в момент открытия чата) НЕ вызывали
                // вибрацию. Выполняется один раз. Последующие новые ответы
                // (idx > knownRendered) завибрируют строго после отрисовки.
                if (!baselineDone) {
                    baselineDone = true
                    knownRendered = snap.messages.indexOfLast { it.role == "assistant" && it.text.isNotBlank() && it.notice == null }
                }
            }

            // Адаптивный интервал: активность (thinking / реальное изменение ленты) →
            // быстрая опрашивание 400мс. Стабильность → плавно растягиваем к idle 900мс.
            stableRounds = nextStableRounds(stableRounds, snap != null && (snap.thinking || changed))
            delay(pollDelayMs(stableRounds))
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
                // «М» — Moltbook: автономный агент. Буква с бейджем неотвеченных
                // вместо иконки намеренно: это не сервис, а сам агент, и цифра на нём —
                // не «сколько серверов сломано», а «кого мы ещё не ответили».
                MoltbookIndicator(
                    awaiting = moltbookStats.awaiting,
                    active = showMoltbookPanel,
                    onClick = { toggleHeaderPanel(PANEL_MOLTBOOK) },
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
            // Сводка Moltbook: сколько комментов ждут ответа, сколько репостов на
            // наши посты, кому именно мы не ответили. Тот же тёмный кард, что и MCP.
            if (showMoltbookPanel) {
                MoltbookPanel(
                    state = moltbookStats,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
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
            // Панель настроек: движок голосового распознавания (system | whisper).
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
            // Панель выбора голосового движка (system | whisper). Отдельная от showSettings:
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
                                .clickable {
                                    sttEngine = "whisper"
                                    // Модель НЕ подставляется здесь: её выбирает человек
                                    // списком ниже. Раньше движок молча затирал модель на
                                    // base, и выбрать turbo было невозможно — настройка
                                    // «движок» затирала настройку «модель».
                                    prefs.edit().putString("stt_engine", "whisper").apply()
                                }.padding(vertical = 6.dp),
                    ) {
                        Text(
                            if (sttEngine ==
                                "whisper"
                            ) {
                                "● "
                            } else {
                                "○ "
                            },
                            color = Color(0xFFFF6D00),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("Локальные модели (whisper.cpp / sherpa-onnx)", color = Color(0xFFE6E6E6), fontSize = 13.sp)
                    }
                    // Список моделей — только для локального движка.
                    if (sttEngine == "whisper") {
                        SttModelPicker(
                            selected = whisperModelId,
                            onSelect = { picked ->
                                whisperModelId = picked
                                prefs.edit().putString("stt_model_whisper", picked).apply()
                            },
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
                                "Модель Supertonic не скачана — скачай её в Настройки -> Диагностика"
                            },
                            color = Color(0xFF90A4AE),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(start = 20.dp, top = 2.dp),
                        )
                    }
                    if (ttsOn) {
                        TtsRadioRow("Supertonic (offline, русский)", ttsConfig.engine == TtsEngine.Sherpa) {
                            ttsConfig = ttsConfig.copy(engine = TtsEngine.Sherpa)
                            TtsConfig.save(context, ttsConfig)
                            TtsNarrator.stop()
                        }
                        TtsRadioRow("ElevenLabs (облако, нужен VPN)", ttsConfig.engine == TtsEngine.ElevenLabs) {
                            ttsConfig = ttsConfig.copy(engine = TtsEngine.ElevenLabs)
                            TtsConfig.save(context, ttsConfig)
                            TtsNarrator.stop()
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
                        if (ttsConfig.engine.isCloud) {
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
                                Text(
                                    when {
                                        !elevenHasKey -> "API-ключ не задан"
                                        else ->
                                            "API-ключ сохранён " +
                                                (ElevenLabsSecret.preview(context) ?: "(зашифрован)")
                                    },
                                    color = if (elevenHasKey) ElevenKeyPalette.HintOk else ElevenKeyPalette.HintMissing,
                                    fontSize = 11.sp,
                                )
                                BasicTextField(
                                    value = elevenKeyDraft,
                                    onValueChange = {
                                        elevenKeyDraft = it
                                        // Правка черновика — новая попытка, поэтому
                                        // старая ошибка больше не про текущий текст.
                                        elevenKeyError = null
                                    },
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
                                            if (elevenKeyDraft.isEmpty()) {
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
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "Сохранить",
                                        color = if (elevenKeyDraft.isBlank()) ElevenKeyPalette.SaveIdle else ElevenKeyPalette.SaveReady,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clickable(enabled = elevenKeyDraft.isNotBlank()) {
                                                when (val r = ElevenLabsSecret.save(context, elevenKeyDraft)) {
                                                    is ElevenLabsSecret.SaveResult.Ok -> {
                                                        elevenKeyDraft = ""
                                                        elevenHasKey = true
                                                        elevenKeyError = null
                                                        // Ключ сменился — движок надо пересоздать,
                                                        // иначе он продолжит работать со старым.
                                                        TtsNarrator.stop()
                                                    }
                                                    is ElevenLabsSecret.SaveResult.Failed -> {
                                                        // Черновик НЕ чистим: ключ юзера остаётся
                                                        // в поле, чтобы он не вводил его заново
                                                        // из-за сбоя хранилища.
                                                        elevenKeyError = r.reason
                                                    }
                                                }
                                            }.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                    if (elevenHasKey) {
                                        Text(
                                            "Удалить",
                                            color = ElevenKeyPalette.Danger,
                                            fontSize = 12.sp,
                                            modifier = Modifier
                                                .clickable {
                                                    ElevenLabsSecret.clear(context)
                                                    elevenHasKey = false
                                                    elevenKeyError = null
                                                    TtsNarrator.stop()
                                                }.padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                                // Причина отказа от сохранения — иначе юзер видит
                                // неподвижную кнопку и гадает, куда делся ключ.
                                elevenKeyError?.let { why ->
                                    Text(
                                        "не сохранено: $why",
                                        color = ElevenKeyPalette.Danger,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                Text(
                                    "Voice ID:",
                                    color = Color(0xFF8A8A8A),
                                    fontSize = 11.sp,
                                )
                                BasicTextField(
                                    value = ttsConfig.elevenVoice,
                                    onValueChange = {
                                        ttsConfig = ttsConfig.copy(elevenVoice = it)
                                        TtsConfig.save(context, ttsConfig)
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
                                    "Модель: ${ttsConfig.elevenModel}",
                                    color = Color(0xFF8A8A8A),
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        // Локальный sid к облаку отношения не имеет: там голос выбирается
                        // Voice ID выше. Показывать 1..10 при выбранном ElevenLabs — значит
                        // предлагать юзеру переключатель, который ничего не делает.
                        if (!ttsConfig.engine.isCloud) {
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
                                            val active = ttsConfig.sid == sid
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
                                                        ttsConfig = ttsConfig.copy(sid = sid)
                                                        TtsConfig.save(context, ttsConfig)
                                                        TtsNarrator.stop()
                                                    }.padding(horizontal = 12.dp, vertical = 5.dp),
                                            )
                                        }
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
            // The empty-state art fades out on the first message instead of
            // blinking off. It is driven here rather than inside the branch that
            // draws it, because a branch flip cannot be animated.
            val emptyAlpha by animateFloatAsState(
                targetValue = if (msgs.isEmpty()) 1f else 0f,
                animationSpec = tween(durationMillis = 450),
                label = "empty-art",
            )
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
            // The box keeps weight(1f) whatever is inside it: it is what pins
            // the input row with the mic and Stop to the bottom of the screen.
            // Without a flexible child the column collapses to its content and
            // the input rides to the top, which is what a new or cleared session
            // used to do.
            //
            // The octopus is composed even while the feed has messages, so it can
            // fade out instead of blinking off. It sits under the feed.
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                EmptyChatArt(alpha = emptyAlpha)
                if (msgs.isNotEmpty()) {
                    LazyColumn(
                        state = listState,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Позиционный ключ (без кастомного key). Кастомный key из контента
                        // крашил LazyColumn (Key "…was already used") при дубликатах сообщений
                        // (например, дважды отправленная команда «Стой»). Лента append-only,
                        // добавление в конец не трогает существующие позиции — позиционный
                        // ключ безопасен и полностью устраняет этот краш.
                        itemsIndexed(msgs) { idx, m ->
                            // Печать — только ПОСЛЕДНЕЙ реплике модели и только один
                            // раз на её текст. Ключ гасится в onTyped: иначе каждая
                            // пересборка ленты (опрос сервера каждые доли секунды)
                            // начинала бы печать заново, а возврат в чат
                            // перепечатывал бы всю историю.
                            // Условие «последняя» обязательно: без него печаталась бы
                            // каждая реплика, ключ которой ещё не погашен, то есть
                            // вся лента целиком при каждом новом ответе.
                            val last = idx == msgs.lastIndex
                            // Пустой ответ модели (шаг с одними вызовами
                            // инструментов) печатать нечего: MessageText для
                            // него вообще не вызывается, значит и гасить ключ
                            // некому — иначе typingKey навсегда остался бы на
                            // предыдущем ответе.
                            val printable = last && m.role == "assistant" && m.text.isNotBlank()
                            MessageRow(
                                m,
                                snapshot?.modelName ?: "Модель",
                                modelFont,
                                modelColor,
                                onTyped =
                                    if (printable && typingKey != typedKeyOf(m)) {
                                        { typingKey = typedKeyOf(m) }
                                    } else {
                                        null
                                    },
                            )
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
            // Запрос, на котором сервер ждёт: команда под «ask» или вопрос
            // агента. Приколот над полем ввода, а не в ленте. В ленте он был
            // последним элементом, и автоскролл уезжал на индекс конца
            // сообщений — то есть на строку «думаю», а карточка оставалась под
            // сгибом. Блокирующий вопрос обязан быть виден всегда, поэтому
            // скроллить его не нужно вовсе.
            snapshot?.ask?.firstOrNull()?.let { card ->
                Box(modifier = Modifier.padding(top = 8.dp)) {
                    AskCardView(
                        card = card,
                        busy = askBusy,
                        error = askError,
                        onReply = { m -> answerAsk(card.ask, "once", m) },
                        onReject = { answerAsk(card.ask, "reject", null) },
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
                // Поле ввода с микрофоном внутри. Раньше три круга по 46 dp стояли
                // в одном ряду и съедали 162 dp из 339: на текст оставалось меньше
                // половины ширины экрана. Микрофон переехал внутрь поля — высота
                // блока не изменилась (46 dp), а текст стал шире на 46 dp.
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
                        onValueChange = { draft = it },
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
                        keyboardActions = KeyboardActions(onSend = { send() }),
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
// Микрофон живёт в поле, а не рядом с ним, и уходит, как только
                    // пользователь начал печатать: в пустом поле он занимает
                    // место, в котором нет смысла держать голосовой ввод.
                    // Пока идёт запись (listening) он остаётся — иначе нечем
                    // остановить её.
                    AnimatedVisibility(
                        visible = draft.isEmpty() || listening,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
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
                                modifier = Modifier.fillMaxSize().padding(8.dp),
                            )
                        }
                    }
                }
                // Кнопка Stop: ВСЕГДА видна рядом с микрофоном.
                // Прерывает текущую генерацию модели (POST /session/{id}/abort).
                // Если модель не думает — abort просто не сработает, сессия не сломается.
                Surface(
                    modifier =
                        Modifier
                            .padding(start = 8.dp)
                            .size(38.dp)
                            .clickable(enabled = !stopping) { stopGen() },
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
        colors = GaugeColors(active, Color(palette.track)),
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
        colors = GaugeColors(active, Color(palette.track)),
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

/**
 * Активная заливка и подложка индикатора одной парой.
 *
 * Два цвета всегда приходят вместе — по одному полосу не нарисовать, — а держать
 * их отдельными параметрами раздувало список аргументов [GaugeBars] до шести.
 */
private data class GaugeColors(
    val active: Color,
    val track: Color,
)

@Composable
private fun GaugeBars(
    ratio: Float,
    colors: GaugeColors,
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
        drawPath(leanBand(0f, bandW, lean, h), colors.track)
        if (fill > 0f) {
            drawPath(leanBand(0f, bandW * fill, lean, h), colors.active)
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

/**
 * Текст реплики.
 *
 * [onTyped] == null — печатать не надо (своя реплика, служебная строка, уже
 * напечатанный ответ). Непустой — текст проявляется по буквам, и колбэк
 * вызывается один раз, когда печать дошла до конца.
 */
@Composable
private fun MessageText(
    m: ChatMsg,
    isModel: Boolean,
    modelFont: FontFamily,
    modelColor: Color,
    onTyped: (() -> Unit)?,
) {
    val style =
        TextStyle(
            color = if (isModel) modelColor else Color(0xFFEDEDED),
            fontFamily = if (isModel) modelFont else FontFamily.Default,
            fontSize = 14.sp,
            lineHeight = 19.sp,
        )
    // Один и тот же composable в обоих случаях: смена ветки означала бы, что при
    // гашении ключа поддерево печати выбрасывается из композиции, а вместе с ним
    // и remember с накопленным прогрессом. Здесь меняется только флаг.
    TypewriterText(
        text = m.text,
        animate = onTyped != null,
        style = style,
        key = typedKeyOf(m),
        onTyped = { onTyped?.invoke() },
    )
}

@Composable
private fun MessageRow(
    m: ChatMsg,
    modelName: String = "Модель",
    modelFont: FontFamily = FontFamily.Monospace,
    modelColor: Color = Color(0xFFD97706),
    onTyped: (() -> Unit)? = null,
) {
    val isModel = m.role == "assistant"
    val roleColor =
        when (m.role) {
            "user" -> Color(0xFF8AB4F8)
            "assistant" -> Color(0xFF9C27B0)
            "error" -> Color(ERROR_ROLE_ARGB)
            else -> Color(0xFFB0B0B0)
        }
    val roleLabel =
        when (m.role) {
            "user" -> "Ты"
            "assistant" -> modelName
            // Отдельная роль, а не «Система»: ход, у которого осталась только
            // причина отказа, показываем как отказ, иначе он читается как
            // служебная реплика приложения.
            "error" -> "Ошибка"
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
            MessageText(
                m = m,
                isModel = isModel,
                modelFont = modelFont,
                modelColor = modelColor,
                onTyped = onTyped,
            )
        }
        // Причина отказа лежит отдельно от текста, поэтому и рисуется отдельно:
        // под репликой модели её не спутать с самим ответом.
        if (m.notice != null) {
            SelectionContainer {
                Text(
                    m.notice,
                    color = Color(ERROR_ROLE_ARGB),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Ключ реплики для анимации печати.
 *
 * Ключ — идентификатор сообщения от сервера. Индекс в ленте для этого не годится:
 * лента обрезается до MAX_SHOWN, при каждом обновлении индексы прыгают, и уже
 * напечатанная реплика начинает печататься заново (жалоба пользователя 14.10).
 * Длина текста тоже не годится по той же причине — она меняется при дописывании.
 * У клиентских реплик (своё сообщение) идентификатора нет, поэтому fallback —
 * роль и длина: две такие реплики подряд дадут разные ключи, лишь одинаковые
 * тексты совпадут, и второй просто не напечатается.
 */
private fun typedKeyOf(m: ChatMsg): String = m.id.ifBlank { "${m.role}:${m.text.length}" }

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
