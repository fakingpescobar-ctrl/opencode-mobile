// Обёртка вокруг llama.cpp (API b12554+) для замера реальной latency на железе.
// Собирается в CI как libllama-bench.so (musl aarch64), грузится через bun:ffi + libldmusl.
// Экспортирует функции: lb_bench, lb_version.
//
// Методология (версия 3):
//  - промпт без чат-спецтокенов, один и тот же набор байт для всех моделей;
//  - декодируется ровно n_predict токенов, EOG не прерывает замер (иначе
//    модели с ранним EOS дают несравнимое «общее время»);
//  - позиция первого EOG тоже возвращается — это наблюдение, не метрика скорости.

#include "llama.h"
#include "ggml.h"
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

static void quiet_log(enum ggml_log_level level, const char *text, void *user_data) {
  (void)level; (void)text; (void)user_data;
}

// Возвращает out[7]:
// [0]=decode tok/s, [1]=prefill ms, [2]=TTFT ms (от начала промпта),
// [3]=всего ms, [4]=сгенерировано токенов, [5]=токенов в промпте,
// [6]=позиция первого EOG (0 — не наступил). 0 — успех, -1 — ошибка.
int lb_bench(const char *model_path, int n_ctx, int n_predict, double *out) {
  if (!model_path || !out || n_ctx < 64 || n_predict < 1) return -1;

  llama_log_set(quiet_log, NULL);
  llama_backend_init();
  struct llama_model_params mp = llama_model_default_params();
  mp.n_gpu_layers = 0;  // только CPU: сравниваем честно, без GPU-поблачек
  struct llama_model *model = llama_model_load_from_file(model_path, mp);
  if (!model) { llama_backend_free(); return -1; }
  const struct llama_vocab *vocab = llama_model_get_vocab(model);

  struct llama_context_params cp = llama_context_default_params();
  cp.n_ctx = n_ctx;
  cp.n_threads = 8;       // 8 ядер CPH2747 (2×4.608 + 6×3.629 ГГц)
  cp.n_threads_batch = 8;
  struct llama_context *ctx = llama_init_from_model(model, cp);
  if (!ctx) { llama_model_free(model); llama_backend_free(); return -1; }

  // Один и тот же нейтральный промпт для всех моделей: никаких <|im_start|>,
  // которые у моделей без ChatML токенизируются в десятки обычных токенов.
  const char *prompt =
      "Ты семантический сопроцессор ядра памяти. Отвечай только JSON.\n"
      "Запрос: куда плагин пишет отчёт moltbook?\n"
      "Выбери 3 релевантных чанка. Ответ:\n";
  llama_token *tokens = (llama_token *)malloc(sizeof(llama_token) * 4096);
  if (!tokens) { llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1; }
  int ntok = llama_tokenize(vocab, prompt, (int)strlen(prompt), tokens, 4096, true, true);
  if (ntok <= 0) { free(tokens); llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1; }
  out[5] = (double)ntok;

  double t0 = now_s();
  struct llama_batch batch = llama_batch_get_one(tokens, (int32_t)ntok);
  if (llama_decode(ctx, batch) != 0) {
    free(tokens); llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1;
  }
  double t1 = now_s();
  out[1] = (t1 - t0) * 1000.0;  // prefill ms

  struct llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
  struct llama_sampler *smpl = llama_sampler_chain_init(sparams);
  if (!smpl) { free(tokens); llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1; }
  llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

  double tf = -1.0;  // момент получения первого токена
  int generated = 0;
  int eos_at = 0;
  for (int i = 0; i < n_predict; i++) {
    llama_token next = llama_sampler_sample(smpl, ctx, -1);
    double tn = now_s();
    if (tf < 0) tf = tn;
    generated++;
    if (llama_vocab_is_eog(vocab, next) && eos_at == 0) eos_at = i + 1;
    // EOG НЕ прерывает цикл: меряем фиксированную работу n_predict токенов.
    struct llama_batch b2 = llama_batch_get_one(&next, 1);
    if (llama_decode(ctx, b2) != 0) break;
  }
  double t2 = now_s();
  out[0] = (generated > 1 && t2 > tf) ? (double)(generated - 1) / (t2 - tf) : 0.0;
  out[2] = tf > 0 ? (tf - t0) * 1000.0 : -1.0;  // TTFT от начала промпта
  out[3] = (t2 - t0) * 1000.0;
  out[4] = (double)generated;
  out[6] = (double)eos_at;

  llama_sampler_free(smpl);
  free(tokens);
  llama_free(ctx);
  llama_model_free(model);
  llama_backend_free();
  return 0;
}

