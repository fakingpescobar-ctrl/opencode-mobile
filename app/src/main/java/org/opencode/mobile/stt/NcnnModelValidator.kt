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
        val int8Complete =
            File(dir, "${base}_encoder_int8.ncnn.param").isFile &&
                File(dir, "${base}_encoder_int8.ncnn.bin").isFile
        val fp32Complete =
            File(dir, "${base}_encoder.ncnn.param").isFile &&
                File(dir, "${base}_encoder.ncnn.bin").isFile
        if (!int8Complete && !fp32Complete) {
            if (!File(dir, "${base}_encoder_int8.ncnn.param").isFile) missing += "${base}_encoder_int8.ncnn.param"
            if (!File(dir, "${base}_encoder_int8.ncnn.bin").isFile) missing += "${base}_encoder_int8.ncnn.bin"
            if (!File(dir, "${base}_encoder.ncnn.param").isFile) missing += "${base}_encoder.ncnn.param"
            if (!File(dir, "${base}_encoder.ncnn.bin").isFile) missing += "${base}_encoder.ncnn.bin"
        }

        if (!File(dir, "whisper_vocab.txt").isFile) missing += "whisper_vocab.txt"
        return Check(ok = missing.isEmpty(), missing = missing)
    }
}
