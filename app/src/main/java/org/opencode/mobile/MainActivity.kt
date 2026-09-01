package org.opencode.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.LaunchedEffect
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
}

@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    val state by OpencodeServerService.state.collectAsState()

    // запуск сервера при появлении экрана
    LaunchedEffect(Unit) {
        OpencodeServerService.start(context)
    }

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Веб-интерфейс opencode во весь экран (как в десктопном браузере),
            // с полноценным полем ввода чата. Панель-заглушка с кнопками удалена.
            when (state.status) {
                OpencodeServerService.ServerStatus.RUNNING -> {
                    Box(Modifier.fillMaxSize()) {
                        OpencodeWebView()
                        // Лента чата поверх WebView (обход бага SPA: история не рендерится).
                        ChatOverlay(Modifier.align(Alignment.BottomCenter))
                    }
                }
                OpencodeServerService.ServerStatus.STARTING -> {
                    CenteredStatus("Starting OpenCode server…")
                }
                OpencodeServerService.ServerStatus.ERROR -> {
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
fun OpencodeWebView() {
    val context = LocalContext.current
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    javaScriptCanOpenWindowsAutomatically = false
                    mediaPlaybackRequiresUserGesture = false
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
        update = { },
        onRelease = { wv ->
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.destroy()
        },
        modifier = Modifier.fillMaxSize()
    )
}
