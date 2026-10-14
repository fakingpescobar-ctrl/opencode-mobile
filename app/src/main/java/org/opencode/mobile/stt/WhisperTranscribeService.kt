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
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.opencode.mobile.engine.Engine
import org.opencode.mobile.engine.EngineLibs
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

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

        /**
         * Идентификатор модели ggml для движка whisper.cpp. Модель в
         * приложении одна, список, описание и скачивание живут в [SttModels].
         *
         * Остальные варианты (base, medium, turbo f16, turbo q5_0, tiny и
         * ncnn-turbo) вычеркнуты 14.10.2026 после замеров на 40 клипах:
         * полные таблицы и причины — в `docs/VOICE-REPORT.md`.
         */
        const val MODEL_SMALL = "small"

        /** Движок распознавания. Системный Android живёт отдельно, в UI. */
        const val ENGINE_WHISPER = "whisper"

        /**
         * Движок sherpa-onnx OfflineRecognizer (nemo_transducer / Parakeet).
         * Экспериментальная ветка для замера на стенде: проверяем, обгоняет ли
         * Parakeet TDT 0.6B v3 (25 европейских языков, включая русский) whisper small
         * по WER и времени. Модель кладётся вручную в getExternalFilesDir/parakeet.
         */
        const val ENGINE_PARAKEET = "parakeet"

        const val PARAKEET_DIR = "parakeet"

        /** Имя каталога для ModelSettings/use-кода (не private в companion лишает доступ вне). */
        const val PARAKEET_DIR_NAME = "parakeet"

        /** Потоки для sherpa-onnx: 4 как у CPU-ветки whisper — на 8 ядрах не голодает. */
        private const val PARAKEET_THREADS = 4

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
            val deferred: CompletableDeferred<String>,
            /** Путь исходного WAV — не null только для задач отладочного стенда.
             *  Нужен, чтобы в лог писать полный результат с привязкой к файлу. */
            val origin: String? = null,
        )

        // FIFO-очередь задач. Доступ — только через synchronized-обёртки ниже,
        // т.к. ArrayDeque не потокобезопасен (enqueue из любого потока, poll из worker).
        private val queue = ArrayDeque<SttTask>()
        private val taskIdGen = AtomicLong(0)

        /** Кэш контекстов: модель грузится один раз за процесс. */
        private val whisperCtxCache = HashMap<String, WhisperContext>()

        /** Кэш parakeet-распознавателя (грузится один раз за процесс, ~650МБ модель). */
        private val parakeetCache = HashMap<String, OfflineRecognizer>()

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
         * Язык, заданный пользователем явно (например "en" или "ru").
         * null — авто. Механизм и дефолт уже на месте, нужен только UI-тумблер.
         */
        @Volatile var languageOverride: String? = null

        /** Язык по умолчанию, если определить не удалось или движок вернул пусто. */
        const val DEFAULT_LANG = "ru"

        /**
         * Ниже этой длины (в секундах) язык не определяем. На коротких клипах
         * whisper выбирает язык по шуму, и решение оказывается случайным —
         * известный DEFAULT_LANG полезнее, чем угадывание.
         *
         * Порог публичный: [ChunkedTranscriber] по нему склеивает ведущие
         * короткие сегменты записи, чтобы решение о языке принималось один раз
         * на достаточном куске. Без этого короткий первый сегмент уходил в
         * декодер с токеном "ru" и давал русскую расшифровку иностранной речи.
         */
        const val MIN_LANG_DETECT_SECONDS = 3.0
        private const val SAMPLE_RATE_HZ = 16_000

        /** Миллисекунд в секунде - переводит «сэмплы» в «мс» для отчёта о скорости. */
        private const val MS_PER_SECOND = 1000L

        /** Сброс состояния языка в начале новой сессии диктовки. */
        fun resetLanguageLatch() {
            languageOverride = null
        }

        /**
         * Язык для этой сессии уже решён явно? Нужен вызывающему, чтобы не
         * склеивать сегменты под детекцию заново.
         *
         * Раньше здесь была ещё и автоопределённая защёлка: ncnn-движок гонял
         * encoder на каждый сегмент ради определения языка, поэтому язык
         * защёлкивался на первом длинном куске. ncnn выпилен 14.10.2026, whisper.cpp
         * определяет язык один раз сам, и защёлкивать больше нечего.
         */
        fun languageDecided(): Boolean = languageOverride != null

        /** Текущий язык: явный override, иначе дефолт. */
        fun currentLang(): String = languageOverride ?: DEFAULT_LANG

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
         * @param model id из каталога [SttModels] — движок выводится из него
         *   ([chooseEngine]): `parakeet-tdt-0.6b-v3` → sherpa-onnx, остальные → whisper.cpp.
         * @param engine [ENGINE_WHISPER]. Явный движок для отладочного стенда: замер
         *   исторически задаёт его отдельно (`-e engine parakeet`), поэтому значение
         *   [ENGINE_PARAKEET] перебивает вывод из [model]. Боевой путь его не задаёт.
         * Возвращает текст либо строку "ОШИБКА <ДВИЖОК>: ..." (в т.ч. по таймауту/переполнению).
         */
        suspend fun transcribe(
            context: Context,
            samples: FloatArray,
            model: String = MODEL_SMALL,
            engine: String = ENGINE_WHISPER,
            timeoutMs: Long = 90_000L,
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
            val result = submit(context, task) ?: withTimeoutOrNull(timeoutMs) { task.deferred.await() }

            // ИНВАРИАНТ: deferred — не-completed ребёнок Job вызывающей корутины
            // (parent= выше), а незавершённый ребёнок блокирует завершение родителя.
            // Отменяем его на каждом пути, где мы ушли без результата воркера (отказ
            // при постановке, запрет запуска сервиса, таймаут) — иначе вызывающий
            // scope (Activity/ViewModel/runBlocking) не завершится никогда.
            // Проверка «воркер не положил результат» покрывает все пути структурно:
            // новый выход раньше этой строки зависнуть уже не может.
            if (!task.deferred.isCompleted) deferred.cancel()
            // Не дождались: снимаем задачу из очереди, если она ещё не в обработке
            // (если уже считается — досчитается и результат будет отброшен).
            if (result == null) removeTask(task.id)

            return result ?: "ОШИБКА WHISPER: таймаут ${timeoutMs.coerceAtLeast(1000) / 1000}с — телефон не даёт CPU"
        }

        /**
         * Ставит задачу в очередь и поднимает сервис, который её посчитает.
         *
         * @return текст ошибки, если задача не принята; null — задача ушла в работу.
         */
        private fun submit(
            context: Context,
            task: SttTask,
        ): String? =
            if (!enqueue(task)) {
                "ОШИБКА WHISPER: очередь переполнена ($MAX_QUEUE_SIZE задач) — попробуй ещё раз"
            } else {
                // Задача в очереди до старта сервиса: даже если сервис в процессе
                // остановки, onStartCommand пересоздаст/подхватит и заберёт её.
                val ctx = context.applicationContext
                try {
                    ctx.startForegroundService(Intent(ctx, WhisperTranscribeService::class.java))
                    null
                } catch (e: Exception) {
                    // Android 12+: запуск сервиса из фона запрещён
                    // (ForegroundServiceStartNotAllowedException). Задача уже в очереди,
                    // но worker стартует только из onStartCommand — без сервиса она
                    // провисела бы до таймаута. Снимаем её и сообщаем честную причину.
                    Log.w(TAG, "startForegroundService запрещён — откат задачи #${task.id}", e)
                    removeTask(task.id)
                    "ОШИБКА WHISPER: запуск сервиса запрещён (Android 12+) — повтори из активного экрана"
                }
            }

        /**
         * Отладочный путь: кладёт в очередь WAV из broadcast вместо записи с
         * микрофона. Нужен, чтобы мерить скорость и точность автономно.
         *
         * Работает только в debug-сборке: приложение ещё и HTTP-сервер поднимает,
         * а экспортированный приёмник, читающий произвольный файл, — дыра.
         * Сервис уже запущен к этому моменту (приёмник вызвал startForegroundService),
         * поэтому submit() здесь не нужен — достаточно enqueue.
         */
        private fun enqueueBench(intent: Intent) {
            // Проверка и в приёмнике, и здесь: сервис можно разбудить и напрямую.
            val task = if (org.opencode.mobile.BuildConfig.DEBUG) benchTask(intent) else null
            if (task == null) return
            enqueue(task)
            Log.d(TAG, "bench: задача #${task.id} в очереди")
        }

        /**
         * Задача из WAV-файла либо null, если файл нечитаем или не в том формате.
         * Один `when` без ранних возвратов — проверок четыре, а detekt держит
         * ReturnCount = 2.
         */
        private fun benchTask(intent: Intent): SttTask? {
            val path = intent.getStringExtra(SttBenchReceiver.EXTRA_FILE).orEmpty()
            val file = File(path)
            if (path.isBlank() || !file.canRead()) {
                Log.w(TAG, "bench: путь '$path' пуст или нечитаем")
                return null
            }
            val bytes = file.readBytes()
            val samples = SttWav.samples(bytes)
            val header = SttWav.headerOf(bytes)
            return if (samples == null) {
                Log.w(TAG, "bench: $path не 16 kHz mono pcm_s16le (${SttWav.describe(header)})")
                null
            } else {
                Log.d(TAG, "bench: $path, ${samples.size} сэмплов (${SttWav.describe(header)})")
                SttTask(
                    id = taskIdGen.incrementAndGet(),
                    samples = samples,
                    model = intent.getStringExtra(SttBenchReceiver.EXTRA_MODEL) ?: MODEL_SMALL,
                    engine = intent.getStringExtra(SttBenchReceiver.EXTRA_ENGINE) ?: ENGINE_WHISPER,
                    deferred = CompletableDeferred(),
                    origin = path,
                )
            }
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
                    // Бенч сравнивает текст с эталоном, поэтому ему нужен результат
                    // целиком, а не первые 80 символов. Пишем отдельной строкой
                    // только для задач из файла — микрофон не должен этим шуметь.
                    task.origin?.let { Log.i(TAG, "BENCH $it => '$text'") }
                    // complete() вернёт false, если вызывающая сторона уже ушла
                    // (таймаут/откат) и отменила deferred — это ожидаемо: результат
                    // больше никому не нужен, и он молча отбрасывается. Раньше здесь
                    // deferred оставался не-завершённым и блокировал Job вызывающего.
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

        /** Распознавание одной задачи. Никогда не бросает: ошибка -> текст-строка.
         *
         * Замеры времени здесь не украшение: без них «распознавание медленное»
         * нечем подтвердить. Печатаем четыре числа, которые и различают все
         * гипотезы - сколько ждала загрузка модели, сколько длился сам
         * проход и во сколько раз он длиннее звука. */
        private suspend fun transcribeTask(task: SttTask): String {
            val engine = chooseEngine(task.model, task.engine)
            return try {
                if (engine == ENGINE_PARAKEET) transcribeParakeet(task) else transcribeWhisper(task)
            } catch (e: Throwable) {
                Log.e(TAG, "распознавание упало (в сервисе)", e)
                "ОШИБКА ${engine.uppercase()}: ${e.message}"
            }
        }

        /**
         * Движок по модели и/или явному запросу. Приоритет у parakeet, если его
         * назвал ЛЮБОЙ из двух источников: боевой путь выводит движок из id модели,
         * а стенд может задать `-e engine parakeet` при произвольной модели.
         */
        private fun chooseEngine(
            modelId: String,
            requested: String,
        ): String =
            if (requested == ENGINE_PARAKEET || modelId == SttModels.PARAKEET_ID) {
                ENGINE_PARAKEET
            } else {
                ENGINE_WHISPER
            }

        /** Ветка whisper.cpp: грузит движок/модель и делает проход, печатая замеры. */
        private suspend fun transcribeWhisper(task: SttTask): String {
            val app = requireAppContext()
            if (!EngineLibs.load(app, Engine.WHISPER)) {
                error(EngineLibs.reasonOf(app, Engine.WHISPER) ?: "движок whisper.cpp не загрузился")
            }
            // Замеры не украшение: без них «распознавание медленное» нечем
            // подтвердить. Загрузка движка и модели видна отдельно от
            // самого прохода.
            val loadStart = SystemClock.elapsedRealtime()
            val ctx = obtainContext(task.model)
            val loaded = SystemClock.elapsedRealtime()
            val text = ctx.transcribeData(task.samples, printTimestamp = false).trim()
            val done = SystemClock.elapsedRealtime()
            val wAudioMs = task.samples.size * MS_PER_SECOND / SAMPLE_RATE_HZ
            val wRtf = if (wAudioMs > 0) (done - loaded).toFloat() / wAudioMs else 0f
            Log.i(
                TAG,
                "whisper: загрузка ${loaded - loadStart} мс, " +
                    "проход ${done - loaded} мс, звук $wAudioMs мс, rtf ${"%.2f".format(wRtf)}"
            )
            return text
        }

        /**
         * Ветка sherpa-onnx OfflineRecognizer (Parakeet TDT). Движок — тот же
         * libsherpa-onnx-jni, что и TTS (bundled), поэтому грузим его через
         * EngineLibs.load(Engine.TTS). Модель offline: подаём весь клип целиком
         * (acceptWaveform) и декодируем одним проходом.
         */
        private fun transcribeParakeet(task: SttTask): String {
            val app = requireAppContext()
            if (!EngineLibs.load(app, Engine.TTS)) {
                error(EngineLibs.reasonOf(app, Engine.TTS) ?: "libsherpa-onnx-jni не загрузился")
            }
            val loadStart = SystemClock.elapsedRealtime()
            val recognizer = parakeetCache.getOrPut(PARAKEET_DIR) { buildParakeetRecognizer(app) }
            val loaded = SystemClock.elapsedRealtime()
            val stream = recognizer.createStream()
            val text =
                try {
                    stream.acceptWaveform(task.samples, SAMPLE_RATE_HZ)
                    recognizer.decode(stream)
                    recognizer.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
            val done = SystemClock.elapsedRealtime()
            val pAudioMs = task.samples.size * MS_PER_SECOND / SAMPLE_RATE_HZ
            val pRtf = if (pAudioMs > 0) (done - loaded).toFloat() / pAudioMs else 0f
            Log.i(
                TAG,
                "parakeet: загрузка ${loaded - loadStart} мс, " +
                    "проход ${done - loaded} мс, звук $pAudioMs мс, rtf ${"%.2f".format(pRtf)}"
            )
            return text
        }

        /**
         * Собирает OfflineRecognizer под модель parakeet-tdt-0.6b-v3-int8.
         * Файлы берём из getExternalFilesDir/PARAKEET_DIR (для стенда кладутся
         * adb push — у Android нет bzip2, а релиз модели идёт .tar.bz2).
         * modelType="nemo_transducer" — то, что ждёт JNI для Parakeet TDT.
         */
        private fun buildParakeetRecognizer(app: Context): OfflineRecognizer {
            val dir = File(app.getExternalFilesDir(null), PARAKEET_DIR)
            val tokens = File(dir, "tokens.txt")
            val encoder = File(dir, "encoder.int8.onnx")
            val decoder = File(dir, "decoder.int8.onnx")
            val joiner = File(dir, "joiner.int8.onnx")
            val missing = listOf(tokens, encoder, decoder, joiner).filterNot { it.exists() }
            if (missing.isNotEmpty()) {
                error(
                    "parakeet: нет файлов ${missing.joinToString { it.name }} в ${dir.absolutePath} " +
                        "— положи модель (adb push) перед замером"
                )
            }
            val config =
                OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = 80),
                    modelConfig =
                        OfflineModelConfig(
                            transducer =
                                OfflineTransducerModelConfig(
                                    encoder = encoder.absolutePath,
                                    decoder = decoder.absolutePath,
                                    joiner = joiner.absolutePath,
                                ),
                            tokens = tokens.absolutePath,
                            modelType = "nemo_transducer",
                            numThreads = PARAKEET_THREADS,
                            debug = org.opencode.mobile.BuildConfig.DEBUG,
                            provider = "cpu",
                        ),
                    decodingMethod = "greedy_search",
                )
            Log.d(TAG, "parakeet: гружу модель из ${dir.absolutePath}")
            return OfflineRecognizer(config = config)
        }

        private fun enqueue(task: SttTask): Boolean =
            synchronized(queue) {
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

        private fun pollQueue(): SttTask? =
            synchronized(queue) {
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

        private fun isQueueEmpty(): Boolean =
            synchronized(queue) {
                queue.isEmpty()
            }

        /**
         * Возвращает (и кэширует) контекст whisper под выбранную модель.
         * Модель лежит файлом в filesDir — её качает ModelDownloader по данным
         * каталога [SttModels]; если файла нет, loadContextLocked даёт понятную
         * ошибку со ссылкой на настройки.
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
                error("модель \"$model\" удалена — перекачай её в настройках STT")
            }
            return whisperCtxCache.getOrPut(model) {
                if (deletedModelFiles.contains(model)) {
                    error("модель \"$model\" удалена — перекачай её в настройках STT")
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
                    // error() в stdlib принимает только message, без cause, поэтому
                    // вариант с причиной остаётся на throw — detekt его не рубит.
                    throw IllegalStateException("модель \"$model\" удалена или повреждена — перекачай её в настройках STT", e)
                }
            }
        }

        /** ВАЖНО (инвариант): вызывается ТОЛЬКО из единственного worker-потока
         *  (в [runPendingTasks]/транскрипции). Кэши контекстов не потокобезопасны;
         *  второй читатель/писатель = гонка. НЕ вызывать из onStartCommand, UI и т.д. */
        private fun loadContextLocked(model: String): WhisperContext {
            val app = requireAppContext()
            // Список моделей живёт в каталоге [SttModels], а не здесь: раньше здесь
            // стоял when на шесть копий одного и того же (имя файла + подпись),
            // и добавление модели означало править и его, и UI, и диагностику.
            val spec = SttModels.byId(model) ?: throw IllegalArgumentException("неизвестная модель: $model")
            return loadGgmlFile(app, spec.fileOf(app), spec.isReady, spec.id)
        }

        /** Общая загрузка ggml-модели: проверить наличие, целостность, открыть контекст.
         *  Всё, что различается между моделями, приходит аргументами из каталога. */
        private fun loadGgmlFile(
            app: Context,
            file: java.io.File,
            ready: (Context) -> Boolean,
            label: String,
        ): WhisperContext {
            if (!ready(app)) {
                error(
                    "$label не скачана (${if (file.exists()) (file.length() / 1024 / 1024) else 0}MB) — " +
                        "скачай в Настройки -> Диагностика -> Голосовое распознавание"
                )
            }
            requireNotCorrupt(file, label)
            Log.d(TAG, "гружу $label-модель с файла (${file.length() / 1024 / 1024}MB)")
            return WhisperContext.createContextFromFile(file.absolutePath)
        }

        private fun requireAppContext(): Context =
            appContext ?: error("STT: context не инициализирован (transcribe() не вызывался до worker)")

        /**
         * Fail-loud перед загрузкой в whisper: если файл на диске повреждён/подменён
         * (SHA-256 не совпал с манифестом) — не грузим (whisper упал бы с мусорными
         * ошибками), а сообщаем понятную причину. NO_MANIFEST — модель скачана до
         * введения манифеста: пропускаем (прежнее доверие ETag+размер).
         */
        private fun requireNotCorrupt(
            file: File,
            label: String,
        ) {
            if (ModelDownloader.checkIntegrity(file) == ModelDownloader.ModelIntegrity.CORRUPT) {
                error(
                    "$label повреждена (SHA-256 не совпал — файл испорчен или подменён). Удали модель и перекачай заново в настройках STT"
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startInForeground()
        // Контекст нужен воркеру, а раньше его ставил только путь микрофона
        // (transcribe()). Сервис жив — значит контекст точно есть; ставим здесь,
        // иначе задача из broadcast падает «context не инициализирован».
        appContext = applicationContext
        // Снимаем отложенную остановку: пришла новая задача.
        mainHandler.removeCallbacks(stopRunnable)
        if (intent?.action == SttBenchReceiver.ACTION) {
            enqueueBench(intent)
        }
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
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification
            .Builder(this, CHANNEL_ID)
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
