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

    /** Имя секции в корне конфига; ищется структурно, а не жёсткой подстрокой. */
    internal const val SECTION_KEY = "mcp"
    const val PATH = "/mcp"
    const val PORT = 4201
    const val LABEL = "moltbook"
    const val ASSET = "mcp/moltbook.js"
    const val SCRIPT = "moltbook.js"

    /**
     * Имя тула, который перечитывает ленту и переписывает снимок панели.
     *
     * Живёт здесь, а не в [org.opencode.mobile.social.MoltbookScanClient], потому
     * что это имя из контракта с плагином, как и [PORT], [PATH] и [KEY_FILE]: одна
     * смена строки в двух местах даст «плагин не отвечает» вместо «тула нет».
     */
    const val SCAN_TOOL = "moltbook_scan"

    /** Приватная папка плагина: журнал, ключ, witness.log, verifier.log, raw/. */
    const val DIR_NAME = "moltbook"

    /**
     * Подпапка отчёта внутри рабочей папки. Отчёт больше не пишется в самый корень:
     * вся живая память агента лежит в `MOBILE_AGENT/`, а бродячая копия рядом
     * превращалась в неотличимый второй экземпляр того же файла.
     */
    const val REPORT_DIR_NAME = "MOBILE_AGENT"

    /** Файл с ключом API. Плагин без него не публикует ничего, но читать журнал может. */
    const val KEY_FILE = "moltkey"

    /** Каталог базы SQLite внутри приватных данных приложения. */
    const val DATABASES_DIR = "databases"

    /**
     * Имя файла журнала. SQLiteOpenHelper кладёт базу в databases/, а не рядом с логами,
     * поэтому плагину передаётся готовый каталог, а он не угадывает путь сам.
     */
    const val LEDGER_NAME = "moltbook.db"

    /**
     * Снимок для панели «М».
     *
     * Путь повторяет то, что плагин собирает из `MCP_MOLTBOOK_DIR`, поэтому он
     * объявлен здесь, а не в разных местах двумя строками: панель читает файл,
     * который переписывает плагин, и путь здесь — единственное место, где
     * можно ошибиться тихо, без всякой ошибки.
     */
    const val PANEL_NAME = "panel.json"

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
 * Границы секции и записи ищутся структурно, через общие примитивы [Jsonc]: они
 * пропускают строки и комментарии. Раньше здесь была жёсткая строка `"mcp": {` и счётчик
 * скобок без учёта литералов: `{` или `}` внутри заголовка/комментария сдвигали границу,
 * и запись уезжала в чужую секцию при молчаливом `Write`.
 *
 * @param text текущий конфиг
 * @param present подключён ли плагин (есть ли ключ API и поднялся ли сервер)
 */
internal fun planMoltbookEntry(
    text: String,
    present: Boolean,
): MoltbookEntryPlan {
    val hasEntry = findInLiterals(text, MoltbookMcp.NAME_KEY) >= 0
    // Один return на функцию: detekt ограничивает число выходов (ReturnCount), а
    // ранняя проверка здесь читается не хуже, чем вложенный when.
    return when {
        present == hasEntry -> MoltbookEntryPlan.Keep
        else ->
            memberObjectSpan(text, MoltbookMcp.SECTION_KEY)?.let { (open, close) ->
                val head = text.substring(0, open + 1)
                val body = text.substring(open + 1, close)
                val tail = text.substring(close)
                val updated = if (present) withMoltbookEntry(body) else withoutMoltbookEntry(body)
                when {
                    updated == null || updated == body -> MoltbookEntryPlan.Refuse
                    else -> MoltbookEntryPlan.Write(head + updated + tail)
                }
            } ?: MoltbookEntryPlan.Refuse
    }
}

/**
 * Индекс [needle] в [text] вне строк и комментариев, либо -1.
 *
 * Обычный `indexOf` здесь не годится: `"mcp": {` в комментарии или `"moltbook"` внутри
 * строкового значения выдавали бы себя за секцию/запись, и правка ушла бы не туда.
 */
