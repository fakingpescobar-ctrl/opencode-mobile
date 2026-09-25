// ncnn-whisper JNI bridge for opencode-mobile
// Base classes (Whisper/Tokenizer) adapted from Tencent/ncnn examples/whisper.cpp
// (Copyright 2025 Tencent, SPDX-License-Identifier: BSD-3-Clause).
// Additions: loading from a model directory, greedy decoding with a step cap,
// JNI entry points for Android. Model format = ncnn fp16 (whisper_base_*).

#include "net.h"
#include "layer.h"
#include "layer_type.h"
#include "allocator.h"

#include <jni.h>
#include <android/log.h>
#include <cstdlib> // getenv (NCNN_VERBOSE)

// Пофазовые тайминги (INFO) в logcat, тег NcnnWhisper.
#define NCNN_PHASE(...) __android_log_print(ANDROID_LOG_INFO, "NcnnWhisper", __VA_ARGS__)

// Пофазные тайминги последнего transcribe (мс + шаги декодера).
// Заполняются в transcribe(), читаются JNI nativeLatencyProfile() — для STT-бенча (R5).
// Должны быть объявлены ДО transcribe (перенос наверх; в JNI-секции ниже не дублировать).
static double g_last_fbank_ms = 0;
static double g_last_encoder_ms = 0;
static double g_last_decoder_ms = 0;
static int g_last_decoder_steps = 0;

// Пер-шаговый трейс декодера (kv_in/kv_out/out0 shapes на КАЖДОМ авторегрессивном
// шаге) — в проде спамил бы INFO в лог-буфер. Включается env NCNN_VERBOSE=1
// (считается ОДИН раз при статической инициализации). Per-итерация decoder iter
// (декодирование) и per-step shapes логируются ТОЛЬКО сюда (Native-14).
static const bool g_ncnn_verbose = []() {
    const char* v = getenv("NCNN_VERBOSE");
    return v != nullptr && v[0] != '\0' && v[0] != '0';
}();
#define NCNN_TRACE(...)                                                             \
    do {                                                                            \
        if (g_ncnn_verbose)                                                         \
            __android_log_print(ANDROID_LOG_DEBUG, "NcnnWhisper", __VA_ARGS__);     \
    } while (0)

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
// Единый стабильный аллокатор KV-кэша для decoder: позволяет MHA переиспользовать
// кэш между prefill (step 0) и автогрегрессивными шагами (reuse=true в
// create_or_grow_kvcache). Без него каждый шаг переаллоцировал бы кэш по
// max_seqlen_hint (2=1638400) -> 8.4GB alloc -> rc=-100 (decoder step fail).
// Аллокатор — член Whisper (unique_ptr, RAII): освобождается вместе с инстансом
// (g_whisper.reset() → ~Whisper), а не висит статиком на весь процесс (Native-12).

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

    // encoder (и проекции/эмбеддинги) — крупные матричные умножения: выигрывают от 8 потоков.
    // decoder — серийные мелкие шаги (в т.ч. авторегрессивные по токену): на 8 потоках
    // оверхед перекроет выигрыш, поэтому его держим на меньшем числе. Вызывается ДО load
    // (ncnn gemm фиксирует число потоков при загрузке модели).
    void set_encoder_threads(int n)
    {
        encoder.opt.num_threads = n;
        embed_token.opt.num_threads = n;
        embed_position.opt.num_threads = n;
        proj_out.opt.num_threads = n;
    }
    void set_decoder_threads(int n)
    {
        fbank.opt.num_threads = n;
        decoder.opt.num_threads = n;
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
    // KV-cache PoolAllocator (RAII, см. комментарий у класса): живёт столько же,
    // сколько Whisper. decoder.opt.kvcache_allocator указывает на него после load().
    std::unique_ptr<ncnn::PoolAllocator> kv_allocator;
    std::vector<int> kv_cache_indexes;
    std::vector<int> out_kv_cache_indexes;
    // Cross-KV-оптимизация в run_decoder_step: подтверждение того, что парный layout
    // MHA в декодере действительно (self, cross) на слой. Cross-KV константен между
    // шагами (зависит только от encoder) — если ПОЛНАЯ ТРОЙКА (w,h,c) cross-кандидата
    // (i%4==2||3) меняется, это растущий self-KV (другой layout) — откатываемся на
    // extract (иначе тихий мусор в расшифровке). mutable: run_decoder_step — const.
    // БЕЗОПАСНОСТЬ ПОТОКОВ: transcribe() сериализован очередью STT (FIFO задач,
    // очередь #2) — рефакторинг, снимающий сериализацию, обязан снять и
    // cross-оптимизацию (вернуть extract-путь). Значения сбрасываются в load().
    mutable int m_cross_kv_w = -1;
    mutable int m_cross_kv_h = -1;
    mutable int m_cross_kv_c = -1;
    mutable bool m_cross_layout_ok = true;
};

