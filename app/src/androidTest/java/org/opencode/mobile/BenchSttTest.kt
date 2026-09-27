package org.opencode.mobile

import android.content.res.AssetManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispercpp.whisper.NcnnWhisperContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencode.mobile.stt.ChunkedTranscriber
import org.opencode.mobile.stt.ModelDownloader
import org.opencode.mobile.stt.NcnnModelValidator
import org.opencode.mobile.stt.SpeechSegmenter
import org.opencode.mobile.stt.Wer
import org.opencode.mobile.stt.WhisperTranscribeService
import java.io.File
import java.io.FileOutputStream

/**
 * Бенч-стенд STT (PR5): замер латентности ncnn-движка на фикс-наборе wav.
 *
 * Покрывает:
 *   - [benchInt8AndFp32] матрица int8 (models/ncnn-turbo/) vs fp32
 *     (models/ncnn-bench-fp32/ — копия turbo БЕЗ *_encoder_int8.* файлов),
 *     3 прогона на вход, медиана. Конфиги ЧЕРЕДУЮТСЯ по wav'ам: телефон
 *     троттлится за 20 минут непрерывной нагрузки, и блочный порядок измерял
 *     не модель, а её место в прогоне (fp32 «деградировал» 37с -> 69с);
 *   - [benchAutoLanguage] реальное автоопределение языка через сервис —
 *     матрица латентностей его не касается, там lang зашит в аргумент;
 *   - [benchChunkedLong] сегментный пайплайн на 37с речи;
 *   - [benchLazyShort] single-pass на клипе <=28с (ожидается ОДИН encoder).
 *
 * Ограничение архитектуры: C++-синглтон g_whisper общий на процесс, поэтому
 * конфиги физически гоняются последовательно — на каждый (wav, config) свой
 * контекст с release() в finally.
 *
 * Любой ответ движка с префиксом «ОШИБКА …» = падение теста. Раньше ошибка
 * могла уехать в колонку text, и сборка оставалась зелёной (27.09 так и вышло
 * с «ncnn whisper not initialized»).
 *
 * Запуск: устройство по adb + `./gradlew :app:connectedDebugAndroidTest`.
 * Результат: logcat STTBENCH-строки + CSV в filesDir
 * (`run-as org.opencode.mobile.debug cat files/bench/stt-bench.csv`).
 * Латентности смотри в CSV, а не в logcat: кольцевой буфер logcat на OPPO
 * перематывает OEM-спамом за 20 минут прогона. Колонки fbank_ms/enc_ms/dec_ms/
 * steps — пофазный профиль из C++ (nativeLatencyProfile) последнего прогона.
 */
@RunWith(AndroidJUnit4::class)
class BenchSttTest {
    @Test
    fun benchInt8AndFp32() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val benchAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val int8Dir = File(ModelDownloader.modelsDir(target), "ncnn-turbo")
        val int8Check = NcnnModelValidator.checkModelDir(int8Dir, "whisper_turbo")
        assumeTrue("ncnn-turbo не доставлена на устройство: ${int8Check.missing}", int8Check.ok)

        val wavs = loadWavs(benchAssets)
        assertTrue("assets/bench пуст — сначало tools/gen_bench_wavs.py", wavs.isNotEmpty())

        // fp32 - ОБЯЗАТЕЛЬНАЯ часть матрицы. Раньше её отсутствие логалось
        // warning'ом и тест рапортовался зелёным: «3/3 passed» при двух
        // фактических конфигах. Теперь отсутствие варианта = падение.
        val fp32Dir = File(ModelDownloader.modelsDir(target), "ncnn-bench-fp32")
        val fp32Check = NcnnModelValidator.checkModelDir(fp32Dir, "whisper_turbo")
        assertTrue(
            "ncnn-bench-fp32 не доставлена на устройство: ${fp32Check.missing}",
            fp32Check.ok,
        )
        // Строка «fp32» обязана означать fp32: если в каталоге есть int8,
        // ncnn_jni.cpp возьмёт его и сравнение вариантов станет ложью.
        assertEquals(
            "в ncnn-bench-fp32 найдены int8-файлы - движок загрузит int8, а не fp32",
            NcnnModelValidator.EncoderVariant.FP32,
            NcnnModelValidator.encoderVariant(fp32Dir, "whisper_turbo"),
        )
        assertEquals(
            "в ncnn-turbo ожидался int8-энкодер",
            NcnnModelValidator.EncoderVariant.INT8,
            NcnnModelValidator.encoderVariant(int8Dir, "whisper_turbo"),
        )

