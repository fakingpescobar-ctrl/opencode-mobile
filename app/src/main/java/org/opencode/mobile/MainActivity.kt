package org.opencode.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.net.Uri
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.opencode.mobile.server.OpencodeServerService
import org.opencode.mobile.ui.ChatOverlay
import org.opencode.mobile.stt.WhisperTranscribeService
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.opencode.mobile.ui.theme.OpencodeMobileTheme

private const val TAG = "OpencodeWebView"

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // «Доступ ко всем файлам» (MANAGE_EXTERNAL_STORAGE, API 30+): открывает
    // системные Настройки — приложения — доступ к файлам, где юзер вручную
    // включает переключатель. Нужно, чтобы рабочая директория модели указывала
    // на настоящие Documents на внешнем хранилище, а не на внутреннюю песочницу.
    private val allFilesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Вернулись из настроек. Если юзер включил «Доступ ко всем файлам» —
            // мягко перезапускаем serve: цикл runServerLoop перезапустит его с
            // внешним workspace (Documents/OpencodeTerminal). Безопасно — не трогает
            // foreground-сервис и не отменяет serverJob (stop+start гонялся и валил сервер).
            if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
                Log.i(TAG, "All-files access granted — soft-restart serve for external workspace")
                OpencodeServerService.restart(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // edge-to-edge: без этого на Android 15+ windowSoftInputMode="adjustResize" не работает,
        // IME рисуется ПОВЕРХ терминала и поле ввода opencode остаётся под клавиатурой.
        // enableEdgeToEdge включает wiring флагов, чтобы окно сжималось под софт-клавиатуру.
        enableEdgeToEdge()
        requestAllFilesAccessIfNeeded()
        OpencodeServerService.start(this)
        requestNotificationPermission()
        Thread {
            try {
                val f = File(filesDir, "test.f32")
                if (f.exists()) {
                    val bytes = f.readBytes()
                    val n = bytes.size / 4
                    val samples = FloatArray(n)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
                    Log.i("AUTOSTT", "autostt: samples=$n engine=ncnn turbo")
                    val res = runBlocking {
                        WhisperTranscribeService.transcribe(
                            applicationContext, samples,
                            model = WhisperTranscribeService.MODEL_TURBO,
                            engine = WhisperTranscribeService.ENGINE_NCNN,
                            timeoutMs = 600_000L
                        )
                    }
                    Log.i("AUTOSTT", "autostt result: '$res'")
                } else {
                    Log.i("AUTOSTT", "no test.f32")
                }
            } catch (e: Throwable) {
                Log.e("AUTOSTT", "autostt fail", e)
            }
        }.start()
        setContent {
            OpencodeMobileTheme {
                TerminalScreen()
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Настоящие файлы юзера на внешнем хранилище: открыть «Доступ ко всем файлам». */
    private fun requestAllFilesAccessIfNeeded() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                allFilesLauncher.launch(intent)
            } catch (_: Exception) {
                try {
                    allFilesLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                    // на очень старых/СCustom ROM нет этой настройки — пропускаем,
                    // workspace останется внутренним (fallback).
                }
            }
        }
    }
}

@Composable
fun TerminalScreen() {
    val state by OpencodeServerService.state.collectAsState()

    // Сервер уже запущен в onCreate() (OpencodeServerService.start(this)).
    // Здесь НЕ дублируем второй start: служба идемпотентна (guard
    // if (serverJob?.isActive != true)), а лишняя intent — просто шум.
    // Если сервер остановлен юзером через уведомление — состояние перейдёт
    // в STOPPED, и повторный onCreate после перезапуска приложения поднимет его.

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.status == OpencodeServerService.ServerStatus.RUNNING -> {
                    Box(Modifier.fillMaxSize()) {
                        // WebView (SPA opencode) живёт в фоне; нативный ChatOverlay
                        // полностью перекрывает его. paused=true → WebView оставлен
                        // для запросов, но его рендер остановлен (нет frame-спайков
                        // от бесконечной перерисовки SPA под чатом).
                        OpencodeWebView(paused = true)
                        ChatOverlay(Modifier.align(Alignment.BottomCenter))
                    }
                }
                state.status == OpencodeServerService.ServerStatus.STARTING -> {
                    CenteredStatus("Starting OpenCode server…")
                }
                state.status == OpencodeServerService.ServerStatus.ERROR -> {
                    CenteredStatus("Server failed. See logcat")
                }
                else -> {
                    CenteredStatus("Server stopped")
                }
            }
        }
    }
}

private enum class ModelOption(val id: String, val label: String) {
    BIG_PICKLE("opencode/big-pickle", "big-pickle"),
    DEFAULT("auto", "Auto / Default"),
}

