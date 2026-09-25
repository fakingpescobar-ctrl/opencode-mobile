package org.opencode.mobile.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencode.mobile.server.OpencodeServerService
import org.opencode.mobile.server.OpencodeServerService.ServerStatus
import org.opencode.mobile.server.RuntimeError
import org.opencode.mobile.server.RuntimeStage
import org.opencode.mobile.server.RuntimeValidation
import org.opencode.mobile.stt.ModelDownloader
import org.opencode.mobile.stt.NcnnModelDownloader
import org.opencode.mobile.stt.NcnnModelValidator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

/** Состояние скачивания ncnn-набора (кнопка в секции «Голосовое распознавание»). */
private sealed interface NcnnDownloadState {
    data object Idle : NcnnDownloadState
    data class Running(val done: Long, val total: Long) : NcnnDownloadState
    data class Failed(val message: String) : NcnnDownloadState
}

/** Снапшот моделей и хранилища, собранный один раз на IO при открытии. */
private data class StorageSnapshot(
    val free: Long,
    val used: Long,
    val ncnnTurboReady: Boolean,
    val ncnnTurboSize: Long,
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
    val scope = rememberCoroutineScope()
    // Системный Back закрывает оверлей, как и иконка ✕ (иначе Back ушёл бы
    // из Activity, оставив оверлей на экране — UX-ловушка).
    BackHandler(onBack = onClose)

    // Снапшот собирается ОДИН раз при открытии, на IO-диспатчере: проверка
    // ncnn-каталога (полный набор файлов — NcnnModelValidator, тот же, что в
    // obtainNcnnContext), суммарный размер файлов, хвост лога serve. В
    // композиции эти вызовы выполнялись бы на main при КАЖДОЙ рекомпозиции
    // (серверный StateFlow тикает) — фризы. Здесь всё собрано в один IO-блок.
    var snap by remember { mutableStateOf<StorageSnapshot?>(null) }
    // Кнопка «Проверить сейчас» инкрементит ключ — LaunchedEffect пересобирает снапшот.
    var refreshKey by remember { mutableIntStateOf(0) }
    // Состояние скачивания ncnn-моделей. Обновляется из IO-корутины (snapshot-state
    // потокобезопасен); выход с экрана отменяет корутину (CancellationException
    // проходит сквозь downloadTurbo через ensureActive) — .part остаётся для resume.
    var ncnnDl by remember { mutableStateOf<NcnnDownloadState>(NcnnDownloadState.Idle) }
    val startNcnnDownload: () -> Unit = {
        if (ncnnDl !is NcnnDownloadState.Running) {
            ncnnDl = NcnnDownloadState.Running(0L, NcnnModelDownloader.TOTAL_BYTES)
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        NcnnModelDownloader.downloadTurbo(context) { done, total ->
                            ncnnDl = NcnnDownloadState.Running(done, total)
                        }
                    }
                    ncnnDl = NcnnDownloadState.Idle
                    refreshKey++ // пересобрать снапшот: строка «ncnn-turbo» станет «✔ готов»
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // уход с экрана — не трогаем состояние
                } catch (e: Exception) {
                    ncnnDl = NcnnDownloadState.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }
    LaunchedEffect(refreshKey) {
        snap =
            withContext(Dispatchers.IO) {
                val modelsDir = ModelDownloader.modelsDir(context)

                // Готовность ncnn-каталога — тот же критерий, что в
                // WhisperTranscribeService.obtainNcnnContext (полный набор сетей,
                // а не только fbank+vocab: декодер/embed/proj_out обязательны).
                val turboDir = File(modelsDir, "ncnn-turbo")
                val tReady = NcnnModelValidator.checkModelDir(turboDir).ok
                val tSize = turboDir.listFiles()?.sumOf { it.length() } ?: 0L
                StorageSnapshot(
                    free = ModelDownloader.freeBytes(context),
                    used = ModelDownloader.modelsUsedBytes(context),
                    ncnnTurboReady = tReady,
                    ncnnTurboSize = tSize,
                    logTail = readLogTail(File(context.filesDir, "opencode.log")),
                    validation = RuntimeValidation.run(context),
                )
            }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color(0xFF0D0D0D),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            // Шапка: заголовок + «поделиться дампом» + закрыть.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Диагностика",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = "Поделиться диагностикой",
                    tint = Color(0xFFBDBDBD),
                    modifier =
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E1E1E))
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
                    tint = Color(0xFFBDBDBD),
                    modifier =
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E1E1E))
                            .padding(3.dp)
                            .clickable { refreshKey++ },
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Закрыть диагностику",
                    tint = Color(0xFFBDBDBD),
                    modifier =
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E1E1E))
                            .padding(3.dp)
                            .clickable { onClose() },
                )
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 6.dp),
            ) {
                Section("Сервер opencode")
                InfoRow("Статус", statusLabel(serverState.status), statusColor(serverState.status))
                serverState.stage?.let { InfoRow("Стадия runtime", stageLabel(it)) }
                InfoRow("Порт", serverState.port.toString())
                InfoRow("Рестарты цикла", serverState.restartCount.toString())
                InfoRow("Workspace", if (serverState.workspaceExternal) "Внешний (MANAGE_EXTERNAL_STORAGE)" else "Внутренний")
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
                    InfoRow("Сбои (последние ${serverState.errorHistory.size})", historyText(serverState.errorHistory))
                }

                Section("Голосовое распознавание")
                InfoRow("Движок", engineLabel(prefs))
                // ncnn = всегда turbo (base-конверт битый и удалён из программы).
                InfoRow("Модель", "turbo")
                if (snap == null) {
                    InfoRow("Модели", "загрузка…")
                } else {
                    val s = requireNotNull(snap)
                    InfoRow(
                        "ncnn-turbo",
                        (if (s.ncnnTurboReady) "✔ готов" else "✘ отсутствует") + " · " + fmtBytes(s.ncnnTurboSize),
                        if (s.ncnnTurboReady) Color(0xFF7BD88F) else Color(0xFFFF6F5A),
                    )
                    InfoRow("Модели заняли", fmtBytes(s.used))
                    InfoRow("Свободно", fmtBytes(s.free))
                    when (val dl = ncnnDl) {
                        is NcnnDownloadState.Running -> {
                            val ratio = if (dl.total > 0) (dl.done.toFloat() / dl.total).coerceIn(0f, 1f) else 0f
                            InfoRow(
                                "Загрузка ncnn-моделей",
                                "${(ratio * 100).toInt()}% · ${fmtBytes(dl.done)} из ${fmtBytes(dl.total)}",
                                Color(0xFFFFC107),
                            )
                            Spacer(Modifier.height(3.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0xFF2A2A2A)),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(ratio)
                                        .height(3.dp)
                                        .background(Color(0xFF7BD88F)),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        is NcnnDownloadState.Failed -> {
                            InfoRow("Скачивание не удалось", dl.message, Color(0xFFFF6F5A))
                            DownButton("Повторить (докачка)") { startNcnnDownload() }
                        }
                        NcnnDownloadState.Idle -> {
                            if (!s.ncnnTurboReady) {
                                DownButton("Скачать ncnn-модели (~2.5 ГБ)") { startNcnnDownload() }
                            }
                        }
                    }
                }

                Section("Валидация runtime")
                if (snap == null) {
                    InfoRow("Проверка", "загрузка…")
                } else {
                    val v = requireNotNull(snap).validation
                    val ok = Color(0xFF7BD88F)
                    val bad = Color(0xFFFF6F5A)
                    InfoRow("Итог", if (v.allOk) "все подсистемы готовы" else "есть проблемы", if (v.allOk) ok else bad)
                    InfoRow(
                        "Нативный runtime",
                        if (v.nativeRuntime) "✔ ассемблирован" else "✘ libopencode.so отсутствует",
                        if (v.nativeRuntime) ok else bad,
                    )
                    InfoRow(
                        "Basic-аутентификация",
                        if (v.serverAuth) "✔ пароль задан" else "✘ пароль не задан",
                        if (v.serverAuth) ok else bad,
                    )
                    InfoRow(
                        "Serve (HTTP)",
                        if (v.serverHttp) "✔ отвечает" else "✘ не отвечает",
                        if (v.serverHttp) ok else bad,
                    )
                    InfoRow(
                        "Локальная память (MCP)",
                        if (v.memoryMcp) "✔ отвечает (/mcp 200)" else "✘ не отвечает по HTTP",
                        if (v.memoryMcp) ok else bad,
                    )
                    InfoRow(
                        "ncnn-turbo",
                        if (v.ncnnTurbo) "✔ полный набор" else "✘ файлов не хватает",
                        if (v.ncnnTurbo) ok else bad,
                    )
                }

                Section("Система")
                // Binder IPC (PackageManager/ActivityManager) — НЕ на каждом рекомпозе
                // (serverState тикает при работе сервера): статику собираем один раз
                // на открытие экрана.
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
                InfoRow("Приложение", "${appInfo?.versionName ?: "?"} (code ${appInfo?.longVersionCode ?: 0})")
                InfoRow("SDK / ABI", "API ${Build.VERSION.SDK_INT} / ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
                InfoRow(
                    "RAM",
                    if (memInfo.totalMem >
                        0L
                    ) {
                        "свободно ${fmtBytes(memInfo.availMem)} / всего ${fmtBytes(memInfo.totalMem)}"
                    } else {
                        "недоступно"
                    },
                )

                Section("Лог сервера (хвост)")
                // Без SelectionContainer: он конфликтует с verticalScroll по жестам
                // (долгое нажатие перехватывается), а копирование всего лога есть
                // через «Поделиться» — там дамп собирается свежим.
                // Хвост читаем из filesDir/opencode.log (туда serve пишет через
                // ProcessBuilder.appendTo; ротация .1/.2/.3) — разово на открытии.
                if (snap == null) {
                    Text("Лог: загрузка…", color = Color(0xFF8A8A8A), fontSize = 12.sp)
                } else {
                    val s = requireNotNull(snap)
                    if (s.logTail.isBlank()) {
                        Text("Пусто — opencode.log ещё не создан (сервер не запускался).", color = Color(0xFF8A8A8A), fontSize = 12.sp)
                    } else {
                        Text(
                            s.logTail,
                            color = Color(0xFFC8C8C8),
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

@Composable
private fun Section(title: String) {
    Text(
        title,
        color = Color(0xFFBDBDBD),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
    Spacer(Modifier.height(1.dp).fillMaxWidth().background(Color(0xFF222222)))
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = Color(0xFFE6E6E6),
) {
    Column(Modifier.fillMaxWidth().padding(top = 5.dp)) {
        Text(label, color = Color(0xFF8A8A8A), fontSize = 12.sp)
        Text(value, color = valueColor, fontSize = 13.sp, lineHeight = 17.sp)
    }
}

/** Кнопка-плашка скачивания моделей (тёмный экран диагностики, акцент зелёный). */
@Composable
private fun DownButton(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clickable(onClick = onClick),
    ) {
        Text(
            label,
            color = Color(0xFF7BD88F),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
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
private fun fmtBytes(b: Long): String {
    if (b < 0) return "недоступно"
    if (b >= 1024L * 1024 * 1024) {
        val gb = b.toDouble() / (1024.0 * 1024 * 1024)
        return String.format(Locale.US, "%.2f ГБ", gb)
    }
    val mb = b.toDouble() / (1024.0 * 1024)
    return String.format(Locale.US, "%.1f МБ", mb)
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

    // raw engine значение в dump неинформативно ("ncnn" → "ncnn (локально)"): дублируем
    // human-readable лейбл из UI, чтобы дамп читался без второй отгадки.
    val engineLabel = engineLabel(prefs)

    val pm = context.packageManager
    val appInfo = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
    sb.append("Приложение: v${appInfo?.versionName ?: "?"} (code ${appInfo?.longVersionCode ?: 0})\n")
    sb.append("SDK: API ${Build.VERSION.SDK_INT}, ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}\n")
    val mi = ActivityManager.MemoryInfo()
    runCatching {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
    }
    if (mi.totalMem > 0L) {
        sb.append("RAM: свободно ${fmtBytes(mi.availMem)} / всего ${fmtBytes(mi.totalMem)}\n")
    } else {
        sb.append("RAM: недоступно\n")
    }

    sb.append("\n--- Сервер ---\n")
    dumpServerSection(sb, st)

    sb.append("\n--- Валидация runtime ---\n")
    dumpValidationSection(sb, snap)

    sb.append("\n--- Голосовое распознавание ---\n")
    sb.append("Движок: $engineLabel; модель: turbo (единственная, base удалён)\n")
    if (snap == null) {
        sb.append("Модели: (не загружено)\n")
    } else {
        val s = snap
        sb.append("ncnn-turbo: ${if (s.ncnnTurboReady) "готов" else "отсутствует"}, ${fmtBytes(s.ncnnTurboSize)}\n")
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
    sb.append("ncnn-turbo: ${v.ncnnTurbo}\n")
}

/** Человекочитаемый лейбл движка STT (stt_engine pref → русское имя). */
private fun engineLabel(prefs: android.content.SharedPreferences): String =
    when (prefs.getString("stt_engine", "system")) {
        "ncnn" -> "ncnn (локально)"
        "whisper" -> "Whisper (локально)"
        else -> "Системный Android"
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