int Whisper::load(const std::string& dir, const std::string& base)
{
    // CPU + fp32 (turbo) / fp16 (base): рабочий режим.
    // ЭКСП-1 (02.09.2026): encoder выносили на Vulkan (int8, subgroup_ops=off) + decoder CPU.
    // РЕЗУЛЬТАТ: encoder быстрее (6.5-6.9s vs 8.82 CPU), но decoder на ЖИВОЙ речи ЗАВИСАЛ на
    // шаге kvidx=16 (конверсия больших Vulkan-encoder kv-состояний в CPU-блоб). AUTOSTT
    // (test.f32) проходил, реальный голос вешал распознавание навсегда. ДЕФЕКТ СТАБИЛЬНОСТИ.
    // Решение: encoder ВОЗВРАЩЁН на CPU int8 (стабильно для живого голоса). Vulkan-encoder
    // без надёжного decoder-моста не годится. Ускорение Vulkan — отдельная задача.
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
    // KV-cache allocator критичен: без него ncnn decoder падает rc=-100 (alloc fail,
    // 8.4GB ctx, reuse=false) уже на 2-м шаге при живой речи (замер 21:27). С ним —
    // reuse=true и без OOM. Второй залип: stall на извлечении kv_out[14]/[15]
    // (cross-attn 64x1500x20) на ~8-м шаге при живой длинной речи — чинится в
    // run_decoder_step (cross-KV берётся из входного кэша, extract не зовётся).
    if (!kv_allocator)
        kv_allocator = std::make_unique<ncnn::PoolAllocator>();
    kv_allocator->set_size_compare_ratio(0.5f);
    decoder.opt.kvcache_allocator = kv_allocator.get();
    proj_out.opt.use_vulkan_compute = false;
    proj_out.opt.use_fp16_packed = fp16;
    proj_out.opt.use_fp16_storage = fp16;
    proj_out.opt.use_fp16_arithmetic = fp16;

std::string p = dir + "/" + base;
    if (fbank.load_param((p + "_fbank.ncnn.param").c_str()) != 0) return -1;
    if (fbank.load_model((p + "_fbank.ncnn.bin").c_str()) != 0) return -1;
    // int8-кандидат для encoder: если рядом есть *_encoder_int8.ncnn.* — грузим его
    // (int8-квантование Gemm/MHA через block-quant), иначе — обычный fp32.
    // ЗАМЕРЕНО 02.09: int8 быстрее FP32 и на CPU, и на Vulkan (int8-Vulkan 6.45s,
    // FP32-Vulkan 11.2s, int8-CPU 8.82s). Поэтому int8 всегда приоритетен.
    // Грузим int8 СРАЗУ в encoder (Native-11): раньше сначала загружали копию в
    // пробную enc_try, чтобы проверить валидность — на пике две копии 500MB сети
    // в памяти, а выгоды ноль: параметры те же. При неудаче — encoder.clear()
    // (убрать частично загруженные слои) и откат на обычный fp32.
    bool int8_ok = false;
    {
        std::string enc_param = p + "_encoder_int8.ncnn.param";
        FILE* fint8 = fopen(enc_param.c_str(), "rb");
        if (fint8)
        {
            fclose(fint8);
            if (encoder.load_param(enc_param.c_str()) == 0 &&
                encoder.load_model((p + "_encoder_int8.ncnn.bin").c_str()) == 0)
            {
                int8_ok = true;
            }
            else
            {
                encoder.clear();
            }
        }
    }
    if (!int8_ok)
    {
        std::string enc_param = p + "_encoder.ncnn.param";
        std::string enc_bin   = p + "_encoder.ncnn.bin";
        if (encoder.load_param(enc_param.c_str()) != 0) return -1;
        if (encoder.load_model(enc_bin.c_str()) != 0) return -1;
    }
    NCNN_PHASE("encoder mode: %s vulkan=%d", int8_ok ? "int8" : "fp32", (int)encoder.opt.use_vulkan_compute);
    if (embed_token.load_param((p + "_embed_token.ncnn.param").c_str()) != 0) return -1;
    if (embed_token.load_model((p + "_embed_token.ncnn.bin").c_str()) != 0) return -1;
    if (embed_position.load_param((p + "_embed_position.ncnn.param").c_str()) != 0) return -1;
    if (embed_position.load_model((p + "_embed_position.ncnn.bin").c_str()) != 0) return -1;
    if (decoder.load_param((p + "_decoder.ncnn.param").c_str()) != 0) return -1;
    if (decoder.load_model((p + "_decoder.ncnn.bin").c_str()) != 0) return -1;
    if (proj_out.load_param((p + "_proj_out.ncnn.param").c_str()) != 0) return -1;
    if (proj_out.load_model((p + "_proj_out.ncnn.bin").c_str()) != 0) return -1;

    NCNN_PHASE("whisper load OK pre-tokenizer: base=%s", base.c_str());
    if (!tokenizer.load((dir + "/whisper_vocab.txt").c_str())) return -1;

    // resolve kv cache blob indexes (each MultiHeadAttention with 3 outputs)
    // Сброс cross-guard: инвариант привязан к текущей модели; повторная загрузка
    // (смена модели на том же инстансе) обязана пере-подтвердить layout.
    m_cross_kv_w = -1;
    m_cross_kv_h = -1;
    m_cross_kv_c = -1;
    m_cross_layout_ok = true;
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
    // Жёсткое соответствие ожидаемому layout: whisper base — 4 слоя декодера ×
    // (self, cross) MHA = 16 KV-индексов. Любое иное количество — иная модель,
    // i%4-схема (i%4==2||3 = cross) недоказуема → cross-оптимизация отключается
    // прямо при загрузке. Полная защита от «все self подряд» при 16 индексах —
    // только runtime-сверка в run_decoder_step (shape cross-KV константен между
    // шагами, у self — растёт).
    if (kv_cache_indexes.size() != 16 || out_kv_cache_indexes.size() != 16)
    {
        NCNN_PHASE("DECODER: KV-индексов %zu/%zu (ожидается 16/16 для whisper base) — cross-оптимизация отключена",
            kv_cache_indexes.size(), out_kv_cache_indexes.size());
        m_cross_layout_ok = false;
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
        double f_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        NCNN_PHASE("ncnn phase fbank=%.0fms", f_ms);
        g_last_fbank_ms = f_ms;
    }

    ncnn::Mat encoder_states;
    {
        auto t0 = std::chrono::steady_clock::now();
        if (run_encoder(input_features, encoder_states) != 0 || encoder_states.empty())
        {
            NCNN_PHASE("ncnn error: encoder failed");
            return -1;
        }
        double e_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        NCNN_PHASE("ncnn phase encoder=%.0fms", e_ms);
        g_last_encoder_ms = e_ms;
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
        // Пер-итерация декодера — TRACE (verbose), а не PHASE (INFO): на живой речи
        // это десятки строк на каждое распознавание (Native-14).
        NCNN_TRACE("decoder iter step=%d decoded=%zu last_id=%d conf=%.3f", step, decoded.size(), id, conf);

        if (id == token_endoftext) break;
        decoded.push_back(id);
        step++;
    }
    NCNN_PHASE("ncnn phase decoder=%d steps=%.0fms avg=%.1fms/step", step, decoder_ms, step > 0 ? decoder_ms / step : 0);
    g_last_decoder_ms = decoder_ms;
    g_last_decoder_steps = step;

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
        {
            int rck = ex.extract(out_kv_cache_indexes[i], out_kvcache[i], 1);
            NCNN_PHASE("prefill kv_out[%d] extract rc=%d shape(%d,%d,%d)", (int)i, rck, out_kvcache[i].w,out_kvcache[i].h,out_kvcache[i].c);
        }
        int rcO = ex.extract("out0", output_states);
        NCNN_PHASE("prefill out0 rc=%d shape(%d,%d,%d)", rcO, output_states.w,output_states.h,output_states.c);
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
        // Защита от рассинхрона (B4): входной кэш обязан совпадать по размеру с
        // ожидаемыми индексами — иначе i%4-ветка и extract выйдут за границы (UB).
        if (kvcache.size() != kv_cache_indexes.size() ||
            out_kv_cache_indexes.size() != kv_cache_indexes.size())
        {
            NCNN_PHASE("decoder kvcache mismatch: in=%zu idx=%zu outidx=%zu",
                kvcache.size(), kv_cache_indexes.size(), out_kv_cache_indexes.size());
            return -1;
        }
        NCNN_TRACE("decoder step in: embeds(%d,%d,%d) enc(%d,%d,%d) mask(%d,%d) kvidx=%d outidx=%d",
            input_embeds.w,input_embeds.h,input_embeds.c,
            encoder_states.w,encoder_states.h,encoder_states.c,
            attention_mask.w,attention_mask.h,
            (int)kv_cache_indexes.size(),(int)out_kv_cache_indexes.size());
        for (size_t i = 0; i < kv_cache_indexes.size(); i++)
            NCNN_TRACE("  kv_in[%d] blob=%d shape(%d,%d,%d) total=%d", (int)i, (int)kv_cache_indexes[i],
                kvcache[i].w,kvcache[i].h,kvcache[i].c,(int)kvcache[i].total());
        ncnn::Extractor ex = decoder.create_extractor();
        ex.input("in0", input_embeds);
        ex.input("in1", encoder_states);
        ex.input("in2", attention_mask);
        for (size_t i = 0; i < kv_cache_indexes.size(); i++)
            ex.input(kv_cache_indexes[i], kvcache[i]);
        out_kvcache.resize(out_kv_cache_indexes.size());
        int rc0, rc1 = 0, rc2 = 0;
        for (size_t i = 0; i < out_kv_cache_indexes.size(); i++)
        {
            // Cross-attention KV (индексы 2,3 / 6,7 / 10,11 / 14,15: i%4==2||3) —
            // константные 64x1500x20, зависят только от encoder, НЕ меняются между шагами.
            // Их переизвлечение через extract на каждом шаге при kvcache_allocator'е давало
            // stall (зависание на ~8-м шаге декодера при живой длинной речи, замер 21:17).
            // Боремся: берём cross-attn kv из входного кэша как есть (const), не трогая extract.
            // ЗАЩИТА layout (другой MHA-порядок модели): cross-KV обязан иметь константный
            // shape между шагами (только encoder). Если h изменился — это растущий self-KV
            // (не [self,cross] на слой) — навсегда откатываемся на extract, иначе тихий мусор.
            if (m_cross_layout_ok && (i % 4 == 2 || i % 4 == 3))
            {
                if (m_cross_kv_h == -1)
                {
                    if (kvcache[i].w <= 0 || kvcache[i].h <= 0 || kvcache[i].c <= 0)
                    {
                        // Пустой/нулевой cross-кандидат: prefill не заполнил — не доверяем
                        // схеме, откат на extract (иначе запомнили бы нули и откатили всё).
                        NCNN_PHASE("  kv[%d] cross guess empty (shape %dx%dx%d) -> откат",
                            (int)i, kvcache[i].w, kvcache[i].h, kvcache[i].c);
                        m_cross_layout_ok = false;
                    }
                    else
                    {
                        // Запоминаем ПОЛНУЮ тройку (w,h,c), а не только h (Native-13):
                        // layout-подтверждение по одному h ложно-позитивно для сеток,
                        // где у self/cross совпадает высота (многие whisper-варианты).
                        m_cross_kv_w = kvcache[i].w;
                        m_cross_kv_h = kvcache[i].h;
                        m_cross_kv_c = kvcache[i].c;
                        NCNN_PHASE("  kv cross guess: shape %dx%dx%d (layout [self,cross])",
                            m_cross_kv_w, m_cross_kv_h, m_cross_kv_c);
                        out_kvcache[i] = kvcache[i]; // shallow: refcount держит блок из prefill
                        continue;
                    }
                }
                else if (m_cross_kv_w == kvcache[i].w &&
                         m_cross_kv_h == kvcache[i].h &&
                         m_cross_kv_c == kvcache[i].c)
                {
                    // Если у другого cross-слоя shape иной (теоретически: encoder_states один
                    // на всех, поэтому нет) — здесь получим !=  → откат, НЕ тихий мусор.
                    out_kvcache[i] = kvcache[i]; // shallow: refcount живого блока из prefill;
                    // аллокатор (PoolAllocator) не отдаст занятый блок, пока жив хоть один
                    // Mat — порча из-за переиспользования невозможна; extract для cross
                    // не вызывается (continue) — записи в этот буфер нет.
                    continue;
                }
                else
                {
                    NCNN_PHASE("  kv[%d] layout mismatch: shape %dx%dx%d != %dx%dx%d -> откат cross-оптимизации",
                        (int)i, kvcache[i].w, kvcache[i].h, kvcache[i].c,
                        m_cross_kv_w, m_cross_kv_h, m_cross_kv_c);
                    m_cross_layout_ok = false;
                }
                // fallthrough на extract: НЕ клонируем out_kvcache[i], даже если он
                // алиасит kvcache[i] — внутришаговый вход=выход это ШТАТНЫЙ протокол
                // ncnn KV (self-индексы всегда так: выход прошлого шага = вход текущего,
                // extract пишет в тот же буфер; экстрактор читает вход на ранних слоях
                // пайплайна до записи результата). Откат cross приводит cross-индексы
                // к тому же паттерну — дополнительная защита не нужна.
            }
            rc0 = ex.extract(out_kv_cache_indexes[i], out_kvcache[i], 1);
            NCNN_TRACE("  kv_out[%d] extract rc=%d shape(%d,%d,%d)", (int)i, rc0, out_kvcache[i].w,out_kvcache[i].h,out_kvcache[i].c);
            if (rc0 != 0) rc1 = rc0;
        }
        rc2 = ex.extract("out0", output_states);
        NCNN_TRACE("  out0 extract rc=%d shape(%d,%d,%d)", rc2, output_states.w,output_states.h,output_states.c);
        if (rc1) return rc1;
        if (rc2) return rc2;
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
    // Число потоков ДО load: gemm-слой фиксирует значение при первой загрузке модели
    // (иначе предупреждение 'gemm will use load-time value' и медленный single-gemm).
    // encoder — крупные gemm: 8 потоков (у OPPO 8 ядер) для ускорения <8с.
    // decoder/fbank — серийные мелкие шаги автогрегрессии: оставляем 4.
    g_whisper->set_num_threads(4);
    g_whisper->set_encoder_threads(8);
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

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_whispercpp_whisper_NcnnWhisperLib_nativeLatencyProfile(JNIEnv* env, jobject /*thiz*/)
{
    // [fbank_ms, encoder_ms, decoder_ms, decoder_steps] последнего transcribe.
    jlong out[4];
    out[0] = (jlong)g_last_fbank_ms;
    out[1] = (jlong)g_last_encoder_ms;
    out[2] = (jlong)g_last_decoder_ms;
    out[3] = (jlong)g_last_decoder_steps;
    jlongArray arr = env->NewLongArray(4);
    if (!arr) return NULL;
    env->SetLongArrayRegion(arr, 0, 4, out);
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_NcnnWhisperLib_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/)
{
    g_whisper.reset();
}
