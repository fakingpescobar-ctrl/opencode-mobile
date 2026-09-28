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
#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

// ---- whisper token constants (base / v1-v3 block) ----
static const int token_endoftext = 50257;
static const int token_startoftranscript = 50258;
static const int token_transcribe = 50360;
static const int token_notimestamps = 50364;

// Языковые токены whisper: 50259..50357 (99 языков, канонический порядок
// multilingual-чекпоинта). Нужен целиком, а не только ru/en: авто-определение
// выбирает argmax по всему диапазону, и без имён вернуть наружу нечего.
static const int token_lang_first = 50259;
static const int token_lang_count = 99;
static const char* token_langs[token_lang_count] = {
    "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar",
    "sv", "it", "id", "hi", "fi", "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu",
    "ta", "no", "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk", "te", "fa",
    "lv", "bn", "sr", "az", "sl", "kn", "et", "mk", "br", "eu", "is", "hy", "ne", "mn",
    "bs", "kk", "sq", "sw", "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc",
    "ka", "be", "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo", "ht", "ps", "tk", "nn",
    "mt", "sa", "lb", "my", "bo", "tl", "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw",
    "su",
};

// Имя языка -> id токена, либо -1. Раньше здесь было два if на ru/en, из-за чего
// любой другой язык молча превращался в русский, а английский текст переводился.
static int token_lang_of(const char* lang)
{
    if (!lang) return -1;
    for (int i = 0; i < token_lang_count; i++)
    {
        if (strcmp(token_langs[i], lang) == 0) return token_lang_first + i;
    }
    return -1;
}
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

    // Определяет язык по аудио и транскрибирует ОДНИМ проходом энкодера.
    //
    // Ключевой момент: encoder_states — самый дорогой артефакт, и он НЕ зависит
    // от языка. Если вызвать определение языка отдельной функцией, а потом
    // transcribe(), энкодер считается дважды: на телефоне это ~6.5 с, то есть
    // удвоение фразы. Здесь язык читается из того же prefill, чьи encoder_states
    // потом уходят в декодер, поэтому добавка — один шаг префилла (десятки мс).
    int transcribe_auto(const std::vector<short>& samples, std::string& lang, std::string& text) const;

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

    // Greedy-декодирование по уже посчитанным encoder_states. Вынесено отдельно,
    // чтобы transcribe() и transcribe_auto() не расходились в двух копиях цикла.
    int decode(const ncnn::Mat& encoder_states, int token_lang, std::string& text) const;
    // fbank + encoder с записью фаз в g_last_* — общая часть обоих входов.
    int encode(const std::vector<short>& samples, ncnn::Mat& encoder_states) const;
    // id языкового токена с максимальным логитом + его вероятность.
    int pick_lang(const ncnn::Mat& logits, float& conf) const;
    // Подавление повторов перед argmax: n-граммы, которые уже встречались,
    // и мягкий штраф за уже выданные токены. start - конец промпта,
    // n-граммы и штраф на служебные токены не распространяются.
    void apply_repetition_penalty(ncnn::Mat& logits, const std::vector<int>& decoded, int start) const;

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

int Whisper::encode(const std::vector<short>& samples, ncnn::Mat& encoder_states) const
{
    ncnn::Mat input_features;
    {
        auto t0 = std::chrono::steady_clock::now();
        extract_fbank_feature(samples, input_features);
        double f_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        NCNN_PHASE("ncnn phase fbank=%.0fms", f_ms);
        g_last_fbank_ms = f_ms;
    }

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
    return 0;
}

int Whisper::pick_lang(const ncnn::Mat& logits, float& conf) const
{
    // argmax по диапазону языков - ровно как в референсном whisper_lang_auto_detect
    // (third_party/ncnn/examples/whisper.cpp): выбор не подменяем эвристикой.
    int best_id = token_lang_first;
    float best = logits[token_lang_first];
    for (int i = token_lang_first; i < token_lang_first + token_lang_count; i++)
    {
        const float v = logits[i];
        if (v > best)
        {
            best = v;
            best_id = i;
        }
    }

    // conf - нормированная вероятность языка внутри набора из 99 языков
    // (softmax, устойчивый к переполнению за счёт вычитания max). Считается
    // ТОЛЬКО для лога: порог по нему без замеров на устройстве поставил бы
    // автоопределение наугад, поэтому гейтинг вынесен в замеры, а не в код.
    double sum = 0.0;
    for (int i = token_lang_first; i < token_lang_first + token_lang_count; i++)
        sum += exp((double)(logits[i] - best));
    conf = (sum > 0.0) ? (float)(1.0 / sum) : 0.f;
    return best_id;
}

