package org.opencode.mobile.tts

import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File

/** Один кусок синтезированной речи: сэмплы [-1..1] и частота. */
data class TtsAudio(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    val durationSec: Float get() = samples.size.toFloat() / sampleRate
    val isEmpty: Boolean get() = samples.isEmpty()
}

/**
 * Обёртка над sherpa-onnx OfflineTts для SupertonicTTS 3.
 *
 * Модель живёт в процессе приложения: загружается один раз, дальше на предложение
 * уходит только синтез (~1.3 с на 2 потоках вместо 2.5 с с перезапуском CLI).
 */
class SupertonicTts private constructor(
    private val tts: OfflineTts,
    val sampleRate: Int,
    val numSpeakers: Int,
) {
    fun synthesize(
        text: String,
        sid: Int,
        speed: Float,
    ): TtsAudio {
        if (text.isBlank()) return TtsAudio(FloatArray(0), sampleRate)
        val audio: GeneratedAudio = tts.generate(text, sid.coerceIn(0, numSpeakers - 1), speed)
        return TtsAudio(audio.samples, audio.sampleRate)
    }

    fun release() = tts.free()

    companion object {
        private const val TAG = "TTS"

        /**
         * Ошибка загрузки движка: нет нативной библиотеки (x86), нет модели,
         * либо модель повреждена: озвучка молчит, причина уходит в UI.
         */
        class LoadFailed(
            message: String,
            cause: Throwable? = null,
        ) : Exception(message, cause)

        /**
         * Запрет на повторную загрузку — только после НЕУДАЧИ и ненадолго.
         *
         * Раньше флаг защёлкивался навсегда, и переключение движка в панели
         * убивало озвучку до перезапуска приложения. Смысл флага — не долбить
         * упавшую модель в цикле, а не запретить нормальную работу после
         * пересоздания спикера.
         *
         * И позже выяснилось, что вечного запрета тоже мало: сброс звал только
         * androidTest, то есть в релизной сборке после OOM или битого onnx озвучка
         * не поднималась вообще никогда — до перезапуска процесса. Теперь запрет
         * живёт [RETRY_AFTER_SEC]: сломанная модель получит одну попытку в минуту
         * вместо одной на всё время работы приложения, а скачанная заново
         * поднимется сама.
         */
        private var failedLoad = false

        /** Когда последняя попытка упала; 0 — попыток ещё не было. */
        private var lastFailAt = 0L

        private const val RETRY_AFTER_SEC = 60L

        private const val MS_IN_SEC = 1000L

        private const val RETRY_AFTER_MS = RETRY_AFTER_SEC * MS_IN_SEC

        private var unsupportedAbi = false

        /** Нативная библиотека есть только для arm64 — на эмуляторе деградируем. */
        fun isSupportedAbi(): Boolean =
            android.os.Build.SUPPORTED_ABIS
                .any { it.startsWith("arm64") }

        /** null — движок недоступен: озвучка молчит, причина уходит в UI. */
        fun loadOrNull(
            modelDir: File,
            threads: Int = TtsConfig.NUM_THREADS,
        ): SupertonicTts? = if (blockedBy(modelDir) != null) null else build(modelDir, threads)

        /**
         * Причина, по которой движок сейчас поднимать нельзя, либо null — можно пробовать.
         *
         * Отдельная функция не ради красоты. Четыре ранних выхода подряд в одном теле
         * давали четыре `return` и лимит detekt два на функцию, и читались как «что
         * может пойти не так», а не как «что проверяем по порядку».
         */
        private fun blockedBy(modelDir: File): String? {
            val missing = missingFiles(modelDir)
            return when {
                !isSupportedAbi() -> abiUnsupported()
                missing.isNotEmpty() -> {
                    Log.w(TAG, "sherpa-onnx: модель не установлена, нет ${missing.joinToString()}")
                    "модель не установлена"
                }
                failedLoad && (lastFailAt > 0L && System.currentTimeMillis() - lastFailAt < RETRY_AFTER_MS) -> {
                    Log.w(
                        TAG,
                        "sherpa-onnx: предыдущая загрузка упала, повтор не раньше чем через $RETRY_AFTER_SEC с",
                    )
                    "предыдущая загрузка упала"
                }
                else -> null
            }
        }

        /** Причина возвращается наружу, потому что вызывающий ищет именно null. */
        private fun abiUnsupported(): String {
            if (!unsupportedAbi) {
                unsupportedAbi = true
                Log.w(TAG, "sherpa-onnx: нет arm64, TTS недоступен на ${android.os.Build.SUPPORTED_ABIS}")
            }
            return "нет arm64"
        }

        private fun missingFiles(modelDir: File): List<String> = TtsModels.SUPERTONIC_FILES.filter { !File(modelDir, it).exists() }

        private fun build(
            modelDir: File,
            threads: Int,
        ): SupertonicTts? {
            // Ноль или отрицательное — «потоки не заданы», а не «один поток»: движок на
            // numThreads=1 падает с Invalid OfflineTtsConfig, а на 2 даёт лучший RTF
            // (0.32 против 0.58 на 4, замер 03.10). Тесты зовут loadOrNull(dir, 0).
            val safeThreads = if (threads > 0) threads else TtsConfig.NUM_THREADS
            return try {
                val tts = OfflineTts(
                    config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            supertonic = OfflineTtsSupertonicModelConfig(
                                durationPredictor = "${modelDir.path}/duration_predictor.int8.onnx",
                                textEncoder = "${modelDir.path}/text_encoder.int8.onnx",
                                vectorEstimator = "${modelDir.path}/vector_estimator.int8.onnx",
                                vocoder = "${modelDir.path}/vocoder.int8.onnx",
                                ttsJson = "${modelDir.path}/tts.json",
                                unicodeIndexer = "${modelDir.path}/unicode_indexer.bin",
                                voiceStyle = "${modelDir.path}/voice.bin",
                            ),
                            numThreads = safeThreads,
                            provider = "cpu",
                        ),
                        maxNumSentences = 1,
                    ),
                )
                val loaded = SupertonicTts(tts, tts.sampleRate(), tts.numSpeakers())
                Log.i(
                    TAG,
                    "sherpa-onnx загружен: rate=${loaded.sampleRate} sid=${loaded.numSpeakers} " +
                        "threads=$safeThreads model=${modelDir.name}",
                )
                loaded
            } catch (t: Throwable) {
                // UnsatisfiedLinkError на не-arm64, OOM на слабом железе, битый onnx.
                failedLoad = true
                lastFailAt = System.currentTimeMillis()
                Log.e(TAG, "sherpa-onnx не поднялся: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
        }

        /** Сбросить запрет повторной загрузки (после скачивания модели). */
        fun resetLoadAttempt() {
            failedLoad = false
            lastFailAt = 0L
        }
    }
}
