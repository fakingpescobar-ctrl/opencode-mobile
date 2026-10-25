package org.opencode.mobile.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import org.opencode.mobile.stt.WhisperTranscribeService
import org.opencode.mobile.tts.TtsConfig
import org.opencode.mobile.tts.TtsEngine
import org.opencode.mobile.tts.TtsNarrator
import org.opencode.mobile.ui.chat.AndroidElevenKeyStore
import org.opencode.mobile.ui.chat.AndroidTtsStore
import org.opencode.mobile.ui.chat.AudioRecorder
import org.opencode.mobile.ui.chat.CONTEXT_LIMIT
import org.opencode.mobile.ui.chat.ChatAppearance
import org.opencode.mobile.ui.chat.ChatCache
import org.opencode.mobile.ui.chat.ChatPollState
import org.opencode.mobile.ui.chat.ContextGauge
import org.opencode.mobile.ui.chat.DeadRow
import org.opencode.mobile.ui.chat.ElevenKeySettings
import org.opencode.mobile.ui.chat.LiveToolRow
import org.opencode.mobile.ui.chat.MCPIndicator
import org.opencode.mobile.ui.chat.McpServerList
import org.opencode.mobile.ui.chat.MessageRow
import org.opencode.mobile.ui.chat.NarrationInput
import org.opencode.mobile.ui.chat.NoticeRow
import org.opencode.mobile.ui.chat.QuotaBadge
import org.opencode.mobile.ui.chat.ServerSendTransport
import org.opencode.mobile.ui.chat.SharedPreferencesAppearanceStore
import org.opencode.mobile.ui.chat.SharedPreferencesVoiceStore
import org.opencode.mobile.ui.chat.StalledRow
import org.opencode.mobile.ui.chat.ThinkingRow
import org.opencode.mobile.ui.chat.TtsNarration
import org.opencode.mobile.ui.chat.TtsRadioRow
import org.opencode.mobile.ui.chat.TtsSettings
import org.opencode.mobile.ui.chat.VoiceEngine
import org.opencode.mobile.ui.chat.VoiceSettings
import org.opencode.mobile.ui.chat.ZenMeter
import org.opencode.mobile.ui.chat.abortSession
import org.opencode.mobile.ui.chat.advancePoll
import org.opencode.mobile.ui.chat.answerPendingAsk
import org.opencode.mobile.ui.chat.createSession
import org.opencode.mobile.ui.chat.createSpeechRecognizer
import org.opencode.mobile.ui.chat.deleteSession
import org.opencode.mobile.ui.chat.deliverPrompt
import org.opencode.mobile.ui.chat.fetchChatSnapshot
import org.opencode.mobile.ui.chat.playNotificationSound
import org.opencode.mobile.ui.chat.pruneEmptySessions
import org.opencode.mobile.ui.chat.readWavPcm16
import org.opencode.mobile.ui.chat.sessionRunning
import org.opencode.mobile.ui.chat.stopAndPurge
import org.opencode.mobile.ui.chat.typedKeyOf
import org.opencode.mobile.ui.chat.vibrate
import java.io.File
import java.util.Locale

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

// Миллисекунд в минуте для паузы между обходами сессий.
private const val MS_PER_MINUTE = 60_000L

// Геометрия и цвета полос расхода живут в GaugeStyle.kt и меняются пользователем
// из панели шестерёнки в шапке. Констант GAUGE_* здесь больше нет намеренно:
// именно из-за того, что они были константами, любую правку приходилось делать
// через меня и пересборку APK.

