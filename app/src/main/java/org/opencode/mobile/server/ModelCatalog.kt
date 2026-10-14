package org.opencode.mobile.server

/**
 * Каталог бесплатных моделей провайдера `opencode` для пикера маршрутизации.
 *
 * Список статический и офлайновый: пикер не должен зависеть от `GET /api/model`
 * (сервер может быть не поднят, когда пользователь открывает настройки), а формат
 * id (`провайдер/модель`) уже используется в конфиге. Заметки — для выбора глазами:
 * размер контекста и роль, под которую модель подходит.
 */
internal data class CatalogModel(
    val id: String,
    val label: String,
    val note: String,
)

internal object ModelCatalog {
    val all =
        listOf(
            CatalogModel(
                "opencode/big-pickle",
                "Big Pickle",
                "текущий дефолт, 200k — тяжёлые многошаговые задачи",
            ),
            CatalogModel(
                "opencode/space-bunny-free",
                "Space Bunny",
                "топ: мультимодальная (текст+картинки+видео), 5 уровней reasoning, 1M",
            ),
            CatalogModel(
                "opencode/mimo-v2.6-flash-free",
                "MiMo v2.6 Flash",
                "быстрая лёгкая, 200k — поиск и вспомогательные шаги",
            ),
            CatalogModel(
                "opencode/nemotron-3.5-lightning-free",
                "Nemotron 3.5 Lightning",
                "быстрая, 262k",
            ),
            CatalogModel(
                "opencode/nemotron-3-ultra-free",
                "Nemotron 3 Ultra",
                "крупная, 1M",
            ),
            CatalogModel(
                "opencode/cat-jam-free",
                "Cat Jam",
                "262k",
            ),
            CatalogModel(
                "opencode/longcat-2.5-preview-free",
                "LongCat 2.5 Preview",
                "1M",
            ),
            CatalogModel(
                "opencode/step-5-preview-free",
                "Step 5 Preview",
                "1M",
            ),
            CatalogModel(
                "opencode/muse-spark-1.3-contributor-free",
                "Muse Spark 1.3",
                "1M",
            ),
        )

    fun labelOf(id: String): String = all.firstOrNull { it.id == id }?.label ?: id
}