// whisper на коротких клипах с микропаузами любит зациклиться и выдать одну
// фразу трижды подряд. Здесь два слоя защиты, как в whisper.cpp:
//
//  1) no-repeat n-грамм (n = 3): токен, который закрыл бы уже встречавшуюся
//     тройку, получает -inf. Это ломает именно повтор ФРАЗЫ целиком.
//  2) мягкий штраф за уже выданные токены: logit делится/умножается на
//     коэффициент, а не обнуляется - слово всё ещё можно выдать, если
//     модель настаивает. Жёсткий запрет выкашивал бы легитимные повторы
//     («да, да, именно»).
void Whisper::apply_repetition_penalty(ncnn::Mat& logits, const std::vector<int>& decoded, int start) const
{
    const int total = (int)logits.total();
    if (total <= 0 || start < 0) return;
    const int len = (int)decoded.size();
    if (start >= len) return;

    // 1) no-repeat n-граммы. Ищем только по выданному тексту (j >= start),
    //    иначе под ban могли бы попасть служебные токены промпта.
    const int n = 3;
    if (len - start >= n)
    {
        const int* suffix = &decoded[len - (n - 1)];
        for (int j = start; j + n - 1 <= len; j++)
        {
            bool match = true;
            for (int k = 0; k < n - 1; k++)
                if (decoded[j + k] != suffix[k]) { match = false; break; }
            if (!match) continue;
            const int next = decoded[j + n - 1];
            if (next >= 0 && next < total) logits[next] = -INFINITY;
        }
    }

    // 2) мягкий штраф за повтор языкового токена: не даём декодеру
    //    перебить текст на другой язык посреди сегмента.
    const float penalty = 1.15f;
    for (int i = start; i < len; i++)
    {
        const int t = decoded[i];
        if (t < token_lang_first || t >= token_lang_first + token_lang_count) continue;
        const float v = logits[t];
        logits[t] = (v > 0.f) ? v / penalty : v * penalty;
    }
}

int Whisper::transcribe(const std::vector<short>& samples, const char* lang, std::string& text) const
{
    int token_lang = token_lang_of(lang);
    if (token_lang == -1)
    {
        NCNN_PHASE("ncnn error: language '%s' not supported", lang ? lang : "(null)");
        return -1;
    }

    ncnn::Mat encoder_states;
    if (encode(samples, encoder_states) != 0) return -1;
    return decode(encoder_states, token_lang, text);
}

int Whisper::transcribe_auto(
    const std::vector<short>& samples,
    std::string& lang,
    std::string& text) const
{
    ncnn::Mat encoder_states;
    if (encode(samples, encoder_states) != 0) return -1;

    // Один префилл на [sot]: из его логитов берётся язык, и он же открывает
    // декодирование. Энкодер к этому моменту уже посчитан и переиспользуется —
    // второго run_encoder здесь нет намеренно.
    std::vector<int> ids_sot(1);
    ids_sot[0] = token_startoftranscript;

    ncnn::Mat logits;
    std::vector<ncnn::Mat> out_kvcache;
    if (run_decoder_prefill(ids_sot, encoder_states, logits, out_kvcache) != 0 || logits.empty())
    {
        NCNN_PHASE("ncnn error: lang prefill failed");
        return -1;
    }

    float conf = 0.f;
    const int token_lang = pick_lang(logits, conf);
    lang = token_langs[token_lang - token_lang_first];
    NCNN_PHASE("ncnn detected lang=%s conf=%.3f", lang.c_str(), conf);

    return decode(encoder_states, token_lang, text);
}

