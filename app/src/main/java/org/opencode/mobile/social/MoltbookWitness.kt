package org.opencode.mobile.social

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Свидетель тика: append-only журнал с хэш-цепочкой, который проверяется ИЗВНЕ.
 *
 * Зачем он нужен — дословно с чужого ревью (TheNovelist + ricksanchez_c137,
 * 08.10.2026), и это единственная причина, по которой файл существует:
 *
 *     «Не давай процессу, который хочет выглядеть успешным, проверять себя сам:
 *      host должен ставить wake receipt ДО запуска и side-effect hash ПОСЛЕ —
 *      что-то, чего мы не писали. Если лог и чек разойдутся — виноваты будем мы.»
 *
 * Отсюда четыре требования, которые нельзя выполнить «по привычке»:
 *
 *  1. `wake` ставится ДО работы тика, `effect` — ПОСЛЕ. Пара `wake` без `effect`
 *     означает «тик начался и не закончился», и никакой отчёт об успехе этого не
 *     отменяет: свидетель не знает, чем кончился тик, он знает только, что между
 *     двумя хэшами прошло записанное в поле времени.
 *  2. Хэш записи считается из её полей, `prevHash` — из хэша предыдущей. Строку,
 *     вырезанную из середины, видно сразу: у следующей записи `prevHash` ведёт в
 *     пустоту. Ровно это и делает внешнюю проверку возможной.
 *  3. Никаких `synchronized` и мьютексов. Файл сам сериализует записи через
 *     `append` (запись одной строки атомарна на уровне ОС), а лок вокруг файловой
 *     операции — это лок вокруг всего тика: если файл окажется на медленной памяти,
 *     ожидание блокировки уедет на минуты вместе с моделью.
 *  4. Журнал лежит НЕ в базе и НЕ в logcat: и то и другое живёт внутри
 *     приложения, то есть внутри того же процесса, который сам себя проверяет.
 *     Ценность журнала ровно в том, что читает его кто-то другой — с ПК через
 *     [MIRROR_RELATIVE].
 *
 * Все [Entry]-поля приходят аргументами, а не берутся из `System.currentTimeMillis()`
 * и не пишутся в поле класса: тогда [hashOf], [format], [parseLine] и
 * [verifyChain] — чистые функции, и их можно проверять без файлов и без Android.
 */
internal object MoltbookWitness {
    /**
     * Защита пары «прочитать голову → записать строку».
     *
     * Писателей два по построению, и живут они в разных потоках: `wake`/`effect`/
     * `tamper` пишет пул `MoltbookAlarmReceiver.executor`, а `stale`/`tamper` от
     * watchdog - пул `MoltbookWatchdogReceiver.executor`. Без блокировки две
     * вставки успевали попасть в окно между чтением головы и первой записью,
     * и обе получали один `seq` и один `prevHash`. `verifyChain` после такого
     * возвращает `Broken` навсегда - то есть гонка неотличима от реальной
     * порчи и не лечится ничем, кроме переустановки.
     */
    private val appendLock = Any()

    /** Receipt ДО работы тика: будильник сработал, тик пошёл. Ставится первой. */
    const val KIND_WAKE: String = "wake"

    /** Side-effect hash ПОСЛЕ: тик закончился, здесь его результат. */
    const val KIND_EFFECT: String = "effect"

    /** Watchdog увидел молчание: ни `wake`, ни `effect` за обещанное время. */
    const val KIND_STALE: String = "stale"

    /** Лог разошёлся с диском: цепочка или зеркало не сошлись. */
    const val KIND_TAMPER: String = "tamper"

    /**
     * Одна запись журнала. Ровно столько же полей, сколько труб в строке файла.
     *
     * @property seq счётчик записей. Растёт монотонно и ЧЕРЕЗ УСЕЧЕНИЕ НЕ
     *   ОБНУЛЯЕТСЯ: обнулили бы — потеряли бы порядок во времени, а счётчик,
     *   который можно сбросить, доказательством не является.
     * @property at абсолютное время мс — аргументом, а не из `System`, иначе
     *   проверка цепочки зависела бы от машины, на которой её гоняют.
     * @property payload полезная нагрузка; единственное поле произвольного вида,
     *   поэтому единственное, которое приходится экранировать.
     * @property prevHash хэш предыдущей записи; у первой — [GENESIS_HASH].
     * @property hash хэш самой записи; вместе с [prevHash] образует цепочку.
     */
    data class Entry(
        val seq: Long,
        val at: Long,
        val kind: String,
        val payload: String,
        val prevHash: String,
        val hash: String,
    )

