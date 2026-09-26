# Правила R8, действующие ТОЛЬКО для build type minVerify.
# Подключается в app/build.gradle.kts рядом с proguard-rules.pro.
# Настоящий release этими правилами не затрагивается - и не должен.
#
# ---------------------------------------------------------------------------
# ЗАЧЕМ ЭТОТ ФАЙЛ
#
# Инструментация физически не может работать против минифицированной сборки
# без keep-правил на тестируемое API. R8 оптимизирует по графу вызовов САМОГО
# приложения и не анализирует тестовый APK. Отсюда два разных класса отказов,
# и оба наблюдались вживую 27.09.2026:
#
# 1) Член, который приложение НЕ вызывает -> R8 выкидывает его как мёртвый код.
#    Пример: WhisperTranscribeService.languageOverride. Приложение не пишет в
#    него ни разу (0 присваиваний в main) - писал только benchAutoLanguage.
#    Тест падал с NoSuchMethodError. В release это безобидно: раз приложение
#    override не выставляет, отсутствие сеттера ничего не меняет.
#
# 2) Член, который приложение ВЫЗЫВАЕТ -> R8 его ПЕРЕИМЕНОВЫВАЕТ. Тест ищет по
#    исходному имени и не находит. Тот же случай с currentLang().
#
# Отсюда правило: держим ИМЕНА нужных тестам классов, но разрешаем оптимизацию.
#
#   -keep,allowoptimization
#
# а не просто -keep. Разница принципиальна: -keep class { *; } запретил бы
# оптимизацию, и проверка не проверяла бы ровно то, ради чего существует.
# allowoptimization оставляет R8 инлайнить, сливать и девиртуализировать -
# именно эти оптимизации и есть источник риска, - а тест при этом линкуется.
#
# Проверено: с этими правилами на minVerify зелёные benchChunkedLong,
# benchLazyShort, nativeInitAndTranscribe и benchAutoLanguage.
# ---------------------------------------------------------------------------

# Классы, к которым обращается androidTest (см. импорты BenchSttTest).
-keep,allowoptimization class org.opencode.mobile.stt.ChunkedTranscriber { *; }
-keep,allowoptimization class org.opencode.mobile.stt.ModelDownloader { *; }
-keep,allowoptimization class org.opencode.mobile.stt.NcnnModelValidator { *; }
-keep,allowoptimization class org.opencode.mobile.stt.SpeechSegmenter { *; }
-keep,allowoptimization class org.opencode.mobile.stt.Wer { *; }
-keep,allowoptimization class org.opencode.mobile.stt.WhisperTranscribeService { *; }

# $Companion - ОТДЕЛЬНЫЙ КЛАСС, и правило на внешний класс его не покрывает.
# Это стоило двух промахов: setLanguageOverride, потом currentLang().
-keep,allowoptimization class org.opencode.mobile.stt.WhisperTranscribeService$Companion { *; }

-keep,allowoptimization class com.whispercpp.whisper.NcnnWhisperContext { *; }

# Kotlin stdlib. Тестовый APK собран против необфусцированных имён и ищет
# kotlin/LazyKt по исходному имени; без этого тестовый процесс падает с
# NoClassDefFoundError. В release проблемы нет - тестового APK там не бывает.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**

# ЧЕГО ТУТ НЕТ, СОЗНАТЕЛЬНО:
#
# * NcnnWhisperLib. Его имя и имена его native-методов сохраняет правило из
#   дефолтного proguard-android-optimize.txt:
#       -keepclasseswithmembernames,includedescriptorclasses class * {
#           native <methods>;
#       }
#   Дублировать не нужно. Проверять, что оно на месте, надо по наличию
#   Lcom/whispercpp/whisper/NcnnWhisperLib; в classes.dex готового APK.
#
# * Правил на остальной код приложения. Если тест начнёт дёргать что-то ещё -
#   сначала проверь, а не расширяй keep слепо: каждое такое правило ослабляет
#   проверку именно в том месте, где она нужнее всего.
