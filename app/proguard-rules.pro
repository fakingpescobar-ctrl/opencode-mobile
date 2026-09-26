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
