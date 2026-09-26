package org.opencode.mobile.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Офлайн-бенч порогов VAD на настоящих bench-wav. Без устройства и без whisper.
 *
 * Зачем он, если в ROADMAP написано «нужен эталонный текст для WER»: WER измеряет, что
 * Whisper сделал с уже нарезанными сегментами, и зависит от модели. Пороги же живут в
 * `SpeechSegmenter`, который - чистый Kotlin. Их правильность проверяется без эталона:
 *
 *  - на тишине не должно быть НИ ОДНОГО сегмента - энергии нет, значит нечему сработать;
 *  - на речи должен появиться хотя бы один сегмент - потерять речь можно только замерив;
 *  - главный сценарий (речь в шуме) в репозитории НЕ представлен: `noise.wav` - это шум
 *    без речи. Поэтому речь и шум склеиваются синтетически, и обе области известны точно,
 *    потому что мы сами их собрали.
 *
 * Метрики по конструированному сигналу `речь + пауза + шум`:
 *  - `recall` - доля речевой области, попавшей в сегменты (если падает - теряем речь);
 *  - `fp` - доля шумовой области, попавшей в сегменты.
 *
 * Итог печатается таблицей и пишется в `app/build/reports/vad-bench.txt`, чтобы решение о
 * смене порогов принималось по числам, а не по ощущению.
 */
class SpeechSegmenterBenchTest {
    // Порог, который предлагал аудит (MIN_RMS 0.015 -> 0.025), плюс границы вокруг него.
    private val thresholds = listOf(0.010f, 0.015f, 0.020f, 0.025f, 0.030f, 0.040f)

    // Отношение сигнал/шум в смеси. 0 dB - шум равен речи по мощности, 40 dB - тихий шум.
    private val snrs = listOf(0, 5, 10, 20, 40)

    @Test
    fun `пороги VAD на реальных bench-wav`() {
        val report = StringBuilder()
        report.appendLine("=== VAD bench: sweep MIN_RMS x SNR ===")
        report.appendLine("jfk = речь, gap = 500мс тишины, хвост = noise.wav, всё 16kHz mono")
        report.appendLine()

        checkNonSpeech(report)
        val speech = wav("jfk")
        val noise = wav("noise")
        checkSpeech(report, speech)
        report.appendLine()
        sweep(report, speech, noise)
        diagnose(report, speech, noise)

        val out = File("build/reports/vad-bench.txt")
        out.parentFile.mkdirs()
        out.writeText(report.toString())
        println(report)
        assertTrue("отчёт должен записаться", out.exists())
    }

    /** Тишина обязана давать ноль сегментов. Для noise/tone утверждения не делаются. */
    private fun checkNonSpeech(report: StringBuilder) {
        for (name in listOf("silence", "noise", "tone")) {
            val pcm = wav(name)
            val segs = SpeechSegmenter().split(pcm)
            report.appendLine(
                fmt(
                    "%-8s rms=%.4f dur=%.1fs -> сегментов %d",
                    name,
                    rms(pcm),
                    pcm.size / 16_000.0,
                    segs.size,
                ),
            )
            // У энергетического VAD нет признака "не речь", кроме громкости: громкий тон
            // энергетически неотличим от речи. Поэтому важно не "есть ли сегмент", а какой
            // порог его отсекает - это показывает свип ниже.
            if (name == "silence") {
                assertEquals("тишина не должна давать сегментов", 0, segs.size)
            }
        }
    }

    private fun checkSpeech(
        report: StringBuilder,
        speech: FloatArray,
    ) {
        val segs = SpeechSegmenter().split(speech)
        val cover = pct(coverage(segs, 0, speech.size))
        report.appendLine("Речь без шума: jfk -> сегментов ${segs.size}, покрытие $cover%")
        assertTrue("jfk содержит речь, нужен хотя бы 1 сегмент", segs.isNotEmpty())
    }

    private fun sweep(
        report: StringBuilder,
        speech: FloatArray,
        noise: FloatArray,
    ) {
        report.appendLine("recall = доля речи в сегментах, fp = доля шума в сегментах")
        report.append(fmt("%-8s", "SNR\\RMS") + thresholds.joinToString("") { fmt("%14.3f", it) })
        val speechEnd = speech.size
        val noiseStart = speech.size + GAP_SAMPLES
        for (snr in snrs) {
            val mix = mix(speech, noise, snr)
            val row = StringBuilder(fmt("%-8s", "${snr}dB"))
            for (thr in thresholds) {
                val segs = SpeechSegmenter(minRms = thr).split(mix)
                val recall = pct(coverage(segs, 0, speechEnd))
                val fp = pct(coverage(segs, noiseStart, mix.size))
                row.append(fmt("%14s/%6s", recall, fp))
            }
            report.appendLine(row)
        }
        report.appendLine()
        report.appendLine("(значения в процентах: recall/fp)")
    }

