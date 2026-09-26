package com.whispercpp.whisper

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

private const val NCNN_LOG_TAG = "NcnnWhisper"

/** Язык не определён: определение провалилось, вызывающий обязан взять fallback. */
const val FAILED_LANG = ""

/** Результат [NcnnWhisperContext.transcribeAuto]: язык сессии + распознанный текст. */
data class AutoResult(val lang: String, val text: String)

/**
 * Контекст распознавания на движке ncnn (Vulkan/Adreno GPU).
 *
 * Параллельный движок к WhisperContext (whisper.cpp CPU): загружает ncnn-модель
 * (whisper_base_*.ncnn.{param,bin} + whisper_vocab.txt) из файловой директории и
 * считает на GPU. Переключение между движками — на уровне WhisperTranscribeService
 * (stt_engine), сам контекст про это не знает.
 */
class NcnnWhisperContext private constructor(
    private val modelDir: String,
    private val base: String
) {
    // Whisper C++ constraint: один поток одновременно (как в WhisperContext).
    private val scope: CoroutineScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )

    @Volatile private var initialized = false

    @Synchronized
    private fun ensureInit(): Boolean {
        if (initialized) return true
        initialized = NcnnWhisperLib.nativeInit(modelDir, base)
        return initialized
    }

    /**
     * Готов ли контекст К ПРОВЕДЕНИЮ РАСПОЗНАВАНИЯ, а не «грузили ли мы когда-то».
     *
     * [initialized] и нативный refcount — два независимых источника истины, и
     * они расходятся: любой, кто зовёт `nativeFree()` (например бенч со своим
     * контекстом на время замера fp32), обнуляет общий `g_whisper` у всех, кто
     * уже загрузился, оставив наши флаги в состоянии «готов». Такой контекст
     * навсегда отдаёт «ncnn whisper not initialized»: кэш контекстов сервиса
     * отдаст его по попаданию, а `getOrPut`-фабрика на попадании не
     * перезапускается, и `nativeInit` никто не повторит.
     *
     * Поэтому перед каждой работой спрашиваем нативную сторону, жив ли синглтон
     * именно на нашей модели, и переинициализируемся, если нет. Проверка идёт
     * по dir+base, а не «не nullptr»: если глобал заняла другая модель, наш
     * контекст тоже нерабочий, и молчать об этом нельзя.
     */
    @Synchronized
    private fun ensureAlive(): Boolean {
        if (!initialized) return ensureInit()
        if (NcnnWhisperLib.nativeIsAlive(modelDir, base)) return true
        Log.w(
            NCNN_LOG_TAG,
            "нативная модель выгружена или вытеснена другой - переинициализирую (dir=$modelDir, base=$base)"
        )
        initialized = false
        return ensureInit()
    }

    /**
     * Блокирующий (suspend) прогон распознавания. Возвращает текст либо строку ошибки.
     * Логирует тайминг в формате `ncnn: NNмс` для сравнения с whisper.cpp на устройстве.
     */
    suspend fun transcribeData(data: FloatArray, lang: String = "ru"): String =
        withContext(scope.coroutineContext) {
            if (!ensureAlive()) {
                Log.e(NCNN_LOG_TAG, "nativeInit вернул false (dir=$modelDir, base=$base)")
                return@withContext "ОШИБКА NCNN: не удалось загрузить ncnn-модель"
            }
            val t0 = System.nanoTime()
            // Тот же приём, что у whisper.cpp: поднимаем nice, иначе ColorOS
            // душит фоновые compute-потоки до 1-5% CPU.
            android.os.Process.setThreadPriority(
                android.os.Process.myTid(),
                android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
            )
            val text = try {
                NcnnWhisperLib.nativeTranscribe(data, lang)
            } finally {
                android.os.Process.setThreadPriority(
                    android.os.Process.myTid(),
                    android.os.Process.THREAD_PRIORITY_DEFAULT
                )
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            Log.d(NCNN_LOG_TAG, "ncnn: ${ms}мс (${data.size} сэмплов, lang=$lang)")
            text
        }

    /**
     * Авто-определение языка + транскрипция одним нативным вызовом.
     *
     * Зачем: encoder_states — самый дорогой артефакт (~6.5 с на телефоне) и он
     * НЕ зависит от языка. Нативная сторона считает encoder один раз, читает
     * язык с префилла [sot] и декодирует уже с найденным токеном языка. Два
     * вызова transcribeData (сначала определить язык, потом распознать) платили
     * бы за encoder дважды; здесь добавка — всего один префилл (~40 мс).
     *
     * Возвращает язык и текст. При неудаче определения язык = [FAILED_LANG].
     */
    suspend fun transcribeAuto(data: FloatArray): AutoResult =
        withContext(scope.coroutineContext) {
            if (!ensureAlive()) {
                Log.e(NCNN_LOG_TAG, "nativeInit вернул false (dir=$modelDir, base=$base)")
                val err = "ОШИБКА NCNN: не удалось загрузить ncnn-модель"
                return@withContext AutoResult(FAILED_LANG, err)
            }
            val t0 = System.nanoTime()
            // Тот же приём, что у whisper.cpp: поднимаем nice, иначе ColorOS
            // душит фоновые compute-потоки до 1-5% CPU.
            android.os.Process.setThreadPriority(
                android.os.Process.myTid(),
                android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
            )
            val res = try {
                NcnnWhisperLib.nativeTranscribeAuto(data)
            } finally {
                android.os.Process.setThreadPriority(
                    android.os.Process.myTid(),
                    android.os.Process.THREAD_PRIORITY_DEFAULT
                )
            }
            val out = AutoResult(res[0], res[1])
            val ms = (System.nanoTime() - t0) / 1_000_000
            Log.d(NCNN_LOG_TAG, "ncnn-auto: ${ms}мс (${data.size} сэмплов, lang=${out.lang})")
            out
        }

    /**
     * Отпускает долю владения нативной моделью.
     *
     * ВАЖНО: finalize() здесь НЕ используется. Контексты переиспользуют один
     * глобальный g_whisper через счётчик ссылок в ncnn_jni.cpp, а сборщик мусора
     * вызывает finalize() в произвольный момент — в том числе на ещё живой
     * ссылке. Это выгружало модель из-под активного контекста. Освобождение
     * теперь только явное, парность гарантирует acquire/release в сервисе.
     */
    suspend fun release() = withContext(scope.coroutineContext) {
        if (initialized) {
            NcnnWhisperLib.nativeFree()
            initialized = false
        }
    }

    /**
     * Переустановка числа потоков в рантайме (JNI nativeSetThreads).
     * ВАЖНО: gemm-слои encoder фиксируют потоки при load (nativeInit ставит 8);
     * вызов после init полностью применяется к decoder/fbank/proj_out и,
     * частично, к новым extractor encoder. Для честной матрицы «потоки»
     * нужен перезапуск процесса/пересборка с другими значениями в nativeInit.
     * Используется бенч-стендом (PR5); в проде не вызывается.
     */
    fun setThreads(n: Int): Boolean {
        if (!ensureAlive()) return false
        return NcnnWhisperLib.nativeSetThreads(n)
    }

    /**
     * Пофазные тайминги последнего transcribe: [fbank_ms, encoder_ms, decoder_ms, decoder_steps].
     * Только для STT-бенча (R5); в проде не вызывается.
     */
    fun latencyProfile(): LongArray? =
        if (initialized) NcnnWhisperLib.nativeLatencyProfile() else null

    companion object {
        /**
         * Создаёт контекст из каталога, где лежат whisper_<base>_*.ncnn.{param,bin}
         * и whisper_vocab.txt. base = "whisper_base" (whisper-base fp16 ncnn).
         */
        fun createFromFilesDir(dir: File, base: String = "whisper_base"): NcnnWhisperContext =
            NcnnWhisperContext(dir.absolutePath, base)
    }
}

// object (не class+companion!): методы объявляются как экземплярные,
// и JNI-имена совпадают с ncnn_jni.cpp: Java_..._NcnnWhisperLib_native*.
// Companion-методы требуют суффикса 00024Companion — в .so его нет.
private object NcnnWhisperLib {
    init {
        Log.d(NCNN_LOG_TAG, "Loading libncnnwhisper.so (ncnn Vulkan)")
        System.loadLibrary("ncnnwhisper")
    }

    // JNI (ncnn_jni.cpp): Java_com_whispercpp_whisper_NcnnWhisperLib_*
      external fun nativeInit(modelDir: String, base: String): Boolean
      external fun nativeSetThreads(n: Int): Boolean
      /** Жив ли нативный синглтон именно на ЭТОЙ модели (dir+base). См. [ensureAlive]. */
      external fun nativeIsAlive(modelDir: String, base: String): Boolean
    external fun nativeTranscribe(samples: FloatArray, lang: String): String
    /** Возвращает String[2] = { language, text }; language пустой при неудаче. */
    external fun nativeTranscribeAuto(samples: FloatArray): Array<String>
    external fun nativeLatencyProfile(): LongArray
    external fun nativeFree()
}
