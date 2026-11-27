# Vosk 0.52/0.54 RU Feasibility Report for opencode-mobile

## Executive Summary
Adding Vosk 0.52/0.54 Russian models to opencode-mobile is **feasible but requires integrating the native `libvosk.so` JNI runtime**, which is currently absent from the APK. The model definitions already exist in the codebase (marked `runnable = false`), but the native library must be added before these models can be used.

---

## 1. Official Vosk RU Model Lineup (alphacephei.com/models)

| Model ID | Size | Description | License |
|---|---|---|---|
| `vosk-model-ru-0.42` | 1.8 GB | Big mixed-band Russian model for servers | Apache 2.0 |
| `vosk-model-small-ru-0.22` | 45 MB | **Lightweight wideband model for Android/iOS/RPi** | Apache 2.0 |
| `vosk-model-ru-0.22` | 1.5 GB | Big mixed-band Russian model for servers | Apache 2.0 |
| `vosk-model-ru-0.10` | 2.5 GB | Big narrowband Russian model for servers | Apache 2.0 |

**Note:** The codebase references `vosk-model-ru-0.54` (1.8 GB) and `vosk-model-small-ru-0.52` (1.5 GB). These version numbers (0.54, 0.52) **do not match** the official website versions (0.10, 0.22, 0.42). They may refer to internal/pre-release variants or outdated references.

---

## 2. Android Integration — Native `libvosk.so` Runtime

### What's needed:
- **`libvosk.so`** — native JNI library providing the Vosk C API. This is **not** currently bundled in the APK.
- The `vosk-api` C++ library (source at `github.com/alphacep/vosk-api`) compiles to `libvosk.so`.
- Model `.zip` files unpacked into `assets/model/`.
- UUID generated and added to `build.gradle` for model validation.

### How to integrate (based on `vosk-android-demo`):

1. **Download prebuilt `libvosk.so`** for Android arm64-v8a, or build from source:
   - Prebuilt binaries are not officially distributed by Alpha Cephei, but community builds exist.
   - The `vosk-api` CMakeLists.txt builds `libvosk` and links Kaldi dependencies.

