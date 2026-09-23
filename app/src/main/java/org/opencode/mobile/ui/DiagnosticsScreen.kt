package org.opencode.mobile.ui

import android.content.Context
import android.content.Intent
import android.app.ActivityManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import org.opencode.mobile.server.OpencodeServerService
import org.opencode.mobile.server.OpencodeServerService.ServerStatus
import org.opencode.mobile.stt.ModelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** Снапшот моделей и хранилища, собранный один раз на IO при открытии. */
private data class StorageSnapshot(
    val free: Long,
    val used: Long,
    val ncnnBaseReady: Boolean,
    val ncnnBaseSize: Long,
    val ncnnTurboReady: Boolean,
    val ncnnTurboSize: Long,
)

@Composable
fun DiagnosticsScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val serverState by OpencodeServerService.state.collectAsState()
    val prefs = remember { context.getSharedPreferences("chat_overlay", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    // Системный Back закрывает оверлей, как и иконка ✕ (иначе Back ушёл бы
    // из Activity, оставив оверлей на экране — UX-ловушка).
    BackHandler(onBack = onClose)

    // Снапшот моделей собирается ОДИН раз при открытии, на IO-диспатчере:
    // проверка ncnn-каталогов (fbank.param + vocab, как в obtainNcnnContext),
    // суммарный размер файлов — это диск. В композиции эти вызовы выполнялись
    // бы на main при КАЖДОЙ рекомпозиции (серверный StateFlow тикает) — фризы.
    // Здесь всё собрано в один IO-блок.
    var snap by remember { mutableStateOf<StorageSnapshot?>(null) }
    LaunchedEffect(Unit) {
        snap = withContext(Dispatchers.IO) {
            val modelsDir = ModelDownloader.modelsDir(context)

            // Готовность ncnn-каталога — тот же критерий, что в
            // WhisperTranscribeService.obtainNcnnContext: fbank.param + vocab.
            fun ncnnSnap(name: String): Pair<Boolean, Long> {
                val model = name.removePrefix("ncnn-")
                val dir = File(modelsDir, name)
                val ready = File(dir, "whisper_${model}_fbank.ncnn.param").exists() && File(dir, "whisper_vocab.txt").exists()
                val size = dir.listFiles()?.sumOf { it.length() } ?: 0L
                return ready to size
            }
            val (bReady, bSize) = ncnnSnap("ncnn-base")
            val (tReady, tSize) = ncnnSnap("ncnn-turbo")
            StorageSnapshot(
                free = ModelDownloader.freeBytes(context),
                used = ModelDownloader.modelsUsedBytes(context),
                ncnnBaseReady = bReady,
                ncnnBaseSize = bSize,
                ncnnTurboReady = tReady,
                ncnnTurboSize = tSize
            )
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color(0xFF0D0D0D)
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            // Шапка: заголовок + «поделиться дампом» + закрыть.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Диагностика",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = "Поделиться диагностикой",
                    tint = Color(0xFFBDBDBD),
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E1E1E))
.padding(3.dp)
                        .clickable {
                            // Сбор дампа — не на main: prefs.getString и Binder IPC
                            // (PackageManager/ActivityManager) в buildDiagnosticsDump
                            // выполняются на IO, и только потом открывается share-chooser.
                            scope.launch {
                                val dump = withContext(Dispatchers.IO) {
                                    buildDiagnosticsDump(context, serverState, prefs, snap)
                                }
                                shareDump(context, dump)
                            }
                        }
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Закрыть диагностику",
                    tint = Color(0xFFBDBDBD),
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E1E1E))
                        .padding(3.dp)
                        .clickable { onClose() }
                )
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 6.dp)
            ) {
                Section("Сервер opencode")
                InfoRow("Статус", statusLabel(serverState.status), statusColor(serverState.status))
                InfoRow("Порт", serverState.port.toString())
                InfoRow("Рестарты цикла", serverState.restartCount.toString())
                InfoRow("Workspace", if (serverState.workspaceExternal) "Внешний (MANAGE_EXTERNAL_STORAGE)" else "Внутренний")
                serverState.lastError?.let { err ->
                    InfoRow(
                        "Последняя ошибка",
                        "${err.stage} / ${err.code}" +
                            (if (err.recoverable) " (recoverable)" else " (терминальная)") +
                            if (err.message.isNotBlank()) "\n${err.message}" else ""
                    )
                }
                serverState.stopReason?.let { InfoRow("Причина остановки", it.name) }

Section("Голосовое распознавание")
                InfoRow(
                    "Движок",
                    when (prefs.getString("stt_engine", "system")) {
                        "ncnn" -> "ncnn (локально)"
                        "whisper" -> "Whisper (локально)"
                        else -> "Системный Android"
                    }
                )
                InfoRow("Модель", prefs.getString("stt_model", "turbo") ?: "turbo")
                if (snap == null) {
                    InfoRow("Модели", "загрузка…")
                } else {
                    val s = requireNotNull(snap)
                    InfoRow("ncnn-base", (if (s.ncnnBaseReady) "✔ готов" else "✘ отсутствует") + " · " + fmtBytes(s.ncnnBaseSize), if (s.ncnnBaseReady) Color(0xFF7BD88F) else Color(0xFFFF6F5A))
                    InfoRow("ncnn-turbo", (if (s.ncnnTurboReady) "✔ готов" else "✘ отсутствует") + " · " + fmtBytes(s.ncnnTurboSize), if (s.ncnnTurboReady) Color(0xFF7BD88F) else Color(0xFFFF6F5A))
                    InfoRow("Модели заняли", fmtBytes(s.used))
                    InfoRow("Свободно", fmtBytes(s.free))
                }

                Section("Система")
                // Binder IPC (PackageManager/ActivityManager) — НЕ на каждом рекомпозе
                // (serverState тикает при работе сервера): статику собираем один раз
                // на открытие экрана.
                val pm = context.packageManager
                val appInfo = remember {
                    runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
                }
                val memInfo = remember {
                    ActivityManager.MemoryInfo().also {
                        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        runCatching { am.getMemoryInfo(it) }
                    }
                }
                InfoRow("Приложение", "${appInfo?.versionName ?: "?"} (code ${appInfo?.longVersionCode ?: 0})")
                InfoRow("SDK / ABI", "API ${Build.VERSION.SDK_INT} / ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
                InfoRow(
                    "RAM",
                    if (memInfo.totalMem > 0L) "свободно ${fmtBytes(memInfo.availMem)} / всего ${fmtBytes(memInfo.totalMem)}" else "недоступно"
                )

                Section("Лог сервера (хвост)")
                // Без SelectionContainer: он конфликтует с verticalScroll по жестам
                // (долгое нажатие перехватывается), а копирование всего лога есть
                // через «Поделиться» — там дамп собирается свежим.
                if (serverState.logTail.isBlank()) {
                    Text("Пусто — сервер ещё не запускался.", color = Color(0xFF8A8A8A), fontSize = 12.sp)
                } else {
                    Text(
                        serverState.logTail,
                        color = Color(0xFFC8C8C8),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    )
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
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
    )
    Spacer(Modifier.height(1.dp).fillMaxWidth().background(Color(0xFF222222)))
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = Color(0xFFE6E6E6)) {
    Column(Modifier.fillMaxWidth().padding(top = 5.dp)) {
        Text(label, color = Color(0xFF8A8A8A), fontSize = 12.sp)
        Text(value, color = valueColor, fontSize = 13.sp, lineHeight = 17.sp)
    }
}

private fun statusLabel(s: ServerStatus): String = when (s) {
    ServerStatus.STARTING -> "Запускается"
    ServerStatus.RUNNING -> "Работает"
    ServerStatus.ERROR -> "Ошибка"
    ServerStatus.STOPPED -> "Остановлен"
}

private fun statusColor(s: ServerStatus): Color = when (s) {
    ServerStatus.STARTING -> Color(0xFFFFC107)
    ServerStatus.RUNNING -> Color(0xFF7BD88F)
    ServerStatus.ERROR -> Color(0xFFFF6F5A)
    ServerStatus.STOPPED -> Color(0xFF8A8A8A)
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

private fun shareDump(context: Context, dump: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
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
    snap: StorageSnapshot?
): String {
    val sb = StringBuilder()
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    sb.append("===== opencode-mobile диагностика =====\n")
    sb.append("Сформировано: $stamp\n")

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
    sb.append("Статус: ${statusLabel(st.status)} (${st.status.name})\n")
    sb.append("Порт: ${st.port}\n")
    sb.append("Рестарты цикла: ${st.restartCount}\n")
    sb.append("Workspace: ${if (st.workspaceExternal) "внешний" else "внутренний"}\n")
    st.lastError?.let {
        sb.append("Последняя ошибка: ${it.stage} / ${it.code} (recoverable=${it.recoverable})\n  ${it.message}\n")
    }
    st.stopReason?.let { sb.append("Причина остановки: ${it.name}\n") }

    sb.append("\n--- Голосовое распознавание ---\n")
    val engine = prefs.getString("stt_engine", "system")
    val model = prefs.getString("stt_model", "base")
    sb.append("Движок: $engine; модель: $model\n")
    if (snap == null) {
        sb.append("Модели: (не загружено)\n")
    } else {
        val s = snap
        sb.append("ncnn-base: ${if (s.ncnnBaseReady) "готов" else "отсутствует"}, ${fmtBytes(s.ncnnBaseSize)}\n")
        sb.append("ncnn-turbo: ${if (s.ncnnTurboReady) "готов" else "отсутствует"}, ${fmtBytes(s.ncnnTurboSize)}\n")
        sb.append("Модели заняли: ${fmtBytes(s.used)}; свободно: ${fmtBytes(s.free)}\n")
    }

    sb.append("\n--- Лог сервера (хвост) ---\n")
    sb.append(if (st.logTail.isBlank()) "(пусто)" else st.logTail)
    if (!st.logTail.endsWith("\n")) sb.append("\n")
    return sb.toString()
}