private val EFFORTS = listOf("low", "medium", "high")

@Composable
private fun ControlBar(status: OpencodeServerService.ServerStatus) {
    var selModel by remember { mutableStateOf(ModelOption.DEFAULT) }
    var selEffort by remember { mutableStateOf("medium") }

    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(status)
            DropdownButton(label = "Model: ${selModel.label}", options = ModelOption.entries.map { it.label }) { picked ->
                selModel = ModelOption.entries.first { it.label == picked }
            }
            DropdownButton(label = "Effort: $selEffort", options = EFFORTS) { picked ->
                selEffort = picked
            }
        }
    }
}

@Composable
private fun StatusDot(status: OpencodeServerService.ServerStatus) {
    val color = when (status) {
        OpencodeServerService.ServerStatus.RUNNING -> MaterialTheme.colorScheme.primary
        OpencodeServerService.ServerStatus.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(status.name, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun DropdownButton(label: String, options: List<String>, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }) {
            Text(label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        onPick(opt)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun CenteredStatus(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun OpencodeWebView(paused: Boolean = false) {
    val context = LocalContext.current
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    javaScriptCanOpenWindowsAutomatically = false
                    // SPA opencode 1.18.25 имеет ВСТРОЕННЫЙ звук уведомления: при приходе
                    // ответа модели через Web Audio (AAudio, USAGE_MEDIA) играет свой
                    // тон — это «первый непонятный звук» из двух. Второй — наш
                    // playNotificationSound (USAGE_NOTIFICATION). Убираем WebView-звук
                    // автоплея: requireUserGesture=true + JS-мьют ниже (см. onPageFinished).
                    mediaPlaybackRequiresUserGesture = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    // SPA opencode сам рендерит тёмную тему (localStorage scheme=dark) —
                    // forceDark отключаем, иначе он ломает контраст элементов.
                }
                WebView.setWebContentsDebuggingEnabled(true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.i(TAG, "onPageFinished: $url")
                        // Глушим звук SPA. Инжектим JS, который переопределяет Web Audio
                        // и замучивает медиа-элементы — чтобы встроенное уведомление
                        // opencode НЕ играло свой тон (он и есть «первый» из двух звуков).
                        // Не срашиваем ничего: best-effort, стабильно молчит.
                        view?.evaluateJavascript(
                            """
                            (function(){
                              try{
                                var Ctor = window.AudioContext || window.webkitAudioContext;
                                if(Ctor){
                                  var origOut = Ctor.prototype.createGain;
                                  // MUTing: перенаправим все выходы на заглушенный gain=0.
                                  Object.defineProperty(Ctor.prototype,'createGain',{value:function(){
                                    var g = origOut.call(this);
                                    try{g.gain.value=0}catch(e){}
                                    return g;
                                  }});
                                  // Также перехватим специализированные узлы-источники.
                                  try{
                                    var g0 = Ctor.prototype.createGain.call(this);
                                    // создаём нулевой gain для будущих connect
                                    window.__muteGain = g0;
                                  }catch(e){}
                                }
                                // Media elements
                                document.querySelectorAll('audio,video').forEach(function(el){
                                  el.muted=true; el.volume=0;
                                });
                              }catch(e){}
                            })();
                            """.trimIndent(),
                            null
                        )
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        Log.e(TAG, "onReceivedError code=${error?.errorCode} desc=${error?.description} url=${request?.url}")
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        Log.d(TAG, "console[${consoleMessage?.messageLevel()}] ${consoleMessage?.message()}")
                        return true
                    }
                }
                loadUrl("http://127.0.0.1:${OpencodeApp.ServerConfig.PORT}/")
            }
        },
        update = { wv ->
            // Фоновый WebView приостановлен, когда поверх него активен нативный
            // ChatOverlay (он полностью закрывает SPA). Это останавливает JS-таймеры
            // SPA и убирает его рендер с GPU — уходит постоянная перерисовка, которая
            // давала frame-спайки на каждом тике поллинга. При paused=false (нужен
            // полный интерфейс SPA) — WebView возвращается к жизни.
            if (paused) {
                if (wv != null && wv.visibility != View.INVISIBLE) {
                    wv.onPause()
                    wv.pauseTimers()
                    wv.visibility = View.INVISIBLE
                }
            } else {
                if (wv != null && wv.visibility != View.VISIBLE) {
                    wv.visibility = View.VISIBLE
                    wv.onResume()
                    wv.resumeTimers()
                }
            }
        },
        onRelease = { wv ->
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.destroy()
        },
        modifier = Modifier.fillMaxSize()
    )
}
