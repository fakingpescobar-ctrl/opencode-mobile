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
        // Сегмент впитывает паузу до SPLIT_GAP_MS — это и есть механизм склейки,
        // поэтому длина первого сегмента = речь 1.2с + пауза 0.9с + кадр запаса.
        val firstMax =
            ((1.2 + SpeechSegmenter.SPLIT_GAP_MS / 1000.0 + 0.05) * 16_000).toInt()
        assertTrue(
            "первый = речь 1.2с + поглощённая пауза 0.9с",
            segs[0].samples.size <= firstMax,
        )
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
    fun `длинная речь с частыми паузами - не дробится на десятки сегментов`() {
        // Реальная структура bench/long.wav: речевые подфразы 2.4/1.6/2.8/3.5 с,
        // повторённые трижды, разделены паузами ~0.61 с.
        //
        // На старом пороге разрыва 450 мс каждая пауза резала поток, и выходило
        // 12 сегментов на 37 с речи. Энкодер берёт фиксированные ~6.5 с на любой
        // вход, поэтому 12 сегментов — это 120 с вместо ~16 с: цена не зависит
        // от длины, и резать надо как можно позже.
        val rng = Random(21)
        val pattern = listOf(2.4, 1.6, 2.8, 3.5)
        val parts = ArrayList<FloatArray>()
        repeat(3) {
            for (d in pattern) {
                parts += speech(rng, d)
                parts += noise(rng, 0.61)
            }
        }
        val clip = concat(*parts.toTypedArray())

        val segs = seg.split(clip)
        assertEquals(
            "37с речи с паузами 0.61с должны дать ровно 2 сегмента (только разрез по 28с)",
            2,
            segs.size,
        )
        for (s in segs) {
            assertTrue(
                "сегмент не должен превышать лимит энкодера 28с",
                s.samples.size <= 28 * 16_000 + 16_000,
            )
        }

        // Регрессия в обе стороны: на старом пороге дробление обязано вернуться,
        // иначе тест выше проходит из-за сломанной сегментации, а не из-за правки.
        val old = SpeechSegmenter(16_000, splitGapMs = 450L).split(clip)
        assertTrue(
            "старый порог 450мс должен давать много сегментов, получено ${old.size}",
            old.size >= 8,
        )
    }

    @Test
    fun `старт не длиннее 28с + pre-roll`() {
        val rng = Random(13)
        val clip = concat(noise(rng, 1.0), speech(rng, 1.5))
        val segs = seg.split(clip)
        assertEquals(1, segs.size)
        assertTrue("pre-roll не длиннее 150мс + кадр", segs[0].startMs < 1_000 + 200)
    }

    /**
     * Частота вне диапазона должна падать на конструкторе, а не делением на ноль в `split()`.
     *
     * Проверяется и то, что обычные частоты проходят: guard не должен отвергать 8 и 48 кГц,
     * иначе это уже не страховка, а запрет.
     */
    @Test
    fun `частота вне диапазона отвергается на конструкторе`() {
        for (bad in listOf(0, 1, 4_000, 96_000, -16_000)) {
            val thrown =
                runCatching { SpeechSegmenter(bad) }.exceptionOrNull()
            assertTrue("sampleRate=$bad должен быть отвергнут", thrown is IllegalArgumentException)
        }
    }

    @Test
    fun `обычные частоты проходят`() {
        for (good in listOf(8_000, 16_000, 22_050, 44_100, 48_000)) {
            assertTrue("sampleRate=$good должен приниматься", runCatching { SpeechSegmenter(good) }.isSuccess)
        }
    }
}
