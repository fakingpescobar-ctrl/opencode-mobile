#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <sys/sysinfo.h>
#include <string.h>
#include "whisper.h"
#include "ggml.h"

#define UNUSED(x) (void)(x)
#define TAG "JNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,     TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,     TAG, __VA_ARGS__)

static inline int min(int a, int b) {
    return (a < b) ? a : b;
}

static inline int max(int a, int b) {
    return (a > b) ? a : b;
}

// Валидирует UTF-8 последовательность начиная с s[0].
// Возвращает длину последовательности (1..4) или 0 если байты битые
// (overlong, суррогаты, > U+10FFFF, оборванный хвост).
static int utf8_seq_len(const unsigned char *s, size_t remaining) {
    unsigned char c = s[0];
    if (c < 0x80) {
        return 1;
    }
    if (c >= 0xC2 && c <= 0xDF) {
        if (remaining < 2 || (s[1] & 0xC0) != 0x80) return 0;
        return 2;
    }
    if (c >= 0xE0 && c <= 0xEF) {
        if (remaining < 3 || (s[1] & 0xC0) != 0x80 || (s[2] & 0xC0) != 0x80) return 0;
        if (c == 0xE0 && s[1] < 0xA0) return 0;      // overlong 3-byte
        if (c == 0xED && s[1] >= 0xA0) return 0;     // surrogate (U+D800..U+DFFF)
        return 3;
    }
    if (c >= 0xF0 && c <= 0xF4) {
        if (remaining < 4 || (s[1] & 0xC0) != 0x80 || (s[2] & 0xC0) != 0x80 || (s[3] & 0xC0) != 0x80) return 0;
        if (c == 0xF0 && s[1] < 0x90) return 0;      // overlong 4-byte
        if (c == 0xF4 && s[1] >= 0x90) return 0;     // > U+10FFFF
        return 4;
    }
    return 0; // 0x80..0xC1, 0xF5..0xFF — битые
}

// Строит jstring из произвольного текста декодера БЕЗ краша:
// невалидные UTF-8 последовательности и контрольные символы (кроме \t\n\r)
// заменяются на '?', NUL игнорируется. CheckJNI абортит NewStringUTF
// на битых байтах — этот слой не даёт уронить приложение.
static jstring safe_new_string_utf(JNIEnv *env, const char *text) {
    if (text == NULL) {
        text = "";
    }
    size_t len = strlen(text);
    if (len > 4096) { // сегменты короче, но страхуемся от гигантского мусора
        len = 4096;
    }
    char *buf = malloc(len + 1);
    if (buf == NULL) {
        return (*env)->NewStringUTF(env, "");
    }
    size_t out = 0;
    size_t i = 0;
    while (i < len) {
        unsigned char c = (unsigned char) text[i];
        if (c == '\t' || c == '\n' || c == '\r') {
            buf[out++] = (char) c;
            i++;
            continue;
        }
        if (c == 0x00) { // NUL — мусор декодера
            i++;
            continue;
        }
        if (c >= 0x80) {
            int seq = utf8_seq_len((const unsigned char *) text + i, len - i);
            if (seq > 0) {
                memcpy(buf + out, text + i, seq);
                out += seq;
                i += seq;
                continue;
            }
        } else if (c >= 0x20) {
            buf[out++] = (char) c;
            i++;
            continue;
        }
        buf[out++] = '?';
        i++;
    }
    buf[out] = '\0';
    jstring result = (*env)->NewStringUTF(env, buf);
    free(buf);
    return result;
}

struct input_stream_context {
    size_t offset;
    JNIEnv * env;
    jobject thiz;
    jobject input_stream;

    jmethodID mid_available;
    jmethodID mid_read;
};

size_t inputStreamRead(void * ctx, void * output, size_t read_size) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint avail_size = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    jint size_to_copy = read_size < avail_size ? (jint)read_size : avail_size;

    jbyteArray byte_array = (*is->env)->NewByteArray(is->env, size_to_copy);

    jint n_read = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_read, byte_array, 0, size_to_copy);

    if (size_to_copy != read_size || size_to_copy != n_read) {
        LOGI("Insufficient Read: Req=%zu, ToCopy=%d, Available=%d", read_size, size_to_copy, n_read);
    }

    jbyte* byte_array_elements = (*is->env)->GetByteArrayElements(is->env, byte_array, NULL);
    memcpy(output, byte_array_elements, size_to_copy);
    (*is->env)->ReleaseByteArrayElements(is->env, byte_array, byte_array_elements, JNI_ABORT);

    (*is->env)->DeleteLocalRef(is->env, byte_array);

    is->offset += size_to_copy;

    return size_to_copy;
}
bool inputStreamEof(void * ctx) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint result = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    return result <= 0;
}
void inputStreamClose(void * ctx) {

}

