package org.opencode.mobile.tts

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * Мост между лентой чата и озвучкой: принимает текст assistant-сообщения
 * (наш чат приходит поллингом, не SSE) и раскладывает его в очередь синтеза.
 *
 * Наружу торчат три вызова: [onAssistantText], [stop] и [release]. Вся механика
 * — дифф по префиксу, чанкер, движок, плеер — внутри. Правила «что вообще
 * можно озвучить» живут в [SpeechFilter], выбор движка — в [SpeechFactory].
 */
object TtsNarrator {
    private const val TAG = "TTS"

    /** Сколько символов очередного куска попадает в лог для разбора. */
    private const val LOG_PREVIEW_CHARS = 40

    /** Откат текста: глухое начало, чтобы не было щелчка на стыке предложений. */
    private const val ROLLBACK_FADE_MS = 60

    /** Явная остановка: пользователь нажал «стоп», гасим мягче отката. */
    private const val STOP_FADE_MS = 120

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
    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        val current = TtsConfig.read(context)
        if (enabled == current.isEnabled) return
        val next = current.copy(
            engine = when {
                !enabled -> TtsEngine.Off
                // Облако выбрано осознанно в панели: переключатель вкл/выкл не
                // должен молча уводить юзера на локальный движок. Локальная
                // модель облаку не нужна, проверять её тут нечего.
                current.engine.isCloud -> current.engine
                current.modelInstalled(context) -> TtsEngine.Sherpa
                else -> {
                    Log.w(TAG, "модель не установлена, берём системный TTS")
                    TtsEngine.System
                }
            },
        )
        TtsConfig.save(context, next)
        if (!enabled) stop()
        ensureSpeaker(context, next)
    }

    /**
     * Текст assistant-сообщения. [isFinal] — ответ дописан, можно отдавать остаток буфера.
     */
    fun onAssistantText(
        context: Context,
        text: String,
        isFinal: Boolean,
    ) {
        // Настройки читаем всегда: пустой снапшот (начало thinking-а или tool call)
        // тоже должен выйти молча, а не доезжать до логики отката — раньше он
        // сбрасывал чанкер и гасил речь на каждом таком шаге.
        val cfg = TtsConfig.read(context)
        if (text.isEmpty() || !cfg.isEnabled) return

        val sp = ensureSpeaker(context, cfg)
        if (sp == null) {
            // Движок грузится в фоне (первый запуск грузит модель — около секунды).
            // Раньше текст здесь просто проглатывался: включил озвучку, отправил
            // вопрос — первый ответ молчал, озвучка начиналась только со второго.
            // Откладываем последний куст текста и доигрываем его сразу после
            // загрузки движка.
            deferred = text to isFinal
            Log.i(TAG, "движок ещё грузится, текст отложен: ${text.length} симв., isFinal=$isFinal")
            return
        }
        speak(sp, cfg, text, isFinal)
    }

    private fun speak(
        sp: TtsSpeaker,
        cfg: TtsConfig,
        text: String,
        isFinal: Boolean,
    ) {
        sp.start(cfg.sid, cfg.speechRate)

        val delta = SpeechFilter.delta(text, consumedPrefix)
        if (delta is SpeechFilter.Delta.Rollback) {
            // Текст стал короче осмотренного: это новый ответ или правка, сбрасываем
            // чанкер целиком.
            Log.i(TAG, "текст откатился, сбрасываю чанкер")
            chunker.reset()
            sp.stop(fadeOutMs = ROLLBACK_FADE_MS)
            sp.start(cfg.sid, cfg.speechRate)
        }
        if (delta is SpeechFilter.Delta.Rewritten) {
            Log.i(TAG, "текст перезаписан, беру хвост с позиции ${delta.from}")
        }
        if (delta.text.isEmpty() && !isFinal) return
        consumedPrefix = text

        val chunks = if (isFinal) chunker.push(delta.text) + chunker.flush() else chunker.push(delta.text)
        val spoken = chunks.filter(SpeechFilter::isSpeechable)
        spoken.forEach { sp.enqueue(it) }
        if (spoken.isNotEmpty()) {
            val head = spoken.first().take(LOG_PREVIEW_CHARS)
            Log.d(TAG, "в очередь ${spoken.size} из ${chunks.size}: $head…")
        }
    }

    /**
     * Агент прислал новое сообщение ответа, а не дополнил текущее.
     *
     * Если агент пишет несколько предложений подряд (обычная практика: рассуждал,
     * вызвал tool, заговорил снова), UI раньше брал только последнее сообщение, а
     * приход нового считался откатом: `sp.stop()` с fadeOut 60мс срезал уже начатое
     * предложение, и на слух получались обрывки — с одного предложения на другое.
     *
     * Здесь остаток текущего сообщения доигрывается, чанкер сбрасывается, но
     * играющее НЕ гасится: новое встаёт в очередь сразу после старого.
     */
    fun onNewAssistantMessage() {
        val sp = speaker
        val rest = if (sp == null) emptyList() else chunker.flush().filter(SpeechFilter::isSpeechable)
        if (rest.isNotEmpty()) {
            rest.forEach { sp?.enqueue(it) }
            Log.d(TAG, "перед новым сообщением доигрываю остаток: ${rest.size}")
        }
        chunker.reset()
        consumedPrefix = ""
    }

    /**
     * Новый ответ или явная остановка: очередь чистим, играющее гасим.
     *
     * Раньше рядом стоял ещё [onNewResponse], который делал ровно то же, но без
     * гашения. Оба вызова шли в паре `stop(); onNewResponse()` — то есть второй
     * был пустым, и его легко было забыть, оставив фразу висеть. Один метод
     * делает состояние очереди однозначным.
     */
    fun stop() {
        consumedPrefix = ""
        chunker.reset()
        deferred = null
        speaker?.stop(fadeOutMs = STOP_FADE_MS)
    }

    /**
     * Применить переключатель дампа PCM к ЖИВОЙ озвучке.
     *
     * Дамп живёт в companion-объекте [AudioTrackPlayer] и переживает смену голоса,
     * движка и перезапуск спикера, поэтому переключатель в настройках обязан
     * применять его сам. Раньше флаг читался только при создании движка: тумблер
     * менял prefs, но файл продолжал писаться до перезапуска приложения.
     */
    fun applyDumpPref(context: Context) {
        if (TtsConfig.read(context).dumpPcm) {
            // Не открываем второй файл поверх уже открытого: startDump перезатирает
            // прежний буфер, и начало текущей записи потерялось бы.
            if (AudioTrackPlayer.isDumping()) return
            AudioTrackPlayer.startDump(java.io.File(context.filesDir, "tts-dump.pcm"))
            Log.i(TAG, "дамп PCM включён: ${context.filesDir}/tts-dump.pcm")
        } else {
            if (!AudioTrackPlayer.isDumping()) return
            AudioTrackPlayer.stopDump()
            Log.i(TAG, "дамп PCM выключен")
        }
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
    private fun ensureSpeaker(
        context: Context,
        cfg: TtsConfig,
    ): TtsSpeaker? {
        // Голос и модель облака входят в ключ пересоздания: смена голоса в UI
        // обязана поднимать новый движок, иначе озвучка продолжила бы старым.
        // Плюс ревизия ключа ElevenLabs — иначе ввод нового ключа при том же
        // голосе/модели оставил бы движок со старым ключом (или без него).
        val key = "${cfg.engine.prefValue}:${cfg.model}:${cfg.elevenVoice}:" +
            "${cfg.elevenModel}:${ElevenLabsSecret.revision(context)}:${context.filesDir}"
        if (key == loadedFor) return speaker
        // Ключ ставим ДО фоновой загрузки, чтобы два быстрые вызова подряд
        // не запустили две загрузки модели. Но если загрузка провалилась,
        // ключ снимаем — иначе движок больше не попробует подняться.
        loadedFor = key
        loader.execute {
            val synth = SpeechFactory.build(context, cfg)
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
            applyDumpPref(context)

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
