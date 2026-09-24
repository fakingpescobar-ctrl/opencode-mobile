package org.opencode.mobile.stt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.whispercpp.whisper.NcnnWhisperContext
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground-сервис для локального распознавания речи (whisper.cpp).
 *
 * Зачем: ColorOS душит CPU фоновых compute-потоков до 1-5% — whisper в обычном
 * coroutine-потоке считается часами. Foreground-сервис получает от ОС нормальный
 * приоритет CPU (тот же механизм, что у навигаторов и плееров).
 *
 * STT-очередь (вместо разовой схемы): transcribe() кладёт задачу в FIFO-очередь
 * companion'а и ждёт СВОЙ результат (CompletableDeferred). Один worker-поток
 * считает задачи строго по одной — whisper-контексты не конкурируют.
 *
 * Состояние очереди (очередь, слот, worker-scope) — в companion и живёт до конца
 * ПРОЦЕССА, не инстанса: если сервис убит системой, worker досчитывает текущую
 * JNI-задачу и продолжает дренировать очередь сам (перезапуск/триггер не нужен).
 * Сервис (инстанс) — только foreground-обёртка: живёт, пока на это есть задачи;
 * гаснет через IDLE_STOP_DELAY_MS после опустошения очереди.
 *
 * Переполнение очереди (MAX_QUEUE_SIZE) — reject-new: новый вызов мгновенно
 * получает ошибку, уже записанная речь не теряется.
 *
 * Таймаут ограничивает ТОЛЬКО ожидание: если задача ещё в очереди — она
 * снимается; если уже в обработке — досчитывается, результат выбрасывается
 * (whisper нельзя чисто прервать на середине).
 */
class WhisperTranscribeService : Service() {

