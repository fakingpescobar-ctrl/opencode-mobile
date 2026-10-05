package org.opencode.mobile.tts

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.InputStream
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
 * Решение — [CloudFormat]: на старте спрашиваем API тем форматом, который
 * прошёл в прошлый раз (кэш в prefs), а отказ `output_format_not_allowed`
 * трактуем как «этот формат закрыт твоим тарифом» и молча пробуем следующий.
 * Отказы запоминаются, чтобы не жечь лимит перебором на каждой фразе.
 *
 * Сжатые форматы (mp3) декодируются через MediaExtractor + MediaCodec: на
 * бесплатном тарифе PCM недоступен, а альтернативы нет. Ответ пишется во
 * временный файл кэша, потому что MediaExtractor умеет брать данные только
 * по пути, а не из массива байт.
 *
 * Отказоустойчивость: при недоступности сети или отказе API предложение уходит в
 * [fallback] (обычно локальный Supertonic) — облако не должно быть единственной
 * причиной молчащей озвучки. Частота сэмплов у локального движка другая, но это
 * безопасно: AudioTrackPlayer.ensureTrack() пересоздаёт трек при смене частоты.
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

    /** Форматы, которые API уже отвергло на этом ключе. Не пробуем их снова. */
    private val rejected: MutableSet<String> =
        readPrefs(PREF_REJECTED).split(",").filter { it.isNotBlank() }.toMutableSet()

    /** Формат, который прошёл в прошлый раз: на нём начинаем, чтобы не перебирать. */
    private var preferred: String? = readPrefs(PREF_PREFERRED).ifEmpty { null }

    override fun synthesize(text: String, sid: Int, speed: Float): TtsAudio? {
        if (text.isBlank()) return null

        var lastError: Throwable? = null
        while (true) {
            val format = nextFormat() ?: break
            val audio = try {
                decode(request(text, speed, format.name), format)
            } catch (e: FormatNotAllowed) {
                // Тариф не даёт этот формат. Не ошибка сети и не лимит — просто
                // идём ниже по списку, запомнив отказ, чтобы не повторять его.
                Log.w(TAG, "формат ${format.name} закрыт тарифом API — пробую следующий")
                lastError = e
                reject(format.name)
                continue
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "облако не ответило (${t.javaClass.simpleName}: ${t.message}) — ухожу в локальный движок",
                )
                return fallback?.synthesize(text, sid, speed)
            }

            if (audio.samples.isEmpty()) {
                Log.w(TAG, "облако вернуло пустой звук в формате ${format.name}")
                return fallback?.synthesize(text, sid, speed)
            }
            // Лог с длительностью — главный признак «пришёл настоящий звук, а не
            // мусор»: у mp3 байт намного меньше, чем нужно для такой же речи.
            rememberWorking(format.name)
            Log.d(
                TAG,
                "${format.name}: ${audio.samples.size} сэмплов @${audio.sampleRate}Гц " +
                    "= ${"%.2f".format(audio.durationSec)}с",
            )
            // sid от облака не зависит: голос выбирается voice_id.
            return audio
        }

        Log.w(
            TAG,
            "ни один формат не доступен этому ключу (${lastError?.message}) — ухожу в локальный движок",
        )
        return fallback?.synthesize(text, sid, speed)
    }

    /** Кэшированный рабочий формат, иначе первый ещё не отвергнутый кандидат. */
    private fun nextFormat(): CloudFormat? {
        CLOUD_FORMATS.forEach { if (it.name == preferred && it.name !in rejected) return it }
        return CLOUD_FORMATS.firstOrNull { it.name !in rejected }
    }

    private fun reject(name: String) {
        rejected += name
        if (preferred == name) preferred = null
        writePrefs(PREF_REJECTED, rejected.joinToString(","))
        writePrefs(PREF_PREFERRED, preferred.orEmpty())
    }

    private fun rememberWorking(name: String) {
        if (preferred == name) return
        preferred = name
        writePrefs(PREF_PREFERRED, name)
        Log.i(TAG, "формат $name доступен этому ключу, запомнил для следующих фраз")
    }

    /** Сырой PCM от API или исключение. Ошибки API читаются и логируются. */
    private fun request(text: String, speed: Float, outputFormat: String): ByteArray {
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
            )
            .toString()

        // output_format ЖИВЁТ В QUERY, а не в теле запроса. Отправленный в JSON
        // он молча игнорировался: сервер отвечал 200 дефолтным mp3_44100_128,
        // decodePcm16() разбирал mp3-байты как сэмплы — и юзер слышал шипение.
        val conn =
            URL("$BASE_URL/$voiceId?output_format=$outputFormat").openConnection() as HttpURLConnection
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
                val loc = conn.getHeaderField("Location").orEmpty()
                Log.w(TAG, "HTTP $code, редирект на $loc — скорее всего геоблокировка по IP, нужен VPN")
                throw IllegalStateException("ElevenLabs HTTP $code -> $loc")
            }
            if (code != HTTP_OK) {
                // Тело ошибки полезно: по нему видно, это 401 (прописные права),
                // 429 (квота/лимит) или закрытый тарифом формат — и чинить их
                // по-разному.
                val err = conn.errorStream?.let { readAll(it) }?.decodeToString().orEmpty()
                Log.w(TAG, "API вернул HTTP $code: ${err.take(300)}")
                if (err.looksLikeFormatRefusal()) {
                    throw FormatNotAllowed("$outputFormat: ${err.errorDetail()}")
                }
                throw IllegalStateException("ElevenLabs HTTP $code")
            }
            return conn.inputStream.use { readAll(it) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Имя ответа, если это НЕ сырой PCM: `html (геоблокировка)`, `mp3`, `ogg`…
     * иначе null.
     *
     * Проверка по первым байтам. Нужна не красоты ради: пользователь обязан
     * узнать из лога, ПОЧЕМУ нет голоса, а не слушать шум.
     *
     * Отдельно проверено 05.10.2026: с телефона из РФ api.elevenlabs.io
     * отвечает 302 на help-страницу про блокировку по странам, а
     * HttpURLConnection редиректы по умолчанию ПОСЛЕДУЕТ. В итоге наш код получал
     * HTTP 200 с ~45 КБ HTML и разбирал его как PCM — юзер слышал шипение.
     */
    private fun detectContainer(b: ByteArray): String? {
        if (b.size < 5) return null
        fun at(i: Int) = b[i].toInt() and 0xFF
        fun starts(s: String) = s.indices.all { at(it) == s[it].code }
        // Редирект на help.elevenlabs.io — самый частый случай: страница начинается
        // с <!DOCTYPE html>.
        if (starts("<!DOC") || starts("<html") || starts("<HTML")) return "html — геоблокировка по IP"
        // JSON-ошибка с кодом 200: {"detail": ...} или {"error": ...}.
        if (starts("{\"") || starts("{\n")) return "json вместо аудио"
        if (at(0) == 0x49 && at(1) == 0x44 && at(2) == 0x33) return "mp3 (ID3)"
        // MPEG-кадр: 11 бит синхро, т.е. байт 0 = 0xFF, старший байт 1 = 0xE0..0xFF.
        if (at(0) == 0xFF && at(1) and 0xE0 == 0xE0) return "mp3"
        if (at(0) == 0x4F && at(1) == 0x67 && at(2) == 0x67 && at(3) == 0x53) return "ogg"
        if (at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46) return "wav"
        if (at(0) == 0x1A && at(1) == 0x45 && at(2) == 0xDF && at(3) == 0xA3) return "webm/matroska"
        return null
    }

    /**
     * Байты ответа -> готовый звук для [AudioTrackPlayer].
     *
     * Для PCM это просто разбор сэмплов. Для сжатого формата — проверка, что
     * ответ действительно не мусор, и полный цикл MediaCodec-декодирования.
     */
    private fun decode(raw: ByteArray, format: CloudFormat): TtsAudio {
        val container = detectContainer(raw)
        // Для сжатого формата mp3 в detectContainer() — ожидаемая сигнатура, а не
        // аномалия. Но HTML и JSON не бывают «допустимым» ни для какого формата.
        val unexpected = container != null && (format.pcm || container.startsWith("html") ||
            container.startsWith("json"))
        if (unexpected) {
            throw IllegalStateException("ответ в формате $container, а не ${format.name}")
        }
        return if (format.pcm) decodePcm16(raw, format.rate) else decodeCompressed(raw)
    }

    /** PCM 16-bit LE -> FloatArray [-1..1], как ждёт [TtsAudio]. */
    private fun decodePcm16(pcm: ByteArray, rate: Int): TtsAudio {
        // Нечётная длина означает битый ответ: последний байт игнорируем,
        // но обрезаем ДО цикла, иначе получим выход за границу.
        val count = pcm.size / 2
        val out = FloatArray(count)
        var i = 0
        while (i < count) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768f
            i++
        }
        return TtsAudio(out, rate)
    }

    /**
     * mp3/ogg -> PCM через штатные кодеки Android.
     *
     * MediaExtractor берёт источник только по пути файла, поэтому ответ сначала
     * ложится в кэш, а потом удаляется. Кадры склеиваем в один FloatArray с
     * прямой перечиткой байт: буфер MediaCodec отдаётся в big-endian, а PCM
     * внутри little-endian, поэтому asShortBuffer() переставил бы сэмплы местами.
     */
    private fun decodeCompressed(raw: ByteArray): TtsAudio {
        val tmp = File(context.cacheDir, "elevenlabs-response")
        try {
            tmp.writeBytes(raw)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(tmp.absolutePath)
                var track = -1
                for (i in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        track = i
                        break
                    }
                }
                if (track < 0) throw IllegalStateException("в ответе нет аудиодорожки")
                extractor.selectTrack(track)
                val inputFormat = extractor.getTrackFormat(track)
                val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                    ?: throw IllegalStateException("у аудиодорожки нет MIME")
                val rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                return decodeTrack(extractor, track, mime, rate)
            } finally {
                extractor.release()
            }
        } finally {
            tmp.delete()
        }
    }

    private fun decodeTrack(
        extractor: MediaExtractor,
        track: Int,
        mime: String,
        rate: Int,
    ): TtsAudio {
        val codec = MediaCodec.createDecoderByType(mime)
        var chunks: MutableList<FloatArray>? = null
        var total = 0
        val info = MediaCodec.BufferInfo()
        try {
            codec.configure(extractor.getTrackFormat(track), null, null, 0)
            codec.start()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        if (buffer == null) throw IllegalStateException("кодек не дал входной буфер")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val bytes = ByteArray(info.size)
                        codec.getOutputBuffer(outIndex)?.let {
                            it.position(info.offset)
                            it.get(bytes)
                        }
                        val samples = leToFloat(bytes)
                        if (chunks == null) chunks = ArrayList()
                        chunks.add(samples)
                        total += samples.size
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }

        if (total == 0) throw IllegalStateException("декодер вернул ни одного сэмпла")
        val out = FloatArray(total)
        var offset = 0
        chunks?.forEach { chunk ->
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return TtsAudio(out, rate)
    }

    /** MediaCodec отдаёт PCM в little-endian: младший байт идёт первым. */
    private fun leToFloat(bytes: ByteArray): FloatArray {
        val count = bytes.size / 2
        val out = FloatArray(count)
        var i = 0
        while (i < count) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768f
            i++
        }
        return out
    }

    private fun readPrefs(key: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "").orEmpty()

    private fun writePrefs(key: String, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }

    private fun readAll(stream: InputStream): ByteArray = stream.readBytes()

    /**
     * Тело ошибки API говорит, закрыт ли тарифом именно формат вывода.
     *
     * Отличать это важно: 401, 429 и 5xx лечатся ключом, лимитом и повтором, а
     * отказ по формату — единственный случай, когда правильный ответ не «ждать»,
     * а «пробовать другой формат».
     */
    private fun String.looksLikeFormatRefusal(): Boolean {
        if (isBlank()) return false
        return contains(CODE_FORMAT_REFUSED) || contains("Output format") ||
            contains(MSG_SUBSCRIPTION)
    }

    /** Человеческая причина отказа из JSON-тела, чтобы лог был полезным. */
    private fun String.errorDetail(): String = try {
        val detail = JSONObject(this).opt("detail")
        when (detail) {
            is JSONObject -> detail.optString("message").ifEmpty { detail.toString() }
            is String -> detail
            else -> this
        }
    } catch (_: Throwable) {
        this
    }.take(200)

    /** Формат API: сжатый или PCM, и какой частоты сэмплы придут. */
    private class CloudFormat(val name: String, val rate: Int, val pcm: Boolean)

    /** API отказал именно в этом формате: тариф его не включает. */
    private class FormatNotAllowed(message: String) : Exception(message)

    private companion object {
        const val TAG = "ElevenLabs"
        const val BASE_URL = "https://api.elevenlabs.io/v1/text-to-speech"
        const val HTTP_OK = 200
        const val SAMPLE_RATE = 44100
        const val HTTP_REDIRECT_MIN = 300
        const val HTTP_REDIRECT_MAX = 399
        const val CODEC_TIMEOUT_US = 10_000L
        const val PREFS = "elevenlabs_tts"
        const val PREF_PREFERRED = "output_format"
        const val PREF_REJECTED = "rejected_formats"
        // Маркеры отказа по тарифу в теле ошибки API (замер 05.10.2026):
        // code=output_format_not_allowed, "only available on the Pro tier".
        const val CODE_FORMAT_REFUSED = "output_format_not_allowed"
        const val MSG_SUBSCRIPTION = "Pro tier"

        // Порядок важен: PCM не требует декодирования и потому первый, но на
        // бесплатном тарифе он закрыт — тогда доходим до mp3 и декодируем его.
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
        const val STABILITY = 0.5f
        const val SIMILARITY_BOOST = 0.75f
        const val STYLE = 0.0f
        const val MIN_SPEED = 0.7f
        const val MAX_SPEED = 1.2f
    }
}