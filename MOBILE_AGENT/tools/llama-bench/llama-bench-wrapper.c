// Обёртка вокруг llama.cpp для замера реальной latency на нашем железе.
// Собирается в CI как libllama-bench.so (musl aarch64), грузится через bun:ffi + libldmusl.
// Экспортирует функции: lb_bench, lb_version.

#include "llama.h"
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static double now_s(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
}

// Возвращает: [0]=decode tokens/s, [1]=prefill ms, [2]=первый токен ms,
// [3]=всего ms, [4]=сгенерировано токенов. 0 — успех, -1 — ошибка.
int lb_bench(const char *model_path, int n_ctx, int n_predict, double *out) {
  if (!model_path || !out || n_ctx < 64 || n_predict < 1) return -1;

  llama_backend_init();
  llama_model_params mp = llama_model_default_params();
  mp.n_gpu_layers = 0;  // только CPU: сравниваем честно, без GPU-поблачек
  llama_model *model = llama_load_model_from_file(model_path, mp);
  if (!model) { llama_backend_free(); return -1; }

  llama_context_params cp = llama_context_default_params();
  cp.n_ctx = n_ctx;
  cp.n_threads = 8;       // 8 ядер CPH2747 (2×4.608 + 6×3.629 ГГц)
  cp.n_threads_batch = 8;
  llama_context *ctx = llama_new_context_with_model(model, cp);
  if (!ctx) { llama_free_model(model); llama_backend_free(); return -1; }

  // Префилл: короткий системный промпт + вопрос по памяти.
  const char *prompt =
      "<|im_start|>system\nТы семантический сопроцессор ядра памяти. "
      "Отвечай только JSON.<|im_end|>\n"
      "<|im_start|>user\nВыбери 3 релевантных чанка для запроса: "
      "куда плагин пишет отчёт moltbook<|im_end|>\n"
      "<|im_start|>assistant\n";
  llama_token *tokens = (llama_token *)malloc(sizeof(llama_token) * 4096);
  if (!tokens) { llama_free(ctx); llama_free_model(model); llama_backend_free(); return -1; }
  int ntok = llama_tokenize(model, prompt, (int)strlen(prompt), tokens, 4096, true, true);
  if (ntok <= 0) { free(tokens); llama_free(ctx); llama_free_model(model); llama_backend_free(); return -1; }

  double t0 = now_s();
  llama_batch batch = llama_batch_init((int32_t)ntok, 0, 1);
  for (int i = 0; i < ntok; i++) {
    llama_batch_add(batch, tokens[i], i, (llama_seq_id[]){ 0 }, i == ntok - 1);
  }
  if (llama_decode(ctx, batch) != 0) {
    llama_batch_free(batch); free(tokens); llama_free(ctx); llama_free_model(model);
    llama_backend_free(); return -1;
  }
  double t1 = now_s();
  out[1] = (t1 - t0) * 1000.0;  // prefill ms

  llama_sampler *smpl = llama_sampler_init_greedy();
  double first_ms = -1.0;
  int generated = 0;
  for (int i = 0; i < n_predict; i++) {
    llama_token next = llama_sampler_sample(smpl, ctx, -1);
    double tn = now_s();
    if (first_ms < 0) first_ms = (tn - t1) * 1000.0;
    generated++;
    if (llama_token_is_eog(model, next)) break;
    llama_batch b2 = llama_batch_init(1, 0, 1);
    llama_batch_add(b2, next, 0, (llama_seq_id[]){ 0 }, true);
    if (llama_decode(ctx, b2) != 0) { llama_batch_free(b2); break; }
    llama_batch_free(b2);
  }
  double t2 = now_s();
  double decode_s = (t2 - t1) - first_ms / 1000.0;
  out[0] = (generated > 1 && decode_s > 0) ? (double)(generated - 1) / decode_s : 0.0;
  out[2] = first_ms;
  out[3] = (t2 - t0) * 1000.0;
  out[4] = (double)generated;

  llama_sampler_free(smpl);
  llama_batch_free(batch);
  free(tokens);
  llama_free(ctx);
  llama_free_model(model);
  llama_backend_free();
  return 0;
}

const char *lb_version(void) { return "llama-bench-musl-1"; }
