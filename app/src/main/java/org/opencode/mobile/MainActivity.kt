package org.opencode.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencode.mobile.account.YandexAccountController
import org.opencode.mobile.account.YandexOAuth
import org.opencode.mobile.server.OpencodeServerService
import org.opencode.mobile.server.ServerAuth
import org.opencode.mobile.server.Workspace
import org.opencode.mobile.server.WorkspacePath
import org.opencode.mobile.ui.ChatOverlay
import org.opencode.mobile.ui.theme.OpencodeMobileTheme

private const val TAG = "OpencodeWebView"
private const val WORKSPACE_ONBOARDING_PREFS = "workspace_onboarding"
private const val WORKSPACE_ONBOARDING_DONE = "done"

class MainActivity : ComponentActivity() {
    /** Пока true — показываем онбординг и НЕ поднимаем serve. */
    private var needWorkspacePermission by mutableStateOf(false)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // «Доступ ко всем файлам» (MANAGE_EXTERNAL_STORAGE, API 30+): открывает
    // системные Настройки — приложения — доступ к файлам, где юзер вручную
    // включает переключатель. Нужно, чтобы рабочая директория модели указывала
    // на настоящие Documents на внешнем хранилище, а не на внутреннюю песочницу.
    private val allFilesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Вернулись из системных настроек. Сервер намеренно не стартовал
            // раньше: гонка «права ещё нет, а serve уже резолвит workspace»
            // и рождала ENOENT на чистой установке.
            prepareWorkspaceAndStart()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // edge-to-edge: без этого на Android 15+ windowSoftInputMode="adjustResize" не работает,
        // IME рисуется ПОВЕРХ терминала и поле ввода opencode остаётся под клавиатурой.
        // enableEdgeToEdge включает wiring флагов, чтобы окно сжималось под софт-клавиатуру.
        enableEdgeToEdge()
        // Пароль serve читаем сразу: WebView (SPA) стартует раньше сервиса и
        // должен успеть авторизоваться Basic-заголовком с первого запроса.
        // load-or-create: если сервис/старый ран уже генерили — читаем тот же,
        // иначе создаём и шифруем в Keystore сами (serve потом прочитает).
        ServerAuth.ensurePassword(this)
        // Сначала выясняем статус доступа к файлам, и только потом поднимаем
        // serve. Раньше запрос уходил в Настройки асинхронно, а сервер стартовал
        // сразу — workspace резолвился без права и падал ENOENT.
        when {
            Workspace.hasAllFilesAccess() -> prepareWorkspaceAndStart()
            onboardingDone() -> startServerWithoutPermission()
            else -> needWorkspacePermission = true
        }
        requestNotificationPermission()
        setContent {
            OpencodeMobileTheme {
                if (needWorkspacePermission) {
                    WorkspacePermissionGate(
                        onGrant = { requestAllFilesAccessIfNeeded() },
                        onSkip = { startServerWithoutPermission() },
                    )
                } else {
                    TerminalScreen()
                }
            }
        }
        // Редирект разбирается и здесь, а не только в onNewIntent. Если процесс убили,
        // пока юзер согласовывал доступ в браузере, система создаст Activity заново и
        // onNewIntent не вызовется вовсе - код просто исчезнет, и юзер увидит, что вход
        // «не сработал», хотя он всё разрешил.
        handleOauthRedirect(intent)
    }

    /**
     * Сюда приходит код согласия Яндекса: приложение `singleTask`, поэтому браузер не
     * создаёт второй экземпляр, а отдаёт intent уже живущей Activity. `setIntent` обязателен —
     * иначе `getIntent()` продолжит отдавать старый MAIN, и перезапуск приложения потеряет код.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOauthRedirect(intent)
    }

    private fun handleOauthRedirect(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme != YandexOAuth.SCHEME || data.host != YandexOAuth.HOST) return
        // Инициализация здесь, а не только в мосту: Activity может пережить перезапуск
        // сервиса, и тогда контроллер останется без контекста ровно в момент возврата.
        YandexAccountController.initialize(this)
        // Обмен кода на токен ходит в сеть, а onNewIntent — главный поток, где сеть запрещена.
        // Поэтому весь разбор уходит в отдельный поток, а результат возвращается в UI.
        Thread(
            {
                val outcome =
                    runCatching { YandexAccountController.handleCallback(data.toString()) }
                        .getOrElse { error ->
                            YandexAccountController.Outcome.Rejected(
                                error.message ?: "authorization failed",
                            )
                        }
                reportOauthOutcome(outcome)
            },
            "yandex-oauth-callback",
        ).start()
    }

    private fun reportOauthOutcome(outcome: YandexAccountController.Outcome) {
        val message =
            when (outcome) {
                is YandexAccountController.Outcome.Connected ->
                    "Yandex Music connected as ${outcome.identity.login}"
                is YandexAccountController.Outcome.Rejected ->
                    "Yandex Music: ${outcome.reason}"
                // Недостижимо на этой дорожке: Waiting рождается только в опросе device-flow,
                // а сюда приходит исключительно PKCE-код из deep link. Ветка названа прямо,
                // а не спрятана в else, иначе новое состояние молча проходило бы как успех.
                is YandexAccountController.Outcome.Waiting -> return
            }
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    /**
     * Готовим рабочую папку и только потом стартуем serve.
     *
     * `WorkspacePath.prepare` создаёт каталог и пишет пробный файл: одного
     * «право выдано» мало — на некоторых ROM папка видна, но запись отбита.
     * Всё это на IO-потоке, в UI возвращаемся уже с готовым путём.
     */
    private fun prepareWorkspaceAndStart() {
        Thread(
            {
                val root = Workspace.externalRoot()
                if (Workspace.hasAllFilesAccess() && root != null) {
                    val prepared = WorkspacePath.prepare(root.absolutePath)
                    Log.i(
                        TAG,
                        "workspace prepared: ${prepared.dir?.absolutePath} problem=${prepared.problem}",
                    )
                }
                runOnUiThread {
                    markOnboardingDone()
                    needWorkspacePermission = false
                    OpencodeServerService.start(this@MainActivity)
                }
            },
            "workspace-prepare",
        ).start()
    }

    /** Юзер отказался от доступа к файлам — работаем в песочнице, без падения. */
    private fun startServerWithoutPermission() {
        markOnboardingDone()
        needWorkspacePermission = false
        OpencodeServerService.start(this)
    }

    private fun onboardingDone(): Boolean =
        getSharedPreferences(WORKSPACE_ONBOARDING_PREFS, MODE_PRIVATE)
            .getBoolean(WORKSPACE_ONBOARDING_DONE, false)

    private fun markOnboardingDone() {
        getSharedPreferences(WORKSPACE_ONBOARDING_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(WORKSPACE_ONBOARDING_DONE, true)
            .apply()
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

/**
 * Первый запуск: объясняем, зачем приложению доступ к файлам, и даём выбор.
 * Пока этот экран наверху, serve не поднят — это и есть гейт от ENOENT.
 */
@Composable
private fun WorkspacePermissionGate(
    onGrant: () -> Unit,
    onSkip: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Рабочая папка", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Приложение работает с вашими файлами. Разрешите доступ — мы создадим " +
                    "папку Documents/OpencodeTerminal и будем хранить проекты там. " +
                    "Без доступа всё останется только внутри приложения.",
                modifier = Modifier.padding(top = 12.dp),
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onGrant,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) {
                Text("Разрешить доступ к файлам")
            }
            Button(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("Продолжить без доступа")
            }
        }
    }
}

