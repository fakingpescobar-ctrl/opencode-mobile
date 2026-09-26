package org.opencode.mobile.stt

import android.content.Context
import java.io.File

/**
 * Единый валидатор набора файлов ncnn-модели.
 *
 * Критерий готовности — РОВНО тот набор, который реально грузит
 * NcnnWhisperContext::load() в whisperlib/.../ncnn_jni.cpp:
 *  - fbank / embed_token / embed_position / decoder / proj_out: param + bin
 *  - encoder: полный int8-набор ИЛИ полный fp32-набор (в C++ при наличии
 *    *_encoder_int8.ncnn.param пробуется int8, иначе — fp32)
 *  - whisper_vocab.txt
 *
 * Раньше DiagnosticsScreen и WhisperTranscribeService проверяли только
 * fbank.param + vocab — диагностика могла показать «готов», а первая
 * транскрипция падала на отсутствующем decoder/embed/proj_out.
 */
object NcnnModelValidator {
    /** Результат проверки: ok + список отсутствующих файлов (относительно каталога модели). */
    data class Check(
        val ok: Boolean,
        val missing: List<String>,
    )

    /**
     * Какой энкодер реально загрузит ncnn_jni.cpp: сначала пробуется int8,
     * при его отсутствии — fp32. Значение нужно бенчу: строка "fp32" в CSV
     * означает прогон fp32 ТОЛЬКО если вариант действительно FP32, иначе
     * движок молча взял int8 и сравнение вариантов бессмысленно.
     */
    enum class EncoderVariant { INT8, FP32, NONE }

    fun encoderVariant(
        dir: File,
        base: String = "whisper_turbo",
    ): EncoderVariant {
        val int8 = File(dir, "${base}_encoder_int8.ncnn.param").isFile &&
            File(dir, "${base}_encoder_int8.ncnn.bin").isFile
        val fp32 = File(dir, "${base}_encoder.ncnn.param").isFile &&
            File(dir, "${base}_encoder.ncnn.bin").isFile
        return when {
            int8 -> EncoderVariant.INT8
            fp32 -> EncoderVariant.FP32
            else -> EncoderVariant.NONE
        }
    }

    /** Проверка продуктивной модели ncnn-turbo/ (DiagnosticsScreen + WhisperTranscribeService). */
    fun checkTurbo(context: Context): Check = checkModelDir(File(ModelDownloader.modelsDir(context), "ncnn-turbo"))

    fun isTurboReady(context: Context): Boolean = checkTurbo(context).ok

    /**
     * Полная проверка каталога модели. Каталог и имена файлов — как их
     * формирует createFromFilesDir: dir/ncnn-turbo + base "whisper_turbo".
     */
    fun checkModelDir(
        dir: File,
        base: String = "whisper_turbo",
    ): Check {
        val missing = mutableListOf<String>()

        // Обязательные сети: каждая = param + bin.
        for (net in listOf("fbank", "embed_token", "embed_position", "decoder", "proj_out")) {
            if (!File(dir, "${base}_$net.ncnn.param").isFile) missing += "${base}_$net.ncnn.param"
            if (!File(dir, "${base}_$net.ncnn.bin").isFile) missing += "${base}_$net.ncnn.bin"
        }

        // Энкодер: полный int8 ИЛИ полный fp32 (как fallback в ncnn_jni.cpp).
        if (encoderVariant(dir, base) == EncoderVariant.NONE) {
            if (!File(dir, "${base}_encoder_int8.ncnn.param").isFile) missing += "${base}_encoder_int8.ncnn.param"
            if (!File(dir, "${base}_encoder_int8.ncnn.bin").isFile) missing += "${base}_encoder_int8.ncnn.bin"
            if (!File(dir, "${base}_encoder.ncnn.param").isFile) missing += "${base}_encoder.ncnn.param"
            if (!File(dir, "${base}_encoder.ncnn.bin").isFile) missing += "${base}_encoder.ncnn.bin"
        }

        if (!File(dir, "whisper_vocab.txt").isFile) missing += "whisper_vocab.txt"
        return Check(ok = missing.isEmpty(), missing = missing)
    }
}