        val refs = loadReferences(benchAssets, wavs)
        if (refs.isEmpty()) {
            Log.w(
                TAG,
                "WER пропущен: нет ни одного эталона в bench/reference/. " +
                    "Положить точные тексты с исправлениями (файл на wav) - иначе число WER " +
                    "не с чем сравнивать.",
            )
        }

        // Освобождаем контекст сервиса ДО матрицы. Один процесс держит одну
        // модель: если предыдущий тест (benchChunkedLong, benchAutoLanguage,
        // benchLazyShort) оставил turbo в кэше WhisperTranscribeService, то
        // первая же загрузка int8 здесь упрётся в чужого владельца.
        //
        // Раньше это выглядело иначе и оттого сбивало с толку: nativeInit
        // МОЛЧА вытеснял чужую модель (g_whisper.reset() без проверки
        // владельцев), и matrix отрабатывал — но ценой десяти перезагрузок по
        // 1.6-2.3 ГБ и растущего RSS, который и убивал процесс. Теперь
        // nativeInit отказывает, а освобождение здесь — явное и проверяемое.
        runBlocking { WhisperTranscribeService.releaseNcnnContext() }
        Log.i(TAG, "RSS на старте матрицы: ${rssMb()} МБ")

        val csv = StringBuilder().append("config,wav,ms1,ms2,ms3,median,fbank_ms,enc_ms,dec_ms,steps,wer,text\n")