@Composable
fun TerminalScreen() {
    val state by OpencodeServerService.state.collectAsState()
    val context = LocalContext.current

    // Отчёт о новом сбое шлёт сервис (onRuntimeState); UI здесь только досылает
    // накопленную очередь отложенных отчётов при каждом запуске.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { FailureReporter.flush(context) }
    }

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
                    ServerErrorState(state)
                }
                else -> {
                    CenteredStatus("Server stopped")
                }
            }
        }
    }
}

/**
 * Экран сбоя сервера. Раньше тут был только CenteredStatus — юзер видел белый
 * экран без причины и не мог зайти в диагностику (в этой ветке нет ControlBar).
 * lastError уже есть в ServerState: показываем код/сообщение прямо здесь,
 * чтобы причину можно было переписать со скрина даже без adb и без входа в UI.
 */
@Composable
private fun ServerErrorState(state: OpencodeServerService.ServerState) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Server failed. See logcat",
            style = MaterialTheme.typography.bodyLarge,
        )
        val err = state.lastError
        if (err != null) {
            Text(
                text = "${err.stage} / ${err.code} (recoverable=${err.recoverable})\n${err.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp),
            )
            val history = state.errorHistory
            if (history.isNotEmpty()) {
                Text(
                    text = history.takeLast(3).joinToString("\n") {
                        "${it.code}: ${it.message}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp),
                )
            }
        } else {
            Text(
                text = "Причина неизвестна: lastError пуст",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

private enum class ModelOption(
    val id: String,
    val label: String,
) {
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
private fun DropdownButton(
    label: String,
    options: List<String>,
    onPick: (String) -> Unit,
) {
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
                    // SPA opencode имеет ВСТРОЕННЫЙ звук уведомления: при приходе
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
                // Remote debugging только в debug-сборке: в release это дыра
                // (chrome://inspect к WebView с правами приложения).
                if (BuildConfig.DEBUG) {
                    WebView.setWebContentsDebuggingEnabled(true)
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView?,
                        url: String?,
                    ) {
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
                            null,
                        )
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        super.onReceivedError(view, request, error)
                        Log.e(TAG, "onReceivedError code=${error?.errorCode} desc=${error?.description} url=${request?.url}")
                    }

                    // serve защищён Basic-auth (OPENCODE_SERVER_PASSWORD) — SPA сама
                    // не знает пароль, отвечаем за неё системным диалогом WebView.
                    override fun onReceivedHttpAuthRequest(
                        view: WebView?,
                        handler: android.webkit.HttpAuthHandler?,
                        host: String?,
                        realm: String?,
                    ) {
                        // Рассчитываем на ServerAuth (заполнен в onCreate), но подстраховываемся
                        // повторным вызовом — serve мог стартовать уже после нашего onCreate.
                        if (ServerAuth.password.isNullOrEmpty()) {
                            // OpencodeWebView — top-level функция, контекст берём из WebView.
                            val ctx = view?.context ?: return
                            ServerAuth.ensurePassword(ctx)
                        }
                        val pwd = ServerAuth.password
                        if (handler != null && pwd != null) {
                            handler.proceed("opencode", pwd)
                        } else {
                            handler?.cancel()
                        }
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        Log.d(TAG, "console[${consoleMessage?.messageLevel()}] ${consoleMessage?.message()}")
                        return true
                    }
                }
                ServerAuth.basicHeader()?.let { h ->
                    loadUrl("http://127.0.0.1:${OpencodeApp.ServerConfig.PORT}/", mapOf("Authorization" to h))
                } ?: loadUrl("http://127.0.0.1:${OpencodeApp.ServerConfig.PORT}/")
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
