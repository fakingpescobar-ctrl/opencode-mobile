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

    /**
     * Движок, который реально поднялся, и разница с тем, что выбрал юзер.
     *
     * Раньше откат был предсказанием по наличию ключа и файлов модели. Это врало:
     * битая или недокачанная модель проходит `isReady` (длины файлов > 0), а
     * `SupertonicTts.loadOrNull` затем возвращает null. Настоящая причина отката
     * известна только тому, кто грузил движок, поэтому её и возвращает он.
     */
    data class Resolved(
        val synth: SpeechSynth?,
        /** Что озвучит на деле — может отличаться от настроек. */
        val effective: TtsEngine,
        /** null — озвучка идёт выбранным движком. Иначе — что и почему подменили. */
        val reason: String?,
    )

    /**
     * Локальный движок: Supertonic, если модель читается, иначе системный TTS.
     *
     * Нужен и для выбора движка, и как страховка облака, поэтому вынесен в
     * отдельный метод: раньше логика жила внутри when и её нельзя было
     * переиспользовать второму месту.
     */
    fun local(
        context: Context,
        cfg: TtsConfig,
    ): Resolved {
        val engine = SupertonicTts.loadOrNull(cfg.modelDir(context))
        if (engine != null) {
            val synth = object : SpeechSynth {
                override val sampleRate: Int get() = engine.sampleRate

                override fun synthesize(
                    text: String,
                    sid: Int,
                    speed: Float,
                ) = engine.synthesize(text, sid, speed)
            }
            return Resolved(synth, TtsEngine.Sherpa, null)
        }
        // Причина формулируется по факту загрузки, а не по isReady: сюда попадает
        // и «модели нет», и «модель есть, но не читается».
        val cause = if (cfg.modelInstalled(context)) "не читается" else "не скачана"
        return Resolved(
            SystemSpeechSynth(context),
            TtsEngine.System,
            "Supertonic $cause — читает системный голос Android",
        )
    }

    /** Движок по настройкам; synth = null означает озвучку выключена ([TtsEngine.Off]). */
    fun build(
        context: Context,
        cfg: TtsConfig,
    ): Resolved =
        when (cfg.engine) {
            TtsEngine.Off -> Resolved(null, TtsEngine.Off, null)
            TtsEngine.System -> Resolved(SystemSpeechSynth(context), TtsEngine.System, null)
            TtsEngine.Sherpa -> local(context, cfg)
            TtsEngine.ElevenLabs -> cloud(context, cfg)
        }

    private fun cloud(
        context: Context,
        cfg: TtsConfig,
    ): Resolved {
        // Ключ проверяем именно load(), а не hasKey: hasKey смотрит только наличие
        // блоба в prefs и врёт, когда блоб есть, а расшифровать его нечем
        // (потерянный AndroidKeyStore после сброса экрана блокировки).
        val key = ElevenLabsSecret.load(context)
        if (key == null) {
            // Ключ не введён — это ошибка настройки, а не повод оставить юзера без
            // озвучки: уходим на локальный движок и говорим об этом и в лог, и в UI.
            val local = local(context, cfg)
            val reason = "API-ключ ElevenLabs не задан или не читается — читает " +
                if (local.effective == TtsEngine.Sherpa) {
                    "локальный движок Supertonic"
                } else {
                    "системный голос Android"
                }
            Log.w(TAG, "откат движка: $reason")
            return Resolved(local.synth, local.effective, reason)
        }
        val cloud = ElevenLabsTts(
            context = context,
            apiKey = key,
            voiceId = cfg.elevenVoice,
            modelId = cfg.elevenModel,
            // Фабрика, а не готовый движок: облако поднимает локальный только если
            // сеть реально не ответила. Заранее поднимать Supertonic нельзя — это
            // 139 МБ и ~2 с старта ради неиспользуемой страховки.
            fallbackFactory = { local(context, cfg).synth },
        )
        // reason = null: облако поднялось. Откат на локальный при сетевой ошибке
        // случается позже, во время синтеза, и про него ElevenLabsTts пишет в лог
        // сам (markNetworkDown) - на момент сборки движка этого ещё не известно,
        // и врать в UI было бы хуже молчания.
        return Resolved(cloud, TtsEngine.ElevenLabs, null)
    }
}
