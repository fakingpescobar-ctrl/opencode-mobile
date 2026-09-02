// Local harness: гоняет whisper encoder (fp32 и int8) на одном входе и считает
// cosine similarity / max|Δ| между выходными блобами, плюс время прогона.
// Вход: синтетический mel-подобный спектр (128 mel x 3000 time), близкий к речи
// (тональные компоненты + шум), чтобы активировать MHA/Gemm-пути.
//
// Сборка:
//   cd C:\Projects\opencode-mobile\tools\ncnn-int8
//   cmake -S . -B build -G "Visual Studio 17 2022" -A x64
//   cmake --build build --config Release
//   build\Release\compare_encoder.exe turbo

#include <cstdio>
#include <cstdlib>
#include <cmath>
#include <string>
#include <chrono>
#include "net.h"

static double cosine(const float* a, const float* b, int n)
{
    double dot = 0, na = 0, nb = 0;
    for (int i = 0; i < n; i++) {
        dot += (double)a[i] * b[i];
        na  += (double)a[i] * a[i];
        nb  += (double)b[i] * b[i];
    }
    if (na < 1e-12 || nb < 1e-12) return 0.0;
    return dot / (std::sqrt(na) * std::sqrt(nb));
}

static float max_abs_delta(const float* a, const float* b, int n)
{
    float m = 0;
    for (int i = 0; i < n; i++) {
        float d = std::fabs(a[i] - b[i]);
        if (d > m) m = d;
    }
    return m;
}

int main(int argc, char** argv)
{
    std::string dir = (argc > 1) ? argv[1] : "turbo";
    std::string p   = dir + "/whisper_turbo_encoder";
    const int W = 3000, H = 128;

    // читаем реальный log-mel из mel_input.npy (C-order, float32, (3000,128))
    ncnn::Mat in(W, H, 1);
    // npy header: magic(6) + version(2) + header dump (до '\n')
    FILE* fn = fopen("mel_input.npy", "rb");
    if (!fn) { printf("FAIL: mel_input.npy not found (run make_real_mel.py)\n"); return 1; }
    char hdr[512]; 
    for (int i = 0; i < 512 && !feof(fn); i++) { 
        hdr[i] = (char)fgetc(fn); 
        if (hdr[i] == '\n') { hdr[i] = 0; break; } 
    }
    (void)hdr;
    size_t rd = fread(in.data, 1, W * H * sizeof(float), fn);
    fclose(fn);
    if (rd != W * H * sizeof(float)) { printf("FAIL: read npy data\n"); return 1; }

    ncnn::Net fp32_net, int8_net;
    ncnn::Option opt;
    opt.use_vulkan_compute = false;
    opt.use_fp16_packed = false;
    opt.use_fp16_storage = false;
    opt.use_fp16_arithmetic = false;
    opt.num_threads = 8;
    fp32_net.opt = opt;
    int8_net.opt  = opt;

    if (fp32_net.load_param((p + ".ncnn.param").c_str()) != 0 ||
        fp32_net.load_model((p + ".ncnn.bin").c_str()) != 0) {
        printf("FAIL: cannot load fp32 %s\n", p.c_str()); return 1;
    }
    std::string ip = dir + "/whisper_turbo_encoder_int8.ncnn.param";
    std::string ib = dir + "/whisper_turbo_encoder_int8.ncnn.bin";
    if (int8_net.load_param(ip.c_str()) != 0 || int8_net.load_model(ib.c_str()) != 0) {
        printf("FAIL: cannot load int8 %s\n", ip.c_str()); return 1;
    }

    ncnn::Extractor ex32 = fp32_net.create_extractor();
    ncnn::Extractor ex8  = int8_net.create_extractor();
    ex32.input("in0", in);
    ex8.input("in0", in);

    auto t0 = std::chrono::steady_clock::now();
    ncnn::Mat out32;
    ex32.extract("out0", out32);
    auto t1 = std::chrono::steady_clock::now();
    ncnn::Mat out8;
    ex8.extract("out0", out8);
    auto t2 = std::chrono::steady_clock::now();

    if (out32.empty() || out8.empty()) { printf("FAIL: empty output\n"); return 1; }

    // числа элементов одинаковы?
    int n32 = out32.w * out32.h * out32.c;
    int n8  = out8.w  * out8.h  * out8.c;
    printf("out fp32: w=%d h=%d c=%d (n=%d)\n", out32.w, out32.h, out32.c, n32);
    printf("out int8: w=%d h=%d c=%d (n=%d)\n", out8.w,  out8.h,  out8.c,  n8);
    if (n32 != n8) { printf("FAIL: output size mismatch\n"); return 1; }

    double cosim = cosine((const float*)out32.data, (const float*)out8.data, n32);
    float  mad   = max_abs_delta((const float*)out32.data, (const float*)out8.data, n32);
    double tfp32 = std::chrono::duration<double, std::milli>(t1 - t0).count();
    double tint8 = std::chrono::duration<double, std::milli>(t2 - t1).count();

    printf("cosine(fp32,int8) = %.5f\n", cosim);
    printf("max|delta|        = %.5f\n", mad);
    printf("time fp32=%.1fms  int8=%.1fms  speedup=%.2fx\n", tfp32, tint8, tfp32 / tint8);
    printf("%s\n", (cosim >= 0.98) ? "PASS (cosine>=0.98)" : "WARN (cosine<0.98)");
    return (cosim >= 0.98) ? 0 : 2;
}