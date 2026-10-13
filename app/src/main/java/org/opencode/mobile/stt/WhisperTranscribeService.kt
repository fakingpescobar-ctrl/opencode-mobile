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
import com.whispercpp.whisper.FAILED_LANG
import com.whispercpp.whisper.NcnnWhisperContext
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
         * Идентификаторы моделей ggml для движка whisper.cpp. Список, порядок,
         * описания и скачивание живут в [SttModels]; здесь только идентификаторы,
         * потому что их знают prefs и UI.
         *
         * Модели turbo q5_0 и tiny вычеркнуты 14.10.2026 после замеров: на Adreno
         * 840 квантованная turbo даёт мусор (rtf 47,64), на CPU — rtf 6,25, а tiny
         * ошибается в 60 % слов и проигрывает даже base. ncnn-turbo — отдельная
         * модель в ncnn-формате, к этому перечню отношения не имеет.
         */
        const val MODEL_BASE = "base"
        const val MODEL_TURBO_F16 = "turbo-f16"
        const val MODEL_MEDIUM = "medium"
        const val MODEL_SMALL = "small"

        /**
         * Модель ncnn-движка — отдельная сущность, к каталогу ggml не имеет
         * отношения: это веса `whisper_turbo` в ncnn-формате (1,2 ГБ), а не
         * turbo f16/q5_0 из HuggingFace. Именно поэтому её не было в
         * `SttModels` и не стало, когда оттуда вычеркнули квантованную turbo.
         */
        const val MODEL_NCNN_TURBO = "turbo"

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
            val deferred: CompletableDeferred<String>,
            /** Путь исходного WAV — не null только для задач отладочного стенда.
             *  Нужен, чтобы в лог писать полный результат с привязкой к файлу. */
            val origin: String? = null,
        )

        // FIFO-очередь задач. Доступ — только через synchronized-обёртки ниже,
        // т.к. ArrayDeque не потокобезопасен (enqueue из любого потока, poll из worker).
        private val queue = ArrayDeque<SttTask>()
        private val taskIdGen = AtomicLong(0)

        /** Кэши контекстов: модель грузится один раз за процесс. */
        private val whisperCtxCache = HashMap<String, WhisperContext>()

        /**
         * Кэш ncnn-контекстов. Именно он, в отличие от [whisperCtxCache],
         * пишется не только из worker-корутины: releaseNcnnContext() дёргает
         * instrumentation-поток (см. BenchSttTest), то есть доступ из двух
         * потоков реален. Поэтому ConcurrentHashMap, а не HashMap — обычный
         * HashMap при параллельном put/remove пережигает внутренний массив.
         * Контракт "не вызывать во время транскрипции" при этом остаётся, но
         * теперь хотя бы не приводит к битой структуре данных.
         */
        private val ncnnCtxCache = ConcurrentHashMap<String, NcnnWhisperContext>()

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
         * Язык текущей сессии диктовки. null — ещё не определён.
         *
         * Защёлка нужна потому, что whisper гоняет encoder на КАЖДЫЙ сегмент
         * (~6.5 с на телефоне) ради определения языка. В сессии язык не меняется,
         * поэтому определяем один раз на первом длинном сегменте и дальше
         * передаём токен напрямую. Без защёлки английский текст посреди русской
         * реплики распознавался как русский — это хуже, чем каша.
         */
        @Volatile private var latchedLang: String? = null

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

