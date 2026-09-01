// ncnn-whisper JNI bridge for opencode-mobile
// Base classes (Whisper/Tokenizer) adapted from Tencent/ncnn examples/whisper.cpp
// (Copyright 2025 Tencent, SPDX-License-Identifier: BSD-3-Clause).
// Additions: loading from a model directory, greedy decoding with a step cap,
// JNI entry points for Android. Model format = ncnn fp16 (whisper_base_*).

#include "net.h"
#include "layer.h"
#include "layer_type.h"

#include <jni.h>
#include <android/log.h>

// Пофазовые тайминги (INFO) в logcat, тег NcnnWhisper.
#define NCNN_PHASE(...) __android_log_print(ANDROID_LOG_INFO, "NcnnWhisper", __VA_ARGS__)

#include <float.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <algorithm>
#include <chrono>
#include <memory>
#include <string>
#include <vector>

// ---- whisper token constants (base / v1-v3 block) ----
static const int token_endoftext = 50257;
static const int token_startoftranscript = 50258;
static const int token_transcribe = 50360;
static const int token_notimestamps = 50364;
static const int token_timestamp_first = 50365;
static const int token_timestamp_last = 51864;

// ---- tokenizer (byte-level BPE decoder) ----
class Tokenizer
{
public:
    std::vector<std::string> reverse_vocab;
    uint8_t byte_decoder[512];

    void generate_byte_decoder()
    {
        memset(byte_decoder, 0, 512 * sizeof(uint8_t));
        auto is_printable = [](int b) {
            return (b >= '!' && b <= '~') || (b >= 161 && b <= 172) || (b >= 174 && b <= 255);
        };
        for (int b = 0; b < 256; ++b)
            if (is_printable(b))
                byte_decoder[b] = static_cast<uint8_t>(b);
        int n = 0;
        for (int b = 0; b < 256; ++b)
            if (!is_printable(b))
            {
                byte_decoder[256 + n] = static_cast<uint8_t>(b);
                n++;
            }
    }

    std::vector<uint32_t> utf8_to_codepoints(const std::string& s) const
    {
        std::vector<uint32_t> codepoints;
        for (size_t i = 0; i < s.length();)
        {
            uint32_t cp = 0;
            int len = 0;
            unsigned char c = s[i];
            if (c < 0x80) { cp = c; len = 1; }
            else if ((c & 0xE0) == 0xC0 && i + 1 < s.length()) { cp = ((s[i] & 0x1F) << 6) | (s[i + 1] & 0x3F); len = 2; }
            else if ((c & 0xF0) == 0xE0 && i + 2 < s.length()) { cp = ((s[i] & 0x0F) << 12) | ((s[i + 1] & 0x3F) << 6) | (s[i + 2] & 0x3F); len = 3; }
            else if ((c & 0xF8) == 0xF0 && i + 3 < s.length()) { cp = ((s[i] & 0x07) << 18) | ((s[i + 1] & 0x3F) << 12) | ((s[i + 2] & 0x3F) << 6) | (s[i + 3] & 0x3F); len = 4; }
            else { i++; continue; }
            codepoints.push_back(cp);
            i += len;
        }
        return codepoints;
    }

    bool load(const char* vocab_path)
    {
        generate_byte_decoder();
        FILE* fp = fopen(vocab_path, "rb");
        if (!fp) { fprintf(stderr, "fopen %s failed\n", vocab_path); return false; }
        char line[256];
        while (!feof(fp))
        {
            char* s = fgets(line, 255, fp);
            if (!s) break;
            int vocab_len = strlen(line);
            // срезаем смешанные окончания строк (\n и \r\n)
            while (vocab_len > 0 && (line[vocab_len - 1] == '\n' || line[vocab_len - 1] == '\r'))
                vocab_len--;
            reverse_vocab.push_back(std::string(line, vocab_len));
        }
        fclose(fp);
        return true;
    }

