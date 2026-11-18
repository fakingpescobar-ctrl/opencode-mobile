package org.opencode.mobile.tts

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Самопроверка озвучки без ушей, без панели и без слуха человека.
 *
 * Зачем: «я не слышу озвучки» — это утверждение о динамике, а причина может быть
 * в трёх разных местах: движок не поднялся, фильтр выбросил все фразы, или звук
 * ушёл в никуда (громкость, маршрут, фокус). Отличить их по экрану нельзя:
 * причина отказа движка видна только в плашке панели, а туда агенту не попасть
 * (тап недоступен, `logcat` приложению закрыт).
 *
 * Поэтому проверка делает ровно то, что делает озвучка, но с известным входом:
 * берёт тот же движок из [SpeechFactory], синтезирует заданную фразу, считает
 * длительность и пик, пишет WAV в filesDir и, если попросили, отправляет звук в
 * тот же [AudioTrackPlayer], что играет обычную речь.
 *
 * Три исхода различимы по числам, а не по обещаниям:
 * - `ok = false` + `reason` — движок не поднялся или вернул тишину;
 * - `ok = true, played = false` — синтез есть, звук не отправляли (`play=false`);
 * - `ok = true, played = true` — звук записан в трек; слышит ли его человек,
 *   по-прежнему доказывает только ухо, но отсутствие звука теперь не списать на
 *   «движок молчит».
 */
object TtsSelfTest {
    /** Фраза по умолчанию: короткая, с числами и без разметки — как обычный ответ. */
    const val DEFAULT_TEXT = "Проверка озвучки. Раз, два, три."

    /** Ниже этого пика сигнал считаем тишиной: -60 dBFS. */
    private const val SILENCE_PEAK = 0.001

    private const val PCM16_MAX = 32767f
    private const val WAV_HEADER_BYTES = 44
    private const val WAV_FMT_CHUNK = 16
    private const val WAV_PCM = 1
    private const val WAV_CHANNELS = 1
    private const val WAV_BITS = 16
    private const val BYTES_PER_SAMPLE = 2
    private const val RIFF_OVERHEAD = 36

    /** Итог одной проверки: всё, что нужно, чтобы не гадать. */
    @Suppress("LongParameterList")
    data class Result(
        val ok: Boolean,
        val reason: String?,
        val engine: String,
        val effective: String,
        val samples: Int,
        val sampleRate: Int,
        val durationSec: Double,
        val peak: Double,
        val file: String?,
        val played: Boolean,
    )

    /**
     * Плеер проверки живёт между вызовами: [AudioTrackPlayer] один на сеанс, и
     * второй экземпляр на том же потоке — это два трека, дерущихся за устройство.
     */
    @Volatile
    private var player: AudioTrackPlayer? = null

    @Volatile
    private var playerRate = 0

    @Suppress("ReturnCount")
    fun run(
        context: Context,
        text: String = DEFAULT_TEXT,
        play: Boolean = false,
    ): Result {
        val cfg = TtsConfig.read(context)
        val resolved = SpeechFactory.build(context, cfg)
        val synth = resolved.synth
        if (synth == null) {
            return failure(cfg, resolved, resolved.reason ?: "движок не поднялся")
        }
        val audio = synth.synthesize(text, cfg.sid, cfg.speechRate)
        if (audio == null) {
            return failure(cfg, resolved, "синтез вернул null")
        }
        val peak = peakOf(audio)
        val file = File(context.filesDir, "tts-test.wav")
        val saved = runCatching { writeWav(file, audio) }.getOrNull()
        val audible = !audio.isEmpty && peak > SILENCE_PEAK
        val played = play && audible && playAudio(audio)
        return Result(
            ok = audible,
            reason = if (audible) null else "синтез вернул тишину",
            engine = cfg.engine.prefValue,
            effective = resolved.effective.prefValue,
            samples = audio.samples.size,
            sampleRate = audio.sampleRate,
            durationSec = audio.durationSec.toDouble(),
            peak = peak,
            file = if (saved != null) file.path else null,
            played = played,
        )
    }

    private fun failure(
        cfg: TtsConfig,
        resolved: SpeechFactory.Resolved,
        reason: String,
    ): Result =
        Result(
            ok = false,
            reason = reason,
            engine = cfg.engine.prefValue,
            effective = resolved.effective.prefValue,
            samples = 0,
            sampleRate = 0,
            durationSec = 0.0,
            peak = 0.0,
            file = null,
            played = false,
        )

    private fun peakOf(audio: TtsAudio): Double = audio.samples.fold(0.0) { acc, value -> maxOf(acc, abs(value).toDouble()) }

    private fun playAudio(audio: TtsAudio): Boolean {
        val current =
            player?.takeIf { playerRate == audio.sampleRate }
                ?: AudioTrackPlayer(audio.sampleRate).also {
                    player = it
                    playerRate = audio.sampleRate
                }
        return current.play(audio)
    }

    /** WAV 16 бит: файл можно отдать человеку и разобрать без приложения. */
    private fun writeWav(
        file: File,
        audio: TtsAudio,
    ) {
        val dataBytes = audio.samples.size * BYTES_PER_SAMPLE
        val header =
            ByteBuffer
                .allocate(WAV_HEADER_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray())
                .putInt(RIFF_OVERHEAD + dataBytes)
                .put("WAVE".toByteArray())
                .put("fmt ".toByteArray())
                .putInt(WAV_FMT_CHUNK)
                .putShort(WAV_PCM.toShort())
                .putShort(WAV_CHANNELS.toShort())
                .putInt(audio.sampleRate)
                .putInt(audio.sampleRate * BYTES_PER_SAMPLE)
                .putShort(BYTES_PER_SAMPLE.toShort())
                .putShort(WAV_BITS.toShort())
                .put("data".toByteArray())
                .putInt(dataBytes)
        val pcm = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in audio.samples) {
            pcm.putShort((sample.coerceIn(-1f, 1f) * PCM16_MAX).toInt().toShort())
        }
        file.outputStream().use { out ->
            out.write(header.array())
            out.write(pcm.array())
        }
    }
}
