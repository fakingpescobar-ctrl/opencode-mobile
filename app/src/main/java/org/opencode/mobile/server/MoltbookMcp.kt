package org.opencode.mobile.server

/**
 * Плагин Moltbook: имя сервера в конфиге, порт и путь к ассету.
 *
 * Отдельный объект, а не ещё пара констант в [OpencodeRuntime], по той же причине, по
 * которой [MemoryMcp] и [YnisonMcp] живут отдельно: всё, чем локальный MCP-сервер
 * отличается от остальных, должно читаться в одном месте, иначе «какой порт у музыки»
 * и «какой у молтбука» начинают отвечать в разных строках файла.
 *
 * Порт 4201 — рядом с памятью (4199) и Яндекс.Музыкой (4200). Три сервера на трёх
 * соседних портах: это не украшение, а способ не перепутать при отладке, потому что все
 * три поднимаются одинаково и различаются только номером.
 */
internal object MoltbookMcp {
    const val NAME = "moltbook"
    const val PATH = "/mcp"
    const val PORT = 4201
    const val LABEL = "moltbook"
    const val ASSET = "mcp/moltbook.js"
    const val SCRIPT = "moltbook.js"

    /** Приватная папка плагина: журнал, ключ, witness.log, verifier.log, raw/. */
    const val DIR_NAME = "moltbook"

    /** Файл с ключом API. Плагин без него не публикует ничего, но читать журнал может. */
    const val KEY_FILE = "moltkey"

    /** Каталог базы SQLite внутри приватных данных приложения. */
    const val DATABASES_DIR = "databases"

    /**
     * Имя файла журнала. SQLiteOpenHelper кладёт базу в databases/, а не рядом с логами,
     * поэтому плагину передаётся готовый каталог, а он не угадывает путь сам.
     */
    const val LEDGER_NAME = "moltbook.db"

    const val URL = "http://127.0.0.1:$PORT$PATH"

    /**
     * Свой bearer, а не токен памяти: у moltbook.js своя проверка `Authorization`, и общий
     * токен означал бы, что любой из серверов открывается заголовком, предназначенным
     * другому. В конфиге — ссылка `{env:...}`, а не значение, поэтому перезапуск с новым
     * токеном не трогает файл.
     */
    const val AUTH_HEADER = "\"Authorization\": \"Bearer {env:MCP_MOLTBOOK_TOKEN}\""

    /**
     * Запись сервера ровно в том виде, в каком её пишет [OpencodeRuntime.remoteServerBlock]
     * для остальных серверов. Держать одну форму обязаны и запись, и проверка: иначе
     * добавление плагина в конфиг не совпадёт с тем, что потом ищет миграция, и конфиг
     * будет молча переписан при следующем запуске.
     */
    internal fun entry(): String =
        "\"$NAME\": {\n" +
            "      \"type\": \"remote\",\n" +
            "      \"url\": \"$URL\",\n" +
            "      \"headers\": {\n" +
            "        $AUTH_HEADER\n" +
            "      }\n" +
            "    }"

    internal val NAME_KEY = "\"$NAME\""
}

/** Что делать с записью «moltbook» в управляемом opencode.jsonc. */
internal sealed interface MoltbookEntryPlan {
    /** Уже в нужном состоянии — файл не трогаем. */
    data object Keep : MoltbookEntryPlan

    /** Перезаписать конфиг указанным текстом. */
    data class Write(
        val text: String,
    ) : MoltbookEntryPlan

    /** Формат не узнан: оставляем как есть, чтобы не портить чужой конфиг. */
    data object Refuse : MoltbookEntryPlan
}

/**
 * Решает, что делать с записью «moltbook», ничего не записывая.
 *
 * Отдельная чистая функция по той же причине, что и [OpencodeRuntime.planMusicEntry]:
 * это место, где легко тихо испортить работающую установку (потерять регистрацию памяти,
 * включить неаутентифицированный локальный порт), а проверять его нужно юнит-тестом, а не
 * на устройстве. Внутри объекта проверка потребовала бы Context и Robolectric, которого
 * в проекте нет, - то есть не проверялась бы вообще никогда.
 *
 * Запись добавляется и убирается точечно, а не пересборкой всей секции `mcp`. Причина
 * та же, что у music: пересборка сделала бы «актуальную форму» зависимой от наличия
 * ключа молтбука, и лестница миграций перестала бы отличать старую форму от новой -
 * любой существующий конфиг упал бы в ветку «не управляемый» и тихо потерял бы память.
 *
 * @param text текущий конфиг
 * @param present подключён ли плагин (есть ли ключ API и поднялся ли сервер)
 */
