package org.opencode.mobile.tts

import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File

/** Один кусок синтезированной речи: сэмплы [-1..1] и частота. */
data class TtsAudio(val samples: FloatArray, val sampleRate: Int) {
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
    fun synthesize(text: String, sid: Int, speed: Float): TtsAudio {
        if (text.isBlank()) return TtsAudio(FloatArray(0), sampleRate)
        val audio: GeneratedAudio = tts.generate(text, sid.coerceIn(0, numSpeakers - 1), speed)
        return TtsAudio(audio.samples, audio.sampleRate)
    }

    fun release() = tts.free()

    companion object {
        private const val TAG = "TTS"

        /**
         * Ошибка загрузки движка: нет нативной библиотеки (x86), нет модели,
         * либо модель повреждена. Разрешается в TtsEngine.system.
         */
        class LoadFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

        /**
         * Запрет на повторную загрузку — только после НЕУДАЧИ.
         *
         * Раньше флаг защёлкивался навсегда, и переключение system → sherpa в панели
         * убивало озвучку до перезапуска приложения. Смысл флага — не долбить
         * упавшую модель в цикле, а не запретить нормальную работу после
         * пересоздания спикера.
         */
        private var failedLoad = false
        private var unsupportedAbi = false

        /** Нативная библиотека есть только для arm64 — на эмуляторе деградируем. */
        fun isSupportedAbi(): Boolean =
            android.os.Build.SUPPORTED_ABIS.any { it.startsWith("arm64") }

        /** null — движок недоступен, вызывающий переходит на system. */
        fun loadOrNull(modelDir: File, threads: Int = TtsConfig.NUM_THREADS): SupertonicTts? {
            if (!isSupportedAbi()) {
                if (!unsupportedAbi) {
                    unsupportedAbi = true
                    Log.w(TAG, "sherpa-onnx: нет arm64, TTS недоступен на ${android.os.Build.SUPPORTED_ABIS}")
                }
                return null
            }
            val missing = TtsModels.SUPERTONIC_FILES.filter { !File(modelDir, it).exists() }
            if (missing.isNotEmpty()) {
                Log.w(TAG, "sherpa-onnx: модель не установлена, нет ${missing.joinToString()}")
                return null
            }
            if (failedLoad) {
                Log.w(TAG, "sherpa-onnx: предыдущая загрузка упала, повтор запрещён до resetLoadAttempt")
                return null
            }
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
                            numThreads = threads,
                            provider = "cpu",
                        ),
                        maxNumSentences = 1,
                    ),
                )
                val loaded = SupertonicTts(tts, tts.sampleRate(), tts.numSpeakers())
                Log.i(
                    TAG,
                    "sherpa-onnx загружен: rate=${loaded.sampleRate} sid=${loaded.numSpeakers} " +
                        "threads=$threads model=${modelDir.name}",
                )
                loaded
            } catch (t: Throwable) {
                // UnsatisfiedLinkError на не-arm64, OOM на слабом железе, битый onnx.
                failedLoad = true
                Log.e(TAG, "sherpa-onnx не поднялся: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
        }

        /** Сбросить запрет повторной загрузки (после скачивания модели). */
        fun resetLoadAttempt() {
            failedLoad = false
        }
    }
}