/** Оранжевый плашки «озвучка переехала на другой движок»: предупреждение, не ошибка. */
private const val TTS_FALLBACK_NOTICE_COLOR = 0xFFFFB74D

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
    // Черновик переживает и пересоздание Activity, и убийство процесса: строка —
    // saveable тип, а терять набранный текст из-за смены размера шрифта или
    // возврата из другого приложения нельзя. Остальные состояния НЕ saveable
    // намеренно: восстановленный sending=true оставил бы кнопку вечно занятой
    // (корутины отправки после смерти процесса уже нет), а noticeLatch и typingKey
    // восстановились бы без своего контекста.
    var draft by rememberSaveable { mutableStateOf("") }
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
    // Счётчик размера ленты на прошлой автопрокрутке. Если лента ВЫРОСЛА
    // (появилось новое сообщение — от юзера или модели) — принудительно
    // спускаемся к низу, даже если юзер перед этим листал вверх (userScrolledUp).
    var prevMsgCount by remember { mutableIntStateOf(0) }
    var userScrolledUp by remember { mutableStateOf(false) }

    // Шрифт и цвет ответов модели плюс полосы расхода: шесть значений и правила их
    // записи в prefs живут в ChatAppearance, где их держит юнит-тест. Здесь остаются
    // только производные для отрисовки.
    val prefs = remember { context.getSharedPreferences("chat_overlay", Context.MODE_PRIVATE) }
    val appearance = remember { ChatAppearance(SharedPreferencesAppearanceStore(prefs)) }
    val modelFont = remember(appearance.fontKey) { fontFor(appearance.fontKey) }
    val modelColor = remember(appearance.colorHex) { parseHexColor(appearance.colorHex) }

    // Голосовой ввод: системный распознаватель (SpeechRecognizer). Удержание кнопки — запись, отпускание — распознавание и отправка.
    val speechRecognizer = remember { createSpeechRecognizer(context) }
    var listening by remember { mutableStateOf(false) }
    var speechError by remember { mutableStateOf<String?>(null) }
    var whisperBusy by remember { mutableStateOf(false) }
    // Движок распознавания и модель whisper.cpp: значения и правила их чтения
    // (миграция выпиленного ncnn, отбраковка неизвестной модели) живут в
    // VoiceSettings, где их держит юнит-тест.
    val voice = remember { VoiceSettings(SharedPreferencesVoiceStore(prefs)) }

    // Модель выбирается из каталога [SttModels]; в продукте она одна
    // (small), но ключ остаётся прежним: перевыбор в будущем не должен
    // ломать сохранённую настройку.

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
    fun sttModel(): String = voice.sttModel()
    // Движок больше не переключается принудительно: whisper.cpp собран
    // с GGML_VULKAN=ON под Adreno и выбирается пользователем.
    // Полноэкранная диагностика: состояние сервера, STT-модели, лог serve.
    // Оверлей рисуется ПОСЛЕДНИМ в корневом Surface — поверх чата и панелей.
    var showDiagnostics by remember { mutableStateOf(false) }

    // Озвучка ответов (§12.3/§12.5). Выключатель — здесь же, в панели STT.
    // Настройки и правило «включённость = engine != Off» живут в TtsSettings.
    val tts = remember { TtsSettings(AndroidTtsStore(context)) }
    val ttsOn = tts.on
