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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.opencode.mobile.MainActivity
import org.opencode.mobile.OpencodeApp
import org.opencode.mobile.R

/**
 * Foreground-сервис, отвечающий за жизненный цикл процесса opencode serve.
 *
 * Тяжёлая логика (валидация, старт памяти/serve, health-check, рестарты
 * с backoff, причины остановки) вынесена в [RuntimeManager]; здесь —
 * только foreground-обвязка, нотификации и публикация статуса в [state]
 * для UI (маппинг RuntimeStage -> ServerStatus; сигнатура для MainActivity
 * не менялась).
 */
class OpencodeServerService : Service() {
    enum class ServerStatus { STARTING, RUNNING, ERROR, STOPPED }

    data class ServerState(
        val status: ServerStatus = ServerStatus.STOPPED,
        val port: Int = OpencodeApp.ServerConfig.PORT,
        val logTail: String = "",
        val workspaceExternal: Boolean = false,
        val lastError: RuntimeError? = null,
        val stopReason: StopReason? = null,
        val restartCount: Int = 0,
    )

    companion object {
        const val ACTION_START = "org.opencode.mobile.START"
        const val ACTION_STOP = "org.opencode.mobile.STOP"
        const val ACTION_RESTART = "org.opencode.mobile.RESTART"

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

        /**
         * Мягкий рестарт: убивает текущий процесс serve, НЕ трогая foreground-сервис
         * и НЕ отменяя serverJob. Цикл RuntimeManager видит мёртвый процесс и
         * перезапускает serve с заново резолвнутым workspace (нужно для подхвата
         * внешнего хранилища после выдачи «Доступа ко всем файлам»).
         */
        fun restart(context: Context) {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(Intent(context, OpencodeServerService::class.java).setAction(ACTION_RESTART))
            } else {
                context.startService(Intent(context, OpencodeServerService::class.java).setAction(ACTION_RESTART))
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var serverJob: Job? = null

    /** false после onDestroy — колбэки менеджера больше ничего не публикуют. */
    @Volatile
    private var serviceActive = true

    /** true после ACTION_STOP: защита от START_STICKY — система может пересоздать
     *  сервис с null intent (после kill), и else-ветка запустила бы runtime заново. */
    @Volatile
    private var stopRequested = false

    /** Лениво создаётся при первом старте; nullable — чтобы onDestroy не триггерил
     *  инициализацию менеджера для сервиса, убитого системой до ACTION_START.
     *  @Volatile: читается из ACTION_RESTART (main), пишется из корутины (worker). */
    @Volatile
    private var runtimeManager: RuntimeManager? = null

    private fun manager(): RuntimeManager =
        synchronized(this) {
            runtimeManager ?: RuntimeManager(applicationContext) { rt -> onRuntimeState(rt) }
                .also { runtimeManager = it }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Обязательно перейти в foreground ПЕРЕД остановкой, иначе
                // ForegroundServiceDidNotStartInTimeException (сервис мог быть не в foreground).
                if (Build.VERSION.SDK_INT >= 26) {
                    try {
                        startAsForeground(buildNotification("Stopping"))
                    } catch (e: Exception) {
                        android.util.Log.w("OpencodeServer", "ACTION_STOP foreground fail: ${e.message}")
                    }
                }
                stopRequested = true
                stopServer()
                // Не даём системе возродить сервис (START_STICKY) после явного стопа.
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                // Мягкий рестарт: убить процесс serve (цикл перезапустит с новым workspace).
                if (Build.VERSION.SDK_INT >= 26) {
                    try {
                        startAsForeground(buildNotification("Restarting"))
                    } catch (e: Exception) {
                        android.util.Log.w("OpencodeServer", "ACTION_RESTART foreground fail: ${e.message}")
                    }
                }
                stopRequested = false
                val mgr = runtimeManager
                if (mgr != null && serverJob?.isActive == true) {
                    // Цикл жив — мягкий рестарт уходит в него и обрабатывается.
                    mgr.requestRestart()
                } else {
                    // Менеджера нет ИЛИ цикл завершился (терминальный CRASHED):
                    // рестартить нечего — стартуем новый цикл с чистого листа.
                    serverJob = scope.launch { manager().run() }
                }
            }
            ACTION_START -> {
                // Явный старт из UI: снимаем запрет на возрождение.
                stopRequested = false
                if (serverJob?.isActive != true) {
                    startAsForeground(buildNotification("Starting"))
                    serverJob = scope.launch { manager().run() }
                }
            }
            null -> {
                // Системный перезапуск (START_STICKY) после kill — не поднимать runtime,
                // если юзер ранее явно остановил сервис.
                if (stopRequested) return START_NOT_STICKY
                if (serverJob?.isActive != true) {
                    startAsForeground(buildNotification("Starting"))
                    serverJob = scope.launch { manager().run() }
                }
            }
        }
        return START_STICKY
    }

