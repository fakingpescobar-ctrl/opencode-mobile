package org.opencode.mobile

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencode.mobile.tts.SentenceChunker
import org.opencode.mobile.tts.SupertonicTts
import org.opencode.mobile.tts.TtsModels
import org.opencode.mobile.tts.TtsNarrator

/**
 * Smoke-тест озвучки: синтез и, главное, непрерывность воспроизведения.
 *
 * Закрывает регресс из части 20 брифа: `AudioTrackPlayer.play()` ждал конца каждого
 * куска синхронно через `drain()`, затем делал `pause()` + `flush()`. В логе это давало
 * постоянный таймаут ~121 с на КАЖДЫЙ кусок — на клипах длиной от 1.56 с до 15.64 с.
 * На слух это даёт «озвучка прочитала первое предложение и замолчала».
 *
 * Тест работает только через публичный API (TtsNarrator / SupertonicTts / TtsModels),
 * поэтому проверяет поведение так, как его видит UI, а не внутренности плеера.
 *
 * Тесты ПРОПУСКАЮТСЯ (assumeTrue), а не падают, если на устройстве нет модели Supertonic
 * или нет аудиовыхода: smoke-тест не должен блокировать CI.
 *
 * Запуск: устройство/эмулятор по adb + `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTtsTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetNarrator() {
        // Тесты идут в общем процессе приложения: спикер из прошлого теста должен умереть,
        // иначе isSpeaking() будет отвечать прошлым ходом.
        TtsNarrator.stop()
        TtsNarrator.onNewResponse()
    }

    @After
    fun releaseNarrator() {
        TtsNarrator.stop()
        TtsNarrator.release()
    }

    /**
     * Чанкер не теряет и не переставляет текст.
     *
     * Ловит класс дефектов из §2.4 брифа: когда текст приходит перерисовкой и
     * `consumedPrefix` не совпадает, чанкер сбрасывается и начало фразы теряется.
     * Здесь склеиваем ВСЕ куски — включая остаток из flush() — и сравниваем с исходником.
     */
    @Test
    fun chunkerKeepsTextLosslessAndInOrder() {
        val source = "Первое предложение для проверки. " +
            "Второе предложение, тоже достаточно длинное. " +
            "Третье предложение закрывает тест."
        val chunker = SentenceChunker()

        // Текст приходит порциями, как из потока: куски не должны теряться на стыках.
        val pieces = listOf(
            "Первое предложение ",
            "для проверки. Второе предложение, ",
            "тоже достаточно длинное. Третье предложение ",
            "закрывает тест."
        )

        val out = ArrayList<String>()
        for (piece in pieces) out += chunker.push(piece)
        out += chunker.flush()

        assertTrue("чанкер не выдал ни одного куска при ${pieces.size} порциях", out.isNotEmpty())
        assertEquals(
            "склейка кусков не равна исходному тексту — текст потерян или продублирован",
            source,
            out.joinToString(""),
        )
    }

    /**
     * `reset()` обнуляет невыданный остаток.
     *
     * Отдельный тест, потому что сброс — это ровно тот путь, которым текст терялся в
     * рантайме (§2.4 брифа). Если остаток переживает `reset()`, следующий `push()`
     * склеит его с новым текстом и получится мусор в начале фразы.
     */
    @Test
    fun resetDropsPendingRemainder() {
        val chunker = SentenceChunker()

        // Граница предложения есть, но текст не кончился — кусок не выдаётся целиком.
        chunker.push("Незаконченная мысль без точки в конце")
        assertTrue(
            "тест не воспроизводит условие: остаток должен накопиться",
            chunker.getPendingChars() > 0,
        )

        chunker.reset()

        assertEquals(
            "reset() не обнулил остаток — он склеится со следующим текстом",
            0,
            chunker.getPendingChars(),
        )
    }

    /**
     * Движок синтеза отдаёт реальное аудио, а не тишину нулевой длины.
     *
     * Аналог `SmokeSttTest.nativeInitAndTranscribe`: проверяем прохождение JNI и
     * правдоподобность PCM, не качество голоса.
     */
    @Test
    fun synthesizesAudibleAudio() {
        assumeTrue("ABI не поддерживается", SupertonicTts.isSupportedAbi())
        val dir = TtsModels.dir(ctx, SUPERTONIC_MODEL)
        assumeTrue("модель $SUPERTONIC_MODEL не установлена: $dir", TtsModels.isReady(dir))

        val tts = SupertonicTts.loadOrNull(dir, 0) ?: run {
            SupertonicTts.resetLoadAttempt()
            return assumeTrue("движок не загрузился", false)
        }

        try {
            val audio = tts.synthesize("Проверка синтеза речи.", 0, 1.0f)

            assertTrue("синтез вернул пустое аудио", !audio.isEmpty)
            assertEquals("частота дискретизации поехала", tts.sampleRate, audio.sampleRate)
            assertEquals("частота не 44100", 44100, audio.sampleRate)

            val expected = audio.samples.size.toFloat() / audio.sampleRate
            assertEquals(
                "durationSec не соответствует числу сэмплов",
                expected,
                audio.durationSec,
                0.05f,
            )
            assertTrue(
                "аудио подозрительно короткое: ${audio.durationSec} с",
                audio.durationSec > 0.3f,
            )
            assertTrue(
                "все сэмплы нулевые — движок вернул тишину",
                audio.samples.any { kotlin.math.abs(it) > 0.001f },
            )
        } finally {
            tts.release()
        }
    }

    /**
     * Регрессионный тест на главный дефект: озвучка обязана произнести ВЕСЬ ответ.
     *
     * Считаем реальное время от `onAssistantText` до `isSpeaking() == false` и сравниваем
     * с длиной синтезированного аудио. При сломанном плеере каждый кусок ждёт таймаут
     * `drain()` ~121 с, поэтому на тексте в несколько кусков wall-clock в разы
     * превышает длину аудио. При здоровом плеере они совпадают.
     *
     * Никаких внутренних классов: только то, чем пользуется UI.
     */
    @Test
    fun speaksWholeAnswerWithoutDrainStalls() {
        assumeTrue("ABI не поддерживается", SupertonicTts.isSupportedAbi())
        val dir = TtsModels.dir(ctx, SUPERTONIC_MODEL)
        assumeTrue("модель не установлена: $dir", TtsModels.isReady(dir))

        val text = ANSWER_TEXT
        val chunks = SentenceChunker().run {
            val all = push(text).toMutableList()
            all += flush()
            all
        }
        assumeTrue(
            "нужно минимум два куска, иначе тест ничего не проверяет",
            chunks.size >= 2,
        )

        // Эталон длительности: суммируем синтез всех кусков тем же движком.
        val tts = SupertonicTts.loadOrNull(dir, 0) ?: run {
            SupertonicTts.resetLoadAttempt()
            return assumeTrue("движок не загрузился", false)
        }
        val expectedMs = try {
            chunks.sumOf { tts.synthesize(it, 0, 1.0f).durationSec.toDouble() } * 1000.0
        } finally {
            tts.release()
        }
        Log.i(TAG, "кусков=${chunks.size}, эталон озвучки=${expectedMs.toLong()} мс")

        TtsNarrator.setEnabled(ctx, true)
        assumeTrue("озвучка не включилась в настройках", TtsNarrator.isEnabled(ctx))

        val t0 = SystemClock.elapsedRealtime()
        TtsNarrator.onAssistantText(ctx, text, true)

        // Ждём, пока озвучка действительно начнётся: без аудиовыхода тест неприменим.
        val started = waitUntil(START_TIMEOUT_MS) { TtsNarrator.isSpeaking() }
        assumeTrue("озвучка так и не началась — нет аудиовыхода", started)

        // Бюджет: терпим 4x к эталону плюс запас на старт. Сломанный drain на двух кусках
        // даёт ~240 с при эталоне ~15 с и тест падает.
        val budget = (expectedMs * 4 + START_TIMEOUT_MS).toLong()
        val finished = waitUntil(budget) { !TtsNarrator.isSpeaking() }
        val wall = SystemClock.elapsedRealtime() - t0

        assertTrue(
            "озвучка не закончилась за ${wall} мс при эталоне ${expectedMs.toLong()} мс " +
                "и бюджете $budget мс — вероятен таймаут drain() на кусок",
            finished,
        )
        assertTrue(
            "озвучка шла $wall мс, а аудио длится ${expectedMs.toLong()} мс: " +
                "playbackHeadPosition не идёт, куски копятся и ждут таймаут",
            wall <= expectedMs * 2 + SLACK_MS,
        )
        Log.i(TAG, "озвучка ${wall} мс против эталонных ${expectedMs.toLong()} мс — ок")
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (cond()) return true
            SystemClock.sleep(POLL_MS)
        }
        return cond()
    }

    private companion object {
        const val TAG = "SmokeTts"
        const val SUPERTONIC_MODEL = "supertonic-3-tts-int8"
        const val POLL_MS = 50L
        const val START_TIMEOUT_MS = 60_000L
        const val SLACK_MS = 5_000.0

        /** Заведомо больше maxChars (220), чтобы чанкер дал несколько кусков. */
        const val ANSWER_TEXT =
            "Первое предложение ответа занимает заметное время, чтобы нагрузка на " +
                "движок была достаточной. Второе предложение продолжает текст и " +
                "проверяет, что озвучка не прерывается на границе между кусками. " +
                "Третье предложение доводит ответ до конца, чтобы тест убедился, что " +
                "произнесено действительно всё, а не только начало."
    }
}