// Причина отката движка, если озвучка пошла не тем голосом, что выбран.
    // Источник - поток TtsNarrator: движок грузится лениво на первом ответе, то
    // есть задолго после открытия панели, и опрос с потолком в несколько секунд
    // показывал бы причину отката через раз. collectAsState перерисовывает панель
    // и когда движок пересобрался сам (докачали модель, вставили ключ).
    val ttsFallback by TtsNarrator.fallbackReason.collectAsState()
    // Ключ ElevenLabs: черновик, отметка «задан» и причина отказа. Правило «при
    // сбое сохранения черновик не чистим» живёт в ElevenKeySettings с тестом.
    val elevenKey = remember { ElevenKeySettings(AndroidElevenKeyStore(context)) }
    // Курсор озвучки: какие реплики уже отданы нарратору и в какой сессии.
    // Правила («первый снапшот не читает историю», «смена сессии гасит речь»,
    // «новый вопрос сбрасывает курсор») живут в TtsNarration, где их держит тест.
    val ttsNarration = remember { TtsNarration() }
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
            // Создание сессии и отправка — одна операция, и правило «сервер мог
            // сохранить сообщение и всё равно ответить ошибкой» живёт в
            // ui/chat/ChatSendFlow.kt с тестом. Здесь только вызов и реакция UI.
            val outcome =
                withContext(Dispatchers.IO) {
                    deliverPrompt(ServerSendTransport(serverPort, context), sessionId, text)
                }
            sending = false
            if (outcome.delivered) {
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
                outcome.createdSession?.let {
                    android.util.Log.w("ChatOverlay", "откат новой сессии $it после сбоя отправки")
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
        Log.d("VOICE", "startVoice: engine=${voice.sttEngine}")
        if (voice.sttEngine == VoiceEngine.WHISPER) {
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
        if (voice.sttEngine == VoiceEngine.WHISPER) {
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
        // Тик опроса — одно решение из нескольких правил: зависание хода, бюджет
        // повторов, защёлка причины, дельта-публикация и адаптивная пауза. Всё это
        // живёт в ui/chat/ChatPollTick.kt с тестом; здесь только цикл и побочные
        // эффекты: запрос снапшота, обрыв хода, озвучка и запись в состояние Compose.
        var poll = ChatPollState()
        while (true) {
            // Экономия батареи/CPU в фоне: если Activity не видна (на заднем плане),
            // обновлять UI бессмысленно. Пропускаем поллинг и спим длинным квантом.
            // При возврате в foreground первый же тик подхватит актуальный snapshot
            // (задержка ≤2с — незаметно, т.к. экран просыпается). Процесс живой,
            // сервер НЕ трогается — это лишь пауза обновления скрытого чата.
            if (lifecycleState.currentState != Lifecycle.State.RESUMED) {
                delay(2_000L)
                poll = poll.copy(stableRounds = 0)
                continue
            }
            // Счётчик Zen обновляем отдельным циклом раз в минуту (см. LaunchedEffect
            // ниже). Здесь только читаем кэш: поллинг чата идёт 2.5 раза/с, обход
            // сессий туда не втыкаем.
            val decision =
                advancePoll(
                    previous = poll,
                    shown = snapshot,
                    fresh = fetchChatSnapshot(serverPort),
                    now = System.currentTimeMillis(),
                )
            poll = decision.state
            noticeLatch = decision.latch
            knownMsgs = decision.knownMsgs
            decision.baselineRendered?.let { knownRendered = it }
            decision.abort?.let { abort ->
                android.util.Log.w(
                    "ChatOverlay",
                    "RETRY budget spent attempt=${abort.attempt} session=${abort.sessionId}, aborting",
                )
                // Сеть на главном потоке: этот вызов делает блокирующий
                // HttpURLConnection, как и остальные abortSession в файле.
                withContext(Dispatchers.IO) { abortSession(serverPort, abort.sessionId) }
            }
            val publish = decision.publish
            if (publish != null) {
                snapshot = publish
                // Озвучка: раскладываем растущий текст ответа на предложения.
                // Все правила (первый снапшот не читает историю, смена сессии
                // гасит речь, новый вопрос сбрасывает курсор, живое сообщение
                // уходит на каждом снапшоте) живут в TtsNarration, где их держит
                // тест. Здесь только применяем решение: реплики — текущий оборот,
                // срез после последнего вопроса, пустые отброшены.
                val lastUserIdx = publish.messages.indexOfLast { it.role == "user" }
                val plan =
                    ttsNarration.onSnapshot(
                        NarrationInput(
                            sessionId = publish.activeId,
                            userCount = publish.messages.count { it.role == "user" },
                            thinking = publish.thinking,
                            assistants = publish.messages
                                .drop(lastUserIdx + 1)
                                .filter { it.role == "assistant" && it.text.isNotEmpty() }
                                .map { it.text },
                        ),
                    )
                if (plan.stop) TtsNarrator.stop()
                if (ttsOn && plan.speak) {
                    if (plan.finishPrevious) TtsNarrator.onNewAssistantMessage()
                    plan.finalMessages.forEach {
                        TtsNarrator.onAssistantText(context, it, isFinal = true)
                    }
                    plan.liveText?.let {
                        TtsNarrator.onAssistantText(context, it, isFinal = plan.liveIsFinal)
                    }
                }
            }
            delay(decision.delayMs)
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
                    shape = appearance.ctxShape,
                    palette = appearance.palette,
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
                shape = appearance.zenShape,
                palette = appearance.palette,
                modifier = Modifier.padding(top = appearance.barGap.dp).testTag(TAG_ZEN_METER),
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
                                    appearance.chooseColor(
                                        "%06X".format(
                                            android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, v)) and 0xFFFFFF
                                        )
                                    )
                                }
                            }.pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { off ->
                                        val h = (off.x / pickSize).coerceIn(0f, 1f) * 360f
                                        val v = 1f - (off.y / pickSize).coerceIn(0f, 1f)
                                        appearance.chooseColor(
                                            "%06X".format(
                                                android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, v)) and 0xFFFFFF
                                            )
                                        )
                                    },
                                    onDrag = { change, _ ->
                                        val h = (change.position.x / pickSize).coerceIn(0f, 1f) * 360f
                                        val v = 1f - (change.position.y / pickSize).coerceIn(0f, 1f)
                                        appearance.chooseColor(
                                            "%06X".format(
                                                android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, v)) and 0xFFFFFF
                                            )
                                        )
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
                        Text(
                            "#${appearance.colorHex}",
                            color = Color(0xFFBDBDBD),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 10.dp)
                        )
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
                        ctxShape = appearance.ctxShape,
                        onCtxShapeChange = { appearance.ctxShape = it },
                        onCtxShapePersist = { appearance.persistCtxShape() },
                        zenShape = appearance.zenShape,
                        onZenShapeChange = { appearance.zenShape = it },
                        onZenShapePersist = { appearance.persistZenShape() },
                        palette = appearance.palette,
                        onColorChange = { k, c -> appearance.choosePaletteColor(k, c) },
                        onPalettePersist = { /* цвета уже записаны по клику */ },
                        barGap = appearance.barGap,
                        onBarGapChange = { appearance.barGap = it },
                        onBarGapPersist = { appearance.persistBarGap() },
                        onResetDefaults = { appearance.resetGauges() },
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
                                    voice.chooseEngine(VoiceEngine.SYSTEM)
                                }.padding(vertical = 6.dp),
                    ) {
                        Text(
                            if (voice.sttEngine == VoiceEngine.SYSTEM) {
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
                                    voice.chooseEngine(VoiceEngine.WHISPER)
                                    // Модель НЕ подставляется здесь: её выбирает человек
                                    // списком ниже. Раньше движок молча затирал модель на
                                    // base, и выбрать turbo было невозможно — настройка
                                    // «движок» затирала настройку «модель».
                                }.padding(vertical = 6.dp),
                    ) {
                        Text(
                            if (voice.sttEngine == VoiceEngine.WHISPER) {
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
                    if (voice.sttEngine == VoiceEngine.WHISPER) {
                        SttModelPicker(
                            selected = voice.whisperModelId,
                            onSelect = { picked -> voice.chooseWhisperModel(picked) },
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
                        if (tts.config.engine.isCloud) {
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
                        }
                        // Локальный sid к облаку отношения не имеет: там голос выбирается
                        // Voice ID выше. Показывать 1..10 при выбранном ElevenLabs — значит
                        // предлагать юзеру переключатель, который ничего не делает.
                        if (!tts.config.engine.isCloud) {
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
                                        appearance.chooseFont(key)
                                        closeHeaderPanel(PANEL_FONT)
                                    }.padding(vertical = 6.dp),
                        ) {
                            Text(
                                if (key == appearance.fontKey) "● " else "○ ",
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
