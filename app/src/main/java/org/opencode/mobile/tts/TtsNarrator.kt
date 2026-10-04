package org.opencode.mobile.tts

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * Мост между лентой чата и озвучкой: принимает текст assistant-сообщения
 * (наш чат приходит поллингом, не SSE) и раскладывает его в очередь синтеза.
 *
 * Наружу торчат три вызова: [onAssistantText], [stop] и [release]. Вся механика
 * — дифф по префиксу, чанкер, движок, плеер — внутри.
 */
object TtsNarrator {
    private const val TAG = "TTS"

    private var config = TtsConfig(TtsEngine.off, TtsConfig.DEFAULT_MODEL, 0, 1.0f)
    private var speaker: TtsSpeaker? = null
    private var chunker = SentenceChunker()
    private var consumedPrefix = ""
    private var loadedFor = ""

    /**
     * Текст, который пришёл, пока движок грузился: пара «текст, ответ дописан».
     *
     * Держим ровно один — последний. Поток ответов в чате быстрее загрузки модели,
     * так что очередь из текстов только размножила бы озвучку устаревших кусков.
     */
    private var deferred: Pair<String, Boolean>? = null

    private val loader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tts-loader").apply { isDaemon = true }
    }

    /** Включён ли выключатель озвучки. */
    fun isEnabled(context: Context): Boolean = TtsConfig.read(context).isEnabled

    /** Переключатель из UI. */
    fun setEnabled(context: Context, enabled: Boolean) {
        val current = TtsConfig.read(context)
        if (enabled == current.isEnabled) return
        val next = current.copy(
            engine = if (enabled && current.modelInstalled(context)) {
                TtsEngine.sherpa
            } else if (enabled) {
                Log.w(TAG, "модель не установлена, берём системный TTS")
                TtsEngine.system
            } else {
                TtsEngine.off
            },
        )
        TtsConfig.save(context, next)
        if (!enabled) stop()
        ensureSpeaker(context, next)
    }

    /**
     * Текст assistant-сообщения. [isFinal] — ответ дописан, можно отдавать остаток буфера.
     */
    fun onAssistantText(context: Context, text: String, isFinal: Boolean) {
        val cfg = TtsConfig.read(context)
        if (!cfg.isEnabled) return
        val sp = ensureSpeaker(context, cfg)
        if (sp == null) {
            // Движок грузится в фоне (первый запуск грузит модель — около секунды).
            // Раньше текст здесь просто проглатывался: включил озвучку, отправил вопрос —
            // первый ответ молчал, озвучка начиналась только со второго. Откладываем
            // последний куст текста и доигрываем его сразу после загрузки движка.
            deferred = text to isFinal
            Log.i(TAG, "движок ещё грузится, текст отложен: ${text.length} симв., isFinal=$isFinal")
            return
        }
        speak(sp, cfg, text, isFinal)
    }

    /**
     * Длина общего префикса двух строк.
     *
     * Нужна для случая, когда снапшот ответа перерисовывается целиком: если брать
     * весь текст заново, уже поставленные в очередь куски теряются, а сброс
     * чанкера поднимает поколение и гасит озвучку — на каждый апдейт опроса чата.
     */
    private fun commonPrefixLength(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    private fun speak(sp: TtsSpeaker, cfg: TtsConfig, text: String, isFinal: Boolean) {
        sp.start(cfg.sid, cfg.speechRate)

        val delta =
            when {
                text.startsWith(consumedPrefix) -> text.substring(consumedPrefix.length)
                // Перерисовка целиком: начало то же, отдаём только хвост. Раньше здесь
                // был безусловный сброс — 46 раз за сессию, и 10 сыгранных кусков
                // против 65 синтезированных.
                text.length >= consumedPrefix.length -> {
                    val common = commonPrefixLength(text, consumedPrefix)
                    Log.i(TAG, "текст перезаписан, беру хвост с позиции $common")
                    text.substring(common)
                }
                // Текст стал короче осмотренного — это настоящий откат (новый ответ
                // или правка), сбрасываем чанкер целиком.
                else -> {
                    Log.i(TAG, "текст откатился, сбрасываю чанкер")
                    chunker.reset()
                    sp.stop(fadeOutMs = 60)
                    sp.start(cfg.sid, cfg.speechRate)
                    text
                }
            }
        if (delta.isEmpty() && !isFinal) return
        consumedPrefix = text

        val chunks = if (isFinal) chunker.push(delta) + chunker.flush() else chunker.push(delta)
        val spoken = chunks.filter(::isSpeechable)
        spoken.forEach { sp.enqueue(it) }
        if (spoken.isNotEmpty()) {
            Log.d(TAG, "в очередь ${spoken.size} из ${chunks.size}: ${spoken.first().take(40)}…")
        }
    }

    /**
     * Годятся ли для озвучки предложение.
     *
     * Через ленту идёт не только ответ ассистента, но и текст мобильного агента —
     * с markdown, таблицами и логами. Татамортно такое читать («это: чанки»),
     * поэтому режем по трем признакам: разметка/код, почти нет букв, либо
     * текст состоит в основном из не-буквенных символов (строки логов, таблицы).
     */
    private fun isSpeechable(text: String): Boolean {
        if (text.length < 2) return false
        if (text.contains("```") || text.contains('`') || text.contains('|')) return false
        val letters = text.count { it.isLetter() }
        if (letters < 4) return false
        return letters.toDouble() / text.length >= 0.6
    }

    /** Новый ответ: то, что было сказано, больше не актуально. */
    fun onNewResponse() {
        consumedPrefix = ""
        chunker.reset()
        deferred = null
    }

    /** Стоп озвучки: очередь чистим, играющее гасим. */
    fun stop() {
        consumedPrefix = ""
        chunker.reset()
        deferred = null
        speaker?.stop(fadeOutMs = 120)
    }

    /**
     * Идёт ли сейчас озвучка.
     *
     * Функция, а не свойство: так её зовёт androidTest `SmokeTtsTest`, написанный
     * мобильным агентом против боевого DEX.
     */
    fun isSpeaking(): Boolean = speaker?.isSpeaking == true

    fun release() {
        stop()
        speaker?.release()
        speaker = null
        loadedFor = ""
    }

    /** Движок и спикер живут между ответами: модель грузится один раз, не каждый ответ. */
    private fun ensureSpeaker(context: Context, cfg: TtsConfig): TtsSpeaker? {
        val key = "${cfg.engine.prefValue}:${cfg.model}:${context.filesDir}"
        if (key == loadedFor) return speaker
        // Ключ ставим ДО фоновой загрузки, чтобы два быстрых вызова подряд
        // не запустили две загрузки модели. Но если загрузка провалилась,
        // ключ снимаем — иначе движок больше не попробует подняться.
        loadedFor = key
        loader.execute {
            val synth: SpeechSynth? =
                when (cfg.engine) {
                    TtsEngine.off -> null
                    TtsEngine.system -> SystemSpeechSynth(context)
                    TtsEngine.sherpa ->
                        SupertonicTts.loadOrNull(cfg.modelDir(context))?.let { engine ->
                            object : SpeechSynth {
                                override val sampleRate: Int get() = engine.sampleRate
                                override fun synthesize(text: String, sid: Int, speed: Float) =
                                    engine.synthesize(text, sid, speed)
                            }
                        }
                }
            if (synth == null) {
                Log.w(TAG, "движок ${cfg.engine.label} недоступен, озвучка выключена")
                // Снимаем ключ: следующий вызов попробует снова (пользователь мог
                // переключить движок обратно или скачать модель).
                if (loadedFor == key) loadedFor = ""
                return@execute
            }
            Log.i(TAG, "движок готов: ${cfg.engine.label}")
            speaker?.release()
            speaker = TtsSpeaker(synth)

            // Текст, пришедший во время загрузки, озвучиваем сразу — иначе первый
            // ответ после включения озвучки пропадал бы молча.
            val late = deferred
            if (late != null) {
                deferred = null
                speaker?.let { fresh ->
                    Log.i(TAG, "доигрываю отложенный текст: ${late.first.length} симв.")
                    speak(fresh, cfg, late.first, late.second)
                }
            }
        }
        // При смене движка возвращаем null: тот спикер, что остался в поле, поднят
        // под ПРЕДЫДУЩИЙ ключ, озвучивать им новый движок нельзя. Текст уйдёт в deferred.
        return null
    }
}