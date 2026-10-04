package org.opencode.mobile.tts

import android.content.Context
import android.util.Log
import java.io.File

/** Движок синтеза речи. */
enum class TtsEngine(val prefValue: String, val label: String) {
    off("off", "Выключено"),
    system("system", "Системный"),
    sherpa("sherpa", "Supertonic (offline)"),
    ;

    companion object {
        fun fromPref(value: String?): TtsEngine = entries.firstOrNull { it.prefValue == value } ?: off
    }
}

/**
 * Настройки озвучки. Читаются из prefs "chat_overlay" рядом с stt_engine/stt_model,
 * потому что тот же prefs использует ChatOverlay.
 */
data class TtsConfig(
    val engine: TtsEngine,
    val model: String,
    val sid: Int,
    val speechRate: Float,
) {
    val isEnabled: Boolean get() = engine != TtsEngine.off

    /** Каталог модели внутри filesDir/models/tts/. */
    fun modelDir(context: Context): File = TtsModels.dir(context, model)

    /** Модель скачана и готова к синтезу. */
    fun modelInstalled(context: Context): Boolean = TtsModels.isReady(modelDir(context))

    companion object {
        const val PREFS = "chat_overlay"
        const val KEY_ENGINE = "tts_engine"
        const val KEY_MODEL = "tts_model"
        const val KEY_SID = "tts_sid"
        const val KEY_RATE = "tts_speech_rate"

        const val DEFAULT_MODEL = "supertonic-3-tts-int8"

        /** sid=0 выбрал юзер прослушиванием 03.10.2026, см. bench/2026-10-03-supertonic3-bench-sm8850.md */
        const val DEFAULT_SID = 0

        /** 2 потока: замер даёт RTF 0.32 против 0.58 на 4 и 0.47–0.61 на 8. */
        const val NUM_THREADS = 2

        /**
         * Модель отдаёт 10 голосов (`numSpeakers=10` в логе загрузки), мы слушали
         * только первые пять. Держим потолок по факту, а не по тому, что успели
         * отслушать, чтобы UI не врал про количество голосов.
         */
        const val MAX_SID = 9

        fun read(context: Context): TtsConfig {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return TtsConfig(
                engine = TtsEngine.fromPref(prefs.getString(KEY_ENGINE, TtsEngine.off.prefValue)),
                model = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
                sid = prefs.getInt(KEY_SID, DEFAULT_SID).coerceIn(0, MAX_SID),
                speechRate = prefs.getFloat(KEY_RATE, 1.0f).coerceIn(0.5f, 2.0f),
            )
        }

        fun save(context: Context, config: TtsConfig) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ENGINE, config.engine.prefValue)
                .putString(KEY_MODEL, config.model)
                .putInt(KEY_SID, config.sid)
                .putFloat(KEY_RATE, config.speechRate)
                .apply()
            Log.d(
                "TTS",
                "saved engine=${config.engine.prefValue} model=${config.model} sid=${config.sid} rate=${config.speechRate}",
            )
        }
    }
}

/** Каталоги моделей TTS: filesDir/models/tts/<каталог>/. В APK модели не кладутся. */
object TtsModels {
    const val SUPERTONIC = "supertonic-3-tts-int8"

    /** Файлы, без которых движок не запустится. */
    val SUPERTONIC_FILES = listOf(
        "duration_predictor.int8.onnx",
        "text_encoder.int8.onnx",
        "vector_estimator.int8.onnx",
        "vocoder.int8.onnx",
        "tts.json",
        "unicode_indexer.bin",
        "voice.bin",
    )

    fun root(context: Context): File {
        val dir = File(context.filesDir, "models/tts")
        dir.mkdirs()
        return dir
    }

    fun dir(context: Context, model: String): File = File(root(context), model)

    /** Готова ли модель к работе. */
    fun isReady(dir: File): Boolean = SUPERTONIC_FILES.all { File(dir, it).length() > 0 }

    /** Занято моделями на диске, для диагностики. */
    fun usedBytes(context: Context): Long = root(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun logState(context: Context) {
        val dir = dir(context, SUPERTONIC)
        Log.i("TTS", "models=${dir.absolutePath} ready=${isReady(dir)} used=${usedBytes(context) / 1024 / 1024}MB")
    }
}