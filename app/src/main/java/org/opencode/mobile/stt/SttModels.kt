package org.opencode.mobile.stt

import android.content.Context
import java.io.File

/**
 * Каталог ggml-моделей для whisper.cpp.
 *
 * Сейчас в нём ровно одна модель — small — и это результат измерений, а не
 * недосмотр. Все варианты замерены на одном и том же устройстве
 * (OnePlus CPH2747, Snapdragon 8 Elite, Adreno 840) и на одном наборе из 40
 * клипов с человеческим эталоном; полные таблицы, методика и то, что
 * вычеркнуто и почему, лежат в `docs/VOICE-REPORT.md`.
 *
 * Коротко, почему осталась одна:
 *
 * - tiny (74 МБ) — 60 % ошибок, хуже base в 1,6 раза, мимо;
 * - base (141 МБ) — 38 % ошибок, треть слов неверных, для диктовки мусор;
 * - medium (1,46 ГБ) — 11 % ошибок, но 6,5 секунды: та же точность, что у
 *   small, втрое дороже по времени и втрое тяжелее;
 * - turbo f16 (1,55 ГБ) — 7 % ошибок, но 11 секунд: выигрыш 3 процентных
 *   пункта не окупает пятикратного времени;
 * - turbo q5_0 (574 МБ) — на GPU Adreno кванты дают мусор (WER ~3500 %),
 *   поэтому в приложении их нет вообще, а `jni.c` не пускает кванты на GPU;
 * - ncnn-turbo — отдельный движок, его Vulkan-путь падает в SIGSEGV на этом
 *   Adreno (см. `docs/VOICE-REPORT.md` и issue Tencent/ncnn#7097), CPU-путь
 *   в 11 раз медленнее small. Движок выпилен из приложения целиком.
 *
 * Каталог остаётся файлом, а не константой, потому что список моделей, кнопки
 * скачивания и проверка «что лежит на диске» читаются из одного места. Когда
 * появится модель, достойная внимания, это одна запись здесь.
 */
@Suppress("LongParameterList")
internal data class SttModel(
    /** Идентификатор, который пишется в prefs и понимает WhisperTranscribeService. */
    val id: String,
    /** Что показываем пользователю: время на фразе и цена ошибки. */
    val note: String,
    /** Размер файла на диске — для подписи и для проверки свободного места. */
    val bytes: Long,
    /** Готова ли модель: файл есть, размер сходится, контрольная сумма не битая. */
    val isReady: (Context) -> Boolean,
    /** Файл модели на диске — нужен, чтобы узнать размер уже скачанного. */
    val fileOf: (Context) -> File,
    /** Скачивание с колбэком прогресса (сделано, всего). */
    val fetch: suspend (Context, (Long, Long) -> Unit) -> Unit,
    /** Короткая подпись размера, чтобы не форматировать его в трёх местах. */
    val sizeLabel: String,
    /**
     * Имя одиночного файла модели в каталоге моделей (modelsDir) или null,
     * если модель лежит иначе (например, набор из нескольких файлов в другом
     * каталоге, как Parakeet). Из этого поля строится [SttModels.fileNames] —
     * белый список для [ModelDownloader.deleteOtherModels], поэтому добавлять
     * имя в двух местах больше не нужно.
     */
    val flatFileName: String?,
    /**
     * Модель ставится вручную (например, adb push набора файлов), а не кнопкой
     * скачивания: [fetch] у такой модели — no-op. Пикеру это нужно, чтобы не
     * отправлять человека в диагностику за кнопкой, которой там нет.
     */
    val manual: Boolean = false,
)

internal object SttModels {
    /**
     * Модель по умолчанию, и она же единственная.
     *
     * На контрольном прогоне 40 клипов small дал 3,1 % ошибок на фразах
     * 1,5–4 секунды (десять из двенадцати дословно) и 2,3 секунды на
     * распознавание. Это единственная из всех моделей, что попадает в бюджет
     * «2–3 секунды на раздумье» и при этом не ошибается в каждой трети слов.
     *
     * Время не зависит от длины записи: whisper всегда кодирует окно 30 секунд,
     * поэтому короткая фраза платит за тишину в остальных. Отсюда и две
     * границы применимости: огрызки короче 1,5 секунды дают высокую долю ошибок
     * (одно слово из двух = 100 %), а длинные развёрнутые фразы модель
     * перефразирует своими словами — смысл сохранён, но текст чужой.
     */
    const val DEFAULT_ID = WhisperTranscribeService.MODEL_SMALL

    /** Идентификатор Parakeet TDT 0.6B v3 (sherpa-onnx nemo_transducer). */
    const val PARAKEET_ID = "parakeet-tdt-0.6b-v3"

    /** Все модели, которые приложение умеет предложить. Сейчас ровно одна. */
    val all: List<SttModel> =
        listOf(
            SttModel(
                id = WhisperTranscribeService.MODEL_SMALL,
                note = "2,3 с · ошибка в 3 % слов на фразе до 4 секунд",
                bytes = ModelDownloader.SMALL_BYTES,
                isReady = ModelDownloader::smallReady,
                fileOf = ModelDownloader::smallFile,
                fetch = { ctx, on -> ModelDownloader.downloadSmall(ctx, on) },
                sizeLabel = "465 МБ",
                flatFileName = ModelDownloader.SMALL_FILE,
            ),
            SttModel(
                id = PARAKEET_ID,
                note = "0,8 с на 12,6 с аудио · WER 11,1% (лучше whisper на длинных) · CPU, ~640 МБ",
                bytes = 670L * 1024 * 1024,
                isReady = { ctx -> parakeetReady(ctx) },
                fileOf = { ctx -> parakeetDir(ctx) },
                fetch = { _, _ -> }, // скачивание вручную (tar.bz2) — оставим бенч-UI, не заводим в основной
                sizeLabel = "~670 МБ",
                flatFileName = null, // не одиночный .bin в modelsDir: 4 файла в external/parakeet
                manual = true,
            ),
        )

    /** Модель по идентификатору; null, если такого выбора уже нет в каталоге. */
    fun byId(id: String): SttModel? = all.firstOrNull { it.id == id }

    /** Название для интерфейса; для неизвестного id возвращаем сам id. */
    fun labelOf(id: String): String = byId(id)?.id ?: id

    /**
     * Имена файлов, которые каталог считует своими. Всё прочее в
     * filesDir/models — кандидат на удаление: см. [ModelDownloader.deleteOtherModels].
     * Строится из [SttModel.flatFileName], чтобы белый список не мог разойтись
     * с каталогом: модель, живущая одним файлом в modelsDir, попадает сюда сама.
     */
    val fileNames: Set<String> =
        all
            .mapNotNull { it.flatFileName }
            .toSet()

    private fun parakeetDir(context: Context): File = File(context.getExternalFilesDir(null), WhisperTranscribeService.PARAKEET_DIR_NAME)

    private fun parakeetReady(context: Context): Boolean {
        val dir = parakeetDir(context)
        if (!dir.exists()) return false
        val files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
        return files.all { File(dir, it).exists() }
    }

    /** Готовность и размер всех моделей разом — только из IO-ветки (модель хешируется). */
    fun snapshotOf(context: Context): Map<String, Long> =
        all
            .filter { it.isReady(context) }
            .associate { it.id to it.fileOf(context).length() }
}