// Генерация текста для quality-gate: тот же движок, но ответ возвращается в out_text.
// metrics[5]: [0]=TTFT мс, [1]=decode tok/s, [2]=всего мс, [3]=токенов, [4]=токенов в промпте.
int lb_gen(const char *model_path, const char *prompt, int n_ctx, int n_predict,
           char *out_text, int out_len, double *metrics) {
  if (!model_path || !prompt || !out_text || out_len < 16 || !metrics ||
      n_ctx < 64 || n_predict < 1) return -1;

  llama_log_set(quiet_log, NULL);
  llama_backend_init();
  struct llama_model_params mp = llama_model_default_params();
  mp.n_gpu_layers = 0;
  struct llama_model *model = llama_model_load_from_file(model_path, mp);
  if (!model) { llama_backend_free(); return -1; }
  const struct llama_vocab *vocab = llama_model_get_vocab(model);

  struct llama_context_params cp = llama_context_default_params();
  cp.n_ctx = n_ctx;
  cp.n_threads = 8;
  cp.n_threads_batch = 8;
  struct llama_context *ctx = llama_init_from_model(model, cp);
  if (!ctx) { llama_model_free(model); llama_backend_free(); return -1; }

  llama_token *ptok = (llama_token *)malloc(sizeof(llama_token) * 8192);
  llama_token *gtok = (llama_token *)malloc(sizeof(llama_token) * (size_t)n_predict);
  if (!ptok || !gtok) {
    free(ptok); free(gtok); llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1;
  }
  int ntok = llama_tokenize(vocab, prompt, (int)strlen(prompt), ptok, 8192, true, true);
  if (ntok <= 0) {
    free(ptok); free(gtok); llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1;
  }
  metrics[4] = (double)ntok;

  double t0 = now_s();
  struct llama_batch batch = llama_batch_get_one(ptok, (int32_t)ntok);
  if (llama_decode(ctx, batch) != 0) {
    free(ptok); free(gtok); llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1;
  }
  struct llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
  struct llama_sampler *smpl = llama_sampler_chain_init(sparams);
  if (!smpl) {
    free(ptok); free(gtok); llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1;
  }
  llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

  double tf = -1.0;
  int generated = 0;
  for (int i = 0; i < n_predict; i++) {
    llama_token next = llama_sampler_sample(smpl, ctx, -1);
    if (tf < 0) tf = now_s();
    gtok[generated++] = next;
    if (llama_vocab_is_eog(vocab, next)) break;
    struct llama_batch b2 = llama_batch_get_one(&next, 1);
    if (llama_decode(ctx, b2) != 0) break;
  }
  double t2 = now_s();
  int nbytes = llama_detokenize(vocab, gtok, generated, out_text, out_len, true, false);
  if (nbytes < 0) nbytes = 0;
  if (nbytes >= out_len) nbytes = out_len - 1;
  out_text[nbytes] = '\0';

  metrics[0] = tf > 0 ? (tf - t0) * 1000.0 : -1.0;
  metrics[1] = (generated > 1 && t2 > tf) ? (double)(generated - 1) / (t2 - tf) : 0.0;
  metrics[2] = (t2 - t0) * 1000.0;
  metrics[3] = (double)generated;

  llama_sampler_free(smpl);
  free(ptok); free(gtok);
  llama_free(ctx);
  llama_model_free(model);
  llama_backend_free();
  return 0;
}

const char *lb_version(void) { return "llama-bench-musl-4"; }