    std::string decode(const std::vector<int>& tokens) const
    {
        // Byte-level BPE decode (как в openai whisper / whisper.cpp):
        // каждый токен — строка из latin-1-представления байтов ('Ġ' = пробел).
        // Символ <= 0xFF -> байт = codepoint; символ 0x100+ -> byte_decoder[cp].
        // Итоговая строка байт = UTF-8 текст.
        std::string outstring;
        for (int token_id : tokens)
        {
            if (token_id < token_endoftext)
            {
                const std::string& s = reverse_vocab[token_id];
                if (s.empty()) continue;
                std::vector<uint32_t> cps = utf8_to_codepoints(s);
                for (uint32_t cp : cps)
                {
                    if (cp <= 0xFF)
                        outstring += (char)cp;
                    else if (cp <= 0x1FF)
                        outstring += (char)byte_decoder[cp];
                    // прочие codepoints (не должны встречаться) пропускаем
                }
                continue;
            }
            if (token_id >= token_timestamp_first && token_id <= token_timestamp_last)
            {
                int timestamp = (token_id - token_timestamp_first) * 2;
                char tmp[256];
                int n = sprintf(tmp, " [%d.%02d] ", timestamp / 100, timestamp % 100);
                outstring.append(tmp, n);
            }
        }
        return outstring;
    }
};

// ---- whisper implementation ----
class Whisper
{
public:
    int load(const std::string& dir, const std::string& base);
    int transcribe(const std::vector<short>& samples, const char* lang, std::string& text) const;

    void set_num_threads(int n)
    {
        fbank.opt.num_threads = n;
        encoder.opt.num_threads = n;
        embed_token.opt.num_threads = n;
        embed_position.opt.num_threads = n;
        decoder.opt.num_threads = n;
        proj_out.opt.num_threads = n;
    }

protected:
    int extract_fbank_feature(const std::vector<short>& samples, ncnn::Mat& input_features) const;
    int run_encoder(const ncnn::Mat& input_features, ncnn::Mat& encoder_states) const;
    int run_decoder_prefill(const std::vector<int>& tokens, const ncnn::Mat& encoder_states, ncnn::Mat& last_logits, std::vector<ncnn::Mat>& out_kvcache) const;

    int run_decoder_step(const std::vector<int>& tokens, const ncnn::Mat& encoder_states, ncnn::Mat& last_logits, const std::vector<ncnn::Mat>& kvcache, std::vector<ncnn::Mat>& out_kvcache) const;
    int argmax(const ncnn::Mat& logits, int& id, float& conf) const;

protected:
    ncnn::Net fbank;
    ncnn::Net encoder;
    ncnn::Net embed_token;
    ncnn::Net embed_position;
    ncnn::Net decoder;
    ncnn::Net proj_out;
    Tokenizer tokenizer;
    std::vector<int> kv_cache_indexes;
    std::vector<int> out_kv_cache_indexes;
};