    companion object {
        private const val CHANNEL_ID = "whisper_stt"
        private const val TAG = "VOICE"

        /** Идентификаторы моделей: base живёт в assets, turbo — скачивается в filesDir. */
        const val MODEL_BASE = "base"
        const val MODEL_TURBO = "turbo"

        /** Движки распознавания. */
        const val ENGINE_WHISPER = "whisper"
        const val ENGINE_NCNN = "ncnn"

        /** Максимум задач в очереди. Клип 30-60с PCM ≈ 2.6-5.3MB — ограничиваем память. */
        private const val MAX_QUEUE_SIZE = 4

        /**
         * Пауза перед остановкой после последней задачи. Прямой stopSelf() из
         * worker гонялся бы с enqueue (убил бы сервис, пока свежая задача уже
         * в очереди, а её onStartCommand ещё не пришёл). Отложенный стоп даёт
         * окно: новая задача -> onStartCommand -> removeCallbacks(stopRunnable)
         * -> сервис остаётся живым.
         */
        private const val IDLE_STOP_DELAY_MS = 3_000L

        /** Задача на распознавание: сэмплы, модель/движок и «обещание» результата. */
        private data class SttTask(
            val id: Long,
            val samples: FloatArray,
            val model: String,
            val engine: String,
            val deferred: CompletableDeferred<String>
        )

        // FIFO-очередь задач. Доступ — только через synchronized-обёртки ниже,
        // т.к. ArrayDeque не потокобезопасен (enqueue из любого потока, poll из worker).
        private val queue = ArrayDeque<SttTask>()
        private val taskIdGen = AtomicLong(0)

        /** Кэши контекстов: модель грузится один раз за процесс. Читаются/пишутся
         *  ТОЛЬКО из worker-корутины — обращений из параллельных потоков нет. */
        private val whisperCtxCache = HashMap<String, WhisperContext>()
        private val ncnnCtxCache = HashMap<String, NcnnWhisperContext>()

        /**
         * Tombstone удалённых моделей (thread-safe): модели, чей файл удалён с диска
         * через UI. Предотвращает гонку «worker грузит контекст из удалённого файла»
         * и «контекст из ниоткуда» после удаления. Проверяется в [obtainContext]
         * ДО getOrPut; снимается при успешной перекачке (clearDeletedModel).
         * Контекст уже в кэше при этом НЕ трогается и не освобождается извне:
         * release() из параллельной корутины = use-after-free, пока worker использует
         * его в активной транскрипции; он безопасно живёт в памяти (ggml держит
         * данные в RAM), а кэш всё равно умирает вместе с процессом/бездействием
         * (stopSelf). Модель удаляли ради МЕСТА на диске, не ради RAM.
         */
        private val deletedModelFiles = ConcurrentHashMap.newKeySet<String>()

        /** Приложение-контекст для работы со статиками (кэши/очередь переживают
         *  смерть инстанса сервиса). Устанавливается каждым вызовом transcribe(). */
        @Volatile private var appContext: Context? = null

        /**
         * Пометить модель удалённой (вызывается из UI после фактического удаления
         * файла). Никаких манипуляций с кэшами извне: tombstone блокирует ДОСТУП,
         * существующий контекст в памяти безопасно доживает (см. поле), новый не
         * создастся — файла нет, [obtainContext] бросит понятную ошибку.
         */
        fun dropModelContext(model: String) {
            deletedModelFiles.add(model)
        }

        /** Снять tombstone после успешной перекачки модели (нельзя: worker бы навсегда
         *  видел «модель удалена» при живом файле). Вызывается из UI/сервиса. */
        fun clearDeletedModel(model: String) {
            deletedModelFiles.remove(model)
        }

        /** Worker-scope живёт до конца процесса: НИКОГДА не отменяется в onDestroy. */
        private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Актуальный инстанс сервиса (для stopSelf из статик-триггера). */
        @Volatile private var instance: WhisperTranscribeService? = null

        /** Слот worker'а: атомарно занимается под локом очереди. */
        @Volatile private var workerActive = false

        private val mainHandler = Handler(Looper.getMainLooper())
        private val stopRunnable = Runnable {
            // Проверка под локом очереди: задача подъехала или worker активен —
            // не гасим. stopSelf() только при реальном бездействии.
            val shouldStop = synchronized(queue) { queue.isEmpty() && !workerActive }
            if (shouldStop) instance?.stopSelf()
        }

        /**
         * Блокирующий (suspend) запуск распознавания через foreground-сервис.
         * Кладёт задачу в очередь; сервис считает её в порядке FIFO.
         * @param model MODEL_BASE (assets) или MODEL_TURBO (файл в filesDir) — для whisper.cpp.
         * @param engine ENGINE_WHISPER (whisper.cpp CPU) или ENGINE_NCNN (ncnn CPU).
         * Возвращает текст либо строку "ОШИБКА WHISPER: ..." (в т.ч. по таймауту/переполнению).
         */
        suspend fun transcribe(
            context: Context,
            samples: FloatArray,
            model: String = MODEL_BASE,
            engine: String = ENGINE_WHISPER,
            timeoutMs: Long = 90_000L
        ): String {
            if (samples.isEmpty()) {
                return "ОШИБКА WHISPER: пустые сэмплы"
            }
            appContext = context.applicationContext
            val deferred = CompletableDeferred<String>(parent = coroutineContext[Job])
            val task = SttTask(
                id = taskIdGen.incrementAndGet(),
                samples = samples,
                model = model,
                engine = engine,
                deferred = deferred
            )
            // Мёртвый груз: если вызывающая корутина отменена (Activity killed и т.п.),
            // deferred отменяется (parent=Job), и задача снимается из очереди — не
            // блокирует новые enqueue и не считается зря, пока в очереди.
            // Идемпотентно: при нормальном complete задача уже вне очереди — no-op.
            deferred.invokeOnCompletion { removeTask(task.id) }
            if (!enqueue(task)) {
                return "ОШИБКА WHISPER: очередь переполнена ($MAX_QUEUE_SIZE задач) — попробуй ещё раз"
            }
            // Задача в очереди до старта сервиса: даже если сервис в процессе
            // остановки, onStartCommand пересоздаст/подхватит и заберёт её.
            val ctx = context.applicationContext
            try {
                ctx.startForegroundService(Intent(ctx, WhisperTranscribeService::class.java))
            } catch (e: Exception) {
                // Android 12+: запуск сервиса из фона запрещён
                // (ForegroundServiceStartNotAllowedException). Задача уже в очереди,
                // но worker стартует только из onStartCommand — без сервиса она
                // провисела бы до таймаута. Снимаем её и сообщаем честную причину.
                Log.w(TAG, "startForegroundService запрещён — откат задачи #${task.id}", e)
                removeTask(task.id)
                return "ОШИБКА WHISPER: запуск сервиса запрещён (Android 12+) — повтори из активного экрана"
            }
            val result = withTimeoutOrNull(timeoutMs) { task.deferred.await() }
            if (result != null) return result
            // Не дождались: снимаем задачу из очереди, если она ещё не в обработке
            // (если уже считается — досчитается и результат будет отброшен).
            removeTask(task.id)
            return "ОШИБКА WHISPER: таймаут ${timeoutMs.coerceAtLeast(1000) / 1000}с — телефон не даёт CPU"
        }

        /**
         * Запускает единственный worker распознавания: крутится, пока в очереди
         * есть задачи, считает их строго по одной (сериализация = нет гонки на
         * whisper-контекстах), затем планирует остановку сервиса. Не требует
         * живого инстанса: слот/очередь/scope — статики процесса.
         */
        private fun ensureWorker() {
            var slotTaken = false
            synchronized(queue) {
                if (!workerActive) {
                    workerActive = true
                    slotTaken = true
                }
            }
            if (!slotTaken) return
            workerScope.launch {
                while (true) {
                    val task = pollQueue() ?: break
                    Log.d(TAG, "worker: задача #${task.id}, ${task.samples.size} сэмплов, модель=${task.model}, движок=${task.engine}")
                    val text = transcribeTask(task)
                    Log.d(TAG, "worker: результат #${task.id}: '${text.take(80)}'")
                    // withTimeoutOrNull отменяет только await вызывающего, сам deferred
                    // не отменяется — complete() успешен даже если никто не ждёт.
                    task.deferred.complete(text)
                }
                synchronized(queue) { workerActive = false }
                if (!isQueueEmpty()) {
                    // Между выходом из цикла и сбросом флага успела прийти задача:
                    // перезапускаем worker (слот уже свободен).
                    ensureWorker()
                    return@launch
                }
                Log.d(TAG, "worker: очередь пуста — стоп через ${IDLE_STOP_DELAY_MS}мс")
                mainHandler.postDelayed(stopRunnable, IDLE_STOP_DELAY_MS)
            }
        }

        /** Распознавание одной задачи. Никогда не бросает: ошибка -> текст-строка. */
        private suspend fun transcribeTask(task: SttTask): String {
            return try {
                if (task.engine == ENGINE_NCNN) {
                    val ctx = obtainNcnnContext(task.model)
                    ctx.transcribeData(task.samples, lang = "ru").trim()
                } else {
                    val ctx = obtainContext(task.model)
                    ctx.transcribeData(task.samples, printTimestamp = false).trim()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "распознавание упало (в сервисе)", e)
                "ОШИБКА WHISPER: ${e.message}"
            }
        }

        private fun enqueue(task: SttTask): Boolean = synchronized(queue) {
            if (queue.size >= MAX_QUEUE_SIZE) {
                // reject: очередь полна -> worker гарантированно активен или стартует
                // (каждое добавление идёт с startForegroundService -> onStartCommand ->
                // ensureWorker), гашение распланирует хвост worker'а. Наш стоп-чек
                // не трогаем и не постим повторно — дублей нет.
                false
            } else {
                queue.addLast(task)
                // Снятие отложенного гашения — под тем же локом, что и добавление:
                // хвост worker'а синхронизирован этим же локом, поэтому он не успеет
                // распланировать stop ПОСЛЕ нашего снятия, пока задача уже в очереди
                // (иначе отложенный стоп слетел бы и сервис завис без гашения).
                mainHandler.removeCallbacks(stopRunnable)
                true
            }
        }

        private fun pollQueue(): SttTask? = synchronized(queue) {
            queue.removeFirstOrNull()
        }

        private fun removeTask(id: Long) {
            synchronized(queue) {
                val it = queue.iterator()
                while (it.hasNext()) {
                    if (it.next().id == id) {
                        it.remove()
                        return
                    }
                }
            }
        }

        private fun isQueueEmpty(): Boolean = synchronized(queue) {
            queue.isEmpty()
        }

        /**
         * Возвращает (и кэширует) контекст whisper под выбранную модель.
         * base — из assets, turbo — с файла в filesDir (предполагается, что
         * уже скачана через ModelDownloader; если нет — понятная ошибка).
         */
        private fun obtainContext(model: String): WhisperContext {
            // Tombstone: файл модели удалён через UI — не загружаем «контекст из
            // ниоткуда», а сообщаем понятную причину (перекачай в настройках STT).
            // Второй check ВНУТРИ лямбды ловит гонку UI-drop между первым check и
            // входом в getOrPut-лямбду: dropModelContext (UI-поток) выполним в любой
            // момент на многоядре, а лямбда — единственное место, где файл реально
            // открывается. После входа в лямбду (второй check пройден) остаётся
            // TOCTOU во время requireNotCorrupt/createContextFromFile (секунды
            // чтения 141-574MB) — его ловит catch ниже (переводим в понятный текст).
            if (deletedModelFiles.contains(model)) {
                throw IllegalStateException("модель \"$model\" удалена — перекачай её в настройках STT")
            }
            return whisperCtxCache.getOrPut(model) {
                if (deletedModelFiles.contains(model)) {
                    throw IllegalStateException("модель \"$model\" удалена — перекачай её в настройках STT")
                }
                try {
                    loadContextLocked(model)
                } catch (e: Exception) {
                    // Файл удалён прямо во время загрузки (unlink на открытом fd —
                    // POSIX-призрак — это ок; хуже: unlink ДО open -> FileNotFound).
                    // Причины: tombstone (UI уже пометил) ИЛИ файл исчез в окне
                    // delete..drop (FileNotFound/IOException до пометки). Обе —
                    // «удалена/повреждена — перекачай». Свой IllegalStateException
                    // («не скачана», «повреждена») не трогаем — текст уже понятный.
                    if (e is IllegalStateException && !deletedModelFiles.contains(model)) {
                        throw e // настоящая семантическая ошибка (не скачана/повреждена)
                    }
                    throw IllegalStateException("модель \"$model\" удалена или повреждена — перекачай её в настройках STT", e)
                }
            }
        }

        /** ВАЖНО (инвариант): вызывается ТОЛЬКО из единственного worker-потока
         *  (в [runPendingTasks]/транскрипции). Кэши контекстов не потокобезопасны;
         *  второй читатель/писатель = гонка. НЕ вызывать из onStartCommand, UI и т.д. */
        private fun loadContextLocked(model: String): WhisperContext {
            val app = requireAppContext()
            return when (model) {
                WhisperTranscribeService.MODEL_BASE -> {
                    val f = ModelDownloader.baseFile(app)
                    if (!ModelDownloader.baseReady(app)) {
                        throw IllegalStateException(
                            "base-модель не скачана (${if (f.exists()) (f.length() / 1024 / 1024) else 0}MB) — скачай в настройках STT"
                        )
                    }
                    requireNotCorrupt(f, "base-модель")
                    Log.d(TAG, "гружу base-модель с файла (${f.length() / 1024 / 1024}MB)")
                    WhisperContext.createContextFromFile(f.absolutePath)
                }
                WhisperTranscribeService.MODEL_TURBO -> {
                    val f = ModelDownloader.turboFile(app)
                    if (!ModelDownloader.turboReady(app)) {
                        throw IllegalStateException("turbo-модель не скачана (${if (f.exists()) (f.length() / 1024 / 1024) else 0}MB) — скачай в настройках")
                    }
                    requireNotCorrupt(f, "turbo-модель")
                    Log.d(TAG, "гружу turbo-модель с файла (${f.length() / 1024 / 1024}MB), может занять время")
                    WhisperContext.createContextFromFile(f.absolutePath)
                }
                else -> throw IllegalArgumentException("неизвестная модель: $model")
            }
        }

        private fun requireAppContext(): Context =
            appContext ?: throw IllegalStateException("STT: context не инициализирован (transcribe() не вызывался до worker)")

        /**
         * Fail-loud перед загрузкой в whisper: если файл на диске повреждён/подменён
         * (SHA-256 не совпал с манифестом) — не грузим (whisper упал бы с мусорными
         * ошибками), а сообщаем понятную причину. NO_MANIFEST — модель скачана до
         * введения манифеста: пропускаем (прежнее доверие ETag+размер).
         */
        private fun requireNotCorrupt(file: File, label: String) {
            if (ModelDownloader.checkIntegrity(file) == ModelDownloader.ModelIntegrity.CORRUPT) {
                throw IllegalStateException(
                    "$label повреждена (SHA-256 не совпал — файл испорчен или подменён). Удали модель и перекачай заново в настройках STT"
                )
            }
        }

        /**
         * Возвращает (и кэширует) контекст ncnn-движка. Модель — ВСЕГДА turbo
         * (models/ncnn-turbo/, whisper_turbo_*.ncnn.{param,bin}): base-конверт
         * битый (декодер петляет, мусор) и из программы удалён, выбирать нечего.
         * Доставка моделей — adb push или ModelDownloader.
         */
        private fun obtainNcnnContext(model: String): NcnnWhisperContext = ncnnCtxCache.getOrPut("turbo") {
            val app = requireAppContext()
            val dir = File(ModelDownloader.modelsDir(app), "ncnn-turbo")
            val baseName = "whisper_turbo"
            val check = NcnnModelValidator.checkModelDir(dir, baseName)
            check(check.ok) {
                "ncnn-модель неполная в $dir — отсутствуют: ${check.missing.joinToString(", ")}"
            }
            Log.d(TAG, "гружу ncnn-модель из $dir (CPU, $baseName)")
            NcnnWhisperContext.createFromFilesDir(dir, baseName)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        // Снимаем отложенную остановку: пришла новая задача.
        mainHandler.removeCallbacks(stopRunnable)
        ensureWorker()
        return START_NOT_STICKY
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
        // Статик-состояние (очередь/worker/кэши) НЕ отменяем: worker дочитывает
        // текущую задачу и продолжает дренировать очередь в фоне процесса.
        // Смерть инстанса лишает его foreground-приоритета (только если сервис
        // убила система mid-JNI) — задача досчитается медленно либо вызывающий
        // словит таймаут (fail-safe).
        if (instance === this) instance = null
        mainHandler.removeCallbacks(stopRunnable)
        super.onDestroy()
    }
}
