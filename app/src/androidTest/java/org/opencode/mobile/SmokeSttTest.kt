package org.opencode.mobile

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispercpp.whisper.NcnnWhisperContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencode.mobile.stt.ModelDownloader
import org.opencode.mobile.stt.NcnnModelValidator
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Smoke-тест нативного слоя (PR3): nativeInit -> nativeTranscribe на устройстве.
 *
 * Прогоняет реальный ncnn-движок (libncnnwhisper.so): инициализацию модели из
 * models/ncnn-turbo/ и один вызов транскрипции синуса. Не проверяет качество
 * распознавания — только что оба JNI-вызова проходят без краша.
 *
 * Если ncnn-turbo модель не доставлена на устройство (models/ncnn-turbo/ пуст) —
 * тест ПРОПУСКАЕТСЯ (assumeTrue), а не падает: smoke не должен блокировать CI.
 *
 * Запуск: устройство/эмулятор по adb + `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class SmokeSttTest {
    @Test
    fun nativeInitAndTranscribe() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ModelDownloader.modelsDir(app), "ncnn-turbo")
        val check = NcnnModelValidator.checkModelDir(dir, "whisper_turbo")
        assumeTrue("ncnn-turbo модель не доставлена ($dir): missing=${check.missing}", check.ok)

        val ctx = NcnnWhisperContext.createFromFilesDir(dir, "whisper_turbo")
        val text = runBlocking { ctx.transcribeData(sineWave(SAMPLE_RATE, 0.5), "ru") }

        assertFalse("nativeTranscribe вернул ошибку движка: $text", text.startsWith("ОШИБКА NCNN"))
        Log.i(TAG, "smoke OK: init+transcribe -> «${text.take(60)}»")
    }

/** Короткий синус 440 Hz — достаточный smoke-сигнал для Whisper. */
    private fun sineWave(
        sampleRate: Int,
        seconds: Double,
    ): FloatArray {
        val n = (sampleRate * seconds).toInt()
        val out = FloatArray(n)
        for (i in out.indices) {
            out[i] = (AMPLITUDE * sin(2.0 * PI * FREQ_HZ * i / sampleRate)).toFloat()
        }
        return out
    }

    private companion object {
        const val TAG = "SmokeStt"
        const val SAMPLE_RATE = 16_000
        const val FREQ_HZ = 440.0
        const val AMPLITUDE = 0.3
    }
}
