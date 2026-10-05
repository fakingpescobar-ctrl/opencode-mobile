package org.opencode.mobile.tts

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Облачный синтез ElevenLabs.
 *
 * ГЛАВНОЕ, что стоит знать про этот класс: доступный `output_format` зависит от
 * тарифа ключа, и API об этом НЕ сообщает заранее — оно отвечает 403 уже на
 * синтезе. Замер 05.10.2026 с телефона:
 *
 *     HTTP 403 {"code":"output_format_not_allowed",
 *       "message":"Output format 'pcm_44100' is only available on the Pro tier and above"}
 *
 * То есть на бесплатном ключе просимый раньше `pcm_44100` недоступен в принципе.
 * Раньше этот отказ уходил в общий fallback-обработчик и озвучка молча пропадала,
 * а ещё раньше (когда редиректы следовали по умолчанию) в PCM попадал HTML
 * страницы про блокировку по странам — и юзер слышал шипение.
 *
 * Решение — перебор [CLOUD_FORMATS] с запоминанием: на старте спрашиваем API тем
 * форматом, который прошёл в прошлый раз (кэш в prefs), а отказ
 * `output_format_not_allowed` трактуем как «этот формат закрыт твоим тарифом»
 * и молча пробуем следующий. Отказы запоминаются, чтобы не жечь лимит перебором
 * на каждой фразе.
 *
 * Сами байты в PCM разбирает [CloudAudio] и [MediaAudioDecoder] — здесь только
 * сеть, тариф и перебор форматов.
 *
 * Отказоустойчивость: при недоступности сети или отказе API предложение уходит в
 * [fallback] (обычно локальный Supertonic) — облако не должно быть единственной
 * причиной молчащей озвучки. Частота сэмплов у локального движка другая, но это
 * безопасно: AudioTrackPlayer.ensureTrack() пересоздаёт трек при смене частоты.
 *
 * Одна сетевая ошибка ставит облако на паузу [NETWORK_COOLDOWN_MS] — иначе каждая
 * фраза оплачивала бы полный connectTimeout до локального движка, и озвучка
 * систематически отставала от текста.
 */
