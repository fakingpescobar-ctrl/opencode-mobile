// Обёртка вокруг llama.cpp (API b12554+) для замера реальной latency на железе.
// Собирается в CI как libllama-bench.so (musl aarch64), грузится через bun:ffi + libldmusl.
// Экспортирует функции: lb_bench, lb_gen, lb_gen_g, lb_code_ids, lb_score,
// lb_classify, lb_embed, lb_quantize, lb_version.
//
// Методология (версия 3):
//  - промпт без чат-спецтокенов, один и тот же набор байт для всех моделей;
//  - декодируется ровно n_predict токенов, EOG не прерывает замер (иначе
//    модели с ранним EOS дают несравнимое «общее время»);
//  - позиция первого EOG тоже возвращается — это наблюдение, не метрика скорости.

#include "llama.h"
#include "ggml.h"
#include <stdint.h>
#include <math.h>
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

// LLAMA_VERBOSE=1 в окружении включает штатный логгер llama.cpp — нужно, чтобы
// видеть причину отказа загрузки сконвертированных GGUF без пересборки обёртки.
static void set_logging(void) {
  if (getenv("LLAMA_VERBOSE")) llama_log_set(NULL, NULL);
  else llama_log_set(quiet_log, NULL);
}

// Возвращает out[7]:
// [0]=decode tok/s, [1]=prefill ms, [2]=TTFT ms (от начала промпта),
// [3]=всего ms, [4]=сгенерировано токенов, [5]=токенов в промпте,
// [6]=позиция первого EOG (0 — не наступил). 0 — успех, -1 — ошибка.
int lb_bench(const char *model_path, int n_ctx, int n_predict, double *out) {
  if (!model_path || !out || n_ctx < 64 || n_predict < 1) return -1;

  set_logging();
  llama_backend_init();
  struct llama_model_params mp = llama_model_default_params();
  mp.n_gpu_layers = 0;  // только CPU: сравниваем честно, без GPU-поблачек
  struct llama_model *model = llama_model_load_from_file(model_path, mp);
  if (!model) { llama_backend_free(); return -1; }
  const struct llama_vocab *vocab = llama_model_get_vocab(model);

  struct llama_context_params cp = llama_context_default_params();
  cp.n_ctx = n_ctx;
  cp.n_batch = n_ctx;     // иначе дефолтные 512 не вмещают длинный промпт quality-gate
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

  set_logging();
  llama_backend_init();
  struct llama_model_params mp = llama_model_default_params();
  mp.n_gpu_layers = 0;
  struct llama_model *model = llama_model_load_from_file(model_path, mp);
  if (!model) { llama_backend_free(); return -1; }
  const struct llama_vocab *vocab = llama_model_get_vocab(model);

  struct llama_context_params cp = llama_context_default_params();
  cp.n_ctx = n_ctx;
  cp.n_batch = n_ctx;     // длинный промпт quality-gate больше дефолтных 512
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

// То же, но с GBNF-ограничением формата: малые модели физически не могут выдать
// невалидный ответ. Используется в quality-gate для честной проверки выбора.
int lb_gen_g(const char *model_path, const char *prompt, const char *grammar,
             int n_ctx, int n_predict, char *out_text, int out_len, double *metrics) {
  if (!model_path || !prompt || !grammar || !out_text || out_len < 16 || !metrics ||
      n_ctx < 64 || n_predict < 1) return -1;

  set_logging();
  llama_backend_init();
  struct llama_model_params mp = llama_model_default_params();
  mp.n_gpu_layers = 0;
  struct llama_model *model = llama_model_load_from_file(model_path, mp);
  if (!model) { llama_backend_free(); return -1; }
  const struct llama_vocab *vocab = llama_model_get_vocab(model);

  struct llama_context_params cp = llama_context_default_params();
  cp.n_ctx = n_ctx;
  cp.n_batch = n_ctx;
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
  struct llama_sampler *gr = llama_sampler_init_grammar(vocab, grammar, NULL);
  if (!gr) {
    llama_sampler_free(smpl); free(ptok); free(gtok);
    llama_free(ctx); llama_model_free(model); llama_backend_free(); return -2;
  }
  llama_sampler_chain_add(smpl, gr);
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

// Однотокенные id spellings кода варианта: "A", " A" и для noul "no", "No",
// " no", " No". serving-код decision-модели использует именно такие алиасы.
int lb_code_ids(const char *model_path, const char *code, int *ids, int max_ids) {
  if (!model_path || !code || !ids || max_ids < 1 || strlen(code) > 60) return -1;
  set_logging();
  llama_backend_init();
  struct llama_model_params mp = llama_model_default_params();
  mp.n_gpu_layers = 0;
  struct llama_model *model = llama_model_load_from_file(model_path, mp);
  if (!model) { llama_backend_free(); return -1; }
  const struct llama_vocab *vocab = llama_model_get_vocab(model);
  char buf[64];
  int n = 0;
  for (int form = 0; form < 4; form++) {
    snprintf(buf, sizeof(buf), "%s%s", (form & 1) ? " " : "", code);
    char *p = buf + ((form & 1) ? 1 : 0);
    if (form >= 2 && *p >= 'a' && *p <= 'z') *p = (char)(*p - 'a' + 'A');
    llama_token tok[8];
    int k = llama_tokenize(vocab, buf, (int)strlen(buf), tok, 8, false, false);
    if (k != 1) continue;
    int id = (int)tok[0];
    int dup = 0;
    for (int i = 0; i < n; i++) if (ids[i] == id) { dup = 1; break; }
    if (!dup && n < max_ids) ids[n++] = id;
  }
  llama_model_free(model);
  llama_backend_free();
  return n;
}

// Decision-модели (hs-decide-1-flash) не генерируют текст: они читают вероятность
// варианта в позиции двоеточия строки "Qn:". Это ровно та схема, которой пользуется
// их serving-код: логиты считаются только в слотах ответа, по ним берутся коды
// вариантов, и softmax идёт по всем вариантам вопроса одним forward pass.
//
// slots:    n_slots индексов токенов-двоеточий (0-based); отрицательное число —
//           offset от конца промпта (-2 = предпоследний токен, наше "Q1:\n");
// groups:   плоский массив n_slots*n_options*aliases id токенов, -1 = пустой алиас;
// probs:    out n_slots*n_options вероятностей (сумма по строке = 1);
// metrics[4]: [0]=prefill мс, [1]=всего мс, [2]=токенов промпта, [3]=размер словаря.
int lb_score(const char *model_path, const char *prompt,
             const int *slots, int n_slots,
             const int *groups, int n_options, int aliases,
             double *probs, double *metrics) {
  if (!model_path || !prompt || !slots || !groups || !probs || !metrics ||
      n_slots < 1 || n_options < 1 || aliases < 1) return -1;
  const size_t total = (size_t)n_slots * (size_t)n_options * (size_t)aliases;
  if (total > 1000000) return -1;

  set_logging();
  llama_backend_init();
  struct llama_model_params mp = llama_model_default_params();
  mp.n_gpu_layers = 0;  // Android без root: только CPU, как и в lb_bench
  struct llama_model *model = llama_model_load_from_file(model_path, mp);
  if (!model) { llama_backend_free(); return -1; }
  const struct llama_vocab *vocab = llama_model_get_vocab(model);

  struct llama_context_params cp = llama_context_default_params();
  cp.n_ctx = 4096;
  cp.n_batch = 4096;
  cp.n_threads = 8;
  cp.n_threads_batch = 8;
  struct llama_context *ctx = llama_init_from_model(model, cp);
  if (!ctx) { llama_model_free(model); llama_backend_free(); return -1; }

  llama_token *ptok = (llama_token *)malloc(sizeof(llama_token) * 4096);
  if (!ptok) { llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1; }
  // special=1: ChatML-маркеры из промпта становятся токенами; add_special=0: BOS не добавляем.
  int ntok = llama_tokenize(vocab, prompt, (int)strlen(prompt), ptok, 4096, false, true);
  if (ntok <= 0) {
    free(ptok); llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1;
  }
  int *rslots = (int *)malloc((size_t)n_slots * sizeof(int));
  if (!rslots) {
    free(ptok); llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1;
  }
  for (int s = 0; s < n_slots; s++) {
    rslots[s] = slots[s] < 0 ? ntok + slots[s] : slots[s];
    if (rslots[s] < 0 || rslots[s] >= ntok) {
      free(rslots); free(ptok);
      llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1;
    }
  }
  metrics[2] = (double)ntok;
  metrics[3] = (double)llama_vocab_n_tokens(vocab);

  struct llama_batch batch = llama_batch_init(ntok, 0, 1);
  for (int i = 0; i < ntok; i++) {
    batch.token[i] = ptok[i];
    batch.pos[i] = i;
    batch.n_seq_id[i] = 1;
    batch.seq_id[i][0] = 0;
    batch.logits[i] = 0;
  }
  for (int s = 0; s < n_slots; s++) batch.logits[rslots[s]] = 1;
  batch.n_tokens = ntok;

  double t0 = now_s();
  int rc = llama_decode(ctx, batch);
  double t1 = now_s();
  if (rc != 0) {
    free(rslots); llama_batch_free(batch); free(ptok);
    llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1;
  }
  metrics[0] = (t1 - t0) * 1000.0;
  metrics[1] = metrics[0];

  double *sums = (double *)malloc((size_t)n_options * sizeof(double));
  if (!sums) {
    free(rslots); llama_batch_free(batch); free(ptok);
    llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1;
  }
  for (int s = 0; s < n_slots; s++) {
    const float *logits = llama_get_logits_ith(ctx, rslots[s]);
    if (!logits) {
      free(sums); free(rslots); llama_batch_free(batch); free(ptok);
      llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1;
    }
    double best = -1e30;
    int nvocab = (int)metrics[3];
    for (int o = 0; o < n_options; o++) {
      double mx = -1e30;
      for (int a = 0; a < aliases; a++) {
        int id = groups[((size_t)s * n_options + o) * aliases + a];
        if (id < 0 || id >= nvocab) continue;
        float v = logits[id];
        if ((double)v > mx) mx = v;
      }
      sums[o] = mx;
      if (mx > best) best = mx;
    }
    double denom = 0.0;
    for (int o = 0; o < n_options; o++) {
      sums[o] = (sums[o] <= -1e29) ? 0.0 : exp(sums[o] - best);
      denom += sums[o];
    }
    for (int o = 0; o < n_options; o++) {
      probs[(size_t)s * n_options + o] = denom > 0.0 ? sums[o] / denom : 0.0;
    }
  }

  free(sums);
  free(rslots);
  llama_batch_free(batch);
  free(ptok);
  llama_free(ctx);
  llama_model_free(model);
  llama_backend_free();
  return 0;
}

// Rerank (ModernBertForSequenceClassification): сырой score классификатора для
// одной пары query+document. metrics[3] = [prefill мс, всего мс, токенов, n_cls_out].
int lb_classify(const char *model_path, const char *prompt,
                double *score, double *metrics) {
  if (!model_path || !prompt || !score || !metrics) return -1;

  set_logging();
  llama_backend_init();
  struct llama_model_params mp = llama_model_default_params();
  mp.n_gpu_layers = 0;
  struct llama_model *model = llama_model_load_from_file(model_path, mp);
  if (!model) { llama_backend_free(); return -1; }
  const struct llama_vocab *vocab = llama_model_get_vocab(model);

  struct llama_context_params cp = llama_context_default_params();
  // Полный ctx=8192 для non-causal ModernBERT строит граф на 8192 токенов —
  // на телефоне это уходит в swap. Наши пары(query,doc) короче 1024 токенов.
  cp.n_ctx = 1024;
  cp.n_batch = 1024;
  cp.n_threads = 8;
  cp.n_threads_batch = 8;
  struct llama_context *ctx = llama_init_from_model(model, cp);
  if (!ctx) { llama_model_free(model); llama_backend_free(); return -1; }

  llama_token *ptok = (llama_token *)malloc(sizeof(llama_token) * 1024);
  if (!ptok) { llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1; }
  // BERT-токенизатор сам добавляет [CLS]/[SEP] при special=1.
  int ntok = llama_tokenize(vocab, prompt, (int)strlen(prompt), ptok, 1024, true, true);
  if (ntok <= 0) {
    free(ptok); llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1;
  }
  metrics[2] = (double)ntok;
  metrics[3] = (double)llama_model_n_cls_out(model);

  struct llama_batch batch = llama_batch_get_one(ptok, ntok);
  double t0 = now_s();
  int rc = llama_decode(ctx, batch);
  double t1 = now_s();
  if (rc != 0) {
    free(ptok); llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1;
  }
  metrics[0] = (t1 - t0) * 1000.0;
  metrics[1] = metrics[0];

  const float *logits = llama_get_logits_ith(ctx, ntok - 1);
  if (!logits) {
    free(ptok); llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1;
  }
  *score = (double)logits[0];

  free(ptok);
  llama_free(ctx);
  llama_model_free(model);
  llama_backend_free();
  return 0;
}

// Embeddings (ModernBertModel + MEAN/CLS pooling): pooled вектор [n_embd].
// Возвращает n_embd или -1. metrics[3] = [всего мс, токенов, pooling, n_embd].
int lb_embed(const char *model_path, const char *prompt,
             float *out, int out_cap, double *metrics) {
  if (!model_path || !prompt || !out || !metrics || out_cap < 1) return -1;

  set_logging();
  llama_backend_init();
  struct llama_model_params mp = llama_model_default_params();
  mp.n_gpu_layers = 0;
  struct llama_model *model = llama_model_load_from_file(model_path, mp);
  if (!model) { llama_backend_free(); return -1; }
  const struct llama_vocab *vocab = llama_model_get_vocab(model);
  const int n_embd = (int)llama_model_n_embd(model);
  if (n_embd < 1 || n_embd > out_cap) {
    llama_model_free(model); llama_backend_free(); return -1;
  }

  struct llama_context_params cp = llama_context_default_params();
  cp.n_ctx = 1024;
  cp.n_batch = 1024;
  cp.n_threads = 8;
  cp.n_threads_batch = 8;
  struct llama_context *ctx = llama_init_from_model(model, cp);
  if (!ctx) { llama_model_free(model); llama_backend_free(); return -1; }

  llama_token *ptok = (llama_token *)malloc(sizeof(llama_token) * 1024);
  if (!ptok) { llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1; }
  int ntok = llama_tokenize(vocab, prompt, (int)strlen(prompt), ptok, 1024, true, true);
  if (ntok <= 0) {
    free(ptok); llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1;
  }
  metrics[1] = (double)ntok;
  metrics[2] = (double)llama_pooling_type(ctx);
  metrics[3] = (double)n_embd;

  struct llama_batch batch = llama_batch_get_one(ptok, ntok);
  double t0 = now_s();
  int rc = llama_decode(ctx, batch);
  double t1 = now_s();
  if (rc != 0) {
    free(ptok); llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1;
  }
  metrics[0] = (t1 - t0) * 1000.0;

  const float *embd = llama_get_embeddings_seq(ctx, 0);
  if (!embd) {
    free(ptok); llama_free(ctx); llama_model_free(model); llama_backend_free(); return -1;
  }
  memcpy(out, embd, (size_t)n_embd * sizeof(float));

  free(ptok);
  llama_free(ctx);
  llama_model_free(model);
  llama_backend_free();
  return n_embd;
}

// Persistent-контур для ModernBERT: модель и контекст грузятся один раз,
// дальше score/embed считаются на одном и том же ctx. Старый lb_classify /
// lb_embed оставляем для совместимости — они грузят модель на каждый вызов.
struct lb_state {
  struct llama_model *model;
  struct llama_context *ctx;
  const struct llama_vocab *vocab;
  llama_token *ptok;
  int n_embd;
  int n_cls;
};

struct lb_state * lb_state_init(const char *model_path) {
  if (!model_path) return NULL;
  set_logging();
  llama_backend_init();
  struct llama_model_params mp = llama_model_default_params();
  mp.n_gpu_layers = 0;
  struct llama_model *model = llama_model_load_from_file(model_path, mp);
  if (!model) { llama_backend_free(); return NULL; }
  struct llama_context_params cp = llama_context_default_params();
  cp.n_ctx = 1024;
  cp.n_batch = 1024;
  cp.n_threads = 8;
  cp.n_threads_batch = 8;
  struct llama_context *ctx = llama_init_from_model(model, cp);
  if (!ctx) { llama_model_free(model); llama_backend_free(); return NULL; }
  llama_token *ptok = (llama_token *)malloc(sizeof(llama_token) * 1024);
  if (!ptok) { llama_free(ctx); llama_model_free(model); llama_backend_free(); return NULL; }
  struct lb_state *st = (struct lb_state *)calloc(1, sizeof(struct lb_state));
  if (!st) { free(ptok); llama_free(ctx); llama_model_free(model); llama_backend_free(); return NULL; }
  st->model = model; st->ctx = ctx; st->vocab = llama_model_get_vocab(model);
  st->ptok = ptok;
  st->n_embd = (int)llama_model_n_embd(model);
  st->n_cls = (int)llama_model_n_cls_out(model);
  return st;
}

static int lb_state_encode(struct lb_state *st, const char *prompt, double *metrics) {
  if (!st || !prompt || !metrics) return -1;
  llama_memory_clear(llama_get_memory(st->ctx), true);
  int ntok = llama_tokenize(st->vocab, prompt, (int)strlen(prompt), st->ptok, 1024, true, true);
  if (ntok <= 0) return -1;
  metrics[1] = (double)ntok;
  metrics[2] = (double)st->n_cls;
  metrics[3] = (double)st->n_embd;
  struct llama_batch batch = llama_batch_get_one(st->ptok, ntok);
  double t0 = now_s();
  int rc = llama_decode(st->ctx, batch);
  metrics[0] = (now_s() - t0) * 1000.0;
  return rc == 0 ? ntok : -1;
}

// Диагностика: первые 8 логитов с каждого из первых/последнего токена.
int lb_logits_dump(struct lb_state *st, const char *prompt, double *out) {
  if (!st || !out) return -1;
  int ntok = lb_state_encode(st, prompt, out + 8);
  if (ntok <= 0) return -1;
  for (int k = 0; k < 8; k++) out[k] = 0.0;
  const float *first = llama_get_logits_ith(st->ctx, 0);
  const float *last = llama_get_logits_ith(st->ctx, ntok - 1);
  for (int k = 0; k < 4; k++) {
    out[k] = first ? first[k] : -999.0;
    out[k + 4] = last ? last[k] : -999.0;
  }
  return ntok;
}

// metrics[4] = [decode мс, токенов, n_cls_out, n_embd]
int lb_cls_score(struct lb_state *st, const char *prompt, double *score, double *metrics) {
  if (!st || !score) return -1;
  int ntok = lb_state_encode(st, prompt, metrics);
  if (ntok <= 0) return -1;
  const float *logits = llama_get_logits_ith(st->ctx, ntok - 1);
  if (!logits) return -1;
  *score = (double)logits[0];
  return 0;
}

// Возвращает n_embd или -1. metrics тот же, что у lb_state_encode.
int lb_emb_run(struct lb_state *st, const char *prompt, float *out, int out_cap, double *metrics) {
  if (!st || !out || out_cap < st->n_embd) return -1;
  int ntok = lb_state_encode(st, prompt, metrics);
  if (ntok <= 0) return -1;
  const float *embd = llama_get_embeddings_seq(st->ctx, 0);
  if (!embd) return -1;
  memcpy(out, embd, (size_t)st->n_embd * sizeof(float));
  return st->n_embd;
}

void lb_state_free(struct lb_state *st) {
  if (!st) return;
  free(st->ptok);
  llama_free(st->ctx);
  llama_model_free(st->model);
  llama_backend_free();
  free(st);
}

// Квантовать GGUF в другой файл тем же llama.cpp, без внешних бинарников.
// ftype — llama_ftype: 7 = Q8_0, 15 = Q4_K_M. 0 = успех.
int lb_quantize(const char *src, const char *dst, int ftype) {
  if (!src || !dst) return -1;
  set_logging();
  struct llama_model_quantize_params p = llama_model_quantize_default_params();
  p.nthread = 4;
  p.ftype = (enum llama_ftype)ftype;
  return llama_model_quantize(src, dst, &p) == 0 ? 0 : -1;
}

const char *lb_version(void) { return "llama-bench-musl-14"; }