private fun findInLiterals(
    text: String,
    needle: String,
    from: Int = 0,
): Int {
    var i = from
    var found = -1
    while (i < text.length && found < 0) {
        val c = text[i]
        if (c == '"') {
            // Ключ начинается с кавычки, и [skipString] перепрыгнул бы через саму запись
            // `"moltbook"`. Поэтому сначала проверяем совпадение именно здесь, и только
            // при отсутствии двоеточия (то есть это значение, а не ключ) идём дальше.
            if (text.startsWith(needle, i) && colonFollows(text, i + needle.length)) {
                found = i
            } else {
                i = skipString(text, i + 1)
            }
        } else if (isLineCommentAt(text, i)) {
            i = skipToLineEnd(text, i)
        } else if (isBlockCommentAt(text, i)) {
            i = skipToBlockEnd(text, i + 2)
        } else if (text.startsWith(needle, i)) {
            found = i
        } else {
            i++
        }
    }
    return found
}

/** Дописывает запись молтбука последней в секции mcp. */
private fun withMoltbookEntry(body: String): String? {
    // Пустой объект mcp мы не пишем никогда: секция без записей - это не валидный
    // конфиг, и «дописать туда одну запись» означало бы чинить чужую ошибку на чужом файле.
    if (findInLiterals(body, MoltbookMcp.NAME_KEY) >= 0 || body.isBlank()) return null
    // Запятая ставится сразу после ЗАКРЫВАЮЩЕЙ СКОБКИ ПОСЛЕДНЕЙ ЗАПИСИ, а не после
    // trimEnd() всего тела: в хвосте секции может стоять комментарий, и тогда
    // запятая уехала бы внутрь `// ...`, а две записи остались бы без разделителя —
    // битый jsonc, который выглядит как «инструмент не появился».
    val at = lastEntryEnd(body)
    return if (at < 0) {
        null
    } else {
        body.substring(0, at) + ",\n    " + MoltbookMcp.entry() + body.substring(at)
    }
}

/**
 * Позиция сразу за закрывающей скобкой последней записи секции, либо -1.
 *
 * Сканер считает пары скобок и пропускает строки и комментарии, поэтому `}` внутри
 * `note` или в хвостовом `// комментарии` не выдаётся за конец записи. Именно этот
 * примитив нужен для вставки: `[matchBrace]` отвечает на «где кончается данная
 * запись», а здесь — «где кончается последняя из существующих».
 */
private fun lastEntryEnd(text: String): Int {
    var depth = 0
    var last = -1
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '"' -> i = skipString(text, i + 1)
            isLineCommentAt(text, i) -> i = skipToLineEnd(text, i)
            isBlockCommentAt(text, i) -> i = skipToBlockEnd(text, i + 2)
            c == '{' || c == '[' -> {
                depth++
                i++
            }
            c == '}' || c == ']' -> {
                depth--
                if (depth == 0) last = i + 1
                i++
            }
            else -> i++
        }
    }
    return last
}

/**
 * Убирает запись молтбука вместе с одним из разделителей - тем, который ей же и был.
 *
 * Конец записи ищется парной скобкой через [matchBrace] с пропуском строк и комментариев,
 * а не первой закрывающей: внутри записи есть свои скобки (`headers: { ... }`), и поиск
 * первой закрытой обрезал бы её на середине, оставив в конфиге обрывок - то есть битый
 * jsonc. Разделитель убирается ровно один, и всегда тот, который принадлежит самой записи:
 * запись в середине теряет запятую СПРАВА от себя, последняя - СЛЕВА, первая - тоже
 * справа, потому что слева у неё ничего нет. Если не убрать ни одной, в конфиге
 * остаётся висячая запятая и opencode не поднимет ни память, ни молтбук, - а ошибка
 * будет выглядеть как «инструмент не появился», а не как «файл сломан».
 */
