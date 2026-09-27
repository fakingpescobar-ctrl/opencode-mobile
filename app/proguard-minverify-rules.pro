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
# ИСТОРИЯ ДВУХ ОТКАТОВ, КОТОРЫЕ НЕ ПОВТОРЯТЬ (28.09.2026). Обе истории
# начинались одинаково - с честного наблюдения, которое затем объяснили не тем.
# Здесь записано, чем оно оказалось на самом деле.
#
# ОТКАТ 1: allowoptimization назначили виновником падения suite. На прогоне
# 27.09 benchInt8AndFp32 упал с "Process crashed" и ПУСТЫМ краш-буфером
# (процесс умер нативно, без Java-стека). Изоляционный прогон ТОГО ЖЕ теста на
# ТОЙ ЖЕ сборке (build\am-fp32.log) дал "OK (1 test)" за 695 с - то есть
# обвинение не подтвердилось.
#
# ОТКАТ 2 (ложный, разобран 28.09.2026). Сюда записали, что allowoptimization
# даёт выигрыш "APK 180.2 -> 172.1 MB", и на этом основании bare -keep считали
# правильным. Проверка это НЕ подтвердила, и опровержение важнее самой цифры:
#
#   * minVerify объявлен isDebuggable = true, а AGP для debuggable-сборок гонит
#     R8 с -dontoptimize -dontobfuscate. При сборке печатается WARNING:
#     "All code optimizations and obfuscation are disabled for debuggable
#     builds". allowoptimization снимает запрет на ОПТИМИЗАЦИЮ, а оптимизации
#     и так выключены - правило является no-op.
#   * Контрольный A/B, переигранный 27.09 с ПРИНУДИТЕЛЬНЫМ прогоном R8.
#     Предыдущая запись утверждала "дельта ровно 0", но была снята прогоном,
#     где minifyMinVerifyWithR8 отдавал UP-TO-DATE, то есть R8 не выполнялся
#     вовсе и цифра была ничем не подкреплена. Переигранно так: каждый вариант
#     собирается assembleMinVerify после смены содержимого этого файла, и задача
#     R8 отрабатывает заново. Результат - APK ПОБАЙТОВО ИДЕНТИЧНЫ:
#         bare -keep                195 564 327 байт  sha256 CE16CAA9C0FC2ACB
#         -keep,allowoptimization   195 564 327 байт  sha256 CE16CAA9C0FC2ACB
#     Тот же хеш и та же длина у повторной сборки allowoptimization, так что
#     совпадение не случайно-нулевое, а артефакты равны байт в байт. Правила R8
#     на размер не влияют - ни на байт.
#
# ПРО АБСОЛЮТНЫЕ ЧИСЛА РАЗМЕРА, ЧТОБЫ НЕ ПЕРЕОЦЕНИВАТЬ ИХ ЗНАЧИМОСТЬ.
# Раньше здесь стояла таблица "432 MB распакованных .so, APK 186.50 MB" и
# версия, что прежние 172.1 MB получены сборкой с -PskipNativeBuild. Обе цифры
# описывали ТОГДАШНЮЮ нативную базу и обе протухли: 27.09 при перепроверке та же
# ветка assembleMinVerify дала 182 114 547 байт (173.68 MB) вместо 186.50 MB,
# а состав .so в APK разросся с четырёх библиотек до девятнадцати (добавились
# glslang, ggml-*, whisper_v8fp16_va и др.). Размер APK плавает вместе с тем,
# какие .so и для скольких ABI попали в сборку, - и к proguard это отношения не
# имеет. -PskipNativeBuild=true на момент проверки дал 180 463 533 байт
# (172.09 MB), то есть отличался от обычной сборки на 1.6 MB, а не на те 14 MB,
# что объясняли прежнюю цифру. То есть и сама привязка 172.1 -> skipNativeBuild
# подтверждается лишь приблизительно.
#
# Вывод, ради которого всё это записано, от абсолютных мегабайт НЕ зависит:
# allowoptimization не даёт выигрыша, потому что для debuggable-сборки
# оптимизации и так выключены на уровне AGP (WARNING печатается при каждой
# сборке, см. выше), и подтверждён попибайтовым A/B. Не переписывайте правило
# обратно на bare -keep ради "экономии", которой не существует.
#
# allowoptimization оставлен осознанно: он не вредит сейчас и защищает смысл
# правила, если minVerify когда-нибудь перестанет быть debuggable. Тогда
# оптимизации включатся, и -keep без allowoptimization начал бы запрещать их
# именно там, где тест и должен их проверять.
#
# ПАДЕНИЕ suite лечится памятью, а не proguard: процесс перечитывал модель ncnn
# по 1.6-2.3 ГБ на каждое переключение int8/fp32, RSS не падал обратно, и
# телефон убивал процесс. См. docs\EXPERIMENTS-STT-LATENCY.md.
-keep,allowoptimization class kotlin.** { *; }
-keep,allowoptimization class kotlinx.** { *; }
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