    /**
     * Зеркало наружу: то, что мы не переписываем.
     *
     * Путь относительный — от корня внешнего хранилища, и это важно: абсолютный
     * путь меняется от прошивки к прошивке, а относительный переживает и
     * переустановку приложения (внутренний `filesDir` при reinstall сносится
     * вместе с кэшем, и весь журнал пропадает ровно тогда, когда он нужнее всего —
     * после сбоя). Относительный путь доходит до ПК через `adb pull`, то есть
     * проверяющий получает байты, которые писались нами, а не выдумывает их.
     */
    const val MIRROR_RELATIVE: String = "OpencodeTerminal/opencode/opencode-data/moltbook/witness.log"

    /**
     * Дописать запись в журнал, при необходимости усечь и отдать наружу зеркало.
     *
     * Порядок именно такой: сначала строка в основной лог, ПОТОМ усечение, и уже
     * после этого зеркало. Обратный порядок дал бы зеркалу неусечённый файл, и
     * внешний `digestOf` начал бы расходиться с журналом после каждого тика,
     * который перевалил за лимит, — то есть тревога срабатывала бы в норме.
     *
     * @param mirrorFile куда копировать; `null` — внешнего хранилища нет
     *   (нет «Доступа ко всем файлам») и зеркалить некуда, это не ошибка.
     * @param now абсолютное время мс, передаётся наружу, а не берётся внутри.
     * @return записанная запись с уже проставленным [Entry.hash].
     */
    fun append(
        logFile: File,
        mirrorFile: File?,
        kind: String,
        payload: String,
        now: Long,
    ): Entry = synchronized(appendLock) { appendLocked(logFile, mirrorFile, kind, payload, now) }

    private fun appendLocked(
        logFile: File,
        mirrorFile: File?,
        kind: String,
        payload: String,
        now: Long,
    ): Entry {
        // Пустой kind нельзя пропустить: такая строка читается как «неизвестно»,
        // и проверяющий с ПК не отличит её от чужой записи. Это ошибка вызова,
        // а не условие внешнего мира, поэтому она обязана быть громкой.
        require(kind.isNotBlank()) { "вид записи обязателен: пустой kind нечитаем как доказательство" }

        logFile.parentFile?.mkdirs()
        // Голова цепочки = последняя запись. Если её нет — начинаем с genesis.
        val tip = head(logFile)
        val pending =
            Entry(
                seq = tip?.seq?.plus(1) ?: 0L,
                at = now,
                kind = kind,
                payload = payload,
                prevHash = tip?.hash ?: GENESIS_HASH,
                hash = "",
            )
        val stamped =
            pending.copy(
                hash = hashOf(pending.seq, pending.at, pending.kind, pending.payload, pending.prevHash),
            )

        // FileOutputStream(append) + немедленный flush, а не writeText: тот
        // перезаписывает файл целиком, и убийство процесса в середине этой
        // операции уничтожило бы весь предыдущий журнал. Хвост записи — и есть
        // доказательство, поэтому он должен пережить смерть процесса.
        //
        // fd.sync() здесь НЕ зовётся сознательно: он гарантирует устойчивость к
        // отключению питания, а не к убийству процесса, и платит за это десятки
        // миллисекунд fsync на каждой записи. От OOM-kill и снятия с wake-lock
        // запись в page cache переживает и без него.
        FileOutputStream(logFile, true).use { stream ->
            stream.write((format(stamped) + NEWLINE).toByteArray(Charsets.UTF_8))
            stream.flush()
        }

        shrinkIfNeeded(logFile)
        // Зеркало — best effort и последним: его провал не имеет права ронять тик
        // (см. [mirrorSafely]), а вот тик без основной записи не существует вовсе.
        mirrorSafely(logFile, mirrorFile)
        return stamped
    }

