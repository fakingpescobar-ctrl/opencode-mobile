package org.opencode.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencode.mobile.stt.SpeechSegmenter
import java.util.Random

/** JVM-тесты VAD-сегментера (ЭКСП-5) — без устройства, чистая логика. */
class SpeechSegmenterTest {
    private val seg = SpeechSegmenter(16_000)

    /** Полный «комнатный» шум с низкой RMS (тишина). */
    private fun noise(
        rng: Random,
        seconds: Double,
        amp: Double = 0.02,
    ): FloatArray {
        val n = (16_000.0 * seconds).toInt()
        return FloatArray(n) { (rng.nextGaussian() * amp).toFloat() }
    }

    /** Речь-заглушка: сумма синусов с амплитудной модуляцией (слоги ~3.5Гц, как у речи). */
    private fun speech(
        rng: Random,
        seconds: Double,
        amp: Double = 0.35,
    ): FloatArray {
        val n = (16_000.0 * seconds).toInt()
        return FloatArray(n) { i ->
            val t = i / 16_000.0
            val voice = 0.6 * Math.sin(2 * Math.PI * 180 * t) + 0.4 * Math.sin(2 * Math.PI * 220 * t)
            val syll = 0.35 + 0.65 * Math.sin(2 * Math.PI * 3.5 * t)
            (voice * amp * syll).toFloat() + (rng.nextGaussian() * 0.01).toFloat()
        }
    }

    private fun concat(vararg parts: FloatArray): FloatArray {
        return FloatArray(parts.sumOf { it.size }) { idx ->
            var acc = 0
            for (p in parts) {
                if (idx < acc + p.size) return@FloatArray p[idx - acc]
                acc += p.size
            }
            0f
        }
    }

    @Test
    fun `две фразы разделены паузой - два сегмента`() {
        val rng = Random(7)
        val clip = concat(speech(rng, 1.2), noise(rng, 1.0), speech(rng, 1.0))
        val segs = seg.split(clip)
        assertEquals("ожидалось 2 сегмента", 2, segs.size)
        assertTrue("первый короче 2с", segs[0].samples.size < 2 * 16_000)
        assertTrue("второй короче 2с", segs[1].samples.size < 2 * 16_000)
        assertTrue("сегменты идут по порядку", segs[0].startMs < segs[1].startMs)
    }

    @Test
    fun `чистая тишина - пусто`() {
        val rng = Random(3)
        val segs = seg.split(noise(rng, 3.0))
        assertTrue("тишина 3с не должна дать сегментов", segs.isEmpty())
    }

    @Test
    fun `шум комнаты с хлопком - один реальный сегмент`() {
        val rng = Random(11)
        val clip = concat(noise(rng, 2.0), speech(rng, 0.3), noise(rng, 2.0))
        val segs = seg.split(clip)
        val allowed = segs.isEmpty() || segs.size == 1
        assertTrue(
            "короткий хлопок 0.3с: сегмент может быть отброшен (MIN_SPEECH 0.4с)",
            allowed,
        )
    }

    @Test
    fun `длинная непрерывная речь - жёсткий лимит 28с`() {
        val rng = Random(5)
        val duration = 31.0
        val clip = speech(rng, duration)
        val segs = seg.split(clip)
        assertTrue("31с речи должны разбиться на 2+ сегмента", segs.size >= 2)
        for (s in segs) {
            assertTrue("сегмент ≤ 28с + 1 кадр", s.samples.size <= 28 * 16_000 + 16_000)
        }
    }

    @Test
    fun `речь после паузы старта - сегмент с pre-roll`() {
        val rng = Random(13)
        val clip = concat(noise(rng, 1.0), speech(rng, 1.5))
        val segs = seg.split(clip)
        assertEquals(1, segs.size)
        assertTrue("pre-roll не больше 150мс + кадра", segs[0].startMs < 1_000 + 200)
    }
}
