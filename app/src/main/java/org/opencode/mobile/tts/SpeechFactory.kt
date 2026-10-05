package org.opencode.mobile.tts

import android.content.Context
import android.util.Log

/**
 * Выбор движка синтеза по настройкам.
 *
 * Отдельно от [TtsNarrator], потому что это разовая задача «какой движок под
 * настройки юзера», а в TtsNarrator вокруг неё — фоновая загрузка, очередь и
 * дифф по префиксу. Смешанные в одном методе они читались как спагетти.
 */
internal object SpeechFactory {
    private const val TAG = "TTS"

    /**
     * Локальный движок: Supertonic, если модель скачана, иначе системный TTS.
     *
     * Нужен и для выбора движка, и как страховка облака, поэтому вынесен в
     * отдельный метод: раньше логика жила внутри when и её нельзя было
     * переиспользовать второму месту.
     */
    fun local(
        context: Context,
        cfg: TtsConfig,
    ): SpeechSynth =
        SupertonicTts.loadOrNull(cfg.modelDir(context))?.let { engine ->
            object : SpeechSynth {
                override val sampleRate: Int get() = engine.sampleRate

                override fun synthesize(
                    text: String,
                    sid: Int,
                    speed: Float,
                ) = engine.synthesize(text, sid, speed)
            }
        } ?: SystemSpeechSynth(context)

    /** Движок по настройкам; null — озвучка выключена ([TtsEngine.Off]). */
    fun build(
        context: Context,
        cfg: TtsConfig,
    ): SpeechSynth? =
        when (cfg.engine) {
            TtsEngine.Off -> null
            TtsEngine.System -> SystemSpeechSynth(context)
            TtsEngine.Sherpa -> local(context, cfg)
            TtsEngine.ElevenLabs -> cloud(context, cfg)
        }

    private fun cloud(
        context: Context,
        cfg: TtsConfig,
    ): SpeechSynth {
        val key = ElevenLabsSecret.load(context)
        if (key == null) {
            // Ключ не введён — это ошибка настройки, а не повод оставить юзера без
            // озвучки: уходим на локальный движок и говорим об этом в лог.
            Log.w(TAG, "у облака нет API-ключа, беру локальный движок")
            return local(context, cfg)
        }
        return ElevenLabsTts(
            context = context,
            apiKey = key,
            voiceId = cfg.elevenVoice,
            modelId = cfg.elevenModel,
            // Фабрика, а не готовый движок: облако поднимает локальный только если
            // сеть реально не ответила. Заранее поднимать Supertonic нельзя — это
            // 139 МБ и ~2 с старта ради неиспользуемой страховки.
            fallbackFactory = { local(context, cfg) },
        )
    }
}