int Whisper::decode(const ncnn::Mat& encoder_states, int token_lang, std::string& text) const
{
    std::vector<int> ids(4);
    ids[0] = token_startoftranscript;
    ids[1] = token_lang;
    ids[2] = token_transcribe;
    ids[3] = token_notimestamps;

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

        // Подавляем повторы ДО argmax, иначе штраф не на что подействовать.
        // Защита не трогает prompt (первые ids.size() токенов).
        if (step > 0) apply_repetition_penalty(logits, decoded, (int)ids.size());

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

// Модель, сидящая в g_whisper. Один глобальный инстанс — значит и одна модель;
// без этого поля второй nativeInit с другим base молча перетирал первый, и
// держатель первой модели получал чужое состояние.

// Счётчик владельцев. Раньше владельцем считался объект Kotlin, но состояние
// процессное: finalize() одного контекста вызывал g_whisper.reset() и убивал
// модель у живого контекста — STT ложился с «ncnn whisper not initialized».
// Теперь освобождает последний владелец, а не любой.
static std::string g_loaded_dir;
static std::string g_loaded_base;
static int g_refcount = 0;

// Мьютекс на всё процессное состояние модели.
//
// Зачем, если "всё равно один worker-поток" (инвариант сервиса): после правки
// 28.09 g_refcount стал ДОКАЗАТЕЛЬСТВОМ для решения "отказать или грузить".
// Проверка "занято ли" обязана быть атомарной с самим освобождением, иначе
// два потока проходят проверку одновременно и оба грузят модель: двойное
// чтение 1.6-2.3 ГБ с диска и перепутанный счётчик. Раньше это не было
// заметно, потому что проверки не было - состояние трогалось безусловно.
//
// Держится и на время load(), и это правильно: загрузка модели по природе
// эксклюзивна, и второй поток обязан ждать, а не грузить своё поверх.
// Взаимной блокировки нет: из под лока не вызывается JNI обратно в Kotlin.
static std::mutex g_model_mutex;

// ---- in-flight guard ----
//
// Счётчик выводов, которые уже начались и ещё не закончились. Живёт под
// g_model_mutex, поэтому проверка "есть ли контекст" и увеличение счётчика
// неразделимы.
//
// Зачем он вообще: g_whisper - unique_ptr, то есть ОБЩЕГО ВЛАДЕНИЯ НЕТ. Ни
// копии указателя, ни shared_ptr удержать контекст не могут, единственный
// способ - этот счётчик. Без него nativeFree() делает g_whisper.reset() и
// ~Whisper() сносит сети и пулы, пока другой поток стоит внутри
// g_whisper->transcribe() в OpenMP-регионе.
//
// Это не теория, это прогон F, 28.09, дословно:
//
//   I/CHUNKED  (16766): начало 2-го чанка из 9   <- тест в transcribe
//   D/VOICE    (16766): worker: задание #1, 79680 <- воркер сервиса грузит модель
//   F/libc     (16766): Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE)
//                       in tid 16869 (pool-5-thread-1)
//   F/DEBUG    (16872): #03 ... libncnnwhisper.so (__kmp_debug_assert+140)
//
// __kmp_debug_assert - ассерт рантайма OpenMP, а не OOM: кто-то разрушил
// состояние параллельной области из-под работающего региона. Мьютекс
// g_model_mutex этот баг не ловил и не мог поймать: он защищает БУХГАЛТЕРИЮ
// (g_refcount, g_loaded_dir), а весь вывод идёт без него, иначе любые два
// параллельных запроса сериализовались бы в очередь.
static int g_inflight = 0;
static std::condition_variable g_inflight_cv;

// RAII-обёртка: конструктор атомарно проверяет контекст и занимает слот,
// деструктор освобождает его на ЛЮБОМ выходе, включая ранний return и
// исключение. Ручной ++/-- в функции с несколькими return'ами - это ровно тот
// класс бага, который здесь и чинится, поэтому счётчик не трогаем руками.
class InFlight {
public:
    // false = контекста нет, вызывающий обязан бросить "not initialized".
    bool acquire()
    {
        std::lock_guard<std::mutex> lk(g_model_mutex);
        if (!g_whisper) return false;
        ++g_inflight;
        m_held = true;
        return true;
    }

    ~InFlight()
    {
        if (!m_held) return;
        std::lock_guard<std::mutex> lk(g_model_mutex);
        if (--g_inflight == 0) g_inflight_cv.notify_all();
    }

    InFlight(const InFlight&) = delete;
    InFlight& operator=(const InFlight&) = delete;
    // ОБЯЗАТЕЛЬНО: удалённый копирующий конструктор ПОДАВЛЯЕТ неявный
    // дефолтный (C++11), а три JNI-входа объявляют `InFlight inFlight;`.
    // Без этой строки сборка падает "no matching constructor".
    InFlight() = default;

private:
    bool m_held = false;
};

// ---- JNI: Java_com_whispercpp_whisper_NcnnWhisperLib_* ----
extern "C" JNIEXPORT jboolean JNICALL
Java_com_whispercpp_whisper_NcnnWhisperLib_nativeInit(JNIEnv* env, jobject /*thiz*/, jstring modelDir, jstring base)
{
    const char* dir = env->GetStringUTFChars(modelDir, 0);
    const char* b = env->GetStringUTFChars(base, 0);
    std::string dirStr = dir ? dir : "";
    std::string baseStr = b ? b : "whisper_base";

    // Лок берём ДО чтения g_loaded_dir/g_refcount: проверка "кто владеет" и сама
    // загрузка должны быть одной атомарной операцией (см. g_model_mutex).
    std::lock_guard<std::mutex> guard(g_model_mutex);

    // Тот же base уже сидит в памяти — делим инстанс вместо повторного load()
    // с диска. Раньше каждый вызов пересоздавал g_whisper, то есть держатели одной
    // модели вышибали состояние друг у друга.
    if (g_whisper && g_loaded_dir == dirStr && g_loaded_base == baseStr)
    {
        g_refcount++;
        if (dir) env->ReleaseStringUTFChars(modelDir, dir);
        if (b) env->ReleaseStringUTFChars(base, b);
        return JNI_TRUE;
    }

    // Другая модель. Вытеснять её молча НЕЛЬЗЯ: ею владеют живые контексты, и
    // после g_whisper.reset() их Kotlin-флаг initialized остаётся true при уже
    // мёртвой модели - владелец узнаёт об этом только на следующей транскрипции,
    // через ensureAlive(), который заново грузит модель и вышибает чужую.
    //
    // В бенче это было не «теоретически плохо», а конкретные числа: матрица
    // int8/fp32 чередуется по wav, и каждый переход перечитывал модель с диска -
    // 10 загрузок по 1.6-2.3 ГБ за прогон. RSS при этом не падал обратно
    // (glibc не отдаёт крупные блоки в ОС без trim), и процесс умирал от
    // lowmemorykiller - с ПУСТЫМ краш-буфером, то есть без Java-стека. Ровно то,
    // что наблюдалось 27.09 и что вначале обвинили в R8.
    //
    // Контракт теперь: один процесс - одна модель. Кто хочет другую - обязан
    // сначала отпустить свою (release()). В проде это условие выполнимо всегда:
    // obtainNcncContext кэширует ровно один контекст с ключом "turbo".
    if (g_whisper && g_refcount > 0)
    {
        __android_log_print(
            ANDROID_LOG_ERROR,
            "NcnnWhisper",
            "nativeInit(%s/%s) refused: %s/%s is still owned by %d holder(s). "
            "Release it first - a process holds one Whisper model at a time.",
            dirStr.c_str(),
            baseStr.c_str(),
            g_loaded_dir.c_str(),
            g_loaded_base.c_str(),
            g_refcount);
        if (dir) env->ReleaseStringUTFChars(modelDir, dir);
        if (b) env->ReleaseStringUTFChars(base, b);
        return JNI_FALSE;
    }

    // Никто не владеет (g_refcount == 0) - можно грузить поверх.
    g_whisper.reset();
    g_loaded_dir.clear();
    g_loaded_base.clear();

    std::unique_ptr<Whisper> w = std::make_unique<Whisper>();
    // Число потоков ДО load: gemm-слой фиксирует значение при первой загрузке модели
    // (иначе предупреждение 'gemm will use load-time value' и медленный single-gemm).
    // encoder — крупные gemm: 8 потоков (у OPPO 8 ядер) для ускорения <8с.
    // decoder/fbank — серийные мелкие шаги автогрессии: оставляем 4.
    w->set_num_threads(4);
    w->set_encoder_threads(8);
    int ret = w->load(dirStr, baseStr);

    if (dir) env->ReleaseStringUTFChars(modelDir, dir);
    if (b) env->ReleaseStringUTFChars(base, b);

    // При ошибке load не публикуем объект. Раньше он оставался в g_whisper
    // непустым, guard `if (!g_whisper)` в transcribe проходил, и падение
    // прилетало уже изнутри движка вместо честного JNI_FALSE.
    if (ret != 0) return JNI_FALSE;

    g_whisper = std::move(w);
    g_loaded_dir = dirStr;
    g_loaded_base = baseStr;
    g_refcount = 1;
    return JNI_TRUE;
}

// nativeSetThreads / nativeTranscribe / nativeTranscribeAuto читают g_whisper
// СОЗНАТЕЛЬНО без g_model_mutex. Не "забыли лок", а по причине:
//
//   1. Транскрипция идёт 9-40 секунд. Лок на всё это время заблокировал бы
//      nativeIsAlive (его зовёт ensureAlive() на пути к транскрипции) и
//      nativeFree, то есть health-check и release встали бы на минуты.
//   2. Взять лок ТОЛЬКО на время чтения указателя не помогает: между
//      чтением g_whisper и разыменованием указатель может сбросить
//      nativeFree. Нужен был бы shared_lock, но тогда nativeFree на
//      unique_lock ждал бы конца самой длинной транскрипции.
//
// Вместо лока действует контракт уровнем выше: модель одна на процесс, а
// освобождать её (nativeFree) запрещено, пока идёт транскрипция. Это
// зафиксировано в KDoc WhisperTranscribeService.releaseNcnnContext() и
// держится тем, что сервис работает через единственный worker-поток.
// Если это перестанет быть верно, нужен reentrant-протокол, а не лок.
// nativeSetThreads / nativeTranscribe / nativeTranscribeAuto НЕ держат
// g_model_mutex на протяжении работы - и это по-прежнему верно: держать его
// 9-40 секунд нельзя, иначе nativeIsAlive (он зовёт ensureAlive() перед каждым
// nativeFree) и любой health-check встали бы на всё время вывода, а два
// параллельных запроса сериализовались бы в очередь.
//
// НО прежнее обоснование под этой строчкой было ложным, и прогон F это доказал.
// Оно гласило: "вывод всегда завершается, поэтому освобождение безопасно, оно
// дождётся". Не дождалось - ждать было НЕЧЕГО. Мьютекс защищал только
// бухгалтерию (g_refcount, g_loaded_dir); вывод шёл мимо лока, а nativeFree
// при обнулении счётчика делал g_whisper.reset() и не проверял, что вывод ещё
// идёт. g_whisper - unique_ptr, удержать его изнутри чужого потока нечем.
//
// Теперь вместо мьютекса на всё время вывода стоит счётчик g_inflight: слот
// берётся на входе, освобождается на любом выходе (RAII), а nativeFree перед
// reset() ждёт g_inflight == 0. Долгий вывод по-прежнему не держит лок и не
// блокирует health-check, но уничтожить контекст из-под него больше нельзя.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_whispercpp_whisper_NcnnWhisperLib_nativeSetThreads(JNIEnv* /*env*/, jobject /*thiz*/, jint n)
{
    // Слот берём и здесь: set_num_threads пишет в тот же объект, что и вывод, и
    // без слота это тот же use-after-free, только без OpenMP-ассерта - тише и
    // вреднее, потому что падает не всегда.
    InFlight inFlight;
    if (!inFlight.acquire()) return JNI_FALSE;
    g_whisper->set_num_threads((int)n);
    return JNI_TRUE;
}

// Возвращает String[2] = { язык, текст }. Отдельный detect_lang() перед
// transcribe() стоил бы второго полного энкодера (~6.5 с на телефоне), потому
// что и detect_lang(), и transcribe() внутри считают fbank + encoder сами.
// Здесь язык читается из тех же encoder_states, что уходят в декодер, поэтому
// добавочная цена — один prefill шаг (десятки мс).
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_whispercpp_whisper_NcnnWhisperLib_nativeTranscribeAuto(
    JNIEnv* env, jobject /*thiz*/, jfloatArray samples)
{
    // Слот берём ДО любой работы с g_whisper и держим до конца функции: пока
    // жив InFlight, nativeFree() обязан ждать и не может уничтожить контекст
    // из-под работающего вывода. Прежняя проверка `if (!g_whisper)` читала
    // unique_ptr без лока и была гонкой сама по себе.
    InFlight inFlight;
    if (!inFlight.acquire())
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

    std::string text;
    std::string detected;
    g_whisper->transcribe_auto(s, detected, text);

    jclass strClass = env->FindClass("java/lang/String");
    jobjectArray pair = env->NewObjectArray(2, strClass, NULL);
    if (!pair) return NULL;
    env->SetObjectArrayElement(pair, 0, env->NewStringUTF(detected.c_str()));
    env->SetObjectArrayElement(pair, 1, env->NewStringUTF(text.c_str()));
    return pair;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_NcnnWhisperLib_nativeTranscribe(JNIEnv* env, jobject /*thiz*/, jfloatArray samples, jstring lang)
{
    // Слот берём ДО любой работы с g_whisper и держим до конца функции: пока
    // жив InFlight, nativeFree() обязан ждать и не может уничтожить контекст
    // из-под работающего вывода. Прежняя проверка `if (!g_whisper)` читала
    // unique_ptr без лока и была гонкой сама по себе.
    InFlight inFlight;
    if (!inFlight.acquire())
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

// Жив ли глобальный g_whisper ДЛЯ КОНКРЕТНОЙ МОДЕЛИ (dir+base совпадают с теми,
// что грузил nativeInit)?
//
// Зачем: Kotlin-флаг `initialized` и нативный refcount — два независимых
// источника истины. Любой, кто зовёт nativeFree() (например бенч со своим
// собственным контекстом), обнуляет g_whisper У ВСЕХ, оставив чужие
// контексты с флагом "готов". Дальше такой контекст навсегда возвращает
// «ncnn whisper not initialized»: фабрика getOrPut на попадании в кэш не
// перезапускается, и повторный nativeInit никто не делает. Проверка по
// dir+base, а не просто «g_whisper != nullptr»: если глобал заняла ДРУГАЯ
// модель, наш контекст тоже нерабочий — своп обратно честнее, чем падать.
//
// Возвращает JNI_TRUE, только если текущий синглтон именно наш.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_whispercpp_whisper_NcnnWhisperLib_nativeIsAlive(JNIEnv* env, jobject /*thiz*/, jstring modelDir, jstring base)
{
    if (!g_whisper) return JNI_FALSE;

    const char* d = env->GetStringUTFChars(modelDir, 0);
    const char* b = env->GetStringUTFChars(base, 0);
    std::string dirStr = d ? d : "";
    std::string baseStr = b ? b : "";
    if (d) env->ReleaseStringUTFChars(modelDir, d);
    if (b) env->ReleaseStringUTFChars(base, b);

    // Лок обязателен даже для чтения: g_loaded_dir - это std::string, и её
    // чтение параллельно с clear() в nativeFree это data race (UB), а не
    // «немного устаревшее значение». Побочный эффект правильный: если модель
    // сейчас грузится, вызов подождёт и получит честный ответ.
    std::lock_guard<std::mutex> guard(g_model_mutex);

    return (g_loaded_dir == dirStr && g_loaded_base == baseStr) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_NcnnWhisperLib_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/)
{
    // Освобождает последний владелец, а не любой: вызов от контекста, который
    // gc не убирал, больше не обнуляет модель у живого соседа.
    //
    // Тот же лок, что в nativeInit: иначе release может попасть в середину
    // загрузки чужой модели и обнулить счётчик у того, кто уже грузит.
    std::unique_lock<std::mutex> lk(g_model_mutex);

    if (g_refcount > 0) g_refcount--;
    if (g_refcount > 0) return;

    // Последний владелец ушёл, но вывод мог ещё идти: g_whisper - unique_ptr,
    // пином под ногами у него никто не стоит, и refcount про такой вызов
    // ничего не знает. Поэтому не уничтожаем, а ЖДЁМ выхода из всех started
    // вызовов - ровно тот SIGABRT из прогона F, который чинится этой строкой.
    g_inflight_cv.wait(lk, [] { return g_inflight == 0; });

    g_whisper.reset();
    g_loaded_dir.clear();
    g_loaded_base.clear();
}
