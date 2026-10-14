package org.opencode.mobile.server

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Проверка «а агент реально работает на выбранных моделях?».
 *
 * Зачем отдельный файл. Конфиг opencode читается ровно один раз — при старте
 * процесса serve; файловый вотчер на Android отключён (`watcher backend not
 * supported`), и `GET /api/config` продолжает отдавать старое расписание, пока
 * процесс жив. Из-за этого «я выбрал Space Bunny, а он отвечает про Big Pickle»
 * — не ошибка записи конфига (файл был правильный), а незамеченный рестарт.
 *
 * Поэтому UI не считает результат по факту записи файла: после рестарта он
 * спрашивает живой сервер и снимает плашку «применить» только когда сервер
 * действительно отдаёт нужное расписание. Иначе плашка остаётся — неудачный
 * рестарт должен быть виден и повторяем, а не спрятан за зелёной галочкой.
 *
 * Разбор вынесен в чистые функции ([parseAgentModels], [routingServed]), потому
 * что единственное, что здесь нетривиально, — форма ответа: она приходит из
 * внешнего процесса и обязана проверяться тестом, а не устройством.
 */
internal object RoutingReadiness {
    /** Путь конфига у serve: единственный источник правды о том, что он обслуживает. */
    private const val CONFIG_PATH = "/api/config"

    /** Пауза между попытками опроса. Рестарт serve — секунды, не миллисекунды. */
    private const val POLL_INTERVAL_MS = 1_000L

    /**
     * Сколько ждём нового процесса. Измерено на устройстве: мягкий рестарт
     * занимает единицы секунд; 40 попыток — запас с трёхкратным множителем,
     * после которого плашка «применить» возвращается и пользователь может
     * повторить сам.
     */
    /** Тип элемента ответа `/api/config`, который содержит разобранный конфиг. */
    private const val DOCUMENT_TYPE = "document"

    private const val POLL_ATTEMPTS = 40

    /**
     * Модели агентов из ответа `GET /api/config`, в виде `id -> "провайдер/модель"`.
     *
     * Ответ — массив документов (`[{type, info}, …]`), и нужный — первый: это
     * разобранный конфиг, остальные элементы массива — сырые файлы и каталоги.
     * Пустой ответ или сломанный JSON дают пустую карту, а не исключение:
     * вызывающему важно «агент этого не подтвердил», а не причина сбоя парсинга.
     */
    fun parseAgentModels(body: String): Map<String, String> =
        runCatching {
            val agents = agentsObject(JSONTokener(body).nextValue())
            buildMap {
                agents?.keys()?.forEach { id ->
                    agentModel(agents.optJSONObject(id))?.let { put(id, it) }
                }
            }
        }.getOrDefault(emptyMap())