    /**
     * Прочитать журнал целиком. Битые строки молча пропускаются.
     *
     * Пропуск — здесь, а не в [verify], и это не уступка: читателю нужен хвост
     * (последний [Entry]) даже когда где-то в середине осталась огрызок записи.
     * Проверяющему [verify] те же строки не прощаются: там любая нечитаемая
     * строка обрывает проверку, потому что её появление означает постороннюю
     * правку файла.
     */
    fun readAll(logFile: File): List<Entry> {
        if (!logFile.isFile) return emptyList()
        return logFile.readLines(Charsets.UTF_8).mapNotNull { parseLine(it) }
    }

    /**
     * Голова цепочки — последняя запись файла (не первая: в журнале «голова» —
     * это конец, как в блокчейне, а не начало).
     *
     * Огрызок на хвосте (убили посреди записи) сюда не попадает: [readAll]
     * пропускает нечитаемое. Это означает, что следующая запись продолжит цепочку
     * от последней ЦЕЛОЙ, и оборванная строка навсегда останется дефектом внутри.
     * Чинить её нельзя — вырезать огрызок значит научить процесс замазывать
     * собственные недописанные записи, а это ровно тот грех, ради которого
     * журнал и написан.
     */
    fun head(logFile: File): Entry? = readAll(logFile).lastOrNull()

    /**
     * Проверить файл целиком: и что каждая строка сама по себе честна, и что
     * строки связаны.
     *
     * Разница с [verifyChain] в том, что здесь ловится ещё и нечитаемая строка:
     * в файле она означает постороннюю правку, в голом списке — просто мусор.
     *
     * Отсутствующий файл — не порча (до первого [append] журнала просто
     * не существует, и ругаться на это нечем), но и не доказательство: вернётся
     * «проверено 0 записей», и это обязано читаться как «проверять нечего».
     * Отличить удалённый файл от ещё не созданного нельзя — принимаем эту цену
     * молча, потому что ловить удаление должен [KIND_TAMPER] на стороне ПК.
     */
    fun verify(logFile: File): VerifyResult {
        if (!logFile.isFile) return VerifyResult(0, null, "")

        var checked = 0
        var previous: Entry? = null
        val lines = logFile.readLines(Charsets.UTF_8)
        for (index in lines.indices) {
            val entry = parseLine(lines[index])
            if (entry == null) {
                // Номера строки нет в записи журнала, поэтому указываем seq, на
                // котором цепочка последней была цела, — это последняя точка,
                // после которой читать можно.
                return VerifyResult(checked, previous?.seq ?: 0L, "строка ${index + 1} не читается как запись журнала")
            }
            val defect = defectIn(entry, previous)
            if (defect != null) {
                return VerifyResult(checked, entry.seq, defect)
            }
            checked++
            previous = entry
        }
        return VerifyResult(checked, null, "")
    }

