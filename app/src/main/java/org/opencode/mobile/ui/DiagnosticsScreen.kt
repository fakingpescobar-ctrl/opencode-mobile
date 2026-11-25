package org.opencode.mobile.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencode.mobile.BuildConfig
import org.opencode.mobile.OpencodeApp
import org.opencode.mobile.engine.Engine
import org.opencode.mobile.engine.EngineStore
import org.opencode.mobile.engine.GgufModel
import org.opencode.mobile.engine.GgufStore
import org.opencode.mobile.installer.InstalledAppController
import org.opencode.mobile.media.MediaUiAutomation
import org.opencode.mobile.media.ScreenshotOutcome
import org.opencode.mobile.media.screenshotFile
import org.opencode.mobile.server.CoreUpdater
import org.opencode.mobile.server.OpencodeServerService
import org.opencode.mobile.server.OpencodeServerService.ServerStatus
import org.opencode.mobile.server.Retention
import org.opencode.mobile.server.RuntimeError
import org.opencode.mobile.server.RuntimeStage
import org.opencode.mobile.server.RuntimeValidation
import org.opencode.mobile.server.Workspace
import org.opencode.mobile.stt.ModelDownloader
import org.opencode.mobile.stt.SttModels
import org.opencode.mobile.tts.TtsModelDownloader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Полноэкранная диагностика приложения: состояние сервера (статус, порт,
 * рестарты, ошибки), STT-модели (готовность/размеры/место), системная инфа
 * (версия приложения, RAM, SDK) и хвост лога opencode serve. Всё собирается
 * в текстовый дамп и отдаётся системным шером («Поделиться диагностикой» —
 * для отчётов о багах). Открывается из шапки чата (иконка Info), рисуется
 * ПОВЕРХ всего оверлея (последний child Surface в ChatOverlay, непрозрачный).
 * Закрывается иконкой ✕ или системным Back (BackHandler).
 */

/** Сколько последних сбоев показываем в UI и дампе (кольцо хранит до MAX_ERROR_HISTORY). */
private const val HISTORY_SHOWN = 5
private const val PERCENT_MAX = 100

/** Подпись автора автоотчёта о сбое: те же ключи, что использует FailureReporter. */
private const val CRASH_NAME_PREFS = "failure_reporter"
private const val CRASH_NAME_KEY = "reporter_name"
private const val CRASH_NAME_DEFAULT = "USER"
private const val CRASH_NAME_MAX = 60

/** Сколько строк вывода retention показывать на экране (хвост, не дамп). */
private const val RETENTION_OUTPUT_TAIL_LINES = 12

/** Состояние скачивания нативного движка. Отделено от моделей: те идут своим каналом. */
/** Приглушённый серо-голубой для пояснений. Константой, потому что detekt
 *  считает второе появление одного и того же литерала в файле магическим числом. */
private const val NOTE_ARGB = 0xFF90A4AEL

private sealed interface EngineDownloadState {
    data object Idle : EngineDownloadState

    data class Running(
        val engine: Engine,
        val done: Long,
        val total: Long,
    ) : EngineDownloadState

    data class Failed(
        val message: String,
    ) : EngineDownloadState
}

/** Состояние закачки STT-модели любого Engine-типа. Tracked by model id. */
private sealed interface SttDownloadState {
    data object Idle : SttDownloadState

    data class Running(
        val modelId: String,
        val done: Long,
        val total: Long,
    ) : SttDownloadState

    data class Failed(
        val modelId: String,
        val message: String,
    ) : SttDownloadState
}

/**
 * Состояние закачки GGUF-модели для ядра памяти. Отдельное от STT/озвучки:
 * модели весят до 400 МБ и лежат рядом с `.so`, а не в каталоге моделей STT.
 */
internal sealed interface GgufDownloadState {
    data object Idle : GgufDownloadState

    data class Running(
        val model: String,
        val done: Long,
        val total: Long,
    ) : GgufDownloadState

    data class Failed(
        val model: String,
        val message: String,
    ) : GgufDownloadState
}

/**
 * Состояние закачки офлайн-модели озвучки. Отдельное от остальных закачек,
 * потому что качать её приходится не по расписанию, а после «Удалить данные»
 * в настройках Android: там сносится filesDir вместе с моделью.
 */
private sealed interface TtsDownloadState {
    data object Idle : TtsDownloadState

    data class Running(
        val done: Long,
        val total: Long,
    ) : TtsDownloadState

    data class Failed(
        val message: String,
    ) : TtsDownloadState
}

/** Снапшот моделей и хранилища, собранный один раз на IO при открытии. */
private data class StorageSnapshot(
    val free: Long,
    val used: Long,
    /**
     * Готовые ggml-модели движка whisper.cpp: идентификатор -> размер на диске.
     * Считается в IO-ветке снапшота, потому что isReady() хеширует файл целиком
     * (141 МБ у base, 1,5 ГБ у turbo) и на главном потоке froze бы экран.
     */
    val ggmlReady: Map<String, Long>,
    /**
     * Офлайн-модель озвучки Supertonic: готова ли и сколько занимает.
     * Считается в IO-ветке снапшота по точным размерам файлов: на главном
     * потоке проверка 138 МБ заметно дёрнула бы экран настроек.
     */
    val ttsVoiceReady: Boolean,
    val ttsVoiceBytes: Long,
    /**
     * Готовность СКАЧИВАЕМЫХ движков (те, что лежат в APK, не считаются):
     * сколько установлено из скольких.
     *
     * Считается в IO-ветке по той же причине, что и остальное: `isReady()`
     * сверяет размеры шести файлов движка, и хотя хеши там не считаются,
     * главному потоку это не работа.
     */
    val enginesReady: Int,
    val enginesTotal: Int,
    /**
     * Файл ядра, который реально будет запущен (скачанный или из APK), на месте.
     * Считается в IO-ветке: это файловая проверка, а не то, что стоит на главном
     * потоке. «Работает» доказывает сервер, но файл всё равно должен быть.
     */
    val coreReady: Boolean,
    val logTail: String,
    /** Единый отчёт готовности runtime-слоя (RuntimeValidation). */
    val validation: RuntimeValidation.Report,
)

