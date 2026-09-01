package com.whispercpp.whisper

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

private const val NCNN_LOG_TAG = "NcnnWhisper"

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
     * Блокирующий (suspend) прогон распознавания. Возвращает текст либо строку ошибки.
     * Логирует тайминг в формате `ncnn: NNмс` для сравнения с whisper.cpp на устройстве.
     */
    suspend fun transcribeData(data: FloatArray, lang: String = "ru"): String =
        withContext(scope.coroutineContext) {
            if (!ensureInit()) {
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

    suspend fun release() = withContext(scope.coroutineContext) {
        if (initialized) {
            NcnnWhisperLib.nativeFree()
            initialized = false
        }
    }

    protected fun finalize() {
        runBlocking { release() }
    }

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
    external fun nativeTranscribe(samples: FloatArray, lang: String): String
    external fun nativeFree()
}
