package org.opencode.mobile

import android.content.res.AssetManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispercpp.whisper.NcnnWhisperContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencode.mobile.stt.ModelDownloader
import org.opencode.mobile.stt.NcnnModelValidator
import java.io.File
import java.io.FileOutputStream

/**
 * Бенч-стенд STT (PR5): замер латентности ncnn-движка на фикс-наборе wav.
 *
 * Покрывает матрицу:
 *   - модели: int8 (models/ncnn-turbo/) и, если доставлен, fp32
 *     (models/ncnn-bench-fp32/ — копия turbo БЕЗ *_encoder_int8.* файлов);
 *   - входы: assets/bench/{silence,noise,tone,jfk [,ru]}.wav (16k mono PCM16);
 *   - 3 прогона на каждый вход, медиана.
 *
 * Ограничение архитектуры: C++-синглтон g_whisper — конфиги гоняются
 * ПОСЛЕДОВАТЕЛЬНО (release() обязателен между int8 и fp32).
 *
 * Запуск: устройство по adb + `./gradlew :app:connectedDebugAndroidTest`.
 * Результат: logcat STTBENCH-строки + CSV в /sdcard/Android/data/<pkg>/files/.
 */
@RunWith(AndroidJUnit4::class)
class BenchSttTest {
    @Test
    fun benchInt8AndFp32() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val benchAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val int8Dir = File(ModelDownloader.modelsDir(target), "ncnn-turbo")
        val int8Check = NcnnModelValidator.checkModelDir(int8Dir, "whisper_turbo")
        assumeTrue("ncnn-turbo не доставлена на устройство: ${int8Check.missing}", int8Check.ok)

        val wavs = loadWavs(benchAssets)
        assertTrue("assets/bench пуст — сначала tools/gen_bench_wavs.py", wavs.isNotEmpty())

        val csv = StringBuilder().append("config,wav,ms1,ms2,ms3,median,text\n")
        runConfig("int8", int8Dir, wavs, csv)
        val fp32Dir = File(ModelDownloader.modelsDir(target), "ncnn-bench-fp32")
        if (NcnnModelValidator.checkModelDir(fp32Dir, "whisper_turbo").ok) {
            runConfig("fp32", fp32Dir, wavs, csv)
        } else {
            Log.w(TAG, "ncnn-bench-fp32 не доставлена — матрица без fp32")
        }
        writeCsv(target, csv.toString())
        Log.i(TAG, "BENCH_DONE\n$csv")
    }

    /** Один конфиг модели: warmup (init+прогрев), затем 3 замера каждого wav. */
    private fun runConfig(
        config: String,
        dir: File,
        wavs: List<WavSample>,
        csv: StringBuilder,
    ) {
        val ctx = NcnnWhisperContext.createFromFilesDir(dir, "whisper_turbo")
        try {
            val warmup = wavs.first { it.name == "silence" }
            val first = runBlocking { ctx.transcribeData(warmup.samples, "ru") }
            assertTrue(
                "$config: nativeInit/transcribe упал: $first",
                !first.startsWith("ОШИБКА NCNN"),
            )
            ctx.setThreads(8)
            Log.i(TAG, "$config: warmup ${warmup.samples.size} сэмплов, потоки=8")

            for (wav in wavs) {
                val runs = IntArray(RUNS)
                var text = ""
                for (r in 0 until RUNS) {
                    val t0 = System.nanoTime()
                    text = runBlocking { ctx.transcribeData(wav.samples, "ru") }
                    runs[r] = ((System.nanoTime() - t0) / 1_000_000).toInt()
                }
                val median = runs.sorted()[RUNS / 2]
                val sanitized = text.trim().replace('\n', ' ').take(60)
                val row = "$config,${wav.name},${runs[0]},${runs[1]},${runs[2]},$median,$sanitized"
                csv.append(row).append('\n')
                Log.i(TAG, "BENCH_ROW $row")
            }
        } finally {
            runBlocking { ctx.release() }
        }
    }

    /** Читает все .wav из assets/bench — молча пропускает битые/чужие форматы. */
    private fun loadWavs(assets: AssetManager): List<WavSample> {
        val names = assets
            .list("bench")
            .orEmpty()
            .filter { it.endsWith(".wav") }
            .sorted()
        val out = mutableListOf<WavSample>()
        for (name in names) {
            val wav = readWav(name, assets) ?: continue
            out.add(wav)
            Log.i(TAG, "wav: ${wav.name} ${wav.samples.size} сэмплов (${wav.samples.size / 16_000.0}s)")
        }
        return out
    }

    private fun writeCsv(
        target: android.content.Context,
        content: String,
    ) {
        val outDir = target.getExternalFilesDir(null) ?: return
        outDir.mkdirs()
        val f = File(outDir, "stt-bench.csv")
        FileOutputStream(f).use { it.write(content.toByteArray()) }
        Log.i(TAG, "CSV: ${f.absolutePath}")
    }

    /** Минимальный RIFF/WAVE-парсер: PCM16 mono 16k → FloatArray -1..1. */
    private fun readWav(
        fileName: String,
        assets: AssetManager,
    ): WavSample? {
        val raw = assets.open("bench/$fileName").use { it.readBytes() }
        var off = 12
        var channels = 0
        var rate = 0
        var bits = 16
        var data: ByteArray? = null
        while (off + 8 <= raw.size) {
            val id = String(raw, off, 4, Charsets.US_ASCII)
            val size = le32(raw, off + 4)
            when (id) {
                "fmt " -> {
                    channels = le16(raw, off + 10)
                    rate = le32(raw, off + 12)
                    bits = le16(raw, off + 22)
                }
                "data" -> data = raw.copyOfRange(off + 8, off + 8 + size.coerceAtMost(raw.size - off - 8))
            }
            off += 8 + size + (size and 1)
        }
        val samples = data ?: run {
            Log.w(TAG, "$fileName: chunk data не найден, пропуск")
            return null
        }
        if (channels != 1 || rate != 16_000 || bits != 16) {
            Log.w(TAG, "$fileName: формат $channels ch/$rate Hz/$bits bit — нужен 1ch/16000/16, пропуск")
            return null
        }
        val floats = FloatArray(samples.size / 2)
        for (i in floats.indices) {
            val lo = samples[i * 2].toInt() and 0xFF
            val hi = samples[i * 2 + 1].toInt() and 0xFF
            val s = (hi shl 8) or lo
            floats[i] = (if (s >= 0x8000) s - 0x10000 else s) / 32768f
        }
        return WavSample(fileName.removeSuffix(".wav"), floats)
    }

    private fun le16(
        b: ByteArray,
        off: Int,
    ): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(
        b: ByteArray,
        off: Int,
    ): Int = le16(b, off) or (le16(b, off + 2) shl 16)

    private data class WavSample(
        val name: String,
        val samples: FloatArray,
    )

    private companion object {
        const val TAG = "STTBENCH"
        const val RUNS = 3
    }
}