@Composable
fun DiagnosticsScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val serverState by OpencodeServerService.state.collectAsState()
    val prefs = remember { context.getSharedPreferences("chat_overlay", Context.MODE_PRIVATE) }
    val reportPrefs = remember { context.getSharedPreferences(CRASH_NAME_PREFS, Context.MODE_PRIVATE) }
    var reporterName by remember {
        mutableStateOf(reportPrefs.getString(CRASH_NAME_KEY, CRASH_NAME_DEFAULT) ?: CRASH_NAME_DEFAULT)
    }
    val scope = rememberCoroutineScope()
    // Системный Back закрывает оверлей, как и иконка ✕ (иначе Back ушёл бы
    // из Activity, оставив оверлей на экране — UX-ловушка).
    BackHandler(onBack = onClose)

    // Право «наложение поверх окон» выдаётся в настройках СИСТЕМЫ, а не на этом экране,
    // поэтому состояние перечитывается при каждом возврате в приложение. Без этого экран
    // после выдачи продолжит врать «не выдано» до перезапуска.
    var overlayGranted by remember { mutableStateOf(InstalledAppController.canDrawOverlays()) }
    // Служба доступности выдаётся в настройках СИСТЕМЫ и снимается обновлением
    // APK, поэтому её состояние перечитывается там же, где наложение: на каждый
    // возврат в приложение. Иначе экран врал бы «выключена» сразу после
    // включения — ровно то, из-за чего мы гоняли скриншоты руками.
    var a11yEnabled by remember { mutableStateOf(accessibilityServiceEnabled(context)) }
    // Результат ручной проверки снимка экрана: тот же путь, что у моста
    // (/v1/screen/shot), поэтому «работает» здесь значит «работает и агенту».
    var shotNote by remember { mutableStateOf("не проверялся") }
    var shotTone by remember { mutableStateOf(DiagTone.IDLE) }
    // Снимок не «просто кнопка»: кадр может поймать чужое. Подтверждение не
    // блокирует тик — оно только стоит между нажатием и реальным снимком.
    var shotConfirm by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    overlayGranted = InstalledAppController.canDrawOverlays()
                    a11yEnabled = accessibilityServiceEnabled(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Снапшот собирается ОДИН раз при открытии, на IO-диспатчере: готовность
    // ggml-моделей (isReady хеширует файл целиком), суммарный размер файлов,
    // хвост лога serve. В композиции эти вызовы выполнялись бы на main при
    // КАЖДОЙ рекомпозиции (серверный StateFlow тикает) — фризы.
    var snap by remember { mutableStateOf<StorageSnapshot?>(null) }
    // Кнопка «Проверить сейчас» инкрементит ключ — LaunchedEffect пересобирает снапшот.
    var refreshKey by remember { mutableIntStateOf(0) }
    // Вывод последнего ручного прогона retention (расчёт или обрезка).
    var retentionOut by remember { mutableStateOf<String?>(null) }
    // Состояние скачивания STT-модели. Обновляется из IO-корутины
    // (snapshot-state потокобезопасен); выход с экрана отменяет корутину
    // (CancellationException проходит сквозь загрузку через ensureActive).
    var sttDl by remember { mutableStateOf<SttDownloadState>(SttDownloadState.Idle) }
    val startSttDownload: (String) -> Unit = { modelId ->
        if (sttDl !is SttDownloadState.Running) {
            val model = SttModels.byId(modelId)
                ?: error("в каталоге нет модели: $modelId")
            sttDl = SttDownloadState.Running(modelId, 0L, model.bytes)
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val onProgress: (Long, Long) -> Unit = { done, total ->
                            sttDl = SttDownloadState.Running(modelId, done, total)
                        }
                        // Унифицированный fetch: для ggml small идет через
                        // downloadSmall, для sherpa-onnx — через downloadSttModel.
                        model.fetch(context, onProgress)
                    }
                    sttDl = SttDownloadState.Idle
                    refreshKey++
                } catch (e: Exception) {
                    // Отмена (уход с экрана) — не ошибка: ensureActive() пробрасывает
                    // CancellationException дальше, а обычные исключения попадают в Failed.
                    currentCoroutineContext().ensureActive()
                    sttDl = SttDownloadState.Failed(modelId, e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }
    // Удаление моделей, вычеркнутых из каталога. Отдельная кнопка, а не молчаливая
    // уборка при запуске: файлы занимают сотни мегабайт, и сносить их без спроса
    // нельзя. Что именно удалилось — показываем строкой.
    var droppedModels by remember { mutableStateOf("") }
    val dropOtherModels: () -> Unit = {
        scope.launch {
            val ggmlGone = withContext(Dispatchers.IO) { ModelDownloader.deleteOtherModels(context) }
            val sttGone = withContext(Dispatchers.IO) { ModelDownloader.deleteOtherSttDirs(context) }
            val gone = ggmlGone + sttGone
            droppedModels =
                if (gone.isEmpty()) {
                    "нечего удалять — все файлы в каталоге"
                } else {
                    "удалено: " + gone.joinToString(", ") { it.name }
                }
            refreshKey++
        }
    }
    // Движок (нативная библиотека) качается отдельно от моделей: без него
    // whisper.cpp не грузится вообще, а модели бесполезны. Порядок и хеши -
    // в Engine.
    var engineDl by remember { mutableStateOf<EngineDownloadState>(EngineDownloadState.Idle) }
    val startEngineDownload = { engine: Engine ->
        // Движок из APK качать нечего: повторная запись заняла бы
        // filesDir и только заставила бы грузить его оттуда, хотя
        // System.loadLibrary и так находит его в самом APK.
        if (!engine.bundled && engineDl !is EngineDownloadState.Running) {
            engineDl = EngineDownloadState.Running(engine, 0L, engine.totalBytes)
            scope.launch {
                try {
                    EngineStore.download(context, engine) { done, total ->
                        engineDl = EngineDownloadState.Running(engine, done, total)
                    }
                    engineDl = EngineDownloadState.Idle
                    refreshKey++ // строка движка станет «установлен»
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    engineDl = EngineDownloadState.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }
    // Офлайн-модель озвучки. Её приходится качать не по расписанию, а после
    // «Удалить данные» в настройках Android: там сносится filesDir вместе с
    // моделью, и больше её неоткуда взять — в APK её нет, в кэше тоже.
    var ttsDl by remember { mutableStateOf<TtsDownloadState>(TtsDownloadState.Idle) }
    val startTtsDownload: () -> Unit = {
        if (ttsDl !is TtsDownloadState.Running) {
            ttsDl = TtsDownloadState.Running(0L, TtsModelDownloader.TOTAL_BYTES)
            scope.launch {
                try {
                    TtsModelDownloader.download(context) { done, total ->
                        ttsDl = TtsDownloadState.Running(done, total)
                    }
                    ttsDl = TtsDownloadState.Idle
                    refreshKey++
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    ttsDl = TtsDownloadState.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }
    // GGUF-модель семантического сопроцессора ядра памяти. В APK её нет:
    // каталог с URL/размером/sha256 лежит в assets, сам файл качается сюда,
    // в files/musl/ — рядом с libllama-bench.so. После сверки sha ставится .ok.
    var ggufDl by remember { mutableStateOf<GgufDownloadState>(GgufDownloadState.Idle) }
    val startGgufDownload: (GgufModel) -> Unit = { model ->
        if (ggufDl !is GgufDownloadState.Running) {
            ggufDl = GgufDownloadState.Running(model.id, 0L, model.size)
            scope.launch {
                try {
                    GgufStore.download(context, model) { done, total ->
                        ggufDl = GgufDownloadState.Running(model.id, done, total)
                    }
                    ggufDl = GgufDownloadState.Idle
                    refreshKey++
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    ggufDl = GgufDownloadState.Failed(model.id, e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }
    LaunchedEffect(refreshKey) {
        snap =
            withContext(Dispatchers.IO) {
                val modelsDir = ModelDownloader.modelsDir(context)

                // Готовность ggml-моделей для движка whisper.cpp. Именно здесь, а не
                // в теле композабла: isReady() считает sha256 файла в 465 МБ, и на
                // главном потоке это повесило бы экран настроек на секунды.
                val ggmlReady = SttModels.snapshotOf(context)
                StorageSnapshot(
                    free = ModelDownloader.freeBytes(context),
                    used = ModelDownloader.modelsUsedBytes(context),
                    ggmlReady = ggmlReady,
                    ttsVoiceReady = TtsModelDownloader.isReady(context),
                    ttsVoiceBytes = TtsModelDownloader.bytesOnDisk(context),
                    enginesReady = Engine.entries.count { !it.bundled && EngineStore.isReady(context, it) },
                    enginesTotal = Engine.entries.count { !it.bundled },
                    coreReady =
                        CoreUpdater.resolveCore(context).let { file ->
                            file.isFile && file.length() > 0L
                        },
                    logTail = readLogTail(File(context.filesDir, "opencode.log")),
                    validation = RuntimeValidation.run(context),
                )
            }
    }

    // Binder IPC (PackageManager/ActivityManager) — НЕ на каждом рекомпозе
    // (serverState тикает при работе сервера): статику собираем один раз на
    // открытие экрана. Вынесено до Surface, потому что те же цифры нужны
    // сводке в шапке.
    val pm = context.packageManager
    val appInfo =
        remember {
            runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        }
    val memInfo =
        remember {
            ActivityManager.MemoryInfo().also {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                runCatching { am.getMemoryInfo(it) }
            }
        }
    // Сведения о телефоне собираются один раз на открытие и по кнопке «Обновить».
    // registerReceiver(null, ...) и StatFs дешёвые, но в рекомпозе сервера они
    // были бы лишним Binder IPC на каждый тик — поэтому remember по refreshKey.
    val deviceRows = remember(refreshKey) { deviceFacts(context, memInfo) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MoltCard,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
            // Шапка: заголовок + «поделиться дампом» + закрыть. Капс и акцент —
            // те же, что у карточки Moltbook: два экрана приложения не должны
            // выглядеть как два разных приложения.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "ДИАГНОСТИКА",
                    color = MoltAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = "Поделиться диагностикой",
                    tint = MoltIdle,
                    modifier =
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MoltChipOff)
                            .padding(3.dp)
                            .clickable {
                                // Сбор дампа — не на main: prefs.getString и Binder IPC
                                // (PackageManager/ActivityManager) в buildDiagnosticsDump
                                // выполняются на IO, и только потом открывается share-chooser.
                                scope.launch {
                                    val dump =
                                        withContext(Dispatchers.IO) {
                                            buildDiagnosticsDump(context, serverState, prefs, snap)
                                        }
                                    shareDump(context, dump)
                                }
                            },
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Проверить сейчас",
                    tint = MoltIdle,
                    modifier =
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MoltChipOff)
                            .padding(3.dp)
                            .clickable { refreshKey++ },
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Закрыть диагностику",
                    tint = MoltIdle,
                    modifier =
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MoltChipOff)
                            .padding(3.dp)
                            .clickable { onClose() },
                )
            }

            Spacer(Modifier.height(4.dp))
            // Главная цифра экрана — как «ждут ответа» в карточке Moltbook:
            // статус сервера крупно, остальные четыре факта одной строкой.
            // Раньше они занимали четыре отдельные строки в секции «Сервер».
            ServerSummary(
                status = serverState.status,
                port = serverState.port,
                restarts = serverState.restartCount,
                ramFree = memInfo.availMem,
                sdk = Build.VERSION.SDK_INT,
            )
            // По кадру должно быть видно, какая сборка стоит на телефоне:
            // раньше версия жила только в «О программе», и снимок экрана её не
            // содержал (18.11, part103).
            BuildLine()
            CreatorCredit(Modifier.padding(top = 1.dp))

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 2.dp),
            ) {
                // --- Что видно сразу, без прокрутки --------------------------
                // Шесть фактов, ради которых экран и открывают, в двух колонках.
                // Считаются из того же состояния, что и секции ниже: расхождение
                // «зелено сверху, красно в списке» читается как баг самого экрана,
                // а не как проблема телефона.
                val snapshot = snap
                val serverTone = statusTone(serverState.status)
                val permsOk = overlayGranted && a11yEnabled
                val sttTone =
                    when {
                        snapshot == null -> DiagTone.IDLE
                        // Системный движок: модели из каталога не участвуют,
                        // значит и «нет модели» здесь не проблема.
                        sttEngineIsSystem(prefs) -> DiagTone.OK
                        snapshot.ggmlReady.isEmpty() -> DiagTone.WARN
                        else -> DiagTone.OK
                    }
                val ttsTone =
                    when {
                        snapshot == null -> DiagTone.IDLE
                        snapshot.ttsVoiceReady -> DiagTone.OK
                        else -> DiagTone.WARN
                    }
                // Движки: «ок» только когда установлено всё, что качается
                // отдельно. Ни одного — серое «не настроено» (движок — опция,
                // а не поломка); часть — «внимание»: половина работает,
                // половина нет. До 17.11 тон был прибит к IDLE, поэтому у
                // человека с установленными движками горел серый кружок и не
                // было «ок», хотя все строки внутри были зелёными.
                val enginesTone =
                    when {
                        snapshot == null -> DiagTone.IDLE
                        snapshot.enginesReady == snapshot.enginesTotal -> DiagTone.OK
                        snapshot.enginesReady == 0 -> DiagTone.IDLE
                        else -> DiagTone.WARN
                    }
                // «Система» — информационная секция: здоровья там проверять нечего.
                // Её «ок» означает только «все данные собраны» (пакет прочитан,
                // ABI известен), а не «система исправна». Если appInfo прочитать
                // не удалось — честное «внимание», а не зелёный бейдж.
                val systemTone =
                    when {
                        snapshot == null || appInfo == null -> DiagTone.IDLE
                        else -> DiagTone.OK
                    }
                // Ядро: файл на месте + сервер реально отвечает. Одна только
                // надпись «в комплекте» доказательством работы не была бы.
                val coreTone =
                    when {
                        snapshot == null -> DiagTone.IDLE
                        !snapshot.coreReady -> DiagTone.BAD
                        serverState.status != ServerStatus.RUNNING -> DiagTone.WARN
                        else -> DiagTone.OK
                    }
                val validation = snapshot?.validation
                val validationTone =
                    when {
                        validation == null -> DiagTone.IDLE
                        validation.allOk -> DiagTone.OK
                        else -> DiagTone.BAD
                    }
                DiagTiles(
                    listOf(
                        DiagFact("Сервер", statusLabel(serverState.status), serverTone),
                        DiagFact(
                            "Наложение",
                            if (overlayGranted) "выдано" else "не выдано",
                            if (overlayGranted) DiagTone.OK else DiagTone.BAD,
                        ),
                        DiagFact(
                            "Служба доступности",
                            if (a11yEnabled) "включена" else "выключена",
                            if (a11yEnabled) DiagTone.OK else DiagTone.BAD,
                        ),
                        // При системном движке модели из каталога приложения не
                        // участвуют вовсе — «модель готова» тут было неправдой
                        // (плитка зелёная, а строка parakeet внутри красная).
                        DiagFact(
                            "Распознавание",
                            if (sttEngineIsSystem(prefs)) "системный движок" else toneWord(sttTone, "модель готова", "нет модели"),
                            sttTone,
                        ),
                        DiagFact("Озвучка", toneWord(ttsTone, "готова", "не скачана"), ttsTone),
                        DiagFact("Runtime", toneWord(validationTone, "всё готово", "есть проблемы"), validationTone),
                    ),
                )

                // Единственное место на экране, где он не рассказывает, а предлагает
                // нажать. Раньше такие кнопки были размазаны по пяти секциям, и
                // «что мне сейчас сделать» требовало прочитать весь экран.
                if (!overlayGranted || !a11yEnabled) {
                    Text(
                        "ТРЕБУЕТ ДЕЙСТВИЯ",
                        color = MoltAccent,
                        fontSize = TINY,
                        lineHeight = TINY_LINE,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    if (!overlayGranted) {
                        DiagActionRow(
                            title = "Нет права «поверх окон»",
                            detail =
                                "Android молча режет фоновый запуск приложений: команда " +
                                    "рапортует об успехе, а процесс не поднимается (BAL_BLOCK).",
                            actionLabel = "Выдать разрешение",
                        ) { openOverlaySettings(context) }
                    }
                    if (!a11yEnabled) {
                        DiagActionRow(
                            title = "Служба доступности выключена",
                            detail =
                                "Без неё не работают снимки экрана и UI-команды. Обновление APK " +
                                    "снимает её каждый раз — это состояние телефона, а не поломка моста.",
                            actionLabel = "Открыть специальные возможности",
                        ) { openAccessibilitySettings(context) }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Секция «Сервер opencode» была третьим повтором одного факта:
                // «Работает» в шапке, плашка «СЕРВЕР», и здесь «Стадия runtime —
                // здоров». Строка `Workspace` дублировала «Рабочую папку» в
                // разрешениях. Поэтому карточка теперь называется «Сбои сервера»
                // и появляется ТОЛЬКО когда есть что сказать: активная ошибка,
                // причина остановки, падение памяти или не-здоровая стадия.
                // На здоровом телефоне экран короче на две карточки (part103).
                val serverHasNews =
                    (serverState.stage != null && serverState.stage != RuntimeStage.HEALTHY) ||
                        serverState.lastError != null ||
                        serverState.stopReason != null ||
                        serverState.lastMemoryFailureAt != null ||
                        serverState.errorHistory.isNotEmpty()
                if (serverHasNews) {
                    DiagSection("Сбои сервера", serverTone) {
                        serverState.stage?.let {
                            if (it != RuntimeStage.HEALTHY) InfoRow("Стадия runtime", stageLabel(it))
                        }
                        serverState.lastError?.let { err ->
                            val resolved = err.resolvedAt != null
                            InfoRow(
                                if (resolved) "Последняя ошибка (разрешена)" else "Активная ошибка",
                                "${err.stage} / ${err.code}" +
                                    (if (err.recoverable) " (recoverable)" else " (терминальная)") +
                                    (if (resolved) "\nв ${fmtTime(err.resolvedAt)}" else "\nс ${fmtTime(err.at)}") +
                                    if (err.message.isNotBlank()) "\n${err.message}" else "",
                                if (resolved) Color(0xFFBDBDBD) else Color(0xFFFF6F5A),
                            )
                        }
                        serverState.stopReason?.let { InfoRow("Причина остановки", it.name) }
                        serverState.lastMemoryFailureAt?.let {
                            InfoRow("Память MCP упала в", fmtTime(it))
                        }
                        serverState.lastRecoveredAt?.let {
                            InfoRow("Восстановилась в", fmtTime(it))
                        }
                        if (serverState.errorHistory.isNotEmpty()) {
                            InfoRow(
                                "Сбои (последние ${serverState.errorHistory.size})",
                                historyText(serverState.errorHistory),
                            )
                        }
                    }
                }

                DiagSection("Отчёты о сбоях", DiagTone.IDLE) {
                    Text("Имя для отчётов о сбоях", color = MoltIdle, fontSize = TINY, lineHeight = TINY_LINE)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp)
                            .background(MoltChipOff, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        BasicTextField(
                            value = reporterName,
                            onValueChange = { raw ->
                                val next = raw.take(CRASH_NAME_MAX)
                                reporterName = next
                                reportPrefs
                                    .edit()
                                    .putString(
                                        CRASH_NAME_KEY,
                                        next
                                            .trim()
                                            .ifEmpty { CRASH_NAME_DEFAULT },
                                    ).apply()
                            },
                            singleLine = true,
                            textStyle = TextStyle(color = MoltValue, fontSize = SMALL, lineHeight = SMALL_LINE),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (reporterName.isEmpty()) {
                                    Text(CRASH_NAME_DEFAULT, color = MoltIdle, fontSize = SMALL, lineHeight = SMALL_LINE)
                                }
                                inner()
                            },
                        )
                    }
                    InfoRow("В отчёте", reporterName.trim().ifEmpty { CRASH_NAME_DEFAULT })
                }

                // Права собраны в одной секции, потому что чинятся они одинаково —
                // уходом в системные настройки и возвратом сюда. Раньше наложение
                // жило в «Фоновом запуске», а службы доступности в диагностике не
                // было вообще, хотя именно она чаще всего и выключена.
                DiagSection("Разрешения и снимок экрана", if (permsOk) DiagTone.OK else DiagTone.BAD) {
                    InfoRow(
                        "Наложение поверх окон",
                        if (overlayGranted) {
                            "✔ выдано — агент запускает приложения из фона"
                        } else {
                            "✘ не выдано — Android блокирует фоновый запуск (BAL_BLOCK)"
                        },
                        if (overlayGranted) Color(0xFF7BD88F) else Color(0xFFFF6F5A),
                    )
                    if (!overlayGranted) {
                        InfoRow(
                            "Что это чинит",
                            "Сервер работает фоновым сервисом без видимого окна. Android запрещает ему " +
                                "запускать activity других приложений и молча режет старт с кодом 102 — " +
                                "команда рапортует об успехе, а процесс не поднимается. Это право снимает " +
                                "запрет: Яндекс.Музыка запускается, даже если Android её убил.",
                        )
                        downloadButton("Выдать разрешение «поверх окон»") { openOverlaySettings(context) }
                    }
                    InfoRow(
                        "Служба доступности",
                        if (a11yEnabled) {
                            "✔ включена — снимок экрана и UI-команды доступны"
                        } else {
                            "✘ выключена — снимок экрана и UI-команды недоступны"
                        },
                        if (a11yEnabled) Color(0xFF7BD88F) else Color(0xFFFF6F5A),
                    )
                    if (!a11yEnabled) {
                        InfoRow(
                            "Почему выключается",
                            "Обновление APK пересчитывает набор прав и снимает службу. У сборки «не из Play» " +
                                "вдобавок спрятан пункт «Разрешить ограниченную настройку»: без него тумблер " +
                                "просто не включается.",
                        )
                        downloadButton("Открыть «Специальные возможности»") { openAccessibilitySettings(context) }
                        downloadButton("Карточка приложения (⋮ → ограниченная настройка)") {
                            openAppDetails(context)
                        }
                    }
                    InfoRow(
                        "Рабочая папка",
                        if (serverState.workspaceExternal) {
                            "внешняя (MANAGE_EXTERNAL_STORAGE)"
                        } else {
                            "внутренняя (доступ ко всем файлам не нужен)"
                        },
                    )
                    InfoRow("Снимок экрана", shotNote, shotTone.color())
                    // Проверка идёт тем же путём, что у моста (/v1/screen/shot):
                    // «работает» здесь значит «работает и агенту», а не «кнопка жива».
                    downloadButton("Проверить снимок экрана сейчас") { shotConfirm = true }

                    if (shotConfirm) {
                        AlertDialog(
                            onDismissRequest = { shotConfirm = false },
                            title = { Text("Снять экран?") },
                            text = {
                                Text(
                                    "Кадр может поймать чужое: сообщения, письма, банковские " +
                                        "приложения. Он сохранится в рабочую папку и попадёт " +
                                        "в диагностику. Снимать только если это безопасно.",
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        shotConfirm = false
                                        shotNote = "снимаю…"
                                        shotTone = DiagTone.WARN
                                        scope.launch {
                                            val outcome =
                                                withContext(Dispatchers.IO) {
                                                    MediaUiAutomation.screenshot(
                                                        screenshotFile("diag-check", System.currentTimeMillis()),
                                                    )
                                                }
                                            when (outcome) {
                                                is ScreenshotOutcome.Saved -> {
                                                    val size =
                                                        "${outcome.width}×${outcome.height} · " +
                                                            fmtBytes(outcome.bytes)
                                                    shotNote = "✔ работает · $size\n${outcome.file.absolutePath}"
                                                    shotTone = DiagTone.OK
                                                }

                                                is ScreenshotOutcome.Failed -> {
                                                    shotNote = "✘ ${outcome.reason}"
                                                    shotTone = DiagTone.BAD
                                                }
                                            }
                                        }
                                    },
                                ) { Text("Снять") }
                            },
                            dismissButton = { TextButton(onClick = { shotConfirm = false }) { Text("Отмена") } },
                        )
                    }
                }

                DiagSection("Движки распознавания и озвучки", enginesTone) {
                    // Без нативной библиотеки кнопка микрофона не даст ничего,
                    // поэтому движок показываем раньше моделей: сначала его.
                    EngineRows(engineDl, startEngineDownload)
                }

                DiagSection("Голосовое распознавание", sttTone) {
                    InfoRow("Движок", engineLabel(prefs))
                    // Модель — выбор пользователя из каталога.
                    InfoRow("Модель", currentModelLabel(prefs))
                    if (snapshot == null) {
                        InfoRow("Модели", "загрузка…")
                    } else {
                        InfoRow("Модели заняли", fmtBytes(snapshot.used))
                        InfoRow("Свободно", fmtBytes(snapshot.free))
                    }
                    if (droppedModels.isNotEmpty()) {
                        InfoRow("Уборка моделей", droppedModels, Color(NOTE_ARGB))
                    }
                    SttModelRows(
                snap = snap,
                state = sttDl,
                onDownload = { startSttDownload(it) },
                onRepeat = { startSttDownload(it) },
                onDeleteOthers = dropOtherModels,
            )
                }

                DiagSection("Озвучка (Supertonic, offline)", ttsTone) {
                    TtsVoiceRows(
                        ready = snapshot?.ttsVoiceReady == true,
                        bytesOnDisk = snapshot?.ttsVoiceBytes ?: 0L,
                        state = ttsDl,
                        onDownload = { startTtsDownload() },
                    )
                }

                // Между STT и валидацией: обе секции — про то, что человек должен один раз
                // настроить, чтобы функция вообще работала, и обе с кнопкой. Валидация
                // runtime читает итог, поэтому встаёт после них.
                DiagSection("Яндекс Музыка", DiagTone.IDLE) {
                    yandexAccountSection()
                }

                // Moltbook переехал сюда из шапки чата: на 363 dp карточка и
                // MCP-чип теснили друг друга. Рядом с Яндексом не случайно —
                // обе секции про вход, который человек настраивает один раз.
                DiagSection("Moltbook", DiagTone.IDLE) {
                    moltbookSection()
                }

                DiagSection("Журнал событий (retention)", DiagTone.IDLE) {
                    retentionSection(context, scope, retentionOut) {
                        retentionOut = it
                        refreshKey++
                    }
                }

                // Единый пульт ядра памяти: статус, инструменты, загрузчик GGUF,
                // записи и технические детали — всё внутри одной карточки, которая
                // раскрывается по тапу. Раньше это были две разрозненные секции.
                DiagSection("Memory Core ⚡", DiagTone.IDLE) {
                    MemoryCorePanel(context, refreshKey, ggufDl, startGgufDownload)
                }

                // Маршрутизация моделей рисует СВОЮ секцию [DiagSection] — ровно
                // такую же, как соседние: карточка, точка тона, заголовок капсом
                // и треугольник. Оборачивать её в ещё одну секцию не нужно:
                // получилась бы рамка в рамке. Раньше здесь была отдельная
                // карточка со своей обводкой и сеткой плашек — внутри этого
                // экрана она читалась как вклеенный чужой экран.
                ModelRoutingPicker()

                DiagSection("Валидация runtime", validationTone) {
                    if (validation == null) {
                        InfoRow("Проверка", "загрузка…")
                    } else {
                        val ok = Color(0xFF7BD88F)
                        val bad = Color(0xFFFF6F5A)
                        InfoRow(
                            "Итог",
                            if (validation.allOk) "все подсистемы готовы" else "есть проблемы",
                            if (validation.allOk) ok else bad,
                        )
                        InfoRow(
                            "Нативный runtime",
                            if (validation.nativeRuntime) "✔ ассемблирован" else "✘ libopencode.so отсутствует",
                            if (validation.nativeRuntime) ok else bad,
                        )
                        InfoRow(
                            "Basic-аутентификация",
                            if (validation.serverAuth) "✔ пароль задан" else "✘ пароль не задан",
                            if (validation.serverAuth) ok else bad,
                        )
                        InfoRow(
                            "Serve (HTTP)",
                            if (validation.serverHttp) "✔ отвечает" else "✘ не отвечает",
                            if (validation.serverHttp) ok else bad,
                        )
                        InfoRow(
                            "Локальная память (MCP)",
                            if (validation.memoryMcp) "✔ отвечает (/mcp 200)" else "✘ не отвечает по HTTP",
                            if (validation.memoryMcp) ok else bad,
                        )
                        // Две строки ниже раньше отсутствовали, хотя обе входят в
                        // [RuntimeValidation.Report.allOk]. Из-за этого «есть проблемы»
                        // стояло над четырьмя зелёными строками, и причину нельзя было
                        // найти на экране — только в коде (17.11). Правило простое:
                        // каждое слагаемое allOk обязано быть видно отдельной строкой.
                        InfoRow(
                            "Музыка (MCP)",
                            if (!validation.musicExpected) {
                                "— не проверяется (Яндекс не подключён)"
                            } else if (validation.musicMcp) {
                                "✔ отвечает (/mcp 200)"
                            } else {
                                "✘ не отвечает по HTTP"
                            },
                            // «Не проверялась» — это не «ок» и не «проблема»: серая
                            // строка, как у остальных необязательных подсистем.
                            when {
                                !validation.musicExpected -> MoltIdle
                                validation.musicMcp -> ok
                                else -> bad
                            },
                        )
                        InfoRow(
                            "Модель распознавания",
                            when {
                                !validation.sttRequired -> "— не требуется (движок: ${engineLabel(prefs)})"
                                validation.sttModelReady -> "✔ на месте"
                                else -> "✘ ${currentModelLabel(prefs)} не установлена"
                            },
                            when {
                                !validation.sttRequired -> MoltIdle
                                validation.sttModelReady -> ok
                                else -> bad
                            },
                        )
                    }
                }

                DiagSection("Система", systemTone) {
                    InfoRow("Приложение", "${appInfo?.versionName ?: "?"} (code ${appInfo?.longVersionCode ?: 0})")
                    // Штамп СБОРКИ, а не версия из манифеста. versionName навсегда
                    // останется 0.1.0 - он про продукт, а не про конкретный APK.
                    // «Какая сборка стоит» отвечает только этот ряд.
                    InfoRow("Сборка", buildStampLabel())
                    // Всё остальное — про устройство, на котором запущено приложение:
                    // прошивка, железо, экран, память, хранилище, питание и время.
                    // Идентификаторы (IMEI, серийный номер) намеренно не показываем:
                    // для диагностики они не нужны, а доступ к ним — отдельное право.
                    deviceRows.forEach { (label, value) -> InfoRow(label, value) }
                }

                DiagSection("Ядро opencode", coreTone) {
                    InfoRow("Работает", activeCoreLabel(context))
                    InfoRow("В APK", opencodeBinaryLabel())
                    coreLastCheckRow(context)
                    coreCheckRow(context, scope)
                    // Редактор правил доступен и здесь: именно ядро opencode
                    // читает AGENTS.md в начале сессии. Тот же редактор есть
                    // в Memory Core ⚡ — человек найдёт его в любом месте.
                    AgentRulesSection(context)
                }

                DiagSection("Лог сервера (хвост)", DiagTone.IDLE) {
                    // Без SelectionContainer: он конфликтует с verticalScroll по жестам
                    // (долгое нажатие перехватывается), а копирование всего лога есть
                    // через «Поделиться» — там дамп собирается свежим.
                    // Хвост читаем из filesDir/opencode.log (туда serve пишет через
                    // ProcessBuilder.appendTo; ротация .1/.2/.3) — разово на открытии.
                    if (snapshot == null) {
                        Text("Лог: загрузка…", color = MoltIdle, fontSize = SMALL)
                    } else {
                        if (snapshot.logTail.isBlank()) {
                            Text("Пусто — opencode.log ещё не создан (сервер не запускался).", color = MoltIdle, fontSize = SMALL)
                        } else {
                            Text(
                                snapshot.logTail,
                                color = MoltValue,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Ручной прогон обрезки журнала событий.
 *
 * Тик retention ходит раз в час, но для диагностики этого мало: чтобы увидеть
 * результат, пришлось бы ждать. Здесь обе кнопки запускают тот же самый код,
 * что и тик, — ничего отдельного «для диагностики» не дублируется.
 *
 * Обрезка необратима, поэтому у неё подтверждение, а расчёт — нет: расчёт
 * ничего не удаляет по построению, и лишний вопрос перед безопасным действием
 * просто приучает нажимать «да» не читая.
 */
@Composable
private fun retentionSection(
    context: Context,
    scope: CoroutineScope,
    output: String?,
    onDone: (String?) -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    var confirmApply by remember { mutableStateOf(false) }
    val logFile = File(context.filesDir, "opencode.log")

    fun run(
        apply: Boolean,
        bytes: Long,
    ) {
        if (busy) return
        busy = true
        scope.launch {
            val text =
                withContext(Dispatchers.IO) {
                    Retention.runOnce(context, logFile, apply = apply, thresholdBytes = bytes)
                }
            busy = false
            onDone(text)
        }
    }

    InfoRow(
        "Что это",
        "event в opencode - append-only журнал изменений, втрое больше итога. " +
            "Он растёт на каждый чанк стрима, и база растёт на 38 МБ в сутки. " +
            "Обрезка режет журнал, итог переписки (part) не трогает.",
    )
    InfoRow("Тик", "раз в час, первый — через 10 мин после старта сервера")
    if (busy) {
        InfoRow("Идёт", "…", Color(0xFFFFC107))
    }
    if (output != null) {
        // Вывод скрипта — это несколько коротких строк, а не дамп: режем
        // до хвоста, иначе сам экран диагностики станет источником проблемы,
        // которую мы тут чиним.
        val tail = output.trim().lines().takeLast(RETENTION_OUTPUT_TAIL_LINES)
        InfoRow(
            "Результат",
            tail.joinToString("\n"),
            if (output.contains("VACUUM")) Color(0xFF7BD88F) else Color(0xFFE6E6E6),
        )
    }
    downloadButton("Расчёт (ничего не удаляет)") {
        run(apply = false, bytes = Retention.DRYRUN_BYTES)
    }
    downloadButton("Обрезать (необратимо)", warn = true) { confirmApply = true }

    if (confirmApply) {
        AlertDialog(
            onDismissRequest = { confirmApply = false },
            title = { Text("Обрезать журнал?") },
            text = {
                Text(
                    "Удалятся события (журнал изменений) у сессий, чей журнал больше " +
                        "8 МБ. Итог переписки останется, но историю изменений восстановить " +
                        "нельзя. Сейчас база ${fmtBytes(dbSize())}.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmApply = false
                    run(apply = true, bytes = Retention.APPLY_BYTES)
                }) { Text("Обрезать") }
            },
            dismissButton = { TextButton(onClick = { confirmApply = false }) { Text("Отмена") } },
        )
    }
}

/**
 * Размер БД вместе с WAL: -wal у нас весит больше самой базы, и показывать
 * только .db - значит показывать половину правды.
 */

/**
 * Размер БД вместе с WAL: -wal у нас весит больше самой базы, и показывать
 * только .db - значит показывать половину правды.
 */
private fun dbSize(): Long =
    runCatching {
        val db = File(OpencodeApp.ServerConfig.opencodeData, "opencode/opencode.db")
        db.length() + File(db.parentFile, "opencode.db-wal").length()
    }.getOrDefault(0L)

/**
 * Одна компактная строка «подпись → значение».
 *
 * Раньше подпись и значение стояли друг под другом: 12 sp серой строкой и
 * 13 sp светлой, по два ряда на каждый факт. На экране в сотню фактов это
 * давало простыню, в которой нельзя было найти нужное, — а диагностика
 * открывается именно чтобы найти. Теперь это одна строка в две колонки:
 * подпись 9 sp слева, значение 11 sp справа, вертикальный отступ 2 dp.
 * Высота списка падает почти вдвое без потери ни одного факта.
 *
 * Две колонки, а не «подпись сверху»: длинные значения (пути, ошибки) и так
 * переносятся внутри своей колонки, а короткие («0», «4096») больше не
 * занимают отдельную строку.
 */
@Composable
internal fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = MoltValue,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            color = MoltIdle,
            fontSize = TINY,
            lineHeight = TINY_LINE,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(LABEL_COLUMN_WEIGHT),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            color = valueColor,
            fontSize = SMALL,
            lineHeight = SMALL_LINE,
            modifier = Modifier.weight(1f - LABEL_COLUMN_WEIGHT),
        )
    }
}

/**
 * Доля ширины подписи. 0,44 — «Наложение поверх окон» и «Basic-аутентификация»
 * влезают в две строки 9 sp, при этом значению остаётся больше половины
 * ширины: пути и тексты ошибок переносятся реже, чем при равных колонках.
 */
private const val LABEL_COLUMN_WEIGHT = 0.44f

/**
 * Кнопка-плашка в стиле чипов карточки Moltbook: тёмная, с оранжевой
 * подписью. `warn` — для необратимых действий (обрезка журнала): красная
 * подпись отличает «посчитай» от «удали навсегда», не добавляя вторую кнопку.
 */
@Composable
internal fun downloadButton(
    label: String,
    warn: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        color = MoltChipOff,
        shape = RoundedCornerShape(6.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 5.dp)
                .clickable(onClick = onClick),
    ) {
        Text(
            label,
            color = if (warn) Color(0xFFFF6B6B) else MoltAccent,
            fontSize = BODY,
            lineHeight = BODY_LINE,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * Что сказала последняя проверка — и когда именно.
 *
 * Строка нужна не для истории, а чтобы отличать «проверили, обновлять нечего»
 * от «не проверяли ни разу»: без неё пустой раздел читается как «всё хорошо».
 * Время поэтому с датой: проверка бывает и вчерашней, и «12:06» без числа
 * выдавало бы прошлое за настоящее.
 */
@Composable
private fun coreLastCheckRow(context: Context) {
    val last = CoreUpdater.lastCheck(context) ?: return
    InfoRow("Последняя проверка", "${fmtDayTime(last.at)} · ${last.text()}")
}

/**
 * Кнопка «проверить сейчас» — единственный способ не ждать шестичасового окна.
 *
 * Она же живой тест механизма: когда реестр отдаст версию новее, здесь видно,
 * что ядро скачалось, прошло сверку и сервер перезапустился. Работа идёт на
 * IO-диспатчере: внутри сеть и до 245 МБ, в композиции это был бы фриз.
 *
 * Честность сообщения важна: «обновления нет» здесь означает «ничего не
 * заменили», а причин может быть три (не новее, тарифицируемая сеть, не прошла
 * сверка) — поэтому причина называется тегом в логе, а не выдумывается тут.
 */
@Composable
private fun coreCheckRow(
    context: Context,
    scope: CoroutineScope,
) {
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    note?.let { InfoRow("Проверка", it) }
    downloadButton(if (busy) "Проверяю реестр…" else "Проверить обновление ядра сейчас") {
        if (busy) return@downloadButton
        busy = true
        note = null
        scope.launch {
            val result =
                withContext(Dispatchers.IO) { CoreUpdater.checkAndApply(context, force = true) }
            busy = false
            // Причина приходит значением, а не угадывается здесь: экран обязан
            // повторять решение проверки, а не пересказывать его тремя «либо».
            note = result.text()
            if (result.applied) OpencodeServerService.restart(context)
        }
    }
}

/**
 * Сводка экрана: статус крупно и четыре факта одной строкой.
 *
 * Это тот же приём, что «ждут ответа» в карточке Moltbook: одно число, ради
 * которого экран открывают, и вокруг него — плотная строка остального. Порт,
 * рестарты, свободная RAM и API раньше были четырьмя отдельными рядами в
 * секции «Сервер»; факты те же, высоты — вчетверо меньше.
 */
@Composable
private fun ServerSummary(
    status: ServerStatus,
    port: Int,
    restarts: Int,
    ramFree: Long,
    sdk: Int,
) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = statusLabel(status),
            color = statusColor(status),
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            lineHeight = 22.sp,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "порт $port · рестарты $restarts",
            color = MoltIdle,
            fontSize = TINY,
            lineHeight = TINY_LINE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(bottom = 3.dp),
        )
    }
    Text(
        text = "свободно ${fmtBytes(ramFree)} · API $sdk",
        color = MoltIdle,
        fontSize = TINY,
        lineHeight = TINY_LINE,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Строка версии сборки под статусом сервера: номер сборки, короткий коммит и
 * версия ядра opencode. Все три — из `BuildConfig`, то есть из той сборки,
 * которая реально запущена, а не из документации.
 */
@Composable
private fun BuildLine() {
    Text(
        text = shortBuildLabel(),
        color = MoltIdle,
        fontSize = TINY,
        lineHeight = TINY_LINE,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Подпись автора: наклонный неон с бегущим бликом.
 *
 * Блик — не анимация цвета текста (она перерисовывала бы весь экран), а
 * градиентная полоса в фазе рисования: `drawWithContent` читает фазу только
 * внутри draw, поэтому композиция не пересобирается на каждый кадр. Режим
 * `SrcAtop` красит только уже нарисованные буквы, фон остаётся прозрачным.
 */
@Composable
private fun CreatorCredit(modifier: Modifier = Modifier) {
    val phase =
        rememberInfiniteTransition(label = "credit").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
            label = "credit-phase",
        )
    Text(
        text = "Developer Titov.Maksim.Alexandrovich ✨",
        color = Color(0xFF39DBFF),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        fontStyle = FontStyle.Italic,
        letterSpacing = 0.6.sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        style = TextStyle(shadow = Shadow(color = Color(0xCC00A8FF), blurRadius = 12f)),
        modifier =
            modifier.drawWithContent {
                drawContent()
                val span = size.width * 2.2f
                val x = -size.width + phase.value * span
                drawRect(
                    brush =
                        Brush.linearGradient(
                            colors =
                                listOf(
                                    Color.Transparent,
                                    Color(0x66FFFFFF),
                                    Color.White,
                                    Color(0x66FFFFFF),
                                    Color.Transparent,
                                ),
                            start = Offset(x, 0f),
                            end = Offset(x + size.width * 0.55f, size.height),
                        ),
                    blendMode = BlendMode.SrcAtop,
                )
            },
    )
}

private fun statusLabel(s: ServerStatus): String =
    when (s) {
        ServerStatus.STARTING -> "Запускается"
        ServerStatus.RUNNING -> "Работает"
        ServerStatus.ERROR -> "Ошибка"
        ServerStatus.STOPPED -> "Остановлен"
    }

private fun statusColor(s: ServerStatus): Color =
    when (s) {
        ServerStatus.STARTING -> Color(0xFFFFC107)
        ServerStatus.RUNNING -> Color(0xFF7BD88F)
        ServerStatus.ERROR -> Color(0xFFFF6F5A)
        ServerStatus.STOPPED -> Color(0xFF8A8A8A)
    }

/**
 * Тон секции «Сервер» по статусу.
 *
 * `STARTING` — не проблема: сервер уже поднимается, и открывать секцию с красной
 * точкой из-за этого значило бы приучать игнорировать красное.
 */
private fun statusTone(s: ServerStatus): DiagTone =
    when (s) {
        ServerStatus.RUNNING -> DiagTone.OK
        ServerStatus.STARTING -> DiagTone.WARN
        ServerStatus.ERROR, ServerStatus.STOPPED -> DiagTone.BAD
    }

/**
 * Слово для плитки по тону: одна и та же тройка «есть / нет / не знаю» на весь
 * экран, чтобы «нет данных» нигде не читалось как «сломано».
 */
private fun toneWord(
    tone: DiagTone,
    ok: String,
    bad: String,
): String =
    when (tone) {
        DiagTone.OK -> ok
        DiagTone.WARN, DiagTone.BAD -> bad
        DiagTone.IDLE -> "нет данных"
    }

/** Человекочитаемое имя стадии runtime-цикла. */
private fun stageLabel(stage: RuntimeStage): String =
    when (stage) {
        RuntimeStage.IDLE -> "idle (нет цикла)"
        RuntimeStage.PREPARING -> "подготовка"
        RuntimeStage.STARTING_MEMORY -> "старт памяти MCP"
        RuntimeStage.STARTING_SERVER -> "старт serve"
        RuntimeStage.HEALTHY -> "здоров"
        RuntimeStage.DEGRADED -> "деградация"
        RuntimeStage.RESTARTING -> "перезапуск (backoff)"
        RuntimeStage.CRASHED -> "сбой витка"
        RuntimeStage.FAILED_PERMANENTLY -> "терминальный отказ"
        RuntimeStage.STOPPING -> "остановка"
        RuntimeStage.STOPPED -> "остановлен"
    }

/** Формат времени сбоя: «14:03:25»; null — «—» (события без времени). */
private fun fmtTime(epochMs: Long?): String {
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return epochMs?.let { fmt.format(Date(it)) } ?: "—"
}

/** Формат «16.11 12:06» — для событий, которые бывают не сегодняшними. */
private fun fmtDayTime(epochMs: Long): String = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(epochMs))

/** История сбоев -> многострочный текст «время · код · сообщение (решен/активен)». */
private fun historyText(history: List<RuntimeError>): String =
    history
        .reversed()
        .take(HISTORY_SHOWN)
        .joinToString("\n") { err ->
            val state = if (err.resolvedAt != null) "решена в ${fmtTime(err.resolvedAt)}" else "активна"
            "${fmtTime(err.at)} · ${err.code} ($state) · ${err.message}"
        }

/** Человекочитаемый размер: МБ (1 десятичный знак) или ГБ; <0 — «недоступно». */
internal fun fmtBytes(b: Long): String =
    when {
        b < 0 -> "недоступно"
        b >= 1024L * 1024 * 1024 ->
            String.format(Locale.US, "%.2f ГБ", b.toDouble() / (1024.0 * 1024 * 1024))
        else -> String.format(Locale.US, "%.1f МБ", b.toDouble() / (1024.0 * 1024))
    }

/**
 * Всё, что можно честно сказать о телефоне без особых прав. Один список на два
 * выхода: секция «Система» и текстовый дамп. Поэтому в дампе не бывает строки,
 * которой нет на экране, и наоборот — расхождение читалось бы как баг диагностики.
 */
private fun deviceFacts(
    context: Context,
    memInfo: ActivityManager.MemoryInfo,
): List<Pair<String, String>> {
    val dm = context.resources.displayMetrics
    val abis = Build.SUPPORTED_ABIS.joinToString(", ")
    val internal = storageSummary(context.filesDir.path)
    val external =
        if (Workspace.hasAllFilesAccess()) {
            storageSummary(Environment.getExternalStorageDirectory().path)
        } else {
            "нет доступа"
        }
    val ram =
        "всего ${fmtBytes(memInfo.totalMem)} · доступно ${fmtBytes(memInfo.availMem)} · " +
            "порог ${fmtBytes(memInfo.threshold)} · мало=${memInfo.lowMemory}"
    return listOf(
        "Устройство" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        "Кодовое имя" to "${Build.DEVICE} / ${Build.PRODUCT}",
        "Плата / чип" to "${Build.BOARD} / ${Build.HARDWARE}",
        "Android" to "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
        "Прошивка" to "${Build.DISPLAY} · ${Build.ID} · ${Build.VERSION.INCREMENTAL}",
        "Патч безопасности" to (Build.VERSION.SECURITY_PATCH ?: "—"),
        "Архитектура" to "$abis · ${Runtime.getRuntime().availableProcessors()} ядер",
        "Экран" to
            "${dm.widthPixels}×${dm.heightPixels} px · ${dm.densityDpi} dpi · " +
                "плотность ${dm.density}f · шрифт ${context.resources.configuration.fontScale}f",
        "Язык / регион" to Locale.getDefault().toString(),
        "Часовой пояс" to TimeZone.getDefault().id,
        "ОЗУ" to ram,
        "Хранилище приложения" to internal,
        "Хранилище телефона" to external,
        "Доступ ко всем файлам" to if (Workspace.hasAllFilesAccess()) "выдан" else "нет",
        "Аккумулятор" to batterySummary(context),
        "Время работы" to uptimeLabel(SystemClock.elapsedRealtime()),
    )
}

/** Свободно/всего по StatFs; значение может быть недоступно — это не «0». */
private fun storageSummary(path: String): String =
    runCatching {
        val stat = StatFs(path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        "свободно ${fmtBytes(free)} / всего ${fmtBytes(total)}"
    }.getOrDefault("недоступно")

/** Sticky-широковещание батареи: level/scale плюс подключение и состояние. */
private fun batterySummary(context: Context): String {
    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    val intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(null, filter)
        } ?: return "нет данных"
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    if (level < 0 || scale <= 0) return "нет данных"
    val power =
        when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "от розетки"
            BatteryManager.BATTERY_PLUGGED_USB -> "от USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "беспроводная"
            else -> "от батареи"
        }
    val health =
        when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "норма"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "перегрев"
            BatteryManager.BATTERY_HEALTH_DEAD -> "разряжена"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "перенапряжение"
            else -> "неизвестно"
        }
    return "${level * 100 / scale}% · $power · $health"
}

/** Uptime в человеческом виде: часы и минуты, если больше часа. */
private fun uptimeLabel(ms: Long): String {
    val minutes = ms / 60_000L
    val hours = minutes / 60L
    return if (hours > 0L) {
        "$hours ч ${minutes % 60L} мин"
    } else {
        "$minutes мин ${(ms / 1000L) % 60L} с"
    }
}

private fun shareDump(
    context: Context,
    dump: String,
) {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "opencode-mobile диагностика")
            putExtra(Intent.EXTRA_TEXT, dump)
            // Безопасно и для Activity-контекста, и для не-Activity (если оверлей
            // когда-нибудь переедет на сервисный контекст — без флага был бы краш).
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Поделиться диагностикой"))
    }
}

private fun buildDiagnosticsDump(
    context: Context,
    st: OpencodeServerService.ServerState,
    prefs: android.content.SharedPreferences,
    snap: StorageSnapshot?,
): String {
    val sb = StringBuilder()
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    sb.append("===== opencode-mobile диагностика =====\n")
    sb.append("Сформировано: $stamp\n")

    // raw engine значение в dump неинформативно ("whisper" → "Whisper (локально)"):
    // дублируем human-readable лейбл из UI, чтобы дамп читался без второй отгадки.
    val engineLabel = engineLabel(prefs)

    val pm = context.packageManager
    val appInfo = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
    sb.append("Приложение: v${appInfo?.versionName ?: "?"} (code ${appInfo?.longVersionCode ?: 0})\n")
    sb.append("Сборка: ${buildStampLabel()}\n")
    sb.append("Ядро opencode: ${activeCoreLabel(context)}\n")
    sb.append("Ядро в APK: ${opencodeBinaryLabel()}\n")
    val mi = ActivityManager.MemoryInfo()
    runCatching {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
    }
    // Полный список устройства — тот же, что в секции «Система»: дамп и экран
    // не должны расходиться. Идентификаторов (IMEI/серийный номер) нет намеренно.
    sb.append("\n--- Устройство ---\n")
    deviceFacts(context, mi).forEach { (label, value) -> sb.append("$label: $value\n") }

    sb.append("\n--- Сервер ---\n")
    dumpServerSection(sb, st)

    sb.append("\n--- Валидация runtime ---\n")
    dumpValidationSection(sb, snap)

    sb.append("\n--- Голосовое распознавание ---\n")
    sb.append("Движок: $engineLabel; модель: ${currentModelLabel(prefs)}\n")
    if (snap == null) {
        sb.append("Модели: (не загружено)\n")
    } else {
        val s = snap
        sb.append("Модели заняли: ${fmtBytes(s.used)}; свободно: ${fmtBytes(s.free)}\n")
    }

    sb.append("\n--- Лог сервера (хвост) ---\n")
    val tail = snap?.logTail
    sb.append(if (tail.isNullOrBlank()) "(пусто)" else tail)
    if (!tail.isNullOrBlank() && !tail.endsWith("\n")) sb.append("\n")
    return sb.toString()
}

/** Секция «Сервер» дампа: стадия/рестарты/ошибка/история сбоев/события памяти. */
private fun dumpServerSection(
    sb: StringBuilder,
    st: OpencodeServerService.ServerState,
) {
    sb.append("Статус: ${statusLabel(st.status)} (${st.status.name})\n")
    st.stage?.let { sb.append("Стадия runtime: ${stageLabel(it)} (${it.name})\n") }
    sb.append("Порт: ${st.port}\n")
    sb.append("Рестарты цикла: ${st.restartCount}\n")
    sb.append("Workspace: ${if (st.workspaceExternal) "внешний" else "внутренний"}\n")
    st.lastError?.let {
        val resolved = it.resolvedAt != null
        val ts = if (resolved) "resolved@" + fmtTime(it.resolvedAt) else "active@" + fmtTime(it.at)
        sb.append("Последняя ошибка: ${it.stage} / ${it.code} (recoverable=${it.recoverable}, $ts)\n  ${it.message}\n")
    }
    st.stopReason?.let { sb.append("Причина остановки: ${it.name}\n") }
    st.lastMemoryFailureAt?.let { sb.append("Память MCP упала: ${fmtTime(it)}\n") }
    st.lastRecoveredAt?.let { sb.append("Восстановление: ${fmtTime(it)}\n") }
    if (st.errorHistory.isNotEmpty()) {
        sb.append("Сбои (${st.errorHistory.size}):\n")
        for (err in st.errorHistory.reversed().take(HISTORY_SHOWN)) {
            val state = if (err.resolvedAt != null) "resolved@${fmtTime(err.resolvedAt)}" else "ACTIVE"
            sb.append("  ${fmtTime(err.at)} [$state] ${err.code}: ${err.message}\n")
        }
    }
}

/** Секция «Валидация runtime» дампа: единый отчёт RuntimeValidation. */
private fun dumpValidationSection(
    sb: StringBuilder,
    snap: StorageSnapshot?,
) {
    if (snap == null) {
        sb.append("(не загружено)\n")
        return
    }
    val v = snap.validation
    sb.append("Итог: ${if (v.allOk) "все подсистемы готовы" else "есть проблемы"}\n")
    sb.append("Нативный runtime: ${v.nativeRuntime}\n")
    sb.append("Basic-аутентификация: ${v.serverAuth}\n")
    sb.append("Serve (HTTP): ${v.serverHttp}\n")
    sb.append("Память MCP: ${v.memoryMcp}\n")
    // Те же два поля, что и строки на экране: проверялась ли музыка и нужна ли
    // модель распознавания. Раньше дамп спрашивал Яндекс отдельно, и «не
    // подключён» могло разойтись с вердиктом `musicMcp` в том же отчёте.
    sb.append(
        if (v.musicExpected) {
            "Музыка MCP: ${v.musicMcp}\n"
        } else {
            "Музыка MCP: (Яндекс не подключён - не проверяется)\n"
        },
    )
    sb.append(
        if (v.sttRequired) {
            "Модель распознавания: ${v.sttModelReady}\n"
        } else {
            "Модель распознавания: (не требуется - системный движок)\n"
        },
    )
}

/**
 * Подпись сборки одной строкой: `b58898e · 2026-10-10 22:13 · dirty`.
 *
 * Хэш и время приходят из `BuildConfig`, их проставляет `app/build.gradle.kts`
 * на этапе конфигурирования. Значения НЕ вычисляются здесь: подпись обязана
 * совпадать с тем, из чего APK реально собрали, а «сейчас» на экране — это
 * время открытия диагностики, то есть другая величина.
 *
 * `GIT_DIRTY` означает, что в момент сборки были незакоммиченные правки.
 * Такой хэш указывает на не тот код, который стоит в репозитории, и молчать
 * об этом нельзя - иначе пользователь пришлёт «сборку b58898e», а там будет
 * половина других правок.
 *
 * `GIT_SHA == "nogit"` означает, что git недоступен (например, сборка из
 * архива без `.git`). Это тоже показываем, а не прячем: иначе неизвестное
 * выглядит как известное.
 */
private fun buildStampLabel(): String {
    val dirty = if (BuildConfig.GIT_DIRTY) " · dirty" else ""
    return "${BuildConfig.GIT_SHA} · ${BuildConfig.BUILD_STAMP}$dirty"
}

/**
 * Короткая версия для шапки диагностики: номер сборки, коммит и ядро, без
 * метки времени. Метка живёт в секции «Система» и в дампе — в шапке она
 * съедала бы всю ширину (363 dp) и вытесняла версию ядра многоточием.
 */
private fun shortBuildLabel(): String {
    val dirty = if (BuildConfig.GIT_DIRTY) "+" else ""
    return "сборка ${BuildConfig.VERSION_CODE} · ${BuildConfig.GIT_SHA}$dirty · ядро ${opencodeBinaryLabel()}"
}

/**
 * Версия бинаря opencode, зашитого в этот APK, из gradle.properties.
 *
 * Не «версия, которая сейчас работает»: если в `jniLibs` положили не тот
 * бинарь, экран обязан показать расхождение, а не молча показывать правильную
 * с виду цифру. Живой источник правды — `GET /api/info` на локальном сервере,
 * его значение стоит рядом отдельной строкой.
 */
private fun opencodeBinaryLabel(): String = "v${BuildConfig.OPENCODE_VERSION}"

/**
 * Ядро, которое реально запускается.
 *
 * С 16.11 приложение умеет накатывать ядро само (`CoreUpdater`), и APK-версия
 * перестала быть ответом на вопрос «что у меня работает». Показывать только
 * её — значит врать ровно в том месте, куда смотрят, когда что-то сломалось.
 *
 * Отсюда и формат: действующее значение первым, источник в скобках. Если
 * скачанное ядро отбраковано падением сервера, `effectiveVersion` уже вернул
 * APK-версию — и это правда, а не «кэш».
 */
private fun activeCoreLabel(context: Context): String {
    val active = CoreUpdater.effectiveVersion(context)
    val apk = BuildConfig.OPENCODE_VERSION
    return if (active == apk) "v$apk (из APK)" else "v$active (скачано; в APK v$apk)"
}

/**
 * Распознаёт системный Android, а не модель из каталога приложения.
 *
 * Одно правило на весь экран: плитка, строка валидации и лейбл движка обязаны
 * отвечать одинаково. До 17.11 плитка «Распознавание» говорила «модель готова»
 * (по `ggmlReady`), хотя при `stt_engine = system` эта модель не используется.
 * Значения `whisper`/`ncnn` — локальный движок (`ncnn` выпилен и читается как
 * whisper), всё остальное оверлей подставляет как систему.
 */
private fun sttEngineIsSystem(prefs: android.content.SharedPreferences): Boolean {
    val engine = prefs.getString("stt_engine", "system") ?: "system"
    return engine != "whisper" && engine != "ncnn"
}

/**
 * Человекочитаемый лейбл движка STT (stt_engine pref → русское имя).
 *
 * Считается тем же правилом [sttEngineIsSystem], что и плитка с валидацией:
 * одно поле — один смысл, иначе подпись говорила бы «системный», а вердикт
 * считался бы по файлам whisper.
 */
private fun engineLabel(prefs: android.content.SharedPreferences): String =
    if (sttEngineIsSystem(prefs)) "Системный Android" else "Whisper (локально)"

/**
 * Какая модель выбрана ПРАКТИЧЕСКИ — выбор пользователя из каталога.
 * Раньше строка показывала жёстко «ggml-base» для whisper, даже когда была
 * выбрана другая: диагностика врала про работающую конфигурацию, а это худший
 * вид неправды.
 *
 * `ncnn` из старых сборок читаем как `whisper` — тот же движок, который
 * приложение подставит вместо него при разборе pref.
 */
private fun currentModelLabel(prefs: android.content.SharedPreferences): String =
    when (prefs.getString("stt_engine", "system")) {
        "whisper", "ncnn" -> {
            val id = prefs.getString("stt_model_whisper", SttModels.DEFAULT_ID) ?: SttModels.DEFAULT_ID
            val spec = SttModels.byId(id)
            // Ручная модель (Parakeet) — не ggml: префикс врал бы про движок.
            if (spec?.manual == true) spec.id else "ggml-" + SttModels.labelOf(id)
        }

        else -> "системный Android"
    }

/** Хвост opencode.log (serve пишет через ProcessBuilder.appendTo; ротация регулярная). */
private fun readLogTail(
    file: File,
    maxBytes: Int = 4000,
): String {
    if (!file.exists()) return ""
    return try {
        val len = file.length()
        val start = (len - maxBytes).coerceAtLeast(0)
        val buf = ByteArray((len - start).toInt())
        file.inputStream().use { ins ->
            ins.skip(start)
            var off = 0
            while (off < buf.size) {
                val n = ins.read(buf, off, buf.size - off)
                if (n < 0) break
                off += n
            }
        }
        val txt = String(buf, Charsets.UTF_8)
        // Не режем посреди строки: откатываемся до последнего \n.
        val cut = txt.indexOf('\n')
        if (cut in 1 until txt.length) txt.substring(cut + 1) else txt
    } catch (e: Exception) {
        ""
    }
}

/**
 * Список из двух движков: готов ли, сколько весит, кнопка скачивания.
 *
 * Показываем оба сразу, а не только STT: озвучка через sherpa - тоже нативная
 * библиотека, и её отсутствие выглядит так же - молчание.
 */

/** Ряды моделей движка whisper.cpp.
 *
 *  Список и кнопки строятся по каталогу [SttModels], а не по захардкоженной
 *  паре base/turbo: пока интерфейс знал две модели, small/medium/turbo f16 были
 *  замерены, но скачать их было нечем, и выбрать модель в настройках было
 *  невозможно. Теперь список и UI растут вместе.
 *
 *  Готовность приходит из снапшота: isReady() считает sha256 целиком (141 МБ у
 *  base, 1,5 ГБ у turbo), и на главном потоке это заморозило бы экран.
 */
@Composable
private fun SttModelRows(
    snap: StorageSnapshot?,
    state: SttDownloadState,
    onDownload: (String) -> Unit,
    onRepeat: (String) -> Unit,
    onDeleteOthers: () -> Unit,
) {
    val ready = snap?.ggmlReady.orEmpty()
    for (model in SttModels.all) {
        val bytes = ready[model.id]
        // Модель уже готова на диске — показываем размер; иначе — статус
        val title = model.id
        val size =
            when {
                bytes != null -> fmtBytes(bytes)
                else -> model.sizeLabel
            }
        val statusText =
            when {
                bytes != null -> "готов · " + size
                model.manual -> "не скачана · вручную (adb push)"
                else -> "не скачана · сначала скачай: Настройки → Диагностика"
            }
        val statusColor =
            when {
                bytes != null -> Color(0xFF7BD88F)
                model.manual -> Color(NOTE_ARGB)
                else -> Color(0xFFFF6F5A)
            }
        InfoRow(
            model.id,
            statusText,
            statusColor,
        )
        // Дополнительная строка с заметкой модели
        InfoRow("  " + model.id, model.note, Color(NOTE_ARGB))
        if (model.recommendedForRu) InfoRow("  RU", "рекомендуется для русского", Color(0xFFFFD54F))
    }
    when (val dl = state) {
        is SttDownloadState.Running -> InfoRow(
            "Загрузка " + dl.modelId,
            "${fmtBytes(dl.done)} из ${fmtBytes(dl.total)}",
            Color(0xFFFFC107),
        )

        is SttDownloadState.Failed -> {
            InfoRow("Скачивание не удалось", dl.message, Color(0xFFFF6F5A))
            downloadButton("Повторить (докачка)") { onRepeat(dl.modelId) }
        }

        SttDownloadState.Idle -> SttDownloadControls(onDownload, ready)
    }
    downloadButton("Удалить модели, которых больше нет в списке") { onDeleteOthers() }
}

/** Кнопки скачивания для STT-моделей, которых ещё нет на диске.
 *  Использует model.fetch() для унифицированного скачивания:
 *  - ggml small → downloadSmall
 *  - sherpa-onnx модели → downloadSttModel (multi-file)
 *  - manual модели — кнопки нет (файлы кладут adb push) */
@Composable
private fun SttDownloadControls(
    onDownload: (String) -> Unit,
    ready: Map<String, Long>,
) {
    for (model in SttModels.all) {
        if (ready[model.id] != null) continue
        if (model.manual) {
            InfoRow("Установка " + model.id, "вручную (adb push) — кнопки скачивания нет", Color(NOTE_ARGB))
        } else {
            downloadButton("Скачать " + model.id + " (~" + model.sizeLabel + ")") { onDownload(model.id) }
        }
    }
}

@Composable
private fun EngineRows(
    state: EngineDownloadState,
    onDownload: (Engine) -> Unit,
) {
    val context = LocalContext.current
    for (engine in Engine.entries) {
        // Движок из APK не проверяем в filesDir: он туда никогда не
        // попадает и не должен. Для него ответ один — «в комплекте».
        val ready = engine.bundled || remember(engine) { EngineStore.isReady(context, engine) }
        val mark = when {
            engine.bundled -> "✔ в комплекте с приложением"
            ready -> "✔ установлен"
            else -> "✘ не установлен"
        }
        InfoRow(
            engine.label,
            "$mark · ${fmtBytes(engine.totalBytes)}",
            if (ready) Color(0xFF7BD88F) else Color(0xFFFF6F5A),
        )
        val dl = state
        if (!engine.bundled && !ready && dl !is EngineDownloadState.Running) {
            downloadButton("Скачать (${fmtBytes(engine.totalBytes)})") { onDownload(engine) }
        }
    }
    val running = state as? EngineDownloadState.Running
    if (running != null) {
        val ratio = if (running.total > 0) (running.done.toFloat() / running.total).coerceIn(0f, 1f) else 0f
        InfoRow(
            "Загрузка движка ${running.engine.label}",
            "${(ratio * PERCENT_MAX).toInt()}% · ${fmtBytes(running.done)} из ${fmtBytes(running.total)}",
            Color(0xFFFFC107),
        )
    }
    if (state is EngineDownloadState.Failed) {
        InfoRow("Движок не скачался", state.message, Color(0xFFFF6F5A))
    }
}

@Composable
private fun TtsVoiceRows(
    ready: Boolean,
    bytesOnDisk: Long,
    state: TtsDownloadState,
    onDownload: () -> Unit,
) {
    InfoRow(
        "Модель озвучки (Supertonic)",
        if (ready) "готова · ${fmtBytes(bytesOnDisk)}" else "не скачана · ${TtsModelDownloader.sizeLabel()}",
        if (ready) Color(0xFF7BD88F) else Color(0xFFFF6F5A),
    )
    when (val dl = state) {
        is TtsDownloadState.Running -> {
            val ratio = if (dl.total > 0) (dl.done.toFloat() / dl.total).coerceIn(0f, 1f) else 0f
            InfoRow(
                "Загрузка модели озвучки",
                "${(ratio * PERCENT_MAX).toInt()}% · ${fmtBytes(dl.done)} из ${fmtBytes(dl.total)}",
                Color(0xFFFFC107),
            )
        }

        is TtsDownloadState.Failed -> {
            InfoRow("Модель не скачалась", dl.message, Color(0xFFFF6F5A))
            downloadButton("Повторить (докачка)") { onDownload() }
        }

        TtsDownloadState.Idle ->
            if (!ready) {
                downloadButton("Скачать модель озвучки (~" + TtsModelDownloader.sizeLabel() + ")") { onDownload() }
            }
    }
}

/**
 * Открыть системную страницу «поверх других приложений».
 *
 * Три попытки по убыванию точности. Первые две важны потому, что ColorOS
 * игнорирует package-URI у этого action и открывает общий список «выберите
 * приложение» — рабочий путь, но с лишней прокруткой. Последняя — страница
 * приложения, где пункт есть всегда.
 *
 * Молчаливый отказ недопустим: это единственный путь к починке BAL, и если все
 * три не сработали, человек должен об этом узнать, а не гадать, почему кнопка
 * «ничего не делает».
 */
private fun openOverlaySettings(context: Context) {
    val attempts =
        listOf(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    if (attempts.none { runCatching { context.startActivity(it) }.isSuccess }) {
        Toast
            .makeText(
                context,
                "Не удалось открыть настройки наложения. Разреши вручную: " +
                    "Настройки → Приложения → " +
                    "${context.applicationInfo.loadLabel(context.packageManager)} " +
                    "→ Специальный доступ → Отображение поверх других приложений",
                Toast.LENGTH_LONG,
            ).show()
    }
}

/**
 * Открыть список служб доступности.
 *
 * Второй барьер на ColorOS — «ограниченная настройка» для сборки, поставленной не
 * из Play: без неё тумблер службы просто не включается, и «нажмите ещё раз» не
 * помогает. Поэтому при отказе подсказка называет весь путь, включая пункт ⋮ в
 * карточке приложения.
 */
private fun openAccessibilitySettings(context: Context) {
    val attempts =
        listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    if (attempts.none { runCatching { context.startActivity(it) }.isSuccess }) {
        Toast
            .makeText(
                context,
                "Не удалось открыть настройки. Путь: Настройки → Специальные возможности → " +
                    "${context.applicationInfo.loadLabel(context.packageManager)}",
                Toast.LENGTH_LONG,
            ).show()
    }
}

/**
 * Карточка приложения — второй барьер после обновления APK.
 *
 * На сборке, поставленной не из Play, пункт «Разрешить ограниченную настройку»
 * спрятан в меню ⋮ именно здесь. Без него тумблер службы доступности просто не
 * включается, и «попробуйте ещё раз» не помогает. Прямая кнопка убирает ручной
 * поиск по настройкам; при отказе показываем полный путь, а не молчим.
 */
private fun openAppDetails(context: Context) {
    val intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
    if (runCatching { context.startActivity(intent) }.isFailure) {
        Toast
            .makeText(
                context,
                "Не удалось открыть карточку. Путь: Настройки → Приложения → " +
                    "${context.applicationInfo.loadLabel(context.packageManager)} → ⋮ → " +
                    "Разрешить ограниченную настройку",
                Toast.LENGTH_LONG,
            ).show()
    }
}