int Whisper::load(const std::string& dir, const std::string& base)
{
    // CPU + fp32 (turbo) / fp16 (base): рабочий режим. Vulkan-путь ПРОВЕРЕН повторно
    // 31.08.2026 с исправленными токенами: encoder на Vulkan FP32 даёт ×6 скорость
    // (3094ms против ~18с), НО decoder зацикливается (448 токенов без EOT, avg
    // 28.8ms/step) — состояния encoder неточны на этом Adreno/ColorOS-драйвере даже
    // в FP32, поэтому GPU-путь непригоден. Остаёмся на CPU.
    // fbank: БЕЗ fp16 — на ARM fp16 даёт NaN в log10 (тишина 0.0), на x86 нет.
    fbank.opt.use_vulkan_compute = false;
    fbank.opt.use_fp16_packed = false;
    fbank.opt.use_fp16_storage = false;
    fbank.opt.use_fp16_arithmetic = false;
    // turbo-модель (d_model=1280, 128 mel) на fp16 NEON деградирует: ранний EOT,
    // распознаётся только начало фразы («Раскар» вместо «Расскажи…»). На CPU
    // считаем её в FP32 — точность как у whisper.cpp (q5 даёт «Расскажи анекдот
    // про цыгана.»). base (512-мерный) остаётся на fp16 для скорости.
    const bool turbo = base.find("turbo") != std::string::npos;
    const bool fp16 = !turbo;
    encoder.opt.use_vulkan_compute = false;
    encoder.opt.use_fp16_packed = fp16;
    encoder.opt.use_fp16_storage = fp16;
    encoder.opt.use_fp16_arithmetic = fp16;
    embed_token.opt.use_vulkan_compute = false;
    embed_token.opt.use_fp16_packed = fp16;
    embed_token.opt.use_fp16_storage = fp16;
    embed_token.opt.use_fp16_arithmetic = fp16;
    embed_position.opt.use_vulkan_compute = false;
    embed_position.opt.use_fp16_packed = fp16;
    embed_position.opt.use_fp16_storage = fp16;
    embed_position.opt.use_fp16_arithmetic = fp16;
    decoder.opt.use_vulkan_compute = false;
    decoder.opt.use_fp16_packed = fp16;
    decoder.opt.use_fp16_storage = fp16;
    decoder.opt.use_fp16_arithmetic = fp16;
    decoder.opt.lightmode = false;   // держим промежуточные блобы (нужно для извлечения KV-кэша через extract)
    proj_out.opt.use_vulkan_compute = false;
    proj_out.opt.use_fp16_packed = fp16;
    proj_out.opt.use_fp16_storage = fp16;
    proj_out.opt.use_fp16_arithmetic = fp16;

    std::string p = dir + "/" + base;
    if (fbank.load_param((p + "_fbank.ncnn.param").c_str()) != 0) return -1;
    if (fbank.load_model((p + "_fbank.ncnn.bin").c_str()) != 0) return -1;
    if (encoder.load_param((p + "_encoder.ncnn.param").c_str()) != 0) return -1;
    if (encoder.load_model((p + "_encoder.ncnn.bin").c_str()) != 0) return -1;
    if (embed_token.load_param((p + "_embed_token.ncnn.param").c_str()) != 0) return -1;
    if (embed_token.load_model((p + "_embed_token.ncnn.bin").c_str()) != 0) return -1;
    if (embed_position.load_param((p + "_embed_position.ncnn.param").c_str()) != 0) return -1;
    if (embed_position.load_model((p + "_embed_position.ncnn.bin").c_str()) != 0) return -1;
    if (decoder.load_param((p + "_decoder.ncnn.param").c_str()) != 0) return -1;
    if (decoder.load_model((p + "_decoder.ncnn.bin").c_str()) != 0) return -1;
    if (proj_out.load_param((p + "_proj_out.ncnn.param").c_str()) != 0) return -1;
    if (proj_out.load_model((p + "_proj_out.ncnn.bin").c_str()) != 0) return -1;

    if (!tokenizer.load((dir + "/whisper_vocab.txt").c_str())) return -1;

    // resolve kv cache blob indexes (each MultiHeadAttention with 3 outputs)
    for (size_t i = 0; i < decoder.layers().size(); i++)
    {
        const ncnn::Layer* mha = decoder.layers()[i];
        if (mha->typeindex != ncnn::LayerType::MultiHeadAttention) continue;
        const size_t input_count = mha->bottoms.size();
        const size_t output_count = mha->tops.size();
        if (output_count == 3)
        {
            kv_cache_indexes.push_back(mha->bottoms[input_count - 2]);
            kv_cache_indexes.push_back(mha->bottoms[input_count - 1]);
            out_kv_cache_indexes.push_back(mha->tops[output_count - 2]);
            out_kv_cache_indexes.push_back(mha->tops[output_count - 1]);
        }
    }
    return 0;
}

static void log_softmax_inplace(ncnn::Mat& m)
{
    ncnn::Option opt;
    opt.use_packing_layout = false;
    opt.use_fp16_storage = false;
    {
        ncnn::Layer* softmax = ncnn::create_layer_cpu("Softmax");
        ncnn::ParamDict pd;
        pd.set(0, 0);
        softmax->load_param(pd);
        softmax->forward_inplace(m, opt);
        delete softmax;
    }
    {
        ncnn::Layer* log = ncnn::create_layer_cpu("UnaryOp");
        ncnn::ParamDict pd;
        pd.set(0, 8); // log
        log->load_param(pd);
        log->forward_inplace(m, opt);
        delete log;
    }
}

