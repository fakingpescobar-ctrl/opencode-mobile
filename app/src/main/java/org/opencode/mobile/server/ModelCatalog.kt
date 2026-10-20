package org.opencode.mobile.server

/**
 * Каталог бесплатных моделей провайдера `opencode` для пикера маршрутизации.
 *
 * У каталога две правды, и это осознанно:
 *  - [all] — встроенный список. Сервер может быть не поднят, когда
 *    пользователь открывает настройки, а пустой пикер там бесполезен.
 *  - [ModelList] — то, что сервер реально обслуживает (`GET /api/model`).
 *    Это главный источник: провайдер меняет состав моделей без нашего
 *    ведома, и жёсткий список однажды устаревает (уже устаревал: модель
 *    переименовали, часть моделей убрали).
 *
 * Форма id — `провайдер/модель`: та же, что в конфиге opencode, поэтому
 * значение из пикера кладётся в конфиг без преобразований.
 */
internal data class CatalogModel(
    val id: String,
    val label: String,
    val note: String,
)

internal object ModelCatalog {
    /**
     * Встроенный список живых моделей (сверен 20.10.2026 с составом провайдера).
     *
     * Порядок — от «дефолта провайдера» к остальным, а не по алфавиту: сначала
     * тяжёлые, потом лёгкие. Заметки — для выбора глазами: размер контекста и
     * подходящая роль.
     */
    val all =
        listOf(
            CatalogModel(
                "opencode/big-pickle",
                "Big Pickle",
                "дефолт провайдера, 200k — тяжёлые многошаговые задачи",
            ),
            CatalogModel(
                "opencode/minimax-m3.1-flash-free",
                "MiniMax M3.1 Flash",
                "текст+картинки+видео, 1M — тяжёлые и мультимодальные",
            ),
            CatalogModel(
                "opencode/deepseek-v4.1-flash-free",
                "DeepSeek V4.1 Flash",
                "текст+картинки, 200k — быстрые тяжёлые шаги",
            ),
            CatalogModel(
                "opencode/glm-5.3-flash-free",
                "GLM 5.3 Flash",
                "текст+картинки+видео+pdf, 200k — тяжёлые с документами",
            ),
            CatalogModel(
                "opencode/nemotron-3.5-super-vl-free",
                "Nemotron 3.5 Super VL",
                "текст+картинки, 1M — длинные мультимодальные задачи",
            ),
            CatalogModel(
                "opencode/longcat-2.5-preview-free",
                "LongCat 2.5 Preview",
                "текст+картинки, 1M — длинные контексты",
            ),
            CatalogModel(
                "opencode/mimo-v2.6-flash-free",
                "MiMo v2.6 Flash",
                "текст+картинки+аудио+видео, 200k — быстрые лёгкие шаги",
            ),
        )

    /** Подпись модели: из встроенного каталога, иначе — имя, разобранное из id. */
    fun labelOf(id: String): String = all.firstOrNull { it.id == id }?.label ?: humanize(id)

    /**
     * Заметка о модели: встроенная, иначе — из живого списка.
     *
     * Встроенная приоритетнее: в ней роль («тяжёлые и мультимодальные»),
     * ради которой модель и выбирают. Живая заметка (лимит контекста и
     * модальности от сервера) — запасной вариант для моделей, которых в
     * каталоге ещё нет.
     */
    fun noteFor(
        id: String,
        live: List<CatalogModel>,
    ): String = all.firstOrNull { it.id == id }?.note ?: live.firstOrNull { it.id == id }?.note.orEmpty()

    /**
     * Человеческое имя из технического id: `minimax-m3.1-flash-free` →
     * `Minimax M3.1 Flash`.
     *
     * Нужен для моделей, которых не было в каталоге на момент сборки: сервер
     * добавляет их без предупреждения, и в пикере должен быть хоть какой-то
     * текст, а не `opencode/some-new-model`.
     */
    fun humanize(id: String): String {
        val bare = id.substringAfterLast('/').trim()
        if (bare.isEmpty()) return id
        return bare
            .split('-', '_')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
    }

    /**
     * Список для показа: [models] плюс текущая выбранная модель, даже если её
     * в списке нет.
     *
     * Зачем: в конфиге может лежать модель, которой провайдер больше не
     * отдаёт. Если такую не показать, пользователь видит выбор без своего
     * значения и не понимает, что агент работает на несуществующей модели.
     */
    fun withCurrent(
        models: List<CatalogModel>,
        current: String,
    ): List<CatalogModel> {
        if (models.any { it.id == current }) return models
        return listOf(CatalogModel(current, labelOf(current), MISSING_NOTE)) + models
    }

    /** Заметка для модели, которой нет в списке сервера. */
    const val MISSING_NOTE = "модели нет в списке сервера — выбери другую"
}
