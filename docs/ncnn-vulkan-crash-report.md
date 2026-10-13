# SIGSEGV in ncnn Vulkan compute on Adreno 840 (Snapdragon 8 Elite Gen 5)

## Summary

Running the **ncnn whisper encoder** with `opt.use_vulkan_compute = true` crashes
with a hard `SIGSEGV` on an Adreno 840 (Snapdragon 8 Elite Gen 5) phone running the
stock Qualcomm driver. The crash is **deterministic**: five attempts with four
different compute-precision / pipeline configurations all died at the same fault
address (`0xe0`) and in the same frames of `libncnnwhisper.so`.

The Vulkan device itself is healthy — `get_gpu_count()` returns 1 and
`get_gpu_info(0).device_index()` succeeds — so this is **not** a "no GPU"
situation. The CPU path works correctly with the same model and weights.

**The most important detail: this configuration previously worked on this exact
device.** An experiment recorded in the integrating project on **2026-09-02** ran
the encoder on Vulkan (int8, `use_subgroup_ops = false`) with the decoder on CPU
and measured **6.5–6.9 s vs 8.82 s for CPU**. It was reverted only because the CPU
decoder hung converting large Vulkan-encoder KV state on live speech — never
because of a crash. ncnn `0a4e85a` is dated **2026-09-02**, i.e. the exact commit
used for that working run. Since then the same graph on the same phone and driver
crashes reliably. This looks like a regression rather than an unsupported device.

I have deliberately excluded my own code as a cause: the integrating side crops
mel frames before the encoder, and attempt **5** below repeats the crash with
that crop **completely disabled**, which still crashes at the same address.

I suspect this may still be related to **#6778** (*gemm subgroup optimization
breaks on adreno*), whose table lists `8elitegen5 / system driver 512.842.19` as
**not working out of the box**, with "disable subgroup ops" as a workaround. My
driver is in the same 512.842.xx family (GLES string `V@0842.44`). Note that the
September run above already had subgroup ops disabled, so that alone does not
explain the earlier success — see "Hypothesis".

## Device

| Property | Value |
|---|---|
| Model | OnePlus CPH2747 |
| SoC | QTI SM8850 (Snapdragon 8 Elite Gen 5) |
| GPU | Qualcomm Adreno (TM) 840 |
| Driver | `OpenGL ES 3.2 V@0842.44 (GIT@718a07726a, Ic95db9d944, 1780021672) (Date:05/29/26)` |
| OS | Android 16 (SDK 36) |
| ABI | arm64-v8a |
| `ro.hardware.vulkan` | `adreno` |

Driver family matches the `512.842.xx` rows in #6778.

## ncnn version

