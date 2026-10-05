package org.opencode.mobile.tts

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * mp3/ogg/ogg-подобное -> PCM через штатные кодеки Android.
 *
 * Это страховка, а не основной путь: замер 05.10.2026 показал, что бесплатный
 * ключ ElevenLabs отвергает только `pcm_44100`, а `pcm_24000` проходит. Но если
 * ElevenLabs закроет и 24k, до mp3 мы дойдём — и он обязан зазвучать, а не
 * выпасть с исключением.
 *
 * MediaExtractor берёт источник только по пути файла, поэтому ответ сначала
 * кладётся в кэш, а потом удаляется. Кадры склеиваются в один FloatArray с
 * прямой перечиткой байт: буфер MediaCodec отдаётся в big-endian, а PCM внутри
 * little-endian, поэтому asShortBuffer() переставил бы сэмплы местами.
 */
internal object MediaAudioDecoder {
    private const val CODEC_TIMEOUT_US = 10_000L
    private const val AUDIO_MIME_PREFIX = "audio/"
    private const val RESPONSE_FILE = "elevenlabs-response"

    fun decode(
        cacheDir: File,
        raw: ByteArray,
    ): TtsAudio {
        val tmp = File(cacheDir, RESPONSE_FILE)
        tmp.writeBytes(raw)
        try {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(tmp.absolutePath)
                val track = findAudioTrack(extractor)
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME)
                    ?: error("у аудиодорожки нет MIME")
                val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                return decodeTrack(extractor, track, mime, rate)
            } finally {
                extractor.release()
            }
        } finally {
            tmp.delete()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(AUDIO_MIME_PREFIX)) return i
        }
        error("в ответе нет аудиодорожки")
    }

    private fun decodeTrack(
        extractor: MediaExtractor,
        track: Int,
        mime: String,
        rate: Int,
    ): TtsAudio {
        val codec = MediaCodec.createDecoderByType(mime)
        val chunks = ArrayList<FloatArray>()
        try {
            val total = pumpCodec(codec, extractor, track, chunks)
            check(total > 0) { "декодер вернул ни одного сэмпла" }
            return concat(chunks, total, rate)
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    /** Крутит цикл кодек-декодирования, пока не придёт флаг конца потока. */
    private fun pumpCodec(
        codec: MediaCodec,
        extractor: MediaExtractor,
        track: Int,
        chunks: MutableList<FloatArray>,
    ): Int {
        codec.configure(extractor.getTrackFormat(track), null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var total = 0
        while (!isEndOfStream(info)) {
            if (!inputDone) inputDone = feedInput(codec, extractor)
            val outIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
            if (outIndex < 0) continue
            if (info.size > 0) {
                val samples = readOutput(codec, outIndex, info)
                chunks.add(samples)
                total += samples.size
            }
            codec.releaseOutputBuffer(outIndex, false)
        }
        return total
    }

    private fun isEndOfStream(info: MediaCodec.BufferInfo): Boolean = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

    /** true, если вход вычерпан и кодек можно больше не кормить. */
    private fun feedInput(
        codec: MediaCodec,
        extractor: MediaExtractor,
    ): Boolean {
        val inIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (inIndex < 0) return false
        val buffer = codec.getInputBuffer(inIndex) ?: error("кодек не дал входной буфер")
        return queueSample(codec, extractor, inIndex, buffer)
    }

    private fun queueSample(
        codec: MediaCodec,
        extractor: MediaExtractor,
        inIndex: Int,
        buffer: java.nio.ByteBuffer,
    ): Boolean {
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
        extractor.advance()
        return false
    }

    private fun readOutput(
        codec: MediaCodec,
        outIndex: Int,
        info: MediaCodec.BufferInfo,
    ): FloatArray {
        val bytes = ByteArray(info.size)
        codec.getOutputBuffer(outIndex)?.let { buffer ->
            buffer.position(info.offset)
            buffer.get(bytes)
        }
        return CloudAudio.pcm16LeToFloat(bytes)
    }

    private fun concat(
        chunks: List<FloatArray>,
        total: Int,
        rate: Int,
    ): TtsAudio {
        val out = FloatArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return TtsAudio(out, rate)
    }
}