    /**
     * Печатает границы сегментов на самом громком шуме. Нужна, чтобы проверить само
     * измерение: если fp нигде не тождественно 0, надо убедиться, что шумовой хвост
     * вообще кем-то просматривается, а не выпал из области подсчёта.
     */
    private fun diagnose(
        report: StringBuilder,
        speech: FloatArray,
        noise: FloatArray,
    ) {
        report.appendLine()
        report.appendLine("=== диагностика измерения: сегменты на 0dB ===")
        val loud = mix(speech, noise, 0)
        val noiseStartMs = (speech.size + GAP_SAMPLES) * 1000 / 16_000
        val endMs = loud.size * 1000 / 16_000
        report.appendLine("шум начинается с $noiseStartMs мс, конец $endMs мс")
        val segs = SpeechSegmenter(minRms = 0.015f).split(loud)
        if (segs.isEmpty()) {
            report.appendLine("сегментов нет вообще")
        }
        for (s in segs) {
            val from = s.startMs
            val to = s.startMs + s.samples.size * 1000 / 16_000
            val inNoise = to > noiseStartMs
            report.appendLine("  сегмент $from..$to мс rms=${pct4(s.rms)} в шуме=$inNoise")
        }
    }

    /** Склеивает `речь + тишина + шум`, масштабируя шум до заданного SNR. */
    private fun mix(
        speech: FloatArray,
        noise: FloatArray,
        snrDb: Int,
    ): FloatArray {
        val speechRms = rms(speech)
        val noiseRms = rms(noise)
        val target = speechRms * Math.pow(10.0, -snrDb / 20.0)
        val gain = if (noiseRms > 1e-9) (target / noiseRms).toFloat() else 0f
        val tail = FloatArray(noise.size) { (noise[it] * gain).coerceIn(-1f, 1f) }
        return FloatArray(speech.size + GAP_SAMPLES + tail.size).also {
            System.arraycopy(speech, 0, it, 0, speech.size)
            System.arraycopy(tail, 0, it, speech.size + GAP_SAMPLES, tail.size)
        }
    }

    /** Доля выборок [from, until), попавших в сегменты. */
    private fun coverage(
        segs: List<SpeechSegmenter.Segment>,
        from: Int,
        until: Int,
    ): Double {
        if (until <= from) return 0.0
        val covered = BooleanArray(until)
        for (s in segs) {
            // startMs - миллисекунды, при 16 кГц это ровно 16 сэмплов на миллисекунду.
            val start = s.startMs * SAMPLES_PER_MS
            for (i in start until minOf(start + s.samples.size, until)) {
                if (i in 0 until until) covered[i] = true
            }
        }
        var hit = 0
        for (i in from until until) if (covered[i]) hit++
        return hit.toDouble() / (until - from)
    }

    private fun rms(a: FloatArray): Double {
        var sum = 0.0
        for (x in a) sum += x * x
        return Math.sqrt(sum / a.size)
    }

    /** Читает 16-битный моно WAV из test resources, минуя 44-байтовый заголовок. */
    private fun wav(name: String): FloatArray {
        val path = "bench/$name.wav"
        val bytes = checkNotNull(javaClass.classLoader!!.getResourceAsStream(path)) {
            "нет бенч-файла $path в test resources"
        }.use { it.readBytes() }
        require(bytes.size > 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") {
            "$path не RIFF"
        }
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "$path не WAVE" }
        val n = (bytes.size - 44) / 2
        return FloatArray(n) { i ->
            val lo = bytes[44 + i * 2].toInt() and 0xFF
            val hi = bytes[45 + i * 2].toInt()
            ((hi shl 8) or lo).toShort() / 32768f
        }
    }

    private fun fmt(
        pattern: String,
        vararg args: Any?,
    ): String = String.format(Locale.ROOT, pattern, *args)

    private fun pct(v: Double): String = fmt("%.1f", v * 100)

    private fun pct4(v: Float): String = fmt("%.4f", v)

    private companion object {
        /** 500 мс тишины между речью и шумом, чтобы области не слипались. */
        const val GAP_SAMPLES = 8_000

        /** Сэмплов на миллисекунду при 16 кГц. */
        const val SAMPLES_PER_MS = 16
    }
}