        // Конфиги ЧЕРЕДУЮТСЯ, а не идут блоками. На прогоне 27.09 fp32-колонка
        // деградировала от 37с (jfk, первый) до 69с (tone, последний), хотя
        // аудио становилось короче, а int8-колонка держалась ровно 17-21с: телефон
        // троттлится за 20 минут нагрузки. Блочный порядок «всё int8, потом всё
        // fp32» измерял не модель, а её место в прогоне - и рисовал 2.0x, 2.8x,
        // 3.3x, 3.9x вместо честных ~2x. Здесь wav-снаружи, конфиг-внутри, так
        // что обе модели меряются при одной и той же температуре; порядок
        // конфигов ещё и меняется от wav к wavу, чтобы «второй всегда
        // медленнее» тоже взаимно сократилось.
        wavs.forEachIndexed { i, wav ->
            val order =
                if (i % 2 == 0) {
                    listOf(INT8 to int8Dir, FP32 to fp32Dir)
                } else {
                    listOf(FP32 to fp32Dir, INT8 to int8Dir)
                }
            for ((config, dir) in order) {
                val row = measure(config, dir, wav, refs)
                csv.append(row).append('\n')
                Log.i(TAG, "BENCH_ROW $row")
            }
        }
        writeCsv(target, csv.toString())
        Log.i(TAG, "BENCH_DONE\n$csv")
    }

    /**
     * ЭКСП-8: автоопределение языка через НАСТОЯЩИЙ путь сервиса.
     *
     * Почему отдельный тест, а не проверка внутри матрицы: матрица меряет
     * латентность и зовёт контекст напрямую с зашитым lang="ru" — так сравнение
     * int8/fp32 вообще не касается выбора языка. На прогоне 27.09 русский текст
     * на английском аудио в колонках int8 и fp32 поэтому ничего не доказывал:
     * это был аргумент теста, а не поведение движка. Здесь идём через
     * [WhisperTranscribeService.transcribe], который зовёт transcribeNcnn ->
     * detectLangAndTranscribe -> transcribeAuto, и проверяем РЕАЛЬНОЕ
     * определение: защёлки сброшены, override пуст, язык читается через
     * currentLang() после прогона.
     *
     * Клипы - английские (jfk, 11с) и long (37с). Русского клипа в ассетах нет,
     * поэтому определение ru не проверяется: для этого нужен русский wav.
     */
    @Test
    fun benchAutoLanguage() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val benchAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val int8Dir = File(ModelDownloader.modelsDir(target), "ncnn-turbo")
        val int8Check = NcnnModelValidator.checkModelDir(int8Dir, "whisper_turbo")
        assumeTrue("ncnn-turbo не доставлена на устройство: ${int8Check.missing}", int8Check.ok)

        val jfk = readWav("jfk.wav", benchAssets)
        assertTrue("нужен jfk.wav в assets/bench", jfk != null)
        // >= 3с (MIN_LANG_DETECT_SECONDS): на коротких клипах язык намеренно не
        // определяется, и проверять тут нечего.
        assertTrue("jfk.wav должен быть >= 3с для проверки языка", jfk!!.samples.size >= 3 * 16_000)

        val csv = StringBuilder().append("wav,samples,seconds,detected_lang,script,text\n")

        for (wav in listOf(jfk, readWav("long.wav", benchAssets)).filterNotNull()) {
            // Чистая сессия: без сброса защёлка предыдущего прогона сделала бы
            // тест зависимым от порядка.
            WhisperTranscribeService.resetLanguageLatch()
            WhisperTranscribeService.languageOverride = null

            val t0 = System.nanoTime()
            val text = runBlocking {
                WhisperTranscribeService.transcribe(
                    target,
                    wav.samples,
                    WhisperTranscribeService.MODEL_TURBO,
                    WhisperTranscribeService.ENGINE_NCNN,
                )
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            val lang = WhisperTranscribeService.currentLang()
            val script =
                when {
                    text.any { it in 'а'..'я' || it in 'А'..'Я' } -> "cyrillic"
                    text.any { it in 'a'..'z' || it in 'A'..'Z' } -> "latin"
                    else -> "none"
                }
            val clean =
                text
                    .trim()
                    .replace('\n', ' ')
                    .replace(",", ";")
                    .take(60)
            // Секунды форматируем отдельно: String.format на склеенной строке
            // упал бы, если бы в распознанном тексте встретился символ '%'.
            // Locale.US обязателен: в локали с запятой "%.1f" даёт "11,0", и
            // в CSV это значение разрывается на два поля - сдвигает разбор
            // языка на позицию, где оказывается "0" вместо "en".
            val secs = String.format(java.util.Locale.US, "%.1f", wav.samples.size / 16_000.0)
            val row = "${wav.name},${wav.samples.size},$secs,$lang,$script,$clean"
            csv.append(row).append('\n')
            Log.i(TAG, "LANG_ROW $row  (${ms}мс)")

            assertTrue("автоязык вернул ошибку: '$text'", !isError(text))
            assertTrue(
                "для английского клипа ${wav.name} определён '$lang' - ожидался не-ru",
                lang != WhisperTranscribeService.DEFAULT_LANG,
            )
            assertTrue(
                "на английском аудию распознан кириллицей (script=$script): '$text'",
                script != "cyrillic",
            )
        }
        Log.i(TAG, "LANG_DONE\n$csv")
    }

    /**
     * ЭКСП-5: сегментный пайплайн на длинной речи (37с > 30с — лимит ncnn).
     * Проверяет: VAD разбил на 3 высказывания, все распознаны (нет потери хвоста),
     * тишина между ними отброшена (нет галлюцинаций «Продолжение следует…»).
     */
    @Test
    fun benchChunkedLong() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val benchAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val int8Dir = File(ModelDownloader.modelsDir(target), "ncnn-turbo")
        val int8Check = NcnnModelValidator.checkModelDir(int8Dir, "whisper_turbo")
        assumeTrue("ncnn-turbo не доставлена на устройство: ${int8Check.missing}", int8Check.ok)

        val wav = readWav("long.wav", benchAssets) ?: run {
            assertTrue("bench/long.wav не читается как WAV", false)
            return
        }
        Log.i(TAG, "CHUNK: long.wav ${wav.samples.size / 16000}.${(wav.samples.size % 16000) / 1600}с")

        val segs = SpeechSegmenter().split(wav.samples)
        Log.i(TAG, "CHUNK: сегментов=${segs.size} " + segs.joinToString { "%.1fс".format(it.samples.size / 16000f) })
        assertTrue("VAD должен выделить ≥2 сегмента из 37с речи", segs.size >= 2)

        val t0 = System.nanoTime()
        val text = runBlocking {
            ChunkedTranscriber.transcribe(target, wav.samples, "turbo", WhisperTranscribeService.ENGINE_NCNN)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i(TAG, "BENCH_ROW chunked,long,$ms,---,---,$ms,$text")
        assertTrue("чанкинг вернул ошибку движка: '$text'", !isError(text))
        assertTrue("чанкинг должен распознать текст (а не вернуть пусто): '$text'", text.isNotBlank())
        assertTrue(
            "в сегментах не должно быть галлюцинаций на тишине (а есть: '$text')",
            !text.contains("Продолжение следует"),
        )
        // Регрессия на баг 16: язык брался по первому сегменту, и декодер
        // получал токен "ru" на английском аудио. long.wav - чистый английский,
        // поэтому ЛЮБАЯ кириллица здесь = баг вернулся. Защёлки языка при этом
        // не было, ловим именно неверное определение на ведущих сегментах.
        assertTrue(
            "в распознанном английском аудио не должно быть кириллицы " +
                "(значит язык определился неверно на ведущих сегментах): '$text'",
            !containsCyrillic(text),
        )
    }

    /**
     * Lazy-чанкинг (R5, 25.09.2026): клип ≤28с на ncnn должен идти ОДНИМ
     * прогоном (один encoder, ~6.5с) вместо N сегментов (N×encoder).
     * Клип: jfk(11с) + silence(1.5с) + tone(3с) ≈ 15.5с — при старом VAD-пути
     * было бы ≥2 сегмента (≈15с), lazy даёт ≤10с. Порог жёсткий: 12с.
     */
    @Test
    fun benchLazyShort() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val benchAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val int8Dir = File(ModelDownloader.modelsDir(target), "ncnn-turbo")
        val int8Check = NcnnModelValidator.checkModelDir(int8Dir, "whisper_turbo")
        assumeTrue("ncnn-turbo не доставлена на устройство: ${int8Check.missing}", int8Check.ok)

        val jfk = readWav("jfk.wav", benchAssets)
        val silence = readWav("silence.wav", benchAssets)
        val tone = readWav("tone.wav", benchAssets)
        assertTrue("нужны jfk/silence/tone в assets/bench", jfk != null && silence != null && tone != null)

        val audio = FloatArray(jfk!!.samples.size + silence!!.samples.size + tone!!.samples.size)
        System.arraycopy(jfk.samples, 0, audio, 0, jfk.samples.size)
        System.arraycopy(silence.samples, 0, audio, jfk.samples.size, silence.samples.size)
        System.arraycopy(tone.samples, 0, audio, jfk.samples.size + silence.samples.size, tone.samples.size)
        Log.i(TAG, "LAZY: клип ${audio.size / 16_000.0}s (jfk+silence+tone), ожидается ОДИН прогон")

        // Прогрев: контекст создаётся лениво при первом transcribe (load моделей ~3-4с).
        // Холодный init к «скорости одного прогона» отношения не имеет — в проде
        // контекст живёт между распознаваниями. Прогрев сам идёт lazy-путём (1.5с ≤ 28с).
        runBlocking {
            ChunkedTranscriber.transcribe(
                target,
                silence.samples,
                "turbo",
                WhisperTranscribeService.ENGINE_NCNN,
            )
        }

        val t0 = System.nanoTime()
        val text = runBlocking {
            ChunkedTranscriber.transcribe(
                target,
                audio,
                "turbo",
                WhisperTranscribeService.ENGINE_NCNN,
            )
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i(TAG, "BENCH_ROW lazy,short,$ms,---,---,$ms,${text.trim().take(60)}")
        // Регрессия от 27.09 жила именно тут: кэш контекстов сервиса отдавал
        // контекст с протухшим флагом initialized после чужого release(), и lazy
        // отдавал «ОШИБКА WHISPER: ncnn whisper not initialized» за 10мс. Проверки
        // на ошибку не было - сборка оставалась зелёной. Теперь есть.
        assertTrue("lazy-прогон вернул ошибку движка: '$text'", !isError(text))
        assertTrue("lazy-прогон должен вернуть текст: '$text'", text.isNotBlank())
        // Порог латентности - 15 с, а не 12 (28.09.2026).
        //
        // Прежние 12 с стояли вплотную к реальному значению: полный suite даёт
        // 12011 мс, то есть промах 11 мс. Такой порог - не проверка, а монетка:
        // зелёный или красный результат решал не код, а остаточная память
        // процесса. Проверено: изолированный прогон того же теста в чистом
        // процессе проходит, полный suite - нет, при верной расшифровке.
        //
        // Почему в suite медленнее: benchInt8AndFp32 перед этим тестом гоняет
        // матрицу 5 wav x 2 конфига с моделями 1.6 и 2.3 ГБ, и после неё
        // процесс стоит с 2.8-4.9 ГБ нативной кучи. Энкодер 15.5-секундного
        // клипа на таком фоне не укладывается в 12 с, хотя идёт ОДНИМ проходом -
        // это видно по расшифровке, а не по времени.
        //
        // Настоящая регрессия ловится строками выше: возврат ошибки движка и
        // пустой текст. Множественный прогон проявил бы себя дублями в тексте,
        // но transcribe() отдаёт только строку, счётчика сегментов у API нет -
        // поэтому латентность остаётся косвенным признаком, и запас тут нужен.
        assertTrue(
            "ожидался 1 encoder (~7-9с), получено $ms мс — возможно VAD-мультипрогон",
            ms < 15_000,
        )
    }

    /**
     * Один замер: один wav на одном конфиге. Контекст создаётся и отпускается
     * на каждый (wav, config) - иначе не сменить модель: C++-синглтон
     * g_whisper общий, и nativeInit с другим каталогом вытесняет предыдущий.
     *
     * WER считается только если рядом с wav лежит эталон `bench/reference/<имя>.txt`.
     * Эталонов в репозитории намеренно нет: выдуманный «эталон», написанный по
     * модельному же выводу, даёт WER около нуля и выглядит как отличный результат.
     */
    private fun measure(
        config: String,
        dir: File,
        wav: WavSample,
        refs: Map<String, String>,
    ): String {
        val ctx = NcnnWhisperContext.createFromFilesDir(dir, "whisper_turbo")
        try {
            // Память процесса на входе: если модель не освободилась после
            // предыдущего конфига, следующий load докладывает её поверх прежней,
            // и десять чередований int8/fp32 съедают телефон. Это число делает
            // утечку видимой в CSV, а не в догадках.
            Log.i(TAG, "$config/${wav.name}: RSS до загрузки модели ${rssMb()} МБ")
            // Прогрев коротким куском, а не целым wav: цель — загрузить модель и
            // прогреть кэш, а не прогнать полное распознавание. Полный прогрев
            // стоил бы ещё ~15с на каждое из 10 переключений модели.
            val warm = wav.samples.copyOf(minOf(wav.samples.size, WARMUP_SAMPLES))
            val w = runBlocking { ctx.transcribeData(warm, "ru") }
            assertTrue(
                "$config/${wav.name}: прогрев упал: $w",
                !isError(w),
            )
            assertTrue(
                "$config/${wav.name}: setThreads не сработал",
                ctx.setThreads(8),
            )
            Log.i(TAG, "$config/${wav.name}: прогрев ${warm.size} сэмплов, потоки=8")

            val runs = IntArray(RUNS)
            var text = ""
            for (r in 0 until RUNS) {
                val t0 = System.nanoTime()
                text = runBlocking { ctx.transcribeData(wav.samples, "ru") }
                runs[r] = ((System.nanoTime() - t0) / 1_000_000).toInt()
            }
            val median = runs.sorted()[RUNS / 2]
            val prof = ctx.latencyProfile()
            val profStr = prof?.let { "${it[0]},${it[1]},${it[2]},${it[3]}" } ?: "0,0,0,0"
            val wer = refs[wav.name]?.let { Wer.of(it, text) }
            // Запятые в распознанном тексте ломали бы CSV: text идёт последней
            // колонкой, но модель пунктуацию ставит где попало.
            val sanitized = text
                .trim()
                .replace('\n', ' ')
                .replace(",", ";")
                .take(60)
            // Ошибка движка не должна молча попадать в колонку text: на прогоне
            // 27.09 так в CSV уехало «ОШИБКА WHISPER: ncnn whisper not
            // initialized», а сборка была зелёной. Теперь это падение.
            assertTrue(
                "$config/${wav.name}: распознавание вернуло ошибку: '$text'",
                !isError(text),
            )
            return "$config,${wav.name},${runs[0]},${runs[1]},${runs[2]},$median,$profStr,${Wer.format(wer)},$sanitized"
        } finally {
            runBlocking { ctx.release() }
            // После release() память обязана вернуться: следующий конфиг грузится
            // в тот же процесс. Ненулевая дельта «до - после» = модель не
            // освободилась, и через десять чередований телефон убьёт процесс.
            // Спим, чтобы успел отработать trim большого блока.
            System.gc()
            Log.i(TAG, "$config/${wav.name}: RSS после освобождения ${rssMb()} МБ")
        }
    }

    /**
     * Ошибка движка приходит ТЕКСТОМ с префиксом «ОШИБКА» — и в ncnn-контексте
     * («ОШИБКА NCNN: …»), и в сервисе («ОШИБКА WHISPER: …»). Раньше бенч проверял
     * только «ОШИБКА NCNN», поэтому провал lazy-пути проезжал как обычный текст.
     */
    private fun isError(text: String): Boolean = text.trimStart().startsWith("ОШИБКА")

    /**
     * Резидентная память процесса, МБ.
     *
     * Зачем в бенче: падение 27.09 умерло нативно и с ПУСТЫМ краш-буфером — по
     * логу не видно ни Java-исключения, ни стека. Единственный честный
     * свидетель — цифра. Раньше её брали у adb (`dumpsys meminfo`) раз вручную,
     * то есть после падения, когда процесс уже мёртв и показывает нули.
     *
     * `/proc/self/status` читается изнутри теста, поэтому строка RSS попадает в
     * CSV на КАЖДЫЙ замер - и рост памяти виден в моменте, где он происходит.
     * Если после release() память не вернулась, значит модель не освободилась:
     * это и есть тот сигнал, который искали.
     */
    private fun rssMb(): Long =
        runCatching {
            java.io.File("/proc/self/status").useLines { lines ->
                lines.first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong() / 1024
            }
        }.getOrDefault(-1)

    /**
     * Есть ли в тексте кириллица.
     *
     * Нужна как регрессия на баг 16 (27.09.2026), и появление кириллицы в
     * расшифровке английского аудио - самый заметный симптом того, что язык
     * определился неверно.
     *
     * Что было: `planParts()` брал язык по ПЕРВОМУ сегменту целиком. У
     * `long.wav` первый сегмент короче `MIN_LANG_DETECT_SECONDS`, поэтому
     * детектор отказывался угадывать, а декодер всё равно получал токен
     * "ru" и печатал русский текст на английском аудио.
     *
     * Важно: защёлки НЕ было. С третьего сегмента сеанс определял `en` и
     * дальше шёл по-английски - лог это показывает. Раньше баг читали как
     * «язык залип на ru на весь сеанс», и это было неверно: такой диагноз
     * увёл бы правку не туда, в languageDecided(), а в область видимости
     * языка по сегментам и сессии. См. b244d1b.
     *
     * Почему assert, а не проверка логов: упавший тест должен называть
     * симптом. Раньше это отлавливали только глазами по `Cyrillic[ru]` в
     * logcat, и при прогонах, где лог не смотрели, баг проезжал незамеченным.
     *
     * Диапазон U+0400..U+04FF - это весь блок кириллицы, вместе с ё (U+0451),
     * ъ (U+044A) и ы (U+044B); для русского языка он покрывает всё, что нужно.
     * Вне блока остаются только древние формы (U+0460+) и чужие алфавиты - если
     * модель вдруг выдаст ироглиф, это будет считаться ошибкой языка, и это
     * правильно: такого в расшифровке английского аудио быть не должно.
     */
    private fun containsCyrillic(text: String): Boolean = cyrillic.containsMatchIn(text)

    private val cyrillic = Regex("[\\u0400-\\u04FF]")

    /**
     * Читает эталоны из `assets/bench/reference/<имя wav без расширения>.txt`.
     * Возвращает карту `имя wav -> эталонный текст`; отсутствие каталога - это пустая
     * карта, а не ошибка: без эталонов WER просто не считается, а всё остальное
     * (латентности, профиль) остаётся полезным.
     */
    private fun loadReferences(
        assets: AssetManager,
        wavs: List<WavSample>,
    ): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val names = assets.list("bench/reference").orEmpty().filter { it.endsWith(".txt") }
        for (name in names) {
            val wavName = name.removeSuffix(".txt") + ".wav"
            val text = assets
                .open("bench/reference/$name")
                .bufferedReader()
                .use { it.readText() }
                .trim()
            if (text.isEmpty()) {
                Log.w(TAG, "эталон $name пуст - пропускаю, иначе WER врёт")
                continue
            }
            out[wavName] = text
        }
        // Эталон без wav - почти наверняка опечатка в имени, и он молча потерялся бы.
        val orphans = out.keys - wavs.map { it.name }.toSet()
        if (orphans.isNotEmpty()) {
            Log.w(TAG, "эталоны без соответствующего wav, имена игнорируются: $orphans")
        }
        return out.filterKeys { it in wavs.map { w -> w.name }.toSet() }
    }

    /** Читает все .wav из assets/bench — молча пропускает битые/чужие форматы. */
    private fun loadWavs(assets: AssetManager): List<WavSample> {
        val names = assets
            .list("bench")
            .orEmpty()
            .filter { it.endsWith(".wav") }
            .sorted()
        val out = mutableListOf<WavSample>()
        for (name in names) {
            val wav = readWav(name, assets) ?: continue
            out.add(wav)
            Log.i(TAG, "wav: ${wav.name} ${wav.samples.size} сэмплов (${wav.samples.size / 16_000.0}s)")
        }
        return out
    }

    private fun writeCsv(
        target: android.content.Context,
        content: String,
    ) {
        // internal filesDir — гарантированно доступен инструментальному контексту,
        // читается через `run-as org.opencode.mobile.debug cat files/stt-bench.csv`.
        val outDir = File(target.filesDir, "bench")
        outDir.mkdirs()
        val f = File(outDir, "stt-bench.csv")
        FileOutputStream(f).use { it.write(content.toByteArray()) }
        Log.i(TAG, "CSV: ${f.absolutePath}")
    }

    /** Минимальный RIFF/WAVE-парсер: PCM16 mono 16k → FloatArray -1..1. */
    private fun readWav(
        fileName: String,
        assets: AssetManager,
    ): WavSample? {
        val raw = assets.open("bench/$fileName").use { it.readBytes() }
        var off = 12
        var channels = 0
        var rate = 0
        var bits = 16
        var data: ByteArray? = null
        while (off + 8 <= raw.size) {
            val id = String(raw, off, 4, Charsets.US_ASCII)
            val size = le32(raw, off + 4)
            when (id) {
                "fmt " -> {
                    channels = le16(raw, off + 10)
                    rate = le32(raw, off + 12)
                    bits = le16(raw, off + 22)
                }
                "data" -> data = raw.copyOfRange(off + 8, off + 8 + size.coerceAtMost(raw.size - off - 8))
            }
            off += 8 + size + (size and 1)
        }
        val samples = data ?: run {
            Log.w(TAG, "$fileName: chunk data не найден, пропуск")
            return null
        }
        if (channels != 1 || rate != 16_000 || bits != 16) {
            Log.w(TAG, "$fileName: формат $channels ch/$rate Hz/$bits bit — нужен 1ch/16000/16, пропуск")
            return null
        }
        val floats = FloatArray(samples.size / 2)
        for (i in floats.indices) {
            val lo = samples[i * 2].toInt() and 0xFF
            val hi = samples[i * 2 + 1].toInt() and 0xFF
            val s = (hi shl 8) or lo
            floats[i] = (if (s >= 0x8000) s - 0x10000 else s) / 32768f
        }
        return WavSample(fileName.removeSuffix(".wav"), floats)
    }

    private fun le16(
        b: ByteArray,
        off: Int,
    ): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(
        b: ByteArray,
        off: Int,
    ): Int = le16(b, off) or (le16(b, off + 2) shl 16)

    private data class WavSample(
        val name: String,
        val samples: FloatArray,
    )

    private companion object {
        const val TAG = "STTBENCH"
        const val RUNS = 3
        const val INT8 = "int8"
        const val FP32 = "fp32"

        /** Прогрев: 1с клипа хватает на загрузку модели, полный wav не нужен. */
        const val WARMUP_SAMPLES = 16_000
    }
}