JNIEXPORT jlong JNICALL
Java_com_whispercppdemo_whisper_WhisperLib_00024Companion_initContextFromInputStream(
        JNIEnv *env, jobject thiz, jobject input_stream) {
    UNUSED(thiz);

    struct whisper_context *context = NULL;
    struct whisper_model_loader loader = {};
    struct input_stream_context inp_ctx = {};

    inp_ctx.offset = 0;
    inp_ctx.env = env;
    inp_ctx.thiz = thiz;
    inp_ctx.input_stream = input_stream;

    jclass cls = (*env)->GetObjectClass(env, input_stream);
    inp_ctx.mid_available = (*env)->GetMethodID(env, cls, "available", "()I");
    inp_ctx.mid_read = (*env)->GetMethodID(env, cls, "read", "([BII)I");

    loader.context = &inp_ctx;
    loader.read = inputStreamRead;
    loader.eof = inputStreamEof;
    loader.close = inputStreamClose;

    loader.eof(loader.context);

    context = whisper_init(&loader);
    return (jlong) context;
}

static bool model_is_quantized(const char *path) {
    if (!path) return false;
    // Теги квантов ggml-конвенции whisper.cpp: q4_0 q4_1 q4_k, q5_0 q5_1 q5_k,
    // q8_0, q2_k, q3_k, q6_K, iq1_s iq2_xxs iq3_xxs и т.п.
    static const char *tags[] = {"q4_", "q5_", "q8_", "q2_", "q3_", "q6_", "iq1_", "iq2_", "iq3_"};
    const char *name = strrchr(path, '/');
    name = name ? name + 1 : path;
    for (size_t i = 0; i < sizeof(tags) / sizeof(tags[0]); i++) {
        if (strstr(name, tags[i]) != NULL) {
            return true;
        }
    }
return false;
}

// Метка-файл рядом с моделью позволяет переключать бэкенд на живом телефоне,
// без пересборки .so: `quant_gpu` — пустить квантованную модель на GPU,
// `cpu` — наоборот, удержать ЛЮБУЮ модель на процессоре. Метка лежит в
// каталоге модели, а не флаг сборки, потому что переключать приходится на
// устройстве, где собирать нечего.
static bool marker_present(const char *path, const char *name) {
    if (!path) return false;
    const char *slash = strrchr(path, '/');
    if (!slash) return false;
    const size_t dir_len = (size_t)(slash - path);
    const size_t name_len = strlen(name);
    char marker[4096];
    if (dir_len + 1 + name_len + 1 >= sizeof(marker)) return false;
    memcpy(marker, path, dir_len);
    marker[dir_len] = '/';
    memcpy(marker + dir_len + 1, name, name_len + 1);
    FILE *f = fopen(marker, "rb");
    if (!f) return false;
    fclose(f);
    return true;
}

static bool quant_gpu_override(const char *path) {
    return marker_present(path, "quant_gpu");
}

static bool force_cpu_override(const char *path) {
    return marker_present(path, "cpu");
}

// Квантованные модели на GPU дают мусор: замер 14.10.2026, q5_0 + flash_attn
// дал WER ~3500 % и rtf 47,64 против 6,25 на CPU. Значит виноват квант, а не
// attention, и гейт по имени файла обязателен.
static bool use_gpu_for(const char *path) {
    if (!path) return false;
    if (force_cpu_override(path)) return false;
    if (!model_is_quantized(path)) return true;
    return quant_gpu_override(path);
}
static size_t asset_read(void *ctx, void *output, size_t read_size) {
    return AAsset_read((AAsset *) ctx, output, read_size);
}

static bool asset_is_eof(void *ctx) {
    return AAsset_getRemainingLength64((AAsset *) ctx) <= 0;
}

static void asset_close(void *ctx) {
    AAsset_close((AAsset *) ctx);
}

