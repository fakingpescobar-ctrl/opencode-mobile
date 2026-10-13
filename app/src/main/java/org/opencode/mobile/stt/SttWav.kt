package org.opencode.mobile.stt

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Минимальный разбор WAV для отладочного прогона распознавания.
 *
 * Отдельный файл и локальные `@Suppress` не из вкуса: разбор заголовка RIFF
 * по определению ветвистый (неизвестные чанки надо пропустить, формат надо
 * проверить, данные могут быть короче заявленного), и в один компактный
 * экран это не влезает без искусственных конструкций. Проверки отсюда
 * уехали в отдельные функции, поэтому осталось три «неразборчивых» места.
 */
@Suppress("ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth", "MagicNumber")
internal object SttWav {
    const val RATE = 16_000
    const val CHANNELS = 1
    const val BITS = 16

    private const val HEADER_BYTES = 12
    private const val FMT_CHUNK_BYTES = 16
    private const val PCM_FORMAT = 1
    private const val U16_MASK = 0xFFFF
    private const val SHORT_MAX = 32_768f
    private const val BYTES_PER_SAMPLE = 2

    /** Что нашли в заголовке: где лежат данные и какие у них параметры. */
    data class Header(
        val rate: Int,
        val channels: Int,
        val bits: Int,
        val dataAt: Int,
        val dataLen: Int,
    )

    /**
     * Отдаёт сэмплы в том же виде, что и микрофон: FloatArray, 16 кГц, моно,
     * значения в [-1, 1]. Формат не тот — null, а не мусор: молчаливый
     * неверный результат хуже явной ошибки.
     */

    /** Заголовок WAV без декодирования сэмплов — чтобы описать файл в логе,
     *  даже когда он не в том формате, который нам нужен. */
    fun headerOf(bytes: ByteArray): Header? = header(bytes)

    fun samples(bytes: ByteArray): FloatArray? {
        val head = header(bytes) ?: return null
        if (head.rate != RATE || head.channels != CHANNELS || head.bits != BITS) {
            return null
        }
        val count = head.dataLen / BYTES_PER_SAMPLE
        if (count <= 0) return null
        val pcm = ByteBuffer.wrap(bytes, head.dataAt, head.dataLen).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count) { pcm.short / SHORT_MAX }
    }

    /** Человекочитаемое описание того, что не так — для строки в логе. */
    fun describe(head: Header?): String =
        if (head == null) {
            "not a RIFF/WAVE file"
        } else {
            "${head.rate}Hz ch=${head.channels} bits=${head.bits} (need $RATE/$CHANNELS/$BITS)"
        }

    // ---- заголовок ----

    private fun header(bytes: ByteArray): Header? {
        if (bytes.size < HEADER_BYTES) return null
        if (String(bytes, 0, 4) != "RIFF") return null
        if (String(bytes, 8, 4) != "WAVE") return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = HEADER_BYTES
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            if (id == "data") return Header(0, 0, 0, body, minOf(size, bytes.size - body))
            if (id == "fmt ") {
                val found = fmt(bytes, buf, size, body) ?: return null
                pos = nextChunk(body, size)
                return dataChunk(bytes, buf, pos, found)
            }
            pos = nextChunk(body, size)
        }
        return null
    }

    private data class Fmt(
        val rate: Int,
        val channels: Int,
        val bits: Int,
    )

    private fun fmt(
        bytes: ByteArray,
        buf: ByteBuffer,
        size: Int,
        body: Int,
    ): Fmt? {
        if (size < FMT_CHUNK_BYTES) return null
        if (body + FMT_CHUNK_BYTES > bytes.size) return null
        val format = buf.getShort(body).toInt() and U16_MASK
        if (format != PCM_FORMAT) return null
        val channels = buf.getShort(body + 2).toInt() and U16_MASK
        val rate = buf.getInt(body + 4)
        val bits = buf.getShort(body + 14).toInt() and U16_MASK
        return Fmt(rate, channels, bits)
    }

    private fun dataChunk(
        bytes: ByteArray,
        buf: ByteBuffer,
        from: Int,
        fmt: Fmt,
    ): Header? {
        var pos = from
        while (pos + 8 <= bytes.size) {
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            if (String(bytes, pos, 4) == "data") {
                return Header(fmt.rate, fmt.channels, fmt.bits, body, minOf(size, bytes.size - body))
            }
            pos = nextChunk(body, size)
        }
        return null
    }

    /** Чанки RIFF выровнены по чётной границе — после нечётного размера один байт пропуск. */
    private fun nextChunk(
        body: Int,
        size: Int,
    ): Int = body + size + (size and 1)
}