2. **Place `libvosk.so`** in `app/src/main/jniLibs/arm64-v8a/libvosk.so`  
   (The `jniLibs` folder is already `.gitignore`'d; natives are expected to be added manually per the project guidelines.)

3. **Add model assets**:
   - Unpack the `.zip` model into `app/src/main/assets/vosk-model-ru-0.54/` (or `vosk-model-small-ru-0.52/`).
   - The model dir must contain `am/final.mdl`, `conf/model.conf`, `ivector/`, `graph/`, etc. (see Vosk model structure docs).

4. **Generate UUID** — The demo app generates a UUID and writes it to `build.gradle`:
   ```gradle
   buildConfigField "String", "VOSK_UUID", "\"<uuid>\""
   ```
   This is required for model validation in newer Vosk versions.

5. **API usage (Java/C++)**:
   - `VoskModel::vosk_model_new(path)` to load the model.
   - `VoskRecognizer::vosk_recognizer_new(model, sample_rate)` to create a recognizer.
   - Streaming API: `vosk_recognizer_accept_waveform` (final) / `vosk_recognizer_partial_result` (partial).
   - PCM 16-bit, 8kHz or 16kHz expected (telephony bandwidth).
   - VAD is available but not auto-driven; developers handle speech detection.

---

## 3. Runtime API — Streaming / Offline

| Feature | Support |
|---|---|
| Offline (static file) | ✅ Full support |
| Streaming (real-time) | ✅ Via `accept_waveform` / `partial_result` |
| PCM 16kHz input | ✅ Recommended for telephony/voice |
| PCM 16kHz 16-bit little-endian | ✅ |
| Partial results | ✅ `partial_result` JSON |
| VAD (voice activity) | ✅ Available, developer-driven |
| Dynamic vocabulary | ✅ Supported in small models |
| Confidence scores | ✅ Included in JSON output |

**Latency expectations** (from project criteria):
- Small model (`vosk-model-small-ru-0.22` ~45 MB): **< 3s** latency on arm64-v8a is realistic.
- Large model (`vosk-model-ru-0.42` 1.8 GB): **> 3s** latency; may exceed 5s on OPPO CPH2747.

---

## 4. Packaging Realities in Current APK

| Aspect | Status |
|---|---|
| **`libvosk.so` missing** | 🔴 **Blocker** — currently absent; APK will not load Vosk models without it. |
| **Small model size (45 MB)** | ✅ Easily bundleable; fits within APK without significant size increase. |
| **Large model size (1.5–1.8 GB)** | 🔴 Would require download-on-demand; 1.5 GB+ may exceed reasonable install size for on-device STT. |
| **Conflict with existing libs** | ✅ No expected conflict. `libvosk.so` is independent of `libonnxruntime.so`, `libsherpa*.so`, `libc_musl.so`, etc. |
| **Gradle/NDK setup** | ✅ The `vosk-api` CMakeLists.txt is straightforward; can be added to existing native build. |
| **UUID generation** | ✅ Straightforward add to `build.gradle`. |

---

## 5. License & Risks

| Item | Status |
|---|---|
| **Vosk license** | Apache 2.0 for both library and all listed Russian models. ✅ Commercial-friendly with attribution. |
| **Model provenance** | Official `alphacephei.com/vosk/models` is the source. ✅ No uncertain origins. |
| **GPU/acceleration** | ❌ Vosk is CPU-only; no GPU offload on most Android devices. |
| **Memory at runtime** | Small model: ~300 MB. Large model: up to 1.8 GB + Kaldi FST overhead. |
| **Known issue** | `400030002` – "Unspecified repeat mode is not allowed." If using streaming, `repeat_mode` must be set to `NONE` (not `UNSPECIFIED`), as the phone publishes `UNSPECIFIED` which is rejected. |

---

## 6. Recommendation

### Status: **IMPLEMENTABLE with engineering effort**

### Blockers (must resolve first):
1. **Add `libvosk.so` JNI runtime** for Android arm64-v8a. This is the primary blocker — without it, no Vosk model can be loaded.
2. **Decide on model size**: 
   - Use `vosk-model-small-ru-0.22` (45 MB) for immediate deployment — lightweight, proven Android compatibility, <3s latency.
   - Use `vosk-model-ru-0.22` or `vosk-model-ru-0.42` (1.5–1.8 GB) only if download-on-demand is acceptable and latency >3s is tolerable for the use case.
3. **Generate and embed UUID** in the Gradle build config.

### Work estimate (engineering effort):
| Task | Approx. effort |
|---|---|
| Download/compile `libvosk.so` for arm64-v8a | 0.5 day |
| Place `libvosk.so` in `jniLibs/arm64-v8a/` | 0.2 day |
| Unpack model assets into `assets/` | 0.3 day |
| Generate and embed UUID in `build.gradle` | 0.2 day |
| Update `SttModels.kt` — set `runnable = true`, fix `fetch` logic | 0.5 day |
| Basic integration test (load model, simple phrase) | 1 day |
| **Total** | **~3 days** |

### Recommended path:
1. Start with **`vosk-model-small-ru-0.22`** (45 MB) — the only Vosk model explicitly tagged "for Android/iOS and RPi" on the official site.
2. Integrate `libvosk.so` from the `vosk-api` repo or a trusted prebuilt.
3. Once the small model works, evaluate whether the larger `vosk-model-ru-0.22` (1.5 GB) provides sufficient quality gain to justify the size/latency trade-off.

### Sources
- Official model list: <https://alphacephei.com/vosk/models>
- Vosk API source: <https://github.com/alphacep/vosk-api>
- Android demo & integration guide: <https://github.com/alphacep/vosk-android-demo>
- Model structure docs: <https://github.com/alphacep/vosk-api/tree/master/training>