private fun withoutMoltbookEntry(body: String): String? {
    // Один early-return вместо четырёх: detekt ограничивает ReturnCount, а цепочка
    // проверок ниже читается как один вопрос «удалось ли разобрать границы записи».
    val at = findInLiterals(body, MoltbookMcp.NAME_KEY)
    val colon = if (at >= 0) nextSignificant(body, at + MoltbookMcp.NAME_KEY.length) else -1
    // nextSignificant возвращает length, когда до конца только пробелы/комментарии:
    // это НЕ индекс. getOrNull спасает от выхода за границы и снижает сложность
    // функции ниже (detekt: CyclomaticComplexMethod, порог 15).
    val open = if (body.getOrNull(colon) == ':') nextSignificant(body, colon + 1) else -1
    val end = if (body.getOrNull(open) == '{') matchBrace(body, open) else -1
    if (end < 0) return null
    val after = nextCommaOutsideLiterals(body, end + 1)
    // separatorBefore и hasEntryAfter сами возвращают -1/false на отрицательном
    // индексе: лишние проверки здесь поднимали CyclomaticComplexMethod до 19.
    val before = separatorBefore(body, at)
    val followedByEntry = hasEntryAfter(body, after)
    return when {
        // Запись не последняя: уходит правый разделитель и весь отступ её строки.
        // Один отступ остаётся от substring(0, at) — иначе он удвоился бы.
        followedByEntry -> {
            var to = after + 1
            while (to < body.length && isIndentOrNewline(body[to])) to++
            body.substring(0, at) + body.substring(to)
        }
        // Последняя запись с завершающей запятой: убираем и её разделитель слева.
        after >= 0 && before >= 0 -> body.substring(0, before) + body.substring(after + 1)
        // Последняя запись без завершающей запятой.
        before >= 0 -> body.substring(0, before) + body.substring(end + 1)
        else -> null
    }
}

/** Отступ или перевод строки: то, что отделяет запись от разделителя. */
private fun isIndentOrNewline(c: Char): Boolean = c == '\n' || c == ' ' || c == '\t'

/**
 * Есть ли после разделителя в [from] ещё запись секции, а не только хвост.
 *
 * [from] — индекс запятой; -1 означает «разделителя нет», такой ответ сразу
 * отрицательный, сканировать тело с нуля нельзя (там лежит сама удаляемая запись).
 */
private fun hasEntryAfter(
    text: String,
    from: Int,
): Boolean {
    if (from < 0) return false
    var i = from + 1
    var found = false
    while (i < text.length && !found) {
        val c = text[i]
        when {
            c.isWhitespace() -> i++
            isLineCommentAt(text, i) -> i = skipToLineEnd(text, i)
            isBlockCommentAt(text, i) -> i = skipToBlockEnd(text, i + 2)
            c == '}' || c == ']' -> i = text.length
            else -> found = true
        }
    }
    return found
}

/**
 * Первая запятая на глубине секции после [from], вне строк и комментариев, либо -1.
 *
 * Запятые внутри вложенных `headers: { ... }` принадлежат соседним записям, а не разделителю
 * самой записи: взять такую значило бы обрезать соседа на середине.
 */
private fun nextCommaOutsideLiterals(
    text: String,
    from: Int,
): Int {
    var i = from
    var depth = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '"' -> i = skipString(text, i + 1)
            isLineCommentAt(text, i) -> i = skipToLineEnd(text, i)
            isBlockCommentAt(text, i) -> i = skipToBlockEnd(text, i + 2)
            c == '{' || c == '[' -> {
                depth++
                i++
            }
            c == '}' || c == ']' -> {
                depth--
                i++
            }
            c == ',' && depth == 0 -> return i
            else -> i++
        }
    }
    return -1
}

/**
 * Индекс запятой перед записью, либо -1, если её там нет (запись первая в секции).
 *
 * Пропускает отступ записи и её перевод строки, но не больше: пропуск двух
 * переводов строк съел бы пустую строку между записями и добавление-удаление перестало
 * бы возвращать файл байт в байт.
 */
private fun separatorBefore(
    body: String,
    at: Int,
): Int {
    var i = at - 1
    while (i >= 0 && (body[i] == ' ' || body[i] == '\t')) i--
    if (i >= 0 && body[i] == '\n') i--
    if (i >= 0 && body[i] == '\r') i--
    return if (i >= 0 && body[i] == ',') i else -1
}