int Whisper::argmax(const ncnn::Mat& logits, int& id, float& conf) const
{
    float maxv = -FLT_MAX;
    int maxi = 0;
    for (int i = 0; i < logits.w; i++)
    {
        if (logits[i] > maxv)
        {
            maxv = logits[i];
            maxi = i;
        }
    }
    id = maxi;
    conf = maxv;
    return 0;
}

int Whisper::transcribe(const std::vector<short>& samples, const char* lang, std::string& text) const
{
    // language token: only ru available from caller for now, but resolve generically.
    // We map a small set of supported langs to their id offset (relative to token_lang_first).
    // For ru: index 4 -> token_lang_first + 4 = 50263.
    int token_lang = 50263; // default ru
    if (lang && strcmp(lang, "ru") == 0) token_lang = 50263;
    else if (lang && strcmp(lang, "en") == 0) token_lang = 50259;

    std::vector<int> ids(4);
    ids[0] = token_startoftranscript;
    ids[1] = token_lang;
    ids[2] = token_transcribe;
    ids[3] = token_notimestamps;

    ncnn::Mat input_features;
    {
        auto t0 = std::chrono::steady_clock::now();
        extract_fbank_feature(samples, input_features);
        NCNN_PHASE("ncnn phase fbank=%.0fms", std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
    }

    ncnn::Mat encoder_states;
    {
        auto t0 = std::chrono::steady_clock::now();
        if (run_encoder(input_features, encoder_states) != 0 || encoder_states.empty())
        {
            NCNN_PHASE("ncnn error: encoder failed");
            return -1;
        }
        NCNN_PHASE("ncnn phase encoder=%.0fms", std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
    }

    // greedy decoding with a hard step cap (no eot -> bounded loop)
    const int max_steps = 448;
    int step = 0;
    std::vector<int> decoded = ids;
    std::vector<ncnn::Mat> kvcache;
    double decoder_ms = 0;
    while (step < max_steps)
    {
        ncnn::Mat logits;
        std::vector<ncnn::Mat> out_kvcache;
        auto t0 = std::chrono::steady_clock::now();
        int rc = step == 0 ? run_decoder_prefill(decoded, encoder_states, logits, out_kvcache)
                           : run_decoder_step(decoded, encoder_states, logits, kvcache, out_kvcache);
        if (rc != 0 || logits.empty())
        {
            NCNN_PHASE("ncnn error: decoder step %d failed rc=%d", step, rc);
            return -1;
        }
        decoder_ms += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        kvcache = out_kvcache;

        int id = 0;
        float conf = 0.f;
        argmax(logits, id, conf);

        if (id == token_endoftext) break;
        decoded.push_back(id);
        step++;
    }
    NCNN_PHASE("ncnn phase decoder=%d steps=%.0fms avg=%.1fms/step", step, decoder_ms, step > 0 ? decoder_ms / step : 0);

    text = tokenizer.decode(decoded);
    return 0;
}

int Whisper::extract_fbank_feature(const std::vector<short>& samples, ncnn::Mat& input_features) const
{
    const int samples_size = (int)samples.size();
    const int copy = samples_size < 480000 ? samples_size : 480000;
    ncnn::Mat waveform(480000);
    waveform.fill(0.f);
    {
        for (int i = 0; i < copy; i++)
            waveform[i] = samples[i] / 32768.0f;
    }
    ncnn::Extractor ex = fbank.create_extractor();
    ex.input("in0", waveform);
    ex.extract("out0", input_features);

    // drop the last frame
    {
        ncnn::Mat input_features_3k(input_features.w - 1, input_features.h);
        for (int i = 0; i < input_features.h; i++)
            memcpy(input_features_3k.row(i), input_features.row(i), (input_features.w - 1) * sizeof(float));
        input_features = input_features_3k;
    }
// FIX NaN: log10(ncnn) на тишине (0.0) выдает NaN вместо -10 = log10(clip(0, 1e-10))
    {
        int fix = 0;
        for (size_t i = 0; i < input_features.total(); i++)
        {
            if (input_features[i] != input_features[i])
            {
                input_features[i] = -10.0f;
                fix++;
            }
        }
        if (fix)
            NCNN_PHASE("ncnn fbank NaN->-10 fixed %d elems (%.1f%%)", fix, 100.0 * fix / input_features.total());
    }
    return 0;
}

int Whisper::run_encoder(const ncnn::Mat& input_features, ncnn::Mat& encoder_states) const
{
    ncnn::Extractor ex = encoder.create_extractor();
    ex.input("in0", input_features);
    int rc = ex.extract("out0", encoder_states);
    NCNN_PHASE("ncnn phase encoder rc=%d", rc);
    return rc;
}

int Whisper::run_decoder_prefill(const std::vector<int>& tokens, const ncnn::Mat& encoder_states, ncnn::Mat& last_logits, std::vector<ncnn::Mat>& out_kvcache) const
{
    const int dst_seqlen = tokens.size();
    ncnn::Mat token_embeds;
    {
        ncnn::Mat input_tokens(dst_seqlen);
        int* p = input_tokens;
        memcpy(p, tokens.data(), tokens.size() * sizeof(int));
        ncnn::Extractor ex = embed_token.create_extractor();
        ex.input("in0", input_tokens);
        ex.extract("out0", token_embeds);
    }
    ncnn::Mat position_embeds;
    {
        ncnn::Mat input_positions(dst_seqlen);
        int* p = input_positions;
        for (int i = 0; i < dst_seqlen; i++) p[i] = i;
        ncnn::Extractor ex = embed_position.create_extractor();
        ex.input("in0", input_positions);
        ex.extract("out0", position_embeds);
    }
    ncnn::Mat input_embeds;
    {
        input_embeds.create_like(token_embeds);
        for (int i = 0; i < input_embeds.total(); i++)
            input_embeds[i] = token_embeds[i] + position_embeds[i];
    }
    ncnn::Mat attention_mask(dst_seqlen, dst_seqlen);
    attention_mask.fill(0.f);
    for (int i = 0; i < dst_seqlen; i++)
        for (int j = i + 1; j < dst_seqlen; j++)
            attention_mask.row(i)[j] = -INFINITY;

    ncnn::Mat output_states;
    {
        ncnn::Extractor ex = decoder.create_extractor();
        ex.input("in0", input_embeds);
        ex.input("in1", encoder_states);
        ex.input("in2", attention_mask);
        out_kvcache.resize(out_kv_cache_indexes.size());
        for (size_t i = 0; i < out_kv_cache_indexes.size(); i++)
            ex.extract(out_kv_cache_indexes[i], out_kvcache[i], 1);
        ex.extract("out0", output_states);
    }
    if (output_states.empty() || output_states.h < dst_seqlen)
        return -1;
    ncnn::Mat last_state = output_states.row_range(dst_seqlen - 1, 1).clone();
    {
        ncnn::Extractor ex = proj_out.create_extractor();
        ex.input("in0", last_state);
        ex.extract("out0", last_logits);
    }
    last_logits = last_logits.reshape(last_logits.w);
    if (last_logits.empty())
        return -1;
    return 0;
}

int Whisper::run_decoder_step(const std::vector<int>& tokens, const ncnn::Mat& encoder_states, ncnn::Mat& last_logits, const std::vector<ncnn::Mat>& kvcache, std::vector<ncnn::Mat>& out_kvcache) const
{
    const int token_id = tokens.back();
    const int dst_seqlen = 1;
    ncnn::Mat token_embeds;
    {
        ncnn::Mat input_tokens(dst_seqlen);
        ((int*)input_tokens)[0] = token_id;
        ncnn::Extractor ex = embed_token.create_extractor();
        ex.input("in0", input_tokens);
        ex.extract("out0", token_embeds);
    }
    ncnn::Mat position_embeds;
    {
        ncnn::Mat input_positions(dst_seqlen);
        ((int*)input_positions)[0] = tokens.size() - 1;
        ncnn::Extractor ex = embed_position.create_extractor();
        ex.input("in0", input_positions);
        ex.extract("out0", position_embeds);
    }
    ncnn::Mat input_embeds;
    {
        input_embeds.create_like(token_embeds);
        for (int i = 0; i < input_embeds.total(); i++)
            input_embeds[i] = token_embeds[i] + position_embeds[i];
    }
    ncnn::Mat attention_mask(dst_seqlen, dst_seqlen);
    attention_mask.fill(0.f);

    ncnn::Mat output_states;
    {
        ncnn::Extractor ex = decoder.create_extractor();
        ex.input("in0", input_embeds);
        ex.input("in1", encoder_states);
        ex.input("in2", attention_mask);
        for (size_t i = 0; i < kv_cache_indexes.size(); i++)
            ex.input(kv_cache_indexes[i], kvcache[i]);
        out_kvcache.resize(out_kv_cache_indexes.size());
        for (size_t i = 0; i < out_kv_cache_indexes.size(); i++)
            ex.extract(out_kv_cache_indexes[i], out_kvcache[i], 1);
        ex.extract("out0", output_states);
    }
    if (output_states.empty() || output_states.h < 1)
        return -1;
    ncnn::Mat last_state = output_states.row_range(dst_seqlen - 1, 1).clone();
    {
        ncnn::Extractor ex = proj_out.create_extractor();
        ex.input("in0", last_state);
        ex.extract("out0", last_logits);
    }
    last_logits = last_logits.reshape(last_logits.w);
    return 0;
}

// ---- JNI state ----
static std::unique_ptr<Whisper> g_whisper;

// ---- JNI: Java_com_whispercpp_whisper_NcnnWhisperLib_* ----
extern "C" JNIEXPORT jboolean JNICALL
Java_com_whispercpp_whisper_NcnnWhisperLib_nativeInit(JNIEnv* env, jobject /*thiz*/, jstring modelDir, jstring base)
{
    const char* dir = env->GetStringUTFChars(modelDir, 0);
    const char* b = env->GetStringUTFChars(base, 0);
    std::string dirStr = dir ? dir : "";
    std::string baseStr = b ? b : "whisper_base";

    g_whisper = std::make_unique<Whisper>();
    // 4 threads ДО load: gemm-слой фиксирует число потоков при первой загрузке модели
    // (иначе предупреждение 'gemm will use load-time value' и медленный single-gemm).
    g_whisper->set_num_threads(4);
    int ret = g_whisper->load(dirStr, baseStr);

    if (dir) env->ReleaseStringUTFChars(modelDir, dir);
    if (b) env->ReleaseStringUTFChars(base, b);

    return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_whispercpp_whisper_NcnnWhisperLib_nativeSetThreads(JNIEnv* /*env*/, jobject /*thiz*/, jint n)
{
    if (!g_whisper) return JNI_FALSE;
    g_whisper->set_num_threads((int)n);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_NcnnWhisperLib_nativeTranscribe(JNIEnv* env, jobject /*thiz*/, jfloatArray samples, jstring lang)
{
    if (!g_whisper)
    {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "ncnn whisper not initialized");
        return NULL;
    }
    if (samples == NULL)
    {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "samples == null");
        return NULL;
    }
    jsize n = env->GetArrayLength(samples);
    jfloat* src = env->GetFloatArrayElements(samples, 0);

    // float(-1..1) -> int16 short, clamped to [-1,1]
    std::vector<short> s;
    s.reserve(n);
    for (jsize i = 0; i < n; i++)
    {
        float v = src[i];
        if (v < -1.f) v = -1.f;
        if (v > 1.f) v = 1.f;
        s.push_back((short)(v * 32767.0f));
    }
    env->ReleaseFloatArrayElements(samples, src, JNI_ABORT);

    const char* l = env->GetStringUTFChars(lang, 0);
    std::string langStr = l ? l : "ru";
    if (l) env->ReleaseStringUTFChars(lang, l);

    std::string text;
    g_whisper->transcribe(s, langStr.c_str(), text);

    // sanitize: trim trailing newline and timestamp artifacts at the Kotlin layer usually;
    // here we return the raw decoded text.
    jstring js = env->NewStringUTF(text.c_str());
    if (!js)
    {
        // invalid UTF-8 -> return empty rather than crash
        return env->NewStringUTF("");
    }
    return js;
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_NcnnWhisperLib_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/)
{
    g_whisper.reset();
}
