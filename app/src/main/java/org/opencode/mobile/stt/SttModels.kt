package org.opencode.mobile.stt

import android.content.Context
import java.io.File

/**
 * Каталог ggml-моделей для whisper.cpp — единственный источник правды сразу для
 * трёх мест: список выбора модели в настройках голоса, кнопки скачивания в
 * диагностике и проверка «какая модель лежит на диске».
 *
 * До этого файла модели были размазаны по константам [ModelDownloader], и
 * интерфейс знал ровно про две из них: base и turbo q5_0. Small, medium и
 * turbo f16 были замерены, но скачать их было нечем — кнопок не существовало.
 * Каталог закрывает эту дыру: добавить модель теперь значит дописать одну
 * строку, а не править UI в трёх местах.
 *
 * Цифры в [SttModel.note] — замеры 14.10.2026 на OnePlus CPH2747 (Snapdragon 8
 * Elite, Adreno 840): whisper.cpp собран с GGML_VULKAN=ON и flash attention,
 * один и тот же набор из 7 клипов, WER по расстоянию Левенштейна к общему
 * эталону. Время не зависит от длины записи — whisper всегда кодирует окно
 * 30 секунд, поэтому короткая фраза платит за тишину в остальных.
 *
 * Квантованные модели сюда НЕ входят: на Adreno 840 ggml-vulkan для квантов
 * даёт мусор (turbo q5_0 — WER ~3500 % и rtf 47,64), поэтому `jni.c` вообще
 * не пускает кванты на GPU. А tiny (WER 60 %) и turbo q5_0 вычеркнуты как
 * бесполезные: первую обгоняет даже base, вторую turbo f16 при втрое меньшем
 * времени.
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
)

internal object SttModels {
    /**
     * Модель по умолчанию. small — единственная из всех, что попадает в бюджет
     * «2–3 секунды на раздумье» (2,0–2,2 с) и при этом ошибается не чаще, чем
     * turbo: 11,3 % против 7,4 % на общем эталоне. Полпроцента не окупают ни
     * трёхкратного времени turbo, ни трёхкратного веса.
     */
    const val DEFAULT_ID = WhisperTranscribeService.MODEL_SMALL

    /**
     * Порядок — от рекомендуемой к более точной и тяжёлой: список читается как
     * «вот что стоит взять по умолчанию, а вот чем можно заплатить точностью».
     */
    val all: List<SttModel> =
        listOf(
            SttModel(
                id = WhisperTranscribeService.MODEL_SMALL,
                note = "2 с · ошибка в 11 % слов · рекомендуется",
                bytes = ModelDownloader.SMALL_BYTES,
                isReady = ModelDownloader::smallReady,
                fileOf = ModelDownloader::smallFile,
                fetch = { ctx, on -> ModelDownloader.downloadSmall(ctx, on) },
                sizeLabel = "465 МБ",
            ),
            SttModel(
                id = WhisperTranscribeService.MODEL_MEDIUM,
                note = "6,5 с · ошибка в 11 % слов · та же точность вдвое дороже",
                bytes = ModelDownloader.MEDIUM_BYTES,
                isReady = ModelDownloader::mediumReady,
                fileOf = ModelDownloader::mediumFile,
                fetch = { ctx, on -> ModelDownloader.downloadMedium(ctx, on) },
                sizeLabel = "1,46 ГБ",
            ),
            SttModel(
                id = WhisperTranscribeService.MODEL_TURBO_F16,
                note = "11 с · ошибка в 7 % слов · самое точное",
                bytes = ModelDownloader.TURBO_F16_BYTES,
                isReady = ModelDownloader::turboF16Ready,
                fileOf = ModelDownloader::turboF16File,
                fetch = { ctx, on -> ModelDownloader.downloadTurboF16(ctx, on) },
                sizeLabel = "1,55 ГБ",
            ),
            SttModel(
                id = WhisperTranscribeService.MODEL_BASE,
                note = "0,6 с · ошибка в 38 % слов · только ради экономии места",
                bytes = ModelDownloader.BASE_BYTES,
                isReady = ModelDownloader::baseReady,
                fileOf = ModelDownloader::baseFile,
                fetch = { ctx, on -> ModelDownloader.downloadBase(ctx, on) },
                sizeLabel = "141 МБ",
            ),
        )

    /** Модель по идентификатору; null, если такого выбора уже нет в каталоге. */
    fun byId(id: String): SttModel? = all.firstOrNull { it.id == id }

    /** Название для интерфейса; для неизвестного id возвращаем сам id. */
    fun labelOf(id: String): String = byId(id)?.id ?: id

    /**
     * Имена файлов, которые каталог считает своими. Всё прочее в
     * filesDir/models — кандидат на удаление: см. [ModelDownloader.deleteOtherModels].
     */
    val fileNames: Set<String> =
        setOf(
            ModelDownloader.SMALL_FILE,
            ModelDownloader.MEDIUM_FILE,
            ModelDownloader.TURBO_F16_FILE,
            ModelDownloader.BASE_FILE,
        )

    /** Готовность и размер всех моделей разом — только из IO-ветки (модель хешируется). */
    fun snapshotOf(context: Context): Map<String, Long> =
        all
            .filter { it.isReady(context) }
            .associate { it.id to it.fileOf(context).length() }
}