    /** RuntimeState -> ServerState (UI) + нотификация. Вызывается из корутины RuntimeManager. */
    private fun onRuntimeState(rt: RuntimeState) {
        // После onDestroy сервис считаем мёртвым: колбэки из in-flight корутины
        // не должны публиковать state и «воскрешать» нотификацию поверх STOP.
        if (!serviceActive) return
        _state.value =
            ServerState(
                status = rt.stage.toServerStatus(),
                port = rt.port,
                workspaceExternal = rt.workspaceExternal,
                lastError = rt.lastError,
                stopReason = rt.stopReason,
                restartCount = rt.restartCount,
            )
        updateNotification(rt.stage.toNotificationText())
    }

    private fun RuntimeStage.toServerStatus() =
        when (this) {
            RuntimeStage.IDLE, RuntimeStage.STOPPING, RuntimeStage.STOPPED -> ServerStatus.STOPPED
            RuntimeStage.PREPARING, RuntimeStage.STARTING_MEMORY, RuntimeStage.STARTING_SERVER,
            RuntimeStage.RESTARTING,
            -> ServerStatus.STARTING
            RuntimeStage.HEALTHY, RuntimeStage.DEGRADED -> ServerStatus.RUNNING
            RuntimeStage.CRASHED, RuntimeStage.FAILED_PERMANENTLY -> ServerStatus.ERROR
        }

    private fun RuntimeStage.toNotificationText() =
        when (this) {
            RuntimeStage.IDLE, RuntimeStage.STOPPED -> "Stopped"
            RuntimeStage.STOPPING -> "Stopping"
            RuntimeStage.PREPARING, RuntimeStage.STARTING_MEMORY, RuntimeStage.STARTING_SERVER -> "Starting"
            RuntimeStage.RESTARTING -> "Restarting"
            RuntimeStage.HEALTHY, RuntimeStage.DEGRADED -> "Running"
            RuntimeStage.CRASHED, RuntimeStage.FAILED_PERMANENTLY -> "Error"
        }

    private fun stopServer() {
        // requestStop неблокирующий: флаг + daemon-тред гасит процессы serve/memory.
        // Здесь же отменяем корутину цикла, чтобы run() не «воскресил» процесс
        // после stopSelf() (CancellationException из delay пробросится в finally).
        // runtimeManager может быть null (STOP без старта) — тогда не создаём.
        runtimeManager?.requestStop()
        serverJob?.cancel()
        serverJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceActive = false
        // Синхронная остановка процессов: daemon-тред requestStop мог бы не успеть
        // до убийства процесса приложения, и дочерние serve/memory выжили бы.
        runtimeManager?.stopNow()
        serverJob?.cancel()
        serverJob = null
        scope.cancel()
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
            },
        )
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Канал сервера — ТИХИЙ: это служебный foreground нотификатор, который
        // обновляется при смене состояния (в т.ч. во время ответа модели). Без
        // setSound(null) часть устройств (OPPO/и др. скинки) проигрывает звук
        // канала при каждом повторном notify() — что давало «второй» (лишний)
        // звук при приходе ответа. Жёстко обнуляем звук на канале.
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_server),
                NotificationManager.IMPORTANCE_LOW,
            )
        channel.setSound(null, null)
        channel.enableVibration(false)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(stateText: String): Notification {
        val pi =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        var builder =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.notif_icon)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("OpenCode server — $stateText")
                .setContentIntent(pi)
                .setOngoing(true)
                // Дублирующая защита от звука на уровне самого уведомления: гарантирует
                // тишину даже если канал переопределён скинкой устройства.
                .setSilent(true)
        // кнопка stop
        val stopPi =
            PendingIntent.getService(
                this,
                1,
                Intent(this, OpencodeServerService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
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
    fun startForegroundService(
        context: Context,
        intent: Intent,
    ) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
