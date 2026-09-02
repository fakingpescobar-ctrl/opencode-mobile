package org.opencode.mobile.stt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.whispercpp.whisper.NcnnWhisperContext
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.CoroutineScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground-сервис для локального распознавания речи (whisper.cpp).
 *
 * Зачем: ColorOS душит CPU фоновых compute-потоков до 1-5% — whisper в обычном
 * coroutine-потоке считается часами. Foreground-сервис получает от ОС нормальный
 * приоритет CPU (тот же механизм, что у навигаторов и плееров).
 *
 * Использование: один статический вызов transcribe(context, samples) — он сам
 * поднимает сервис, ждёт результат и гасит сервис. WhisperContext кэшируется
 * между вызовами (модель грузится один раз).
 */
class WhisperTranscribeService : Service() {

    companion object {
        private const val CHANNEL_ID = "whisper_stt"
        private const val TAG = "VOICE"

        /** Идентификаторы моделей: base живёт в assets, turbo — скачивается в filesDir. */
        const val MODEL_BASE = "base"
        const val MODEL_TURBO = "turbo"

        @Volatile private var pendingSamples: FloatArray? = null
        @Volatile private var pendingModel: String = MODEL_BASE
        @Volatile private var pendingEngine: String = ENGINE_WHISPER
        private val results = MutableSharedFlow<String>(extraBufferCapacity = 4)

        // Кэш контекстов живёт в сервисе: каждая модель грузится один раз за процесс.
        // Turbo (574MB) грузится долго — кэш на две модели, чтобы не перезагружать.
        private val whisperCtxCache = HashMap<String, WhisperContext>()
        // Отдельный кэш для ncnn-движка (другой тип контекста, свой JNI).
        private val ncnnCtxCache = HashMap<String, NcnnWhisperContext>()

        /** Движки распознавания. */
        const val ENGINE_WHISPER = "whisper"
        const val ENGINE_NCNN = "ncnn"

        /**
         * Блокирующий (suspend) запуск распознавания через foreground-сервис.
         * @param model MODEL_BASE (assets) или MODEL_TURBO (файл в filesDir) — для whisper.cpp.
         * @param engine ENGINE_WHISPER (whisper.cpp CPU) или ENGINE_NCNN (ncnn CPU).
         * Возвращает текст либо строку "ОШИБКА WHISPER: ..." (в т.ч. по таймауту).
         */
        suspend fun transcribe(
            context: Context,
            samples: FloatArray,
            model: String = MODEL_BASE,
            engine: String = ENGINE_WHISPER,
            timeoutMs: Long = 90_000L
        ): String {
            pendingSamples = samples
            pendingModel = model
            pendingEngine = engine
            val ctx = context.applicationContext
            val intent = Intent(ctx, WhisperTranscribeService::class.java)
            ctx.startForegroundService(intent)
            val result = withTimeoutOrNull(timeoutMs) { results.first() }
            if (result == null) {
                // Не дождались — гасим сервис, иначе останется висеть с уведомлением.
                ctx.stopService(Intent(ctx, WhisperTranscribeService::class.java))
                return "ОШИБКА WHISPER: таймаут ${timeoutMs / 1000}с — телефон не даёт CPU"
            }
            return result
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val samples = pendingSamples
        val model = pendingModel
        if (samples == null || samples.isEmpty()) {
            Log.w(TAG, "сервис запущен без сэмплов — гасну")
            stopSelf()
            return START_NOT_STICKY
        }
        pendingSamples = null
        val engine = pendingEngine
        Log.d(TAG, "сервис: считаю ${samples.size} сэмплов (foreground), модель=$model, движок=$engine")
        scope.launch {
            val text = try {
                if (engine == ENGINE_NCNN) {
                    val ctx = obtainNcnnContext(model)
                    ctx.transcribeData(samples, lang = "ru").trim()
                } else {
                    val ctx = obtainContext(model)
                    ctx.transcribeData(samples, printTimestamp = false).trim()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "распознавание упало (в сервисе)", e)
                "ОШИБКА WHISPER: ${e.message}"
            }
            Log.d(TAG, "сервис: результат '${text.take(80)}'")
            results.tryEmit(text)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * Возвращает (и кэширует) контекст whisper под выбранную модель.
     * base — из assets, turbo — с файла в filesDir (предполагается, что
     * уже скачана через ModelDownloader; если нет — понятная ошибка).
     */
    private fun obtainContext(model: String): WhisperContext =
        whisperCtxCache.getOrPut(model) {
            when (model) {
                WhisperTranscribeService.MODEL_BASE -> {
                    val f = ModelDownloader.baseFile(application)
                    if (!f.exists() || f.length() < 140L * 1024 * 1024) {
                        throw IllegalStateException(
                            "base-модель не скачана (${if (f.exists()) (f.length() / 1024 / 1024) else 0}MB) — скачай в настройках STT"
                        )
                    }
                    Log.d(TAG, "гружу base-модель с файла (${f.length() / 1024 / 1024}MB)")
                    WhisperContext.createContextFromFile(f.absolutePath)
                }
                WhisperTranscribeService.MODEL_TURBO -> {
                    val f = ModelDownloader.turboFile(application)
                    if (!f.exists() || f.length() < 500L * 1024 * 1024) {
                        throw IllegalStateException("turbo-модель не скачана (${f.length() / 1024 / 1024}MB) — скачай в настройках")
                    }
                    Log.d(TAG, "гружу turbo-модель с файла (${f.length() / 1024 / 1024}MB), может занять время")
                    WhisperContext.createContextFromFile(f.absolutePath)
                }
                else -> throw IllegalArgumentException("неизвестная модель: $model")
            }
        }

    /**
     * Возвращает (и кэширует) контекст ncnn-движка. Каталог выбирается по sttModel:
     * base -> models/ncnn-base/ (whisper_base_*.ncnn.{param,bin}),
     * turbo -> models/ncnn-turbo/ (whisper_turbo_*.ncnn.{param,bin}).
     * Если выбранный turbo-каталог не найден — откат на base (быстрее, чем ошибка
     * для пользователя). Доставка моделей — adb push или ModelDownloader.
     */
    private fun obtainNcnnContext(model: String): NcnnWhisperContext = ncnnCtxCache.getOrPut(
        if (model == MODEL_TURBO) "turbo" else "base"
    ) {
        val turbo = model == MODEL_TURBO
        val dirName = if (turbo) "ncnn-turbo" else "ncnn-base"
        val baseName = if (turbo) "whisper_turbo" else "whisper_base"
        var dir = File(ModelDownloader.modelsDir(application), dirName)
        if (!File(dir, "${baseName}_fbank.ncnn.param").exists() || !File(dir, "whisper_vocab.txt").exists()) {
            if (turbo) {
                // turbo-каталог не доставлен — откат на base, чтобы не ломать STT
                Log.w(TAG, "ncnn-turbo не найден в $dir — откат на ncnn-base")
                dir = File(ModelDownloader.modelsDir(application), "ncnn-base")
                if (!File(dir, "whisper_base_fbank.ncnn.param").exists() || !File(dir, "whisper_vocab.txt").exists()) {
                    throw IllegalStateException("ncnn-модель не найдена (ncnn-turbo/ и ncnn-base/) — закинь param+bin+vocab в filesDir/models или включи движок whisper")
                }
                NcnnWhisperContext.createFromFilesDir(dir, "whisper_base")
            } else {
                throw IllegalStateException("ncnn-модель не найдена в $dir — закинь ncnn-base/ (param+bin+vocab) в filesDir/models или включи движок whisper")
            }
        } else {
            Log.d(TAG, "гружу ncnn-модель из $dir (CPU, $baseName)")
            NcnnWhisperContext.createFromFilesDir(dir, baseName)
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "Распознавание речи", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Локальное распознавание голоса на устройстве"
        }
        nm.createNotificationChannel(ch)
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Распознаю голос")
            .setContentText("Локальный Whisper считает на устройстве")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(1, notif)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