    /**
     * Модель одного агента в виде `провайдер/имя`.
     *
     * Формы две, и вторая встречается в ответе не гипотетически: сам конфиг мы
     * пишем строкой (`"model": "провайдер/имя"`), а serve может отдать её хоть
     * строкой, хоть разобранным объектом `{providerID, model}`. Читать только
     * объект нельзя — тогда в строковой форме агент молча выпадает из карты,
     * [routingServed] даёт false и плашка «применить» не снимается никогда.
     *
     * У строки провайдер вытаскиваем из префикса до `/`: в нашем формате он
     * всегда там, а оставить просто имя значило бы сравнивать «big-pickle» с
     * «opencode/big-pickle» и никогда не сойтись.
     */
    private fun agentModel(agent: JSONObject?): String? {
        val model = agent?.opt("model")
        return if (model is JSONObject) {
            val provider = model.optString("providerID").orEmpty()
            val name = model.optString("model").orEmpty()
            if (provider.isNotEmpty() && name.isNotEmpty()) "$provider/$name" else null
        } else {
            model?.toString()?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Достаёт `agents` из ответа `/api/config` независимо от формы обёртки.
     *
     * Формы две, и это не косметика: с массивом вместо `data` разбор возвращал
     * пустую карту, а пустая карта в [routingServed] — это «сервер не подтвердил
     * ни одну модель», то есть проверка рестарта вечно считала бы провалом.
     * Поэтому обёртку не угадываем, а берём из массива элемент с
     * `type == "document"`: остальные записи — это каталоги и сырые файлы, у
     * которых `info` может быть чем угодно. Одной сортировки элементов мало —
     * полагаться на «каталог всегда последний» значит повесить всю проверку
     * применения на порядок, который сервер вправе поменять.
     *
     * Если документов нет вовсе, откатываемся на первый элемент с `info`:
     * лучше разобрать не то, чем вернуть пустую карту и вечную «применить».
     */
    private fun agentsObject(root: Any?): JSONObject? {
        val items =
            when (root) {
                is JSONArray -> root
                is JSONObject -> root.optJSONArray("data")
                else -> null
            }
        val info =
            items?.let { arr ->
                val objects = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
                val document = objects.firstOrNull { it.optString("type") == DOCUMENT_TYPE }
                val withInfo = objects.firstOrNull { it.optJSONObject("info") != null }
                (document ?: withInfo)?.optJSONObject("info")
            }
        return (info ?: root as? JSONObject)?.optJSONObject("agents")
    }

    /**
     * Обслуживает ли сервер именно это расписание.
     *
     * Проверяем только тех агентов, чьи модели пишет сам [planRoutingEntry]:
     * `build`/`plan` — тяжёлые, `explore`/`general` — лёгкие. Служебные роли
     * (`title`, `summary`) в конфиг не пишутся вовсе: они выводятся сервером из
     * `small_model` (проверено на устройстве — `agents.title.model` совпадает с
     * выбранным `small_model`), и сам `small_model` в ответе `/api/config` вообще
     * не echoes. Поэтому требовать от них совпадения нельзя: если бы такая
     * проверка стояла в этом месте, любое расхождение по производной роли
     * заставляло бы плашку «применить» возвращаться навсегда — ровно та ошибка,
     * ради устранения которой проверка и написана.
     *
     * Отсутствующий агент пропускаем (его просто нет в этой сборке), а `build`
     * обязателен — иначе пустой ответ считался бы успехом.
     */
    fun routingServed(
        agents: Map<String, String>,
        routing: ModelRouting,
    ): Boolean {
        val expected =
            mapOf(
                "plan" to routing.heavy,
                "explore" to routing.light,
                "general" to routing.light,
            )
        val buildOk = sameModel(agents["build"], routing.heavy)
        return buildOk && expected.all { (id, model) -> agents[id]?.let { sameModel(it, model) } ?: true }
    }

    /**
     * Совпадают ли две записи о модели.
     *
     * Сравниваем по имени, без префикса провайдера: ответ сервера приходит то
     * разобранным (`{providerID, model}`), то строкой, а строка может быть и с
     * префиксом, и без него. Тогда как `routingServed` со строгим `==` врала бы
     * в обе стороны — и «применено» мимо, и вечная «применить» мимо.
     */
    private fun sameModel(
        served: String?,
        expected: String,
    ): Boolean = served != null && served.substringAfter('/') == expected.substringAfter('/')

    /**
     * Расхождение по служебным ролям, если сервер их всё-таки назвал.
     *
     * Не влияет на решение «применилось ли» — только на текст статуса: у нас
     * нет способа донести `small_model` иначе (сервер его не отдаёт), поэтому
     * расхождение показываем, но не блокируем им карточку.
     */
    fun smallRoleMismatch(
        agents: Map<String, String>,
        routing: ModelRouting,
    ): Boolean {
        val served = listOfNotNull(agents["title"], agents["summary"])
        return served.isNotEmpty() && served.any { !sameModel(it, routing.small) }
    }

    /** Расписание живого сервера или null, если он не ответил. */
    fun servedAgents(port: Int): Map<String, String>? = LocalOpenCodeClient.get(port, CONFIG_PATH)?.let { parseAgentModels(it) }

    /**
     * Ждёт, пока сервер начнёт обслуживать [routing] — сразу возвращает true,
     * если он уже обслуживает, иначе опрашивает до [POLL_ATTEMPTS] раз.
     *
     * Первая попытка без паузы: чаще всего рестарт не нужен вовсе (пользователь
     * выбрал то же, что уже записано), и ждать перед проверкой было бы лишним.
     */
    suspend fun awaitServed(
        port: Int,
        routing: ModelRouting,
    ): Boolean {
        repeat(POLL_ATTEMPTS) {
            if (routingServed(servedAgents(port) ?: emptyMap(), routing)) return true
            delay(POLL_INTERVAL_MS)
        }
        return false
    }
}