/** Миллисекунд в секунде — переводит «сэмплы» в «мс» для отчёта о скорости. */
        private const val MS_PER_SECOND = 1000L

        /** Сброс защёлки в начале новой сессии диктовки. */
        fun resetLanguageLatch() {
            latchedLang = null
        }

        /**
         * Язык для этой сессии уже решён (пользователем или защёлкой)?
         * Нужен вызывающему, чтобы не склеивать сегменты под детекцию заново.
         * Сравнивать currentLang() с DEFAULT_LANG нельзя: защёлка может
         * законно стоять в "ru", и это не то же самое, что "язык не решали".
         */
        fun languageDecided(): Boolean = languageOverride != null || latchedLang != null

        /** Текущий язык: явный override, иначе защёлка, иначе дефолт. */
        fun currentLang(): String = languageOverride ?: latchedLang ?: DEFAULT_LANG

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
         * @param model MODEL_SMALL (по умолчанию) или MODEL_MEDIUM/MODEL_TURBO_F16/MODEL_BASE
         *   из каталога [SttModels] — движок whisper.cpp, файлы в filesDir/models.
         * @param engine ENGINE_WHISPER (whisper.cpp CPU) или ENGINE_NCNN (ncnn CPU).
         * Возвращает текст либо строку "ОШИБКА WHISPER: ..." (в т.ч. по таймауту/переполнению).
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
        private suspend fun transcribeTask(task: SttTask): String =
            try {
                if (task.engine == ENGINE_NCNN) {
                    val loadStart = SystemClock.elapsedRealtime()
                    val ctx = obtainNcnnContext(task.model)
                    val loaded = SystemClock.elapsedRealtime()
                    val audioMs = task.samples.size * MS_PER_SECOND / SAMPLE_RATE_HZ
                    val text = transcribeNcnn(ctx, task.samples).trim()
                    val done = SystemClock.elapsedRealtime()
                    val rtf = if (audioMs > 0) (done - loaded).toFloat() / audioMs else 0f
                    Log.i(
                        TAG,
                        "ncnn: загрузка ${loaded - loadStart} мс, " +
                            "проход ${done - loaded} мс, звук $audioMs мс, rtf ${"%.2f".format(rtf)}"
                    )
                    text
                } else {
                    val app = requireAppContext()
                    if (!EngineLibs.load(app, Engine.WHISPER)) {
                        error(EngineLibs.reasonOf(app, Engine.WHISPER) ?: "движок whisper.cpp не загрузился")
                    }
                    // Замеры — те же, что в ncnn-ветке, иначе скорость приходится
                    // считать по меткам logcat вручную. Здесь загрузка движка и
                    // модели видна отдельно от самого прохода.
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
                    text
                }
            } catch (e: Throwable) {
                Log.e(TAG, "распознавание упало (в сервисе)", e)
                "ОШИБКА WHISPER: ${e.message}"
            }

        /**
         * Транскрипция ncnn с учётом языка сессии.
         *
         * Язык передаётся токеном в НАЧАЛО декодера, а не меняет препроцессинг:
         * encoder_states — самый дорогой артефакт (~6.5 с на телефоне) и он от
         * языка не зависит вообще. Поэтому «язык известен -> один вызов»,
         * «язык неизвестен -> один вызов с автоопределением», а не два вызова
         * (определить, потом распознать), которые платили бы за encoder дважды.
         */
        private suspend fun transcribeNcnn(
            ctx: NcnnWhisperContext,
            samples: FloatArray,
        ): String {
            val known = languageOverride ?: latchedLang
            return if (known != null) {
                ctx.transcribeData(samples, lang = known)
            } else {
                detectLangAndTranscribe(ctx, samples)
            }
        }

        /**
         * Первый сегмент сессии: определяем язык и защёлкиваем его.
         * Стоит одну авто-транскрипцию; все остальные сегменты идут с токеном.
         */
        private suspend fun detectLangAndTranscribe(
            ctx: NcnnWhisperContext,
            samples: FloatArray,
        ): String {
            val minSamples = (MIN_LANG_DETECT_SECONDS * SAMPLE_RATE_HZ).toInt()
            if (samples.size < minSamples) {
                // Короткий клип: язык не решаем, но и не защёлкиваем - если
                // клип был лишь вступлением, язык определится на следующем.
                Log.d(TAG, "язык не определяем: ${samples.size} сэмплов < $minSamples")
                return ctx.transcribeData(samples, lang = DEFAULT_LANG)
            }
            val res = ctx.transcribeAuto(samples)
            return if (res.lang.isBlank() || res.lang == FAILED_LANG) {
                Log.w(TAG, "определение языка не удалось, беру $DEFAULT_LANG")
                ctx.transcribeData(samples, lang = DEFAULT_LANG)
            } else {
                latchedLang = res.lang
                Log.i(TAG, "язык сессии: ${res.lang} (${samples.size} сэмплов)")
                res.text
            }
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

        /**
         * Возвращает (и кэширует) контекст ncnn-движка. Модель — ВСЕГДА turbo
         * (models/ncnn-turbo/, whisper_turbo_*.ncnn.{param,bin}): base-конверт
         * битый (декодер петляет, мусор) и из программы удалён, выбирать нечего.
         * Доставка моделей — adb push или ModelDownloader.
         */
        // computeIfAbsent, а не getOrPut: у ConcurrentHashMap getOrput остаётся
        // неатомарным (get, потом put), и два потока создали бы два контекста
        // на одну модель. computeIfAbsent держит бин-лок, поэтому контекст
        // создаётся ровно один раз даже при гонке.
        private fun obtainNcnnContext(model: String): NcnnWhisperContext =
            ncnnCtxCache.computeIfAbsent(MODEL_NCNN_TURBO) {
                val app = requireAppContext()
                // Движок лежит в filesDir/engines/stt, а не в APK, поэтому
                // System.loadLibrary его не видит - грузим по абсолютному
                // пути и только потом разрешаем нативные вызовы.
                if (!EngineLibs.load(app, Engine.STT)) {
                    error(EngineLibs.reasonOf(app, Engine.STT) ?: "движок ncnn не загрузился")
                }
                NcnnWhisperContext.markEngineReady()
                val dir = File(ModelDownloader.modelsDir(app), "ncnn-turbo")
                val baseName = "whisper_turbo"
                val check = NcnnModelValidator.checkModelDir(dir, baseName)
                val missing = check.missing.size
                val ofTotal = NcnnModelDownloader.FILE_COUNT
                val size = NcnnModelDownloader.SIZE_LABEL
                check(check.ok) {
                    // Не перечисляем все 15 имён: в чат это уходит строкой
                    // ответа, и она обрезается по длине. Причина одна -
                    // набор не скачан, и пользователю нужно знать куда
                    // идти, а не что именно отсутствует.
                    "ncnn-модели не скачаны — Настройки -> Диагностика -> " +
                        "«Скачать ncnn-модели» (~$size, отсутствует $missing из $ofTotal)"
                }
                Log.d(TAG, "гружу ncnn-модель из $dir ($baseName)")
                val t0 = SystemClock.elapsedRealtime()
                val created = NcnnWhisperContext.createFromFilesDir(dir, baseName)
                Log.i(TAG, "ncnn-модель загружена за ${SystemClock.elapsedRealtime() - t0} мс")
                created
            }

        /**
         * Отпускает кэшированный ncnn-контекст, если он есть.
         *
         * Зачем, если onDestroy намеренно НЕ трогает кэши: без этого метода
         * контекст живёт весь процесс. В бенче это значило, что первый удержал
         * turbo, а матрица int8/fp32 уже не могла загрузить свою модель — C++
         * -синглтон на процесс. Теперь nativeInit отказывает, если модель ещё
         * кем-то держится (см. ncnn_jni.cpp), и чтобы прогон был честным,
         * тест должен сам освободить контекст ДО смены модели.
         *
         * ВАЖНО (тот же инвариант, что у [obtainNcnnContext]): кэш
         * потокобезопасен, но вызывать можно ТОЛЬКО когда ни одна транскрипция
         * не идёт. Иначе release() выгрузит модель из-под работающего
         * контекста - нативный мьютекс защищает структуры данных, но не
         * гарантирует, что модель не нужна прямо сейчас.
         */
        suspend fun releaseNcnnContext() {
            val ctx = ncnnCtxCache.remove(MODEL_NCNN_TURBO) ?: return
            Log.d(TAG, "отпускаю кэшированный ncnn-контекст (модель освободится нативно)")
            ctx.release()
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