internal fun planMoltbookEntry(
    text: String,
    present: Boolean,
): MoltbookEntryPlan {
    if (present == text.contains(MoltbookMcp.NAME_KEY)) return MoltbookEntryPlan.Keep
    val start = text.indexOf(MCP_OBJECT_START)
    val end = if (start >= 0) mcpObjectEnd(text, start) else -1
    val block = if (start >= 0 && end > start) text.substring(start, end) else null
    // Скобки обязательны: без них `?:` относился бы только к ветке else, и при present=true
    // пустой результат доезжал до конкатенации строкой "null" - то есть в конфиг
    // записывалось слово null вместо отказа. Конфиг после этого не поднялся бы,
    // а выглядело бы это как «инструмент не появился», а не как «файл сломан».
    val updated = block?.let { if (present) withMoltbookEntry(it) else withoutMoltbookEntry(it) }
    return when {
        updated == null -> MoltbookEntryPlan.Refuse
        updated == block -> MoltbookEntryPlan.Refuse
        else -> MoltbookEntryPlan.Write(text.substring(0, start) + updated + text.substring(end))
    }
}

/** Начало управляемой секции. Жёсткая строка: чужую секцию mcp мы не правим. */
private const val MCP_OBJECT_START = "\"mcp\": {"

/**
 * Индекс символа сразу после закрывающей скобки объекта `mcp`.
 *
 * Считает глубину, а не ищет первую `}`: внутри каждой записи сервера есть свои скобки
 * (`{ ... headers: { ... } }`), и поиск первой закрытой обрезал бы объект на середине.
 *
 * @return индекс после `}`, либо -1, если скобки не сошлись
 */
private fun mcpObjectEnd(
    text: String,
    start: Int,
): Int {
    var depth = 0
    var i = text.indexOf('{', start)
    while (i < text.length) {
        when (text[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return i + 1
            }
        }
        i++
    }
    return -1
}

/** Дописывает запись молтбука последней в секции mcp. */
private fun withMoltbookEntry(block: String): String? {
    val close = block.lastIndexOf('}')
    val body = if (close > MCP_OBJECT_START.length) block.substring(MCP_OBJECT_START.length, close) else ""
    // Пустой объект mcp мы не пишем никогда: секция без записей - это не валидный
    // конфиг, и «дописать туда одну запись» означало бы чинить чужую ошибку на чужом файле.
    // Отступ перед закрывающей скобкой секции срезаем, иначе запятая встала бы после
    // него: получалось бы `}` на одной строке с запятой, а не после последней записи.
    return if (block.contains(MoltbookMcp.NAME_KEY) || body.isBlank()) {
        null
    } else {
        block.substring(0, close).trimEnd() + ",\n    " + MoltbookMcp.entry() + "\n  }"
    }
}

/**
 * Убирает запись молтбука вместе с одним из разделителей - тем, который ей же и был.
 *
 * Конец записи ищется по глубине скобок, а не по первой `}`: внутри записи есть свои
 * скобки (`headers: { ... }`), и поиск первой закрытой обрезал бы её на середине,
 * оставив в конфиге обрывок - то есть битый jsonc.
 *
 * Разделитель убирается ровно один, и всегда тот, который принадлежит самой записи:
 * запись в середине теряет запятую СПРАВА от себя, последняя - СЛЕВА, первая - тоже
 * справа, потому что слева у неё ничего нет. Если не убрать ни одной, в конфиге
 * остаётся висячая запятая и opencode не поднимет ни память, ни молтбук, - а ошибка
 * будет выглядеть как «инструмент не появился», а не как «файл сломан».
 */
private fun withoutMoltbookEntry(block: String): String? {
    val at = block.indexOf(MoltbookMcp.NAME_KEY)
    val end = if (at >= 0) mcpObjectEnd(block, at) else -1
    val after = if (end >= 0) block.indexOf(',', end) else -1
    // Запись не последняя: уходит вместе со своим правым разделителем. Левый остаётся -
    // он разделяет между собой уже существующие записи, и убрать его значило бы
    // склеить две валидные записи в битый jsonc.
    val to = when {
        after < 0 -> -1
        after + 1 < block.length && block[after + 1] == '\n' -> after + 2
        else -> after + 1
    }
    val before = if (at >= 0) separatorBefore(block, at) else -1
    return when {
        at < 0 || end < 0 -> null
        to > at -> block.substring(0, at) + block.substring(to)
        before >= 0 -> block.substring(0, before) + block.substring(end)
        else -> null
    }
}

/**
 * Индекс запятой перед записью, либо -1, если её там нет (запись первая в секции).
 *
 * Пропускает отступ записи и её перевод строки, но не больше: пропуск двух
 * переводов строк съел бы пустую строку между записями и добавление-удаление перестало
 * бы возвращать файл байт в байт.
 */
private fun separatorBefore(
    block: String,
    at: Int,
): Int {
    var i = at - 1
    while (i >= 0 && (block[i] == ' ' || block[i] == '\t')) i--
    if (i >= 0 && block[i] == '\n') i--
    if (i >= 0 && block[i] == '\r') i--
    return if (i >= 0 && block[i] == ',') i else -1
}