    /**
     * sha256 всего файла целиком — то, чем телефон и ПК сверяются, не читая
     * журнал построчно. Пустая строка означает «файла нет».
     *
     * Файл читается потоком, а не `readBytes()`: журнал ограничен [MAX_LINES],
     * но ограничение — это обещание, а не запрет, и падать по OutOfMemory на
     * чужом же файле здесь неуместно.
     */
    fun digestOf(logFile: File): String {
        if (!logFile.isFile) return ""
        val digest = newDigest()
        logFile.inputStream().use { stream ->
            val buffer = ByteArray(DIGEST_CHUNK)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hexOf(digest.digest())
    }

    /**
     * Итог проверки.
     *
     * @property checked сколько записей проверено ДО обрыва; при обрыве это
     *   ровно то, до чего цепочке можно верить, — а не число строк в файле.
     * @property brokenAt `seq` первой записи с неверным [Entry.hash] или разрывом
     *   [Entry.prevHash]; `null` — цепочка цела.
     * @property reason человекочитаемая причина; пустая строка означает «цело».
     */
    data class VerifyResult(
        val checked: Int,
        val brokenAt: Long?,
        val reason: String,
    )

    /**
     * Хэш записи: sha256 от `seq|at|kind|payload|prevHash`, hex в нижнем регистре.
     *
     * Хэш берётся от СЫРОГО payload, а не от экранированного, и это не
     * недосмотр: порядок полей фиксирован, `seq` и `at` — числа, `kind` взят из
     * закрытого словаря, и ни одно из них не содержит `|`. Значит перенести
     * разделитель из payload в соседнее поле невозможно, и две разные записи
     * дать одну строку preimage не могут. Хэш от экранированного payload был бы
     * тоже корректен, но требовал бы пересобирать экранирование при проверке —
     * то есть держать две реализации формата вместо одной.
     */
    fun hashOf(
        seq: Long,
        at: Long,
        kind: String,
        payload: String,
        prevHash: String,
    ): String = hexOf(newDigest().digest("$seq|$at|$kind|$payload|$prevHash".toByteArray(Charsets.UTF_8)))

    /**
     * Разобрать строку журнала. `null` — строка не запись: пустая, не из шести
     * полей, с нечисловым `seq`/`at` или с пустым хэшем.
     *
     * Падать здесь нельзя: этот же разбор используется при чтении файла, который
     * могла править чужая сторона. Исключение из парсера превратило бы проверку
     * подделки в проверку «упало» — то есть в молчание.
     */
    fun parseLine(line: String): Entry? {
        if (line.isEmpty()) return null
        val parts = line.split('|')
        // Ровно шесть полей, а не «шесть или больше»: payload экранирован, поэтому
        // лишняя труба означает либо другой формат, либо подделку, и обе версии
        // обязаны выглядеть одинаково — как нечитаемая строка.
        if (parts.size != FIELDS) return null
        val seq = parts[0].toLongOrNull() ?: return null
        val at = parts[1].toLongOrNull() ?: return null
        if (parts[4].isEmpty() || parts[5].isEmpty()) return null
        return Entry(
            seq = seq,
            at = at,
            kind = parts[2],
            payload = unescape(parts[3]),
            prevHash = parts[4],
            hash = parts[5],
        )
    }

    /**
     * Собрать строку журнала из записи. Обратна [parseLine] по построению:
     * единственное место в проекте, где определяется формат на диске.
     */
    fun format(e: Entry): String = "${e.seq}|${e.at}|${e.kind}|${escape(e.payload)}|${e.prevHash}|${e.hash}"

    /**
     * Проверить цепочку в памяти — та же проверка, что [verify], но без файла.
     *
     * Обрыв выше головы (усечённый журнал, у которого нет предшествующих строк)
     * порчей НЕ считается: проверка начинается с первой ПРИСУТСТВУЮЩЕЙ записи, а
     * её собственная целостность проверяется всегда.
     *
     * Пустой список — чистый результат: тик, который ещё ни разу не писал, не
     * обязан оправдываться за пустой журнал.
     */
    fun verifyChain(entries: List<Entry>): VerifyResult {
        var checked = 0
        var previous: Entry? = null
        for (entry in entries) {
            val defect = defectIn(entry, previous)
            if (defect != null) {
                return VerifyResult(checked, entry.seq, defect)
            }
            checked++
            previous = entry
        }
        return VerifyResult(checked, null, "")
    }

    /**
     * [GENESIS_HASH] — хэш, которым ссылается первая запись журнала.
     *
     * Не `""` и не ноль байт: пустая строка сливается с «поле потерялось», а
     * настоящая строка в одно поле легко спутать с обрезанной записью.
     */
    const val GENESIS_HASH: String = "0"

    /**
     * Сколько строк максимум живёт в журнале. Усечение НЕ обнуляет счётчик.
     *
     * Схема усечения и её цена — здесь целиком, потому что это единственное
     * место в журнале, где цепочка намеренно неполна:
     *
     *  - при превышении лимита файл переписывается целиком и в нём остаются
     *    ПОСЛЕДНИЕ [MAX_LINES] строк — как есть, с их настоящими [Entry.seq] и
     *    [Entry.hash], без единой пересборки;
     *  - первая строка оставшегося окна — это «genesis» журнала в практическом
     *    смысле: её собственный хэш проверяется всегда, а её `prevHash` ведёт на
     *    запись, которой в файле уже нет, и поэтому НЕ проверяется. Разрыв выше
     *    головы — не порча, а граница окна;
     *  - внутри окна цепочка обязана быть непрерывной, и [verify] это требует:
     *    вторая строка файла обязана ссылаться на первую. Поэтому в файле не
     *    может быть разрыва ПОСЛЕ первой строки — и усечение, оставляющее
     *    «якорь + отброшенный хвост», ломает проверку с первой же попытки;
     *  - отвергнутый вариант: якорь навсегда (первая запись в жизни журнала) плюс
     *    последние [MAX_LINES] − 1 строк. Выглядит бережливее — якорь доказывает,
     *    что журнал начинался с genesis, — но между якорем и хвостом остаётся
     *    разрыв, у первой строки хвоста `prevHash` указывает на запись, которой
     *    в файле нет, и [verify] честно рапортует об обрыве на втором хвосте.
     *    То есть такой якорь не просто бесполезен, он ломает проверку ровно на
     *    том файле, ради которого проверка и писалась;
     *  - отвергнутый вариант: обнулять `seq` при усечении. Тогда файл выглядел бы
     *    свежим и аккуратным, но счётчик, который можно сбросить, доказательством
     *    не является, а внешний проверяющий увидел бы «свежий журнал» там, где на
     *    самом деле стёрли пол-истории.
     */
    const val MAX_LINES: Int = 500

    /** Разделитель строк — всегда LF, как в .editorconfig и как в `adb pull`. */
    private const val NEWLINE: String = "\n"

    /** Труб в строке записи: `seq|at|kind|payload|prevHash|hash`. */
    private const val FIELDS: Int = 6

    /** Размер буфера для [digestOf] — 8 КБ, хватает для любой строки журнала. */
    private const val DIGEST_CHUNK: Int = 8192

    /** Запас под экранирование: payload длиннее исходной строки максимум вчетверо. */
    private const val ESCAPE_SLACK: Int = 16

    /** Старший полубайт в байте — сдвиг на четыре бита. */
    private const val NIBBLE_SHIFT: Int = 4

    /** Маска младшего полубайта. */
    private const val NIBBLE_MASK: Int = 0x0F

    /** Маска байта до unsigned: без неё отрицательный байт даст отрицательный индекс. */
    private const val BYTE_MASK: Int = 0xFF

    /** Таблица hex. `String.format("%02x")` в цикле медленный — вызовов мало, но и таблица не стоит ничего. */
    private val HEX: CharArray = "0123456789abcdef".toCharArray()

    /**
     * Единственное место, где решается, цела ли цепочка: [verify] и
     * [verifyChain] отличаются только тем, откуда берут записи.
     *
     * @param previous предыдущая запись или `null` для начала проверки.
     * @return причина обрыва или `null`, если всё сошлось.
     */
    private fun defectIn(
        entry: Entry,
        previous: Entry?,
    ): String? {
        // Сначала целостность самой записи, потом связь с предыдущей: подмена
        // payload даёт и неверный hash, и сломанный prevHash у следующей записи,
        // и сообщать об этом дважды не нужно.
        if (hashOf(entry.seq, entry.at, entry.kind, entry.payload, entry.prevHash) != entry.hash) {
            return "seq=${entry.seq}: hash не пересчитывается из полей — запись правили после записи"
        }
        if (previous != null && entry.prevHash != previous.hash) {
            return "seq=${entry.seq}: prevHash не ссылается на предыдущую запись"
        }
        return null
    }

    /**
     * Зеркало наружу — ровно копия журнала на внешнем хранилище.
     *
     * Копия, а не дозапись одной строки: зеркало обязано совпадать с журналом
     * побайтно, иначе `digestOf` на ПК сравнивал бы разные файлы и кричал бы
     * «tamper» после каждого усечения. Ложная тревога опаснее отсутствия
     * зеркала — её перестают читать.
     *
     * Отдельной функцией, а не строчкой внутри [append], потому что её провал
     * обязан быть проглочен: нет «Доступа ко всем файлам», каталога ещё нет,
     * хранилище занято — тик от этого рассыпаться не должен. Исключение
     * поднимается наружу, и тик уходит в ретрай вместо того, чтобы выполнить
     * работу.
     *
     * @return `true` — зеркало записано и совпадает с журналом; `false` — записи
     *   не было (нет файла зеркала, нет прав, нет места). Основной лог при
     *   `false` цел.
     */
    fun mirrorSafely(
        logFile: File,
        mirrorFile: File?,
    ): Boolean {
        if (mirrorFile == null) return false
        return try {
            mirrorFile.parentFile?.mkdirs()
            mirrorFile.writeText(logFile.readText(Charsets.UTF_8), Charsets.UTF_8)
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            // Ровно этот случай на Android без MANAGE_EXTERNAL_STORAGE, и он
            // штатный: зеркала нет, журнал есть.
            false
        }
    }

    /**
     * Усечь файл до последних [MAX_LINES] строк.
     *
     * Строки переносятся как есть, ничего не пересобирается: иначе «усечение»
     * станет местом, где можно тихо переписать историю, а это ровно то, ради
     * чего журнал существует.
     *
     * Пишем во временный файл и переименовываем, а не перезаписываем на месте.
     * Это единственный момент, когда журнал перестаёт быть append-only, и если
     * процесс умрёт посреди перезаписи, `writeText` оставит файл обрезанным
     * НА СЧИТАЕМОМ — то есть потеряется именно хвост, который и был
     * доказательством. Переименование в пределах одного каталога атомарно.
     *
     * Отказ переименования оставляет журнал как есть: цепочка цела, файл просто
     * больше лимита, а следующий тик повторит усечение. Бросать исключение
     * здесь нельзя — тик важнее размера файла.
     */
    private fun shrinkIfNeeded(logFile: File) {
        val lines = logFile.readLines(Charsets.UTF_8)
        if (lines.size <= MAX_LINES) return

        val kept = lines.subList(lines.size - MAX_LINES, lines.size)
        // Рядом с журналом, а не в каталоге по умолчанию: переименование должно
        // остаться в пределах одного раздела, иначе оно не атомарно.
        val scratch = File("${logFile.absolutePath}.shrinking")
        scratch.writeText(kept.joinToString(NEWLINE, postfix = NEWLINE), Charsets.UTF_8)
        scratch.renameTo(logFile)
    }

    /** sha256 одной строки. Общий digest для строки и для файла — реализация одна. */
    private fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    /** Байты в hex в нижнем регистре, через таблицу символов. */
    private fun hexOf(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and BYTE_MASK
            out[i * 2] = HEX[value ushr NIBBLE_SHIFT]
            out[i * 2 + 1] = HEX[value and NIBBLE_MASK]
        }
        return String(out)
    }

