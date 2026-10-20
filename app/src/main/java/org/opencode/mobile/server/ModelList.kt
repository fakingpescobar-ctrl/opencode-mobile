package org.opencode.mobile.server

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Живой список моделей локального opencode serve: `GET /api/model`.
 *
 * Зачем он, если есть [ModelCatalog]: состав бесплатных моделей у провайдера
 * меняется без предупреждения. Статический список однажды устарел — модель
 * переименовали (`space-bunny-free` → `minimax-m3.1-flash-free`), часть убрали,
 * часть добавили. Поэтому главный источник для пикера — сервер, а встроенный
 * каталог остаётся запасным вариантом на случай, когда сервер не поднят.
 *
 * Разбор вынесен в чистую функцию [parse] намеренно: ответ приходит из
 * внешнего процесса, его форма обязана проверяться тестом, а не устройством.
 */
internal object ModelList {
    /**
     * Список моделей, которые сервер обслуживает. Пусто, если сервер не ответил.
     *
     * Пустой список — не ошибка, а «сервера нет»: вызывающий показывает
     * встроенный каталог и не врёт пользователю про источник.
     */
    fun fetch(port: Int): List<CatalogModel> = LocalOpenCodeClient.get(port, ApiPaths.MODELS)?.let { parse(it) }.orEmpty()

    /**
     * Разбор ответа `/api/model`.
     *
     * Форма ответа — `{location, data:[…]}`; берём `data`. Массив без
     * обёртки тоже принимаем: сервер отдаёт форму `{data:[…]}` стабильно, но
     * пустой результат здесь хуже, чем лишняя терпимость к форме.
     *
     * Сломанный JSON или неожиданная форма дают пустой список, а не
     * исключение: вызывающему важно «сервер не дал списка», а не причина сбоя
     * разбора.
     */
    fun parse(raw: String): List<CatalogModel> {
        val root = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        val items =
            when (root) {
                is JSONArray -> root
                is JSONObject -> root.optJSONArray("data")
                else -> null
            } ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val info = items.optJSONObject(index) ?: return@mapNotNull null
            model(info)
        }
    }

    /**
     * Одна модель из ответа сервера. Null, если запись без идентификатора:
     * такую модель нельзя ни показать, ни положить в конфиг.
     *
     * Id приходит в двух видах: составным (`opencode/big-pickle`) или раздельно
     * (`id` + `providerID`). Разбираем оба, потому что формат ответа —
     * воля сервера, а id нужен в виде `провайдер/модель` — это наш формат.
     */
    private fun model(info: JSONObject): CatalogModel? {
        val rawId = modelId(info)
        if (rawId.isEmpty()) return null
        val provider = info.optString("providerID").trim()
        val bare = rawId.substringAfterLast('/')
        val providerId = rawId.substringBefore('/', provider)
        val id = if (providerId.isEmpty()) bare else "$providerId/$bare"
        return CatalogModel(id = id, label = label(info, id, bare), note = note(info))
    }

    /**
     * Идентификатор модели из ответа сервера.
     *
     * Сервер отдаёт `id`, но форма может поменяться — тогда берём `modelID`.
     * Пустой результат означает «модель без имени», её нельзя ни показать,
     * ни положить в конфиг.
     */
    private fun modelId(info: JSONObject): String {
        val primary = info.optString("id").trim()
        if (primary.isNotEmpty()) return primary
        return info.optString("modelID").trim()
    }

    /**
     * Подпись модели: из встроенного каталога, иначе — имя от сервера.
     *
     * Каталог приоритетнее: у него проверенные подписи («MiMo v2.6 Flash»),
     * а сервер отдаёт машинные имена («MiMo v2.6 Flash Free»), которые в
     * плашке обрезаются многоточием.
     */
    private fun label(
        info: JSONObject,
        id: String,
        bare: String,
    ): String {
        val catalogLabel = ModelCatalog.all.firstOrNull { it.id == id }?.label
        val serverName = info.optString("name").trim()
        val shortName = serverName.removeSuffix(FREE_SUFFIX).trim()
        val fallback =
            when {
                shortName.isNotEmpty() -> shortName
                serverName.isNotEmpty() -> serverName
                else -> ModelCatalog.humanize(bare)
            }
        return catalogLabel ?: fallback
    }

    /**
     * Заметка из данных сервера: лимит контекста и входные модальности.
     *
     * Читается ровно то, что сервер отдаёт: `limit.context` и
     * `capabilities.input`. Своих значений не придумываем — иначе заметка
     * врала бы про модель, которой список уже не соответствует.
     */
    private fun note(info: JSONObject): String {
        val context = info.optJSONObject("limit")?.optInt("context") ?: 0
        val inputs = info.optJSONObject("capabilities")?.optJSONArray("input")
        val modalities = modalitiesOf(inputs)
        return listOfNotNull(
            context.takeIf { it > 0 }?.let { "контекст ${contextText(it)}" },
            modalities.takeIf { it.isNotEmpty() }?.joinToString(", "),
        ).joinToString(SEPARATOR)
    }

    /** Модальности модели из массива `capabilities.input`. */
    private fun modalitiesOf(inputs: JSONArray?): List<String> {
        if (inputs == null) return emptyList()
        return (0 until inputs.length()).mapNotNull { index -> modalityName(inputs.optString(index)) }
    }

    /** Человеческое название модальности; неизвестные пропускаем. */
    private fun modalityName(raw: String): String? =
        when (raw.trim()) {
            "text" -> "текст"
            "image" -> "картинки"
            "video" -> "видео"
            "audio" -> "аудио"
            "pdf" -> "pdf"
            else -> null
        }

    /** Лимит контекста в человеческих единицах: 1 000 000 → «1M», 200 000 → «200k». */
    private fun contextText(limit: Int): String =
        if (limit < THOUSAND) {
            "$limit"
        } else if (limit < MILLION) {
            "${limit / THOUSAND}k"
        } else {
            millionsText(limit)
        }

    /** Лимит от миллиона: 1 000 000 → «1M», 1 100 000 → «1.1M». */
    private fun millionsText(limit: Int): String {
        val whole = limit / MILLION
        val tenth = (limit % MILLION) / (MILLION / TENTH)
        return if (tenth == 0) "${whole}M" else "$whole.${tenth}M"
    }

    /** Хвост имени бесплатной модели — его не показываем. */
    private const val FREE_SUFFIX = " Free"

    /** Разделитель частей заметки. */
    private const val SEPARATOR = " · "

    private const val THOUSAND = 1_000

    private const val MILLION = 1_000_000

    /** Десятая доля миллиона — для «1.1M». */
    private const val TENTH = 10
}
