package org.opencode.mobile.tts

import android.content.Context
import android.util.Log
import java.io.File

/** Движок синтеза речи. */
enum class TtsEngine(
    val prefValue: String,
    val label: String,
) {
    Off("off", "Выключено"),
    System("system", "Системный"),
    Sherpa("sherpa", "Supertonic (offline)"),

    /**
     * Облако. Требует API-ключа (ElevenLabsSecret) и работает только при
     * доступном api.elevenlabs.io — из РФ хост закрыт, нужен VPN.
     */
    ElevenLabs("elevenlabs", "ElevenLabs (облако)"),
    ;

    /** Движок ходит в сеть — локальная модель ему не нужна. */
    val isCloud: Boolean get() = this == ElevenLabs

    companion object {
        fun fromPref(value: String?): TtsEngine = entries.firstOrNull { it.prefValue == value } ?: Off
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
    /** Писать сырой PCM в файл для проверки озвучки без ушей. */
    val dumpPcm: Boolean = false,
    /** Voice ID облака ElevenLabs (не путать с локальным sid Supertonic). */
    val elevenVoice: String = DEFAULT_ELEVEN_VOICE,
    /** Model ID облака. */
    val elevenModel: String = DEFAULT_ELEVEN_MODEL,
) {
    val isEnabled: Boolean get() = engine != TtsEngine.Off

    /** Нужен ли облаку ключ: без него движок сразу уйдёт в локальный откат. */
    val needsCloudKey: Boolean get() = engine.isCloud

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
        const val KEY_ELEVEN_VOICE = "tts_eleven_voice"
        const val KEY_ELEVEN_MODEL = "tts_eleven_model"

        /** Отладочный ключ: дамп PCM в filesDir/tts-dump.pcm для проверки озвучки. */
        const val KEY_DUMP = "tts_dump_pcm"

        const val DEFAULT_MODEL = "supertonic-3-tts-int8"

        /**
         * Голос и модель облака по умолчанию — те, что проверены живым запросом
         * 05.10.2026 (HTTP 200, валидный PCM). Оба меняются из UI.
         */
        const val DEFAULT_ELEVEN_VOICE = "N2lVS1w4EtoT3dr4eOWO"
        const val DEFAULT_ELEVEN_MODEL = "eleven_flash_v2_5"

        /** sid=0 выбрал юзер прослушиванием 03.10.2026, см. bench/2026-10-03-supertonic3-bench-sm8850.md */
        const val DEFAULT_SID = 0

        /** Границы ползунка скорости речи в UI. */
        const val MIN_SPEECH_RATE = 0.5f
        const val MAX_SPEECH_RATE = 2.0f

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
                engine = TtsEngine.fromPref(prefs.getString(KEY_ENGINE, TtsEngine.Off.prefValue)),
                model = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
                sid = prefs.getInt(KEY_SID, DEFAULT_SID).coerceIn(0, MAX_SID),
                speechRate = prefs
                    .getFloat(KEY_RATE, 1.0f)
                    .coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE),
                dumpPcm = prefs.getBoolean(KEY_DUMP, false),
                elevenVoice = prefs
                    .getString(KEY_ELEVEN_VOICE, DEFAULT_ELEVEN_VOICE)
                    ?.takeIf { it.isNotBlank() } ?: DEFAULT_ELEVEN_VOICE,
                elevenModel = prefs
                    .getString(KEY_ELEVEN_MODEL, DEFAULT_ELEVEN_MODEL)
                    ?.takeIf { it.isNotBlank() } ?: DEFAULT_ELEVEN_MODEL,
            )
        }

        fun save(
            context: Context,
            config: TtsConfig,
        ) {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ENGINE, config.engine.prefValue)
                .putString(KEY_MODEL, config.model)
                .putInt(KEY_SID, config.sid)
                .putFloat(KEY_RATE, config.speechRate)
                .putBoolean(KEY_DUMP, config.dumpPcm)
                .putString(KEY_ELEVEN_VOICE, config.elevenVoice)
                .putString(KEY_ELEVEN_MODEL, config.elevenModel)
                .apply()
            Log.d(
                "TTS",
                "saved engine=${config.engine.prefValue} model=${config.model} " +
                    "sid=${config.sid} rate=${config.speechRate}",
            )
        }
    }
}

/** Каталоги моделей TTS: filesDir/models/tts/<каталог>/. В APK модели не кладутся. */
object TtsModels {
    const val SUPERTONIC = "supertonic-3-tts-int8"

    private const val BYTES_PER_KB = 1024

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

    fun dir(
        context: Context,
        model: String,
    ): File = File(root(context), model)

    /** Готова ли модель к работе. */
    fun isReady(dir: File): Boolean = SUPERTONIC_FILES.all { File(dir, it).length() > 0 }

    /** Занято моделями на диске, для диагностики. */
    fun usedBytes(context: Context): Long = root(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun logState(context: Context) {
        val dir = dir(context, SUPERTONIC)
        val mb = usedBytes(context) / BYTES_PER_KB / BYTES_PER_KB
        Log.i("TTS", "models=${dir.absolutePath} ready=${isReady(dir)} used=${mb}MB")
    }
}