static struct whisper_context *whisper_init_from_asset(
        JNIEnv *env,
        jobject assetManager,
        const char *asset_path
) {
    LOGI("Loading model from asset '%s'\n", asset_path);
    AAssetManager *asset_manager = AAssetManager_fromJava(env, assetManager);
    AAsset *asset = AAssetManager_open(asset_manager, asset_path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGW("Failed to open '%s'\n", asset_path);
        return NULL;
    }

whisper_model_loader loader = {
            .context = asset,
            .read = &asset_read,
            .eof = &asset_is_eof,
            .close = &asset_close
    };

    // GPU (ggml-vulkan) на Adreno включаем ТОЛЬКО для f16-моделей.
    //
    // Замеры на OnePlus CPH2747 / Snapdragon 8 Elite Gen 5 / Adreno 840 (13.10.2026):
    //   ggml-base f16             + GPU: rtf 0,07-0,13, текст точный
    //   ggml-large-v3-turbo-q5_0  + GPU: rtf 58,52, текст МУСОР на эталоне "Кто ты?"
    //   ggml-base f16             CPU : rtf 0,38,   текст точный
    //   ggml-large-v3-turbo-q5_0  CPU : rtf 6,25,   текст точный
    //
    // То есть ggml-vulkan на Adreno не сломан — его ломает КВАНТОВАНИЕ (q5_0). На
    // кванте GPU в 9 раз МЕДЛЕНЕЕ CPU и выдаёт мусор вместо текста. Поэтому решает
    // имя файла, а не флаг: модель с тегом кванта уходит на CPU.
    //
    // Прежде здесь стояло use_gpu = false с комментарием "выдаёт мусор и медленнее
    // CPU" — это было обобщение краша ncnn на ggml-vulkan. Формулировка была верной
    // только про кванты, и потому отключала и f16, который на GPU отлично работает.
    struct whisper_context_params ctx_params = whisper_context_default_params();
    ctx_params.use_gpu = use_gpu_for(asset_path);
    return whisper_init_with_params(&loader, ctx_params);
}


JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    struct whisper_context_params ctx_params = whisper_context_default_params();
    // Квант на Adreno+Vulkan даёт мусор и в сотни раз БОЛЬШЕ времени, чем CPU.
    // f16 на той же связке отличен (rtf 0,07 против 6,25 у turbo-q5_0). Решает имя файла.
    ctx_params.use_gpu = use_gpu_for(model_path_chars);
    // Тир 1 плана оптимизации: flash-attention (поле в context_params, не full_params).
    // Меняет арифметику внимания, поэтому сверяем и текст против эталона, а не только
    // время. Выигрыш в ggml даёт fp16-вход, то есть ровно наш случай — GPU + f16.
    ctx_params.flash_attn = true;
    context = whisper_init_from_file_with_params(model_path_chars, ctx_params);
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    whisper_free(context);
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads, jfloatArray audio_data) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);

    // The below adapted from the Objective-C iOS sample
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = true;
    params.print_progress = false;
    params.print_timestamps = true;
    params.print_special = false;
    params.translate = false;
    params.language = "ru";
    // Русский контекст для декодера: заметно снижает бред на коротких клипах.
    params.initial_prompt = "Разговорная русская речь.";
    // GREEDY с дефолтным temperature fallback: перезапуск декода с температурой
    // при плохом результате чистит мусор лучше, чем beam без fallback.
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;

    whisper_reset_timings(context);

    LOGI("About to run whisper_full");
    if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
        LOGI("Failed to run the model");
    } else {
        whisper_print_timings(context);
    }
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_n_segments(context);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *text = whisper_full_get_segment_text(context, index);
    return safe_new_string_utf(env, text);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t0(context, index);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t1(context, index);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv *env, jobject thiz
) {
    UNUSED(thiz);
    const char *sysinfo = whisper_print_system_info();
    return safe_new_string_utf(env, sysinfo);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_benchMemcpy(JNIEnv *env, jobject thiz,
                                                                      jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_memcpy = whisper_bench_memcpy_str(n_threads);
    return safe_new_string_utf(env, bench_ggml_memcpy);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_benchGgmlMulMat(JNIEnv *env, jobject thiz,
                                                                          jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_mul_mat = whisper_bench_ggml_mul_mat_str(n_threads);
    return safe_new_string_utf(env, bench_ggml_mul_mat);
}
