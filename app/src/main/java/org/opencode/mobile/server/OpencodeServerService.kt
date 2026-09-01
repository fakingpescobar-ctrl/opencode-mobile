package org.opencode.mobile.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opencode.mobile.MainActivity
import org.opencode.mobile.OpencodeApp
import org.opencode.mobile.R
import java.io.File

/**
 * Foreground-сервис, отвечающий за жизненный цикл процесса opencode serve.
 * - стартует opencode через OpencodeRuntime
 * - рестартует с backoff при падении
 * - валидирует HTTP-доступность
 * - публикует статус в [state] для UI
 */
class OpencodeServerService : Service() {

    enum class ServerStatus { STARTING, RUNNING, ERROR, STOPPED }

    data class ServerState(
        val status: ServerStatus = ServerStatus.STOPPED,
        val port: Int = OpencodeApp.ServerConfig.PORT,
        val logTail: String = "",
    )

    companion object {
        const val ACTION_START = "org.opencode.mobile.START"
        const val ACTION_STOP = "org.opencode.mobile.STOP"

        private const val CHANNEL_ID = "opencode_server"
        private const val NOTIF_ID = 1001

        private val _state = MutableStateFlow(ServerState())
        val state: StateFlow<ServerState> = _state

        fun start(context: Context) {
            val i = Intent(context, OpencodeServerService::class.java).setAction(ACTION_START)
            ContextCompatSafe.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(Intent(context, OpencodeServerService::class.java).setAction(ACTION_STOP))
            } else {
                context.startService(Intent(context, OpencodeServerService::class.java).setAction(ACTION_STOP))
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var serverJob: Job? = null
    private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Обязательно перейти в foreground ПЕРЕД остановкой, иначе
                // ForegroundServiceDidNotStartInTimeException (сервис мог быть не в foreground).
                if (Build.VERSION.SDK_INT >= 26) {
                    try {
                        startAsForeground(buildNotification("Stopping"))
                    } catch (e: Exception) {
                        // ignore — сервис уже умирает
                    }
                }
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                if (serverJob?.isActive != true) {
                    startAsForeground(buildNotification("Starting"))
                    serverJob = scope.launch { runServerLoop() }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun runServerLoop() {
        val logFile = File(filesDir, "opencode.log")
        var attempt = 0
        val context = applicationContext
        while (currentCoroutineContext().isActive) {
            _state.value = _state.value.copy(status = ServerStatus.STARTING)
            updateNotification("Starting")

            // Рабочая директория (workspace): без неё у opencode serve нет ни одного
            // проекта — SPA показывал "Здесь пока ничего нет" и сессии не создавались.
            // Эта версия serve не понимает --dir, поэтому директория задаётся
            // через CWD процесса (workDir).
            val workspace = File(filesDir, "workspace").apply { mkdirs() }
            val readme = File(workspace, "README.md")
            if (!readme.exists()) {
                readme.writeText("# OpenCode Mobile Workspace\n\nРабочая директория для сессий на устройстве.\n")
            }

            val proc = OpencodeRuntime.startServe(
                context,
                logFile = logFile,
                workDir = workspace,
            )
            if (proc == null) {
                _state.value = _state.value.copy(status = ServerStatus.ERROR)
                updateNotification("Error")
                return
            }
            process = proc

            if (waitForHttp(OpencodeApp.ServerConfig.PORT)) {
                _state.value = _state.value.copy(status = ServerStatus.RUNNING)
                updateNotification("Running")
                attempt = 0
                // ЖИВЁМ пока процесс жив и сервер отвечает. Не спавним новый поверх
                // живого (иначе — порт занят, респавн каждые ~2 сек).
                while (proc.isAlive && currentCoroutineContext().isActive) {
                    delay(3000)
                }
                process = null
                if (!currentCoroutineContext().isActive) return
                _state.value = _state.value.copy(status = ServerStatus.STARTING)
                attempt = 0
            } else {
                proc.destroy()
                if (proc.isAlive) proc.destroyForcibly()
                process = null
                _state.value = _state.value.copy(status = ServerStatus.ERROR)
                updateNotification("Error")
            }

            attempt++
            // backoff: 1s, 2s, 4s ... cap 15s
            val waitMs = (1000L shl minOf(attempt, 4)).coerceAtMost(15_000L)
            delay(waitMs)
        }
    }

    /** Пингует HTTP localhost:port, ждёт пока сервер ответит. */
    private suspend fun waitForHttp(port: Int): Boolean {
        repeat(60) {
            if (!currentCoroutineContext().isActive) return false
            if (pingOk(port)) return true
            delay(500)
        }
        return false
    }

    private fun pingOk(port: Int): Boolean = try {
        val url = java.net.URL("http://127.0.0.1:$port/")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 1000
        conn.readTimeout = 1000
        conn.requestMethod = "HEAD"
        val code = conn.responseCode
        conn.disconnect()
        code in 200..499
    } catch (e: Exception) {
        false
    }

    private fun stopServer() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        serverJob?.cancel()
        serverJob = null
        process?.destroy()
        process = null
        _state.value = _state.value.copy(status = ServerStatus.STOPPED)
    }

    override fun onDestroy() {
        scope.cancel()
        process?.destroy()
        process = null
        super.onDestroy()
    }

    // --- notification ---

    /**
     * Поднимает сервис в передний план с явным типом specialUse.
     * На Android 10+ тип обязан совпадать с foregroundServiceType в манифесте.
     * Явная передача типа обязательна на Android 14+, иначе
     * MissingForegroundServiceTypeException.
     */
    private fun startAsForeground(notif: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notif,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_server), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(stateText: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        var builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.notif_icon)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("OpenCode server — $stateText")
            .setContentIntent(pi)
            .setOngoing(true)
        // кнопка stop
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, OpencodeServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        builder = builder.addAction(0, "Stop", stopPi)
        return builder.build()
    }

    private fun updateNotification(stateText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(stateText))
    }
}

/** Минимальный хелпер: startForegroundService с fallback на startService (API<26). */
object ContextCompatSafe {
    fun startForegroundService(context: Context, intent: Intent) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
