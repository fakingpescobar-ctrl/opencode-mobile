# Правила R8 для release. Файл намеренно содержит только комментарии —
# сюда добавляют keep-правила, когда они понадобятся.
#
# ВАЖНО, про минификацию. Раньше здесь стоял комментарий «no minify in
# release for now», и он был неверным: в app/build.gradle.kts стоит
# isMinifyEnabled = true и isShrinkResources = true. Минификация включена.
#
# Почему JNI при этом не ломается. Мы вызываем native-методы по имени из C:
# extern "C" JNIEXPORT ... Java_com_whispercpp_whisper_NcnnWhisperLib_nativeInit.
# Обфускация переименовала бы класс NcnnWhisperLib, и JNI перестал бы находить
# символ. Спасает правило из подключённого по умолчанию
# proguard-android-optimize.txt:
#
#     -keepclasseswithmembernames,includedescriptorclasses class * {
#         native <methods>;
#     }
#
# Оно сохраняет имя класса и имена его native-методов. Проверено на собранном
# app-release.aab: в classes.dex дескриптор
# Lcom/whispercpp/whisper/NcnnWhisperLib; присутствует, а
# Lcom/whispercpp/whisper/NcnnWhisperContext; обфусцирован — так и должно быть,
# нативной привязки у него нет.
#
# СЛЕДСТВИЕ: этот файл НЕЛЬЗЯ считать «место, где отключают минификацию».
# Если добавить сюда класс с native-методами и он перестанет работать в release,
# сначала проверь, что default-файл действительно подключён в build.gradle.kts
# через getDefaultProguardFile("proguard-android-optimize.txt").

# Keep для sherpa-onnx (com.k2fsa.sherpa.onnx). Здесь отличие от whispercpp выше:
# ломается не имя класса, а ПОЛЯ конфигов. JNI (sherpa-onnx-jni) собирает конфиг
# из Kotlin-объекта через GetFieldID по именам полей
# (modelConfig.transducer.encoder, tokens, numThreads, debug, provider, modelType…).
# Правило для native <methods> поля не защищает, а R8 вырезает поля, которые не
# читает Kotlin-код: у OfflineModelConfig это paraformer, whisper, senseVoice,
# nemo, fireRedAsr, qwen3Asr и прочие подконфиги, нужные только нативу. После
# вырезания GetFieldID вернёт NULL, JNI кинет исключение и приложение упадёт
# (std::terminate → SIGABRT). Касается и TTS (sherpa_Tts.kt, sherpa_WaveReader.kt
# — тот же пакет), и offline-распознавания (sherpa_OfflineRecognizer.kt).
-keep class com.k2fsa.sherpa.onnx.** { *; }
