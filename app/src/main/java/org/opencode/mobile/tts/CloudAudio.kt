package org.opencode.mobile.tts

/**
 * Что именно пришло от облака, и как из этого получить звук.
 *
 * Вынесено из [ElevenLabsTts] не для красоты: там сеть, тариф и перебор
 * форматов, а разбор байтов — отдельная кухня. Смешанные в одном классе они
 * мешали друг другу и раздували методы до нечитаемых.
 *
 * Узнавание контейнера нужно по делу, а не для логов: пользователь обязан
 * понять из лога, ПОЧЕМУ нет голоса. Отдельно проверено 05.10.2026 — с телефона
 * из РФ api.elevenlabs.io отвечает 302 на help-страницу про блокировку по
 * странам, и разбирать такой HTML как PCM значит услышать шипение вместо
 * внятного «нужен VPN».
 */
internal object CloudAudio {
    /** Ответ — не звук: HTML-страница блокировки по IP. */
    const val CONTAINER_HTML = "html — геоблокировка по IP"

    /** Ответ — не звук: JSON с кодом 200 вместо аудио. */
    const val CONTAINER_JSON = "json вместо аудио"

    /** Ответ — не звук: mp3, который мы поймём, но который всё равно придётся декодировать. */
    const val CONTAINER_MP3 = "mp3"

    /** Короткая сигнатура, с которой начинается настоящий сырой PCM. */
    private const val HEADER_MIN = 5
    private const val BYTE_MASK = 0xFF
    private const val BYTES_PER_SAMPLE = 2

    /** MPEG-кадр начинается с 11 бит синхронизации: 0xFF и старший бит 1. */
    private const val MPEG_SYNC = 0xFF
    private const val MPEG_SYNC_MASK = 0xE0

    /** Амплитуда полной шкалы 16-битного PCM — делить на неё, а не на 32767. */
    private const val PCM_FULL_SCALE = 32768f

    /** Сколько бит в байте: младший байт сэмпла сдвигаем на столько. */
    private const val BYTE_BITS = 8

    /**
     * Контейнер, который не бывает «допустимым» ни для одного формата.
     *
     * Для сжатых форматов mp3 в [containerOf] — ожидаемая сигнатура, а не
     * аномалия. А вот HTML и JSON не аудио в любом случае: их видно, чтобы
     * не декодировать мусор и сказать в лог правду.
     */
    fun isRefusal(container: String): Boolean = container == CONTAINER_HTML || container == CONTAINER_JSON

    /**
     * Подписи, которые читаются прямо как текст.
     *
     * Тут же ID3 и «OggS»/«RIFF»: их байты — это ровно эти символы, так что
     * сравнение строками честнее, чем перечисление шестнадцатеричных литералов,
     * из-за которых и сыпались замечания линтера.
     */
    private val TEXT_SIGNATURES = listOf(
        "<!DOC" to CONTAINER_HTML,
        "<html" to CONTAINER_HTML,
        "<HTML" to CONTAINER_HTML,
        "{\"" to CONTAINER_JSON,
        "{\n" to CONTAINER_JSON,
        "ID3" to "$CONTAINER_MP3 (ID3)",
        "OggS" to "ogg",
        "RIFF" to "wav",
    )

    /**
     * Подписи, которые в текст не представимы (matroska/webm).
     *
     * Подавление честное: это байты сигнатуры формата, они и обязаны быть
     * конкретными числами — «магическим» тут нечего, значение задаёт протокол.
     */
    @Suppress("MagicNumber")
    private val BINARY_SIGNATURES = listOf(
        byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) to "webm/matroska",
    )

    /**
     * Имя ответа, если это НЕ сырой PCM: `html — геоблокировка по IP`, `mp3`,
     * `ogg`… иначе null.
     */
    fun containerOf(bytes: ByteArray): String? =
        when {
            bytes.size < HEADER_MIN -> null
            else -> textSignature(bytes) ?: mpegFrameName(bytes) ?: binarySignature(bytes)
        }

    private fun textSignature(bytes: ByteArray): String? = TEXT_SIGNATURES.firstOrNull { (sig, _) -> startsWith(bytes, sig) }?.second

    private fun binarySignature(bytes: ByteArray): String? = BINARY_SIGNATURES.firstOrNull { (sig, _) -> startsWith(bytes, sig) }?.second

    /** У MPEG-кадра битовая подпись, а не фиксированные байты — проверяем маской. */
    private fun mpegFrameName(bytes: ByteArray): String? {
        val sync = bytes[0].toInt() and BYTE_MASK
        val next = bytes[1].toInt() and MPEG_SYNC_MASK
        return if (sync == MPEG_SYNC && next == MPEG_SYNC) CONTAINER_MP3 else null
    }

    private fun startsWith(
        bytes: ByteArray,
        signature: String,
    ): Boolean = signature.indices.all { (bytes[it].toInt() and BYTE_MASK) == signature[it].code }

    private fun startsWith(
        bytes: ByteArray,
        signature: ByteArray,
    ): Boolean = signature.indices.all { bytes[it] == signature[it] }

    /** PCM 16-bit LE -> FloatArray [-1..1], как ждёт [TtsAudio]. */
    fun decodePcm16(
        pcm: ByteArray,
        rate: Int,
    ): TtsAudio {
        // Нечётная длина означает битый ответ: непарный хвостовой байт просто
        // не попадает в счётчик целых сэмплов, без выхода за границу массива.
        return TtsAudio(pcm16LeToFloat(pcm), rate)
    }

    /**
     * MediaCodec отдаёт PCM в little-endian: младший байт идёт первым.
     *
     * Публично, потому что этим же преобразованием разбирается выход кодеков
     * в [MediaAudioDecoder] — две одинаковые копии однажды разъедутся.
     */
    fun pcm16LeToFloat(bytes: ByteArray): FloatArray {
        val count = bytes.size / BYTES_PER_SAMPLE
        val out = FloatArray(count)
        for (i in 0 until count) {
            val lo = bytes[i * BYTES_PER_SAMPLE].toInt() and BYTE_MASK
            val hi = bytes[i * BYTES_PER_SAMPLE + 1].toInt()
            out[i] = ((hi shl BYTE_BITS) or lo).toShort() / PCM_FULL_SCALE
        }
        return out
    }
}