| | Commit | Date |
|---|---|---|
| Originally observed on | `0a4e85a` ("x86 fma and fma4 dispatch", #6940) | 2026-09-02 |
| Reproduced after update to | `b0f97f4` ("riscv: add int8 convolution im2col gemm", #7090) | 2026-10-13 |

The crash was tested against **both**. Updating ncnn by 50 commits (including a
commit from the same day) did not change the behaviour — same fault address,
same frames.

## Build configuration

ncnn is consumed via `add_subdirectory()` in an Android NDK CMake project with:

```cmake
set(NCNN_VULKAN ON CACHE BOOL "" FORCE)
set(NCNN_RUNTIME_CPU ON CACHE BOOL "" FORCE)
set(NCNN_SHARED OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_TOOLS/EXAMPLES/BENCHMARK/TESTS OFF CACHE BOOL "" FORCE)
```

Resulting `libncnnwhisper.so` is 49 966 760 bytes, statically linked against
ncnn, and additionally depends on `libglslang.so` and `libSPIRV.so`.

## Model

ncnn-format Whisper **turbo**, int8 encoder (~728 MB of weights), 1500 encoder
positions (`n_ctx = 1500`, hence `shape(64,1500,20)` in the prefill log), 2.28 s
of 16 kHz audio per request.

## Reproduction

```cpp
ncnn::Net encoder;
// ... load int8 ncnn weights from whisper-turbo ...
encoder.opt.use_vulkan_compute = true;
// (attempt 2 additionally set, explicitly:
//    use_int8_packed = use_int8_storage = use_int8_arithmetic = true;
//    use_fp16_packed = use_fp16_storage = use_fp16_arithmetic = false;)
// (attempt 3 flipped this to fp16 with int8 off)

const int ret = encoder.load_param("whisper_turbo_encoder_int8.ncnn.param");
encoder.load_model("whisper_turbo_encoder_int8.ncnn.bin");

ncnn::Mat in = ...;  // 3000 mel frames, cropped to ~250 before the forward pass
ncnn::Extractor ex = encoder.create_extractor();
ex.input("x", in);
ncnn::Mat out = ex.extract("out");
```

## Observed

`get_gpu_count()` and `get_gpu_info(0).device_index()` both succeed immediately
before the crash:

```
gpu count=1 index=0
encoder mode: int8 vulkan=1
ncnn crop frames 3000 -> 212
ncnn phase fbank=840ms
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0xe0
```

Backtrace (attempt 4, ncnn `b0f97f4`):

```
NOTE:   .../libncnnwhisper.so
  #00 pc 00000000002a5b04  libncnnwhisper.so
  #01 pc 00000000002c8390  libncnnwhisper.so
  #02 pc 000000000028a5e8  libncnnwhisper.so
  #03 pc 000000000028ac1c  libncnnwhisper.so
  #04 pc 000000000028e664  libncnnwhisper.so
```

Attempt 3 (ncnn `0a4e85a`) is the same code path at slightly different addresses
because the library was relinked:

```
  #00 pc 00000000002a5d04
  #01 pc 00000000002c8590
  #02 pc 000000000028a61c
```

`fault addr 0xe0` is a small fixed offset, which smells like a null/neutered
object pointer plus a struct field access rather than corrupted data — but I have
not symbolised these offsets, so that is speculation on my part.

### What I tried

| # | Configuration | Result |
|---|---|---|
| 1 | `use_vulkan_compute = true` (int8 default) | SIGSEGV, `fault addr 0xe0` |
| 2 | + explicit `use_int8_packed/storage/arithmetic = true`, fp16 off | SIGSEGV, **same** `0xe0` |
| 3 | fp16 packed/storage/arithmetic on, int8 off | SIGSEGV, **same** `0xe0` |
| 4 | same as 1, on updated ncnn `b0f97f4` | SIGSEGV, **same** `0xe0` |
| 5 | as 1, **with the integrating side's mel-frame crop disabled entirely** | SIGSEGV, **same** `0xe0` |

Since fp16 and int8 fail identically, this is not specific to typed int8
storage/packing. The failure is in the Vulkan compute path for this model on this
driver.

### Attempt 5 in detail — excludes my own code

The integrating side crops the mel matrix before the encoder (Whisper always
feeds a fixed 30 s / 3000-frame window, so short clips pay for silence). All of
attempts 1–4 logged `ncnn crop frames 3000 -> 212` immediately before dying, so
the crop was a plausible co-conspirator. Attempt 5 disables it at runtime via a
marker file, and the crop line disappears from the log entirely:

```
NcnnWhisper: gpu count=1 index=0
NcnnWhisper: encoder mode: int8 mode=1
NcnnWhisper: whisper load OK pre-tokenizer: base=whisper_turbo
NcnnWhisper: ncnn phase fbank=793ms
libc: Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0xe0 in tid 23890 (pool-5-thread-1)
  NOTE:   .../files/engines/stt/libncnnwhisper.so
  #00 pc 00000000002acfe0  libncnnwhisper.so
  #01 pc 00000000002d0400  libncnnwhisper.so
  #02 pc 000000000029125c  libncnnwhisper.so
  #03 pc 00000000002922e0  libncnnwhisper.so
  #04 pc 0000000000294b7c  libncnnwhisper.so
  #10 pc 0000000000002ea4  base.apk (NcnnWhisperContext$transcribeAuto$2)
```

The crash now happens immediately after `fbank`, i.e. as soon as the encoder
starts — earlier than before, and with the unmodified graph. The crop is not the
cause.

### What works

The CPU path with the same weights completes correctly:

```
encoder mode: int8 vulkan=0
ncnn phase fbank=970ms
ncnn phase encoder=6351ms
ncnn phase decoder=13 steps=569ms avg=43.8ms/step
```

Output text is correct, so the weights and the graph are fine.

## Performance note (in case it is useful to you)

With Vulkan disabled the encoder takes ~6.3 s per request regardless of clip
length, because the Whisper encoder always runs its fixed 30 s / 1500-position
window. The September run of the Vulkan encoder on this same phone measured
6.45–6.9 s vs 8.82 s for CPU, i.e. the upside was ~27% even when it worked — which
is why it is still worth getting right rather than working around.

## Regression evidence

- ncnn `0a4e85a` — dated **2026-09-02** — Vulkan encoder **worked** here (6.5–6.9 s,
  int8, `use_subgroup_ops = false`, decoder on CPU).
- ncnn `b0f97f4` — dated **2026-10-13**, 50 commits later — SIGSEGV.
- Attempts 1–3 also SIGSEGV on `0a4e85a` with today's driver, so either the
  working September run used a different graph, or something changed on the
  device side (driver update: the GLES string is dated 05/29/26, after the
  September run).

That last point is the one ambiguity I cannot rule out from my side: I did not
record the driver version in September, so the device driver may itself have
been updated in the meantime.

## Hypothesis

#6778 concluded that on `8elitegen5` with the system driver the subgroup path is
unusable and that "disable subgroup ops" works. `ncnn::Option` exposes
`use_subgroup_ops`.

However the September run that worked already had subgroup ops **disabled**, so
subgroup support alone does not explain why it worked then and crashes now.
That points at either a changed default in the graph/pipeline options, or a
driver update on my side. I have not yet tested `use_subgroup_ops = false`
against the current crash — I will, and will report back.

## Questions

1. Between `0a4e85a` (2026-09-02) and `b0f97f4` (2026-10-13), did any default
   change for Vulkan compute on int8 graphs (packing, subgroup detection, shader
   generation) that could turn a previously working path into a fault at `0xe0`?
2. If `use_subgroup_ops = false` is not the answer, is there a known-good way to
   run a graph of this shape (1500 positions, large int8 encoder) on Adreno
   Vulkan?
3. #6449 looks like the same device class crashing on Adreno 840 under Android
   16 — is that the same underlying fault?

## Reproduction artefacts

- Crash: `libncnnwhisper.so` (49 966 760 b) built from ncnn `b0f97f4` as above.
- Log excerpt available on request; the four crash signatures are reproduced
  verbatim above.