    /**
     * Экранирование payload для одной строки.
     *
     * Три символа, и каждый закрывает свой класс поломок: `|` разрезал бы строку
     * на четыре, перевод строки — разорвал бы запись пополам и тихо сдвинул
     * всю цепочку дальше (это и есть «молчаливый тик-фейл»), `\` — экранировал
     * бы сам себя. `|` кодируется как `\p`, а не `\u007C`, чтобы вывод читался
     * глазами: разбирать журнал будут в том числе руками.
     *
     * Порядок обхода важен: `\` экранируется первым, иначе экранированная им
     * `\p` второй раз превратится в настоящую трубу.
     */
    private fun escape(payload: String): String {
        val out = StringBuilder(payload.length + ESCAPE_SLACK)
        for (ch in payload) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '|' -> out.append("\\p")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * Обратное к [escape] одним проходом.
     *
     * Неизвестная последовательность (`\x`) не считается поломкой и остаётся
     * как есть: строка, записанная нашей старой версией без этого правила,
     * обязана читаться, а не молчать. Обратное неверно — `\\` обязан съедать
     * оба символа, иначе payload, где буквально встречается `\p`, распадётся на
     * трубу.
     */
    private fun unescape(raw: String): String {
        if (!raw.contains('\\')) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch != '\\' || i == raw.lastIndex) {
                out.append(ch)
                i++
                continue
            }
            when (val next = raw[i + 1]) {
                '\\' -> out.append('\\')
                'p' -> out.append('|')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                else -> out.append('\\').append(next)
            }
            i += 2
        }
        return out.toString()
    }
}