class ElevenLabsTts(
    private val context: Context,
    private val apiKey: String,
    private val voiceId: String,
    private val modelId: String,
    private val fallbackFactory: () -> SpeechSynth?,
) : SpeechSynth {
    /** Константа API: 44100 Гц, PCM 16-bit LE, моно. */
    override val sampleRate: Int = SAMPLE_RATE

    /**
     * Локальный движок поднимается при ПЕРВОМ же отказе облака, а не заранее.
     *
     * Раньше fallback создавался в TtsNarrator до старта спикера, и приложение
     * всегда поднимало Supertonic (139 МБ, ~2 с) даже когда облако отвечало без
     * единой ошибки — платили памятью и задержкой старта за неиспользуемую
     * страховку. by lazy создаёт движок ровно тогда, когда он понадобился.
     */
    private val fallback: SpeechSynth? by lazy { fallbackFactory() }

    private val formats = FormatMemory(context)

    /**
     * Момент, до которого облако считается недоступным (0 = доступно).
     *
     * Зачем: без VPN или при упавшей сети КАЖДАЯ фраза сначала честно ждала
     * connectTimeout (8 с) и только потом уходила в локальный движок. Ответ
     * длиной в целую фразу стыковался — озвучка вставала заметно позже текста.
     * Одна неудача теперь выключает облако на [NETWORK_COOLDOWN_MS], и все
     * следующие фразы идут в локальный движок сразу, без ожидания.
     *
     * Поле живёт в памяти, а не в prefs: перезапуск движка (смена голоса, модели
     * или ключа — они входят в ключ пересоздания в TtsNarrator) честно даёт
     * облаку новую попытку. Это ровно то поведение, которое нужно юзеру после
     * того, как он включил VPN или вставил правильный ключ.
     *
     * Засекается по [SystemClock.elapsedRealtime], а не по wall clock: перевод
     * часов телефона не должен вдруг включить или выключить паузу.
     */
    private var networkDownUntil = 0L

    /** Итог попытки поговорить с облаком одним форматом. */
    private sealed interface CloudResult {
        /** Пришёл настоящий звук. */
        data class Audio(
            val samples: TtsAudio,
        ) : CloudResult

        /** Сеть или API недоступны: облако ставится на паузу, фраза уходит локально. */
        data class Offline(
            val cause: Throwable,
        ) : CloudResult

        /** Этот формат не подошёл (тайриф или пустой ответ). Ждём следующий кандидат. */
        data class Failed(
            val reason: String,
        ) : CloudResult
    }

    /**
     * Почему облаку НЕЛЬЗЯ отвечать прямо сейчас — без попытки и без ожидания.
     *
     * Голос пустой — это настройка, а не сеть: ждать тут нечего, и пробовать
     * нечего тоже. Иначе URL ушёл бы на корневую папку API и вернул бы JSON со
     * списком голосов, который мы бы честно, но бесполезно декодировали.
     */
    private fun cloudBlockedReason(): String? =
        when {
            voiceId.isBlank() -> "не задан Voice ID"
            SystemClock.elapsedRealtime() < networkDownUntil -> "облако на паузе после сетевой ошибки"
            else -> null
        }

    private fun markNetworkDown(cause: Throwable) {
        networkDownUntil = SystemClock.elapsedRealtime() + NETWORK_COOLDOWN_MS
        Log.w(
            TAG,
            "сеть/облако недоступно (${cause.javaClass.simpleName}: ${cause.message}) — " +
                "локальный движок следующие ${NETWORK_COOLDOWN_MS / MS_PER_SECOND}с без повторной попытки",
        )
    }

    override fun synthesize(
        text: String,
        sid: Int,
        speed: Float,
    ): TtsAudio? = if (text.isBlank()) null else speak(text, sid, speed)

    private fun speak(
        text: String,
        sid: Int,
        speed: Float,
    ): TtsAudio? {
        val blocked = cloudBlockedReason()
        if (blocked != null) {
            Log.d(TAG, "$blocked — беру локальный движок сразу")
            return fallback?.synthesize(text, sid, speed)
        }

        // sid от облака не зависит: голос выбирается voice_id.
        return when (val result = cloudAttempt(text, speed)) {
            is CloudResult.Audio -> result.samples
            is CloudResult.Offline -> {
                markNetworkDown(result.cause)
                Log.w(
                    TAG,
                    "облако не ответило (${result.cause.javaClass.simpleName}: " +
                        "${result.cause.message}) — ухожу в локальный движок",
                )
                fallback?.synthesize(text, sid, speed)
            }
            is CloudResult.Failed -> {
                Log.w(TAG, "${result.reason} — ухожу в локальный движок")
                fallback?.synthesize(text, sid, speed)
            }
        }
    }

    /**
     * Перебор форматов: как только появился не-`Failed` результат, он и есть
     * ответ — дальше сеть не трогаем.
     */
    private fun cloudAttempt(
        text: String,
        speed: Float,
    ): CloudResult {
        val exhausted: CloudResult =
            CloudResult.Failed("ни одного непроверенного формата не осталось")
        return formats.candidates().fold(exhausted) { acc, format ->
            if (acc !is CloudResult.Failed) acc else probeFormat(text, speed, format)
        }
    }

    private fun probeFormat(
        text: String,
        speed: Float,
        format: CloudFormat,
    ): CloudResult =
        try {
            val audio = decode(request(text, speed, format.name), format)
            if (audio.samples.isEmpty()) {
                CloudResult.Failed("облако вернуло пустой звук в формате ${format.name}")
            } else {
                formats.rememberWorking(format.name)
                // Лог с длительностью — главный признак «пришёл настоящий звук, а не
                // мусор»: у mp3 байт намного меньше, чем нужно для такой же речи.
                Log.d(
                    TAG,
                    "${format.name}: ${audio.samples.size} сэмплов @${audio.sampleRate}Гц " +
                        "= ${"%.2f".format(audio.durationSec)}с",
                )
                CloudResult.Audio(audio)
            }
        } catch (e: FormatNotAllowed) {
            // Тариф не даёт этот формат. Не ошибка сети и не лимит — просто идём
            // ниже по списку, запомнив отказ, чтобы не повторять его.
            Log.w(TAG, "формат ${format.name} закрыт тарифом API (${e.message}) — пробую следующий")
            formats.reject(format.name)
            CloudResult.Failed("${format.name}: тариф этого ключа его не даёт")
        } catch (e: IOException) {
            CloudResult.Offline(e)
        } catch (e: IllegalStateException) {
            CloudResult.Offline(e)
        } catch (e: IllegalArgumentException) {
            CloudResult.Offline(e)
        }

    /** Сырой PCM от API или исключение. Ошибки API читаются и логируются. */
    private fun request(
        text: String,
        speed: Float,
        outputFormat: String,
    ): ByteArray {
        val body = JSONObject()
            .put("text", text)
            .put("model_id", modelId)
            .put(
                "voice_settings",
                JSONObject()
                    .put("stability", STABILITY)
                    .put("similarity_boost", SIMILARITY_BOOST)
                    .put("style", STYLE)
                    // У API диапазон 0.7–1.2, а ползунок в приложении даёт 0.5–2.0.
                    // Без клампа API отвечает 400 на значения вне диапазона, и вся
                    // озвучка на «быстром» ползунке молча пропадала бы.
                    .put("speed", speed.coerceIn(MIN_SPEED, MAX_SPEED)),
            ).toString()

        // output_format ЖИВЁТ В QUERY, а не в теле запроса. Отправленный в JSON
        // он молча игнорировался: сервер отвечал 200 дефолтным mp3_44100_128,
        // decodePcm16() разбирал mp3-байты как сэмплы — и юзер слышал шипение.
        //
        // Voice ID приходит из поля ввода, поэтому кодируется: незакодированный
        // «?», «#» или «/» в значении разорвал бы путь и увёл бы запрос не туда
        // — на другой эндпойнт, который ответит 200 JSON-ом, и мы бы честно
        // сообщили «json вместо аудио» вместо правды про битый Voice ID.
        // outputFormat приходит из нашего же списка констант, но кодируется
        // тоже: правило «в URL только закодированное» не должно зависеть от того,
        // кто именно подставил строку.
        val url = "$BASE_URL/${Uri.encode(voiceId)}?output_format=${Uri.encode(outputFormat)}"
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("xi-api-key", apiKey)
            conn.setRequestProperty("Content-Type", "application/json")
            // Редиректы НЕ следуем. Именно на этом всё и ломалось: api.elevenlabs.io
            // из РФ отдаёт 302 на help-страницу про блокировку по странам, а
            // HttpURLConnection по умолчанию послушно за ней идёт — и мы получали
            // «HTTP 200» с HTML вместо голоса. Не следуя редирект мы видим сам
            // код ответа и говорим в лог ПРАВДУ («геоблок»), а не «шипение».
            conn.instanceFollowRedirects = false

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code in HTTP_REDIRECT_MIN..HTTP_REDIRECT_MAX) {
                error(
                    "HTTP $code -> ${conn.getHeaderField("Location").orEmpty()}" +
                        ", скорее всего геоблокировка по IP, нужен VPN",
                )
            }
            if (code != HTTP_OK) throw readApiError(conn, code, outputFormat)
            return conn.inputStream.readBytes()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Тело ошибки API читается целиком — по нему видно, это 401 (прописные
     * права), 429 (квота/лимит) или закрытый тарифом формат, и лечатся они
     * по-разному.
     *
     * Отказ по формату уходит на перебор следующего кандидата, всё остальное —
     * как ошибка API: облако ставится на паузу, фраза идёт в локальный движок.
     */
    private fun readApiError(
        conn: HttpURLConnection,
        code: Int,
        outputFormat: String,
    ): Exception {
        val body = conn.errorStream
            ?.readBytes()
            ?.decodeToString()
            .orEmpty()
        Log.w(TAG, "API вернул HTTP $code: ${body.take(LOG_BODY_LIMIT)}")
        val detail = body.errorDetail()
        return if (body.looksLikeFormatRefusal()) {
            FormatNotAllowed("$outputFormat недоступен этому ключу: $detail")
        } else {
            IllegalStateException("ElevenLabs HTTP $code: $detail")
        }
    }

    /**
     * Байты ответа -> готовый звук для [AudioTrackPlayer].
     *
     * Для PCM это просто разбор сэмплов. Для сжатого формата — проверка, что
     * ответ действительно не мусор, и полный цикл MediaCodec-декодирования.
     */
    private fun decode(
        raw: ByteArray,
        format: CloudFormat,
    ): TtsAudio {
        val container = CloudAudio.containerOf(raw)
        // Для сжатого формата mp3 в containerOf() — ожидаемая сигнатура, а не
        // аномалия. Но HTML и JSON не бывают «допустимыми» ни для какого формата.
        check(container == null || format.pcm || !CloudAudio.isRefusal(container)) {
            "ответ в формате $container, а не ${format.name}"
        }
        return if (format.pcm) {
            CloudAudio.decodePcm16(raw, format.rate)
        } else {
            MediaAudioDecoder.decode(context.cacheDir, raw)
        }
    }

    /** Формат API: сжатый или PCM, и какой частоты сэмплы придут. */
    private class CloudFormat(
        val name: String,
        val rate: Int,
        val pcm: Boolean,
    )

    /** API отказал именно в этом формате: тариф его не включает. */
    private class FormatNotAllowed(
        message: String,
    ) : Exception(message)

    /**
     * Что помним между фразами: какой формат уже отвергнут этим ключом и какой
     * реально прошёл.
     *
     * Отдельный класс, а не пара полей у движка: все три операции (выбрать
     * кандидатов, запомнить отказ, запомнить успех) — это работа с одним и тем же
     * состоянием в prefs, и в одном классе с сетью они только мешали.
     */
    private class FormatMemory(
        context: Context,
    ) {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** Форматы, которые API уже отвергло на этом ключе. Не пробуем их снова. */
        private val rejected: MutableSet<String> =
            prefs
                .getString(PREF_REJECTED, "")
                .orEmpty()
                .split(PREF_SEP)
                .filter { it.isNotBlank() }
                .toMutableSet()

        /** Формат, который прошёл в прошлый раз: на нём начинаем, чтобы не перебирать. */
        private var preferred: String? =
            prefs.getString(PREF_PREFERRED, "").orEmpty().ifEmpty { null }

        /** Кэшированный рабочий формат первым, дальше — ещё не отвергнутые тарифом. */
        fun candidates(): List<CloudFormat> {
            val first = CLOUD_FORMATS.firstOrNull { it.name == preferred }
            val rest = CLOUD_FORMATS.filter { it.name != preferred && it.name !in rejected }
            return listOfNotNull(first) + rest
        }

        fun reject(name: String) {
            rejected += name
            if (preferred == name) preferred = null
            write(PREF_REJECTED, rejected.joinToString(PREF_SEP))
            write(PREF_PREFERRED, preferred.orEmpty())
        }

        fun rememberWorking(name: String) {
            if (preferred == name) return
            preferred = name
            write(PREF_PREFERRED, name)
            Log.i(TAG, "формат $name доступен этому ключу, запомнил для следующих фраз")
        }

        private fun write(
            key: String,
            value: String,
        ) {
            prefs.edit().putString(key, value).apply()
        }
    }

    private companion object {
        const val TAG = "ElevenLabs"
        const val BASE_URL = "https://api.elevenlabs.io/v1/text-to-speech"
        const val HTTP_OK = 200
        const val SAMPLE_RATE = 44100
        const val HTTP_REDIRECT_MIN = 300
        const val HTTP_REDIRECT_MAX = 399
        const val MS_PER_SECOND = 1000
        const val LOG_BODY_LIMIT = 300
        const val PREFS = "elevenlabs_tts"
        const val PREF_PREFERRED = "output_format"
        const val PREF_REJECTED = "rejected_formats"
        const val PREF_SEP = ","

        // Порядок важен: PCM не требует декодирования и потому первый.
        // Замер 05.10.2026 уточнил картину: на бесплатном ключе закрыт
        // только pcm_44100 («Pro tier and above»), а pcm_24000 отвечает 200.
        // mp3 лежит последним и остаётся страховкой на случай, если ElevenLabs
        // закроет и 24k — до него доходим лишь когда отвергнуты все PCM.
        val CLOUD_FORMATS = listOf(
            CloudFormat("pcm_44100", 44100, pcm = true),
            CloudFormat("pcm_24000", 24000, pcm = true),
            CloudFormat("pcm_22050", 22050, pcm = true),
            CloudFormat("pcm_16000", 16000, pcm = true),
            CloudFormat("mp3_44100_128", 44100, pcm = false),
            CloudFormat("mp3_44100_64", 44100, pcm = false),
        )

        // Замеры 05.10.2026: до 2.7 с на 1035 символов. Отдаём запас, но не
        const val CONNECT_TIMEOUT_MS = 8000
        const val READ_TIMEOUT_MS = 25000

        /**
         * Пауза после сетевой ошибки, 60 с.
         *
         * 60 с — с запасом больше типичной паузы между фразами в чате: к тому
         * моменту, когда юзер успел включить VPN или разобраться с сетью, пауза
         * уже и так истекла сама. Намеренно НЕ длинная: движок переживает смену
         * ключа, голоса и модели, так что восстановление связи не откладывается
         * до перезапуска приложения.
         */
        const val NETWORK_COOLDOWN_MS = 60_000L
        const val STABILITY = 0.5f
        const val SIMILARITY_BOOST = 0.75f
        const val STYLE = 0.0f
        const val MIN_SPEED = 0.7f
        const val MAX_SPEED = 1.2f
    }
}

/**
 * Тело ошибки API говорит, закрыт ли тарифом именно формат вывода.
 *
 * Отличать это важно: 401, 429 и 5xx лечатся ключом, лимитом и повтором, а
 * отказ по формату — единственный случай, когда правильный ответ не «ждать»,
 * а «пробовать другой формат».
 */
private fun String.looksLikeFormatRefusal(): Boolean {
    if (isBlank()) return false
    return contains(CODE_FORMAT_REFUSED) ||
        contains("Output format") ||
        contains(MSG_SUBSCRIPTION)
}

/** Человеческая причина отказа из JSON-тела, чтобы лог был полезным. */
private fun String.errorDetail(): String =
    try {
        val detail = JSONObject(this).opt("detail")
        when (detail) {
            is JSONObject -> detail.optString("message").ifEmpty { detail.toString() }
            is String -> detail
            else -> this
        }
    } catch (_: Throwable) {
        this
    }.take(ERROR_DETAIL_LIMIT)

private const val CODE_FORMAT_REFUSED = "output_format_not_allowed"
private const val MSG_SUBSCRIPTION = "Pro tier"
private const val ERROR_DETAIL_LIMIT = 200
