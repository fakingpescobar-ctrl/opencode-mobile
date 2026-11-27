package org.opencode.mobile.stt

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.opencode.mobile.engine.Engine
import org.opencode.mobile.engine.EngineLibs
import java.io.File

/**
 * Silero VAD v6 через sherpa-onnx. Не STT-модель: режет аудио на речевые сегменты
 * перед распознаванием. Если модель/библиотека недоступны, вызывающий код должен
 * fallback на RMS [SpeechSegmenter].
 */
object SileroVadSegmenter {
    private const val TAG = "SileroVAD"
    private const val SAMPLE_RATE = 16_000
    private const val WINDOW = 512

    @Volatile private var cached: Vad? = null
    @Volatile private var cachedPath: String? = null
    private val lock = Any()

    fun isAvailable(context: Context): Boolean = ModelDownloader.sileroVadReady(context)

    fun split(context: Context, samples: FloatArray): List<SpeechSegmenter.Segment>? {
        val file = ModelDownloader.sileroVadFile(context)
        if (!file.exists() || file.length() != ModelDownloader.SILERO_VAD_BYTES) return null
        if (!EngineLibs.load(context, Engine.TTS)) {
            Log.w(TAG, "libsherpa-onnx-jni не загружен: ${EngineLibs.reasonOf(context, Engine.TTS)}")
            return null
        }
        return runCatching { splitWithVad(file, samples) }.onFailure {
            Log.w(TAG, "Silero VAD failed, fallback to RMS", it)
        }.getOrNull()
    }

    private fun splitWithVad(file: File, samples: FloatArray): List<SpeechSegmenter.Segment> {
        val vad = obtain(file)
        val segments = ArrayList<SpeechSegmenter.Segment>()
        synchronized(lock) {
            vad.reset()
            var offset = 0
            while (offset + WINDOW <= samples.size) {
                val chunk = samples.copyOfRange(offset, offset + WINDOW)
                vad.acceptWaveform(chunk)
                drain(vad, segments)
                offset += WINDOW
            }
            if (offset < samples.size) {
                val tail = FloatArray(WINDOW)
                samples.copyInto(tail, 0, offset, samples.size)
                vad.acceptWaveform(tail)
            }
            vad.flush()
            drain(vad, segments)
        }
        Log.i(TAG, "segments=${segments.size} from ${samples.size / SAMPLE_RATE}s audio")
        return segments
    }

    private fun drain(vad: Vad, out: MutableList<SpeechSegmenter.Segment>) {
        while (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            val segSamples = seg.samples
            if (segSamples.isNotEmpty()) {
                val startMs = ((seg.start.toDouble() / SAMPLE_RATE) * 1000.0).toInt()
                out.add(SpeechSegmenter.Segment(startMs = startMs, samples = segSamples, rms = rms(segSamples)))
            }
        }
    }

    private fun obtain(file: File): Vad {
        val path = file.absolutePath
        val existing = cached
        if (existing != null && cachedPath == path) return existing
        synchronized(lock) {
            val again = cached
            if (again != null && cachedPath == path) return again
            again?.release()
            val cfg = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = path,
                    threshold = 0.45F,
                    minSilenceDuration = 0.35F,
                    minSpeechDuration = 0.20F,
                    windowSize = WINDOW,
                    maxSpeechDuration = 28.0F,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
            val vad = Vad(config = cfg)
            cached = vad
            cachedPath = path
            Log.i(TAG, "loaded $path")
            return vad
        }
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s * s
        return kotlin.math.sqrt(sum / samples.size).toFloat()
    }
}
