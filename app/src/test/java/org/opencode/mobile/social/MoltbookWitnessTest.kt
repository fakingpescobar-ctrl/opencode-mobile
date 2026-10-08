package org.opencode.mobile.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.opencode.mobile.social.MoltbookWitness.Entry
import java.io.File

/**
 * Свидетель проверяется на трёх вещах, и все три — про молчание.
 *
 *  1. Хэш обязан меняться от ЛЮБОГО поля. Иначе подмена payload или времени
 *     проходит незаметно, и весь смысл цепочки вместе с ним.
 *  2. Усечение файла не должно читаться как порча. Это главная ловушка журнала
 *     с лимитом строк: либо проверка врёт на нормальном файле (и её перестают
 *     читать), либо усечение запрещают (и файл растёт вечно).
 *  3. Битая строка обязана читаться как «битая», а не как исключение: этот же
 *     парсер работает на файле, который могли править со стороны.
 *
 * Тесты ходят через настоящие файлы (`append`/`verify`/`digestOf`), а не через
 * моки: смысл проверки именно в том, пережил ли усечённый файл настоящий
 * round-trip, и подмена этого в моке стёрла бы ровно тот класс багов, который
 * файл закрывает.
 */
class MoltbookWitnessTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val startedAt = 1_757_000_000_000L

    /** Журнал в подкаталоге — как в жизни: путь `moltbook/witness.log`. */
    private fun logFile(): File = File(folder.root, "moltbook/witness.log")

    private fun mirrorFile(): File = File(folder.root, "external/${MoltbookWitness.MIRROR_RELATIVE}")

    /**
     * Цепочка в памяти — те же правила, что у [MoltbookWitness.append], но без
     * файла. Ссылка берётся из предыдущей записи, а не из таблички хэшей: иначе
     * тест проверял бы не правило цепочки, а собственную константу.
     */
    private fun chainOf(vararg kinds: String): List<Entry> {
        var prevHash = MoltbookWitness.GENESIS_HASH
        return kinds.mapIndexed { index, kind ->
            val seq = index.toLong()
            val at = startedAt + seq
            val payload = "payload-$seq"
            val hash = MoltbookWitness.hashOf(seq, at, kind, payload, prevHash)
            val entry = Entry(
                seq = seq,
                at = at,
                kind = kind,
                payload = payload,
                prevHash = prevHash,
                hash = hash,
            )
            prevHash = hash
            entry
        }
    }

    /** Хэш стабилен и меняется от каждого поля — иначе цепочка никого не защищает. */
    @Test
    fun `хеш стабилен и меняется от любого поля`() {
        val seq = 7L
        val at = startedAt
        val kind = MoltbookWitness.KIND_WAKE
        val payload = "будильник сработал"
        val prevHash = MoltbookWitness.GENESIS_HASH

        val base = MoltbookWitness.hashOf(seq, at, kind, payload, prevHash)
        assertEquals(
            "тот же вход обязан давать тот же хэш",
            base,
            MoltbookWitness.hashOf(seq, at, kind, payload, prevHash),
        )

        assertNotEquals("seq — часть хэша", base, MoltbookWitness.hashOf(seq + 1, at, kind, payload, prevHash))
        assertNotEquals("время — часть хэша", base, MoltbookWitness.hashOf(seq, at + 1, kind, payload, prevHash))
        assertNotEquals(
            "вид записи — часть хэша",
            base,
            MoltbookWitness.hashOf(seq, at, MoltbookWitness.KIND_EFFECT, payload, prevHash),
        )
        assertNotEquals("payload — часть хэша", base, MoltbookWitness.hashOf(seq, at, kind, "подмена", prevHash))
        assertNotEquals(
            "предыдущий хэш — часть хэша",
            base,
            MoltbookWitness.hashOf(seq, at, kind, payload, MoltbookWitness.hashOf(1, 2, "wake", "x", "0")),
        )
        assertEquals("нижний регистр hex, 64 символа", 64, base.length)
        assertTrue("только hex-символы", base.all { it in '0'..'9' || it in 'a'..'f' })
    }

    /** Цепочка из трёх записей, связанная по-настоящему, — чистая. */
    @Test
    fun `цепочка из трёх записей цела`() {
        val entries = chainOf(MoltbookWitness.KIND_WAKE, MoltbookWitness.KIND_EFFECT, MoltbookWitness.KIND_STALE)

        val result = MoltbookWitness.verifyChain(entries)

        assertNull("цепочка обязана быть целой: ${result.reason}", result.brokenAt)
        assertEquals("", result.reason)
        assertEquals(3, result.checked)
    }

    /**
     * Разрыв `prevHash` — это вырезанная строка журнала.
     *
     * Подделываем именно разрыв, а не порчу записи: у вырезанной строки её
     * СОБСТВЕННЫЙ хэш остаётся честным, не сходится только ссылка на предыдущую.
     * Если бы мы поменяли `prevHash` вместе с хэшем, мы бы проверяли совсем
     * другой дефект — тот же, что подмена payload, и второй тест бы упал.
     *
     * Ловим обрыв и называем точный seq: «последние две записи выглядят
     * странно» проверяющему ничего не даёт, а у него нет доступа к нашим логам.
     */
    @Test
    fun `разрыв prevHash ломает цепочку на следующей записи`() {
        val entries = chainOf(MoltbookWitness.KIND_WAKE, MoltbookWitness.KIND_EFFECT, MoltbookWitness.KIND_STALE)
        val middle = entries[1]
        val cutPrevHash = "0".repeat(64)
        val cut =
            middle.copy(
                prevHash = cutPrevHash,
                hash = MoltbookWitness.hashOf(middle.seq, middle.at, middle.kind, middle.payload, cutPrevHash),
            )
        val cutChain = listOf(entries[0], cut, entries[2])

        val result = MoltbookWitness.verifyChain(cutChain)

        assertEquals(1L, result.brokenAt!!)
        assertEquals("цела только первая запись", 1, result.checked)
        assertTrue("причина называет разрыв связи: ${result.reason}", result.reason.contains("prevHash"))
    }

    /**
     * Подмена payload без пересчёта хэша — самая частая подделка, потому что
     * меняет смысл записи, а не её длину. Ловится пересчётом самого хэша.
     */
    @Test
    fun `подмена payload без пересчёта хэша ловится`() {
        val entries = chainOf(MoltbookWitness.KIND_WAKE, MoltbookWitness.KIND_EFFECT)
        val forged = entries.toMutableList()
        forged[1] = forged[1].copy(payload = "тик прошёл успешно")

        val result = MoltbookWitness.verifyChain(forged)

        assertEquals(1L, result.brokenAt!!)
        assertTrue("причина называет неверный хэш: ${result.reason}", result.reason.contains("hash"))
    }

    /**
     * Payload с трубой, переводом строки, обратной косой и кириллицей обязан
     * пережить round-trip. Без экранирования одна такая запись рвёт файл, и
     * цепочка «молча» уезжает дальше — это тот самый тик-фейл, из-за которого
     * журнал и писался.
     */
    @Test
    fun `payload с трубой переводом строки и косой переживает round-trip`() {
        val payload = "отчёт | тик-42\nстрока \\p и \\\\ буквально\nтретья: кириллица, тире — «ёлочки»"
        val pending =
            Entry(
                seq = 5L,
                at = startedAt,
                kind = MoltbookWitness.KIND_EFFECT,
                payload = payload,
                prevHash = MoltbookWitness.GENESIS_HASH,
                hash = "",
            )
        val sealed =
            pending.copy(
                hash = MoltbookWitness.hashOf(pending.seq, pending.at, pending.kind, pending.payload, pending.prevHash),
            )

        val line = MoltbookWitness.format(sealed)
        assertEquals("в строке журнала не может быть перевода строки", 1, line.lines().size)
        assertEquals("труба экранирована, полей ровно шесть", 6, line.split('|').size)
        assertEquals("запись равна себе после разбора", sealed, MoltbookWitness.parseLine(line))
        assertEquals(payload, MoltbookWitness.parseLine(line)!!.payload)
        assertNull(MoltbookWitness.verifyChain(listOf(sealed)).brokenAt)
    }

    /** Пустой журнал — не проблема: тик, который ещё не писал, не оправдывается. */
    @Test
    fun `пустой журнал чист а головы не имеет`() {
        val empty = MoltbookWitness.verifyChain(emptyList())
        assertEquals(0, empty.checked)
        assertNull(empty.brokenAt)
        assertEquals("", empty.reason)

        val file = logFile()
        assertNull("до первого append файла нет, и это не поломка", MoltbookWitness.head(file))
        assertEquals(emptyList<Entry>(), MoltbookWitness.readAll(file))
        assertEquals("", MoltbookWitness.digestOf(file))
        assertNull(MoltbookWitness.verify(file).brokenAt)

        file.parentFile.mkdirs()
        file.writeText("", Charsets.UTF_8)
        assertNull("пустой файл — тоже не порча", MoltbookWitness.verify(file).brokenAt)
        assertNull(MoltbookWitness.head(file))
    }

    /** Битая строка читается как «битая», а не роняет проверку. */
    @Test
    fun `битая строка не запись и не исключение`() {
        assertNull("нет ни одной трубы", MoltbookWitness.parseLine("просто мусор"))
        assertNull("пустая строка", MoltbookWitness.parseLine(""))
        assertNull("полей меньше шести", MoltbookWitness.parseLine("1|2|kind|payload|0"))
        assertNull("полей больше шести", MoltbookWitness.parseLine("1|2|kind|payload|0|hash|7"))
        assertNull("seq не число", MoltbookWitness.parseLine("x|2|kind|payload|0|hash"))
        assertNull("at не число", MoltbookWitness.parseLine("1|y|kind|payload|0|hash"))
        assertNull("пустой хэш", MoltbookWitness.parseLine("1|2|kind|payload|0|"))

        // Посторонняя правка файла видна именно через verify: там битая строка
        // обрывает проверку, потому что её появление означает чужую руку.
        val file = logFile()
        MoltbookWitness.append(file, null, MoltbookWitness.KIND_WAKE, "до", startedAt)
        val good = MoltbookWitness.append(file, null, MoltbookWitness.KIND_EFFECT, "после", startedAt + 1)
        file.writeText(file.readText(Charsets.UTF_8) + "мусор без труб\n", Charsets.UTF_8)

        val result = MoltbookWitness.verify(file)
        assertEquals("обрыв на последней целой записи", good.seq, result.brokenAt!!)
        assertEquals("до битой строки обе записи целы", 2, result.checked)
        assertTrue("причина называет строку: ${result.reason}", result.reason.contains("строка"))
    }

    /**
     * Главный сценарий: 600 записей в файле с лимитом 500 строк.
     *
     * Проверяется ровно то, ради чего придумано окно усечения: усечённый файл
     * остаётся проверяемым целиком, `seq` продолжает расти (счётчик, который
     * можно сбросить, доказательством не является), а обрыв выше первой строки
     * файла не считается порчей.
     */
    @Test
    fun `усечение до лимита не ломает проверку и не сбрасывает счётчик`() {
        val file = logFile()
        val total = 600
        repeat(total) { index ->
            MoltbookWitness.append(file, null, MoltbookWitness.KIND_WAKE, "тик $index", startedAt + index)
        }

        assertEquals(MoltbookWitness.MAX_LINES, file.readLines(Charsets.UTF_8).size)
        val entries = MoltbookWitness.readAll(file)
        assertEquals(MoltbookWitness.MAX_LINES, entries.size)
        assertEquals("счётчик через усечение НЕ обнуляется", (total - 1).toLong(), MoltbookWitness.head(file)!!.seq)

        val result = MoltbookWitness.verify(file)
        assertNull("усечение не должно читаться как порча: ${result.reason}", result.brokenAt)
        assertEquals("окно проверяется целиком", MoltbookWitness.MAX_LINES, result.checked)

        // Граница окна: первая оставшаяся строка ссылается на запись, которой в
        // файле уже нет, — и это единственный допустимый разрыв, он выше головы.
        val headOfWindow = entries.first()
        assertEquals((total - MoltbookWitness.MAX_LINES).toLong(), headOfWindow.seq)
        assertNotEquals("ссылка в пустоту выше окна не проверяется", MoltbookWitness.GENESIS_HASH, headOfWindow.prevHash)
        assertEquals(MoltbookWitness.KIND_WAKE, headOfWindow.kind)
        assertEquals(
            "хвост окна связан с его головой",
            headOfWindow.hash,
            entries[1].prevHash,
        )
    }

    /** Порча ВНЕ усечённого файла не спрятаться: лимит строк не даёт иммунитета. */
    @Test
    fun `правка в усечённом файле всё равно находится`() {
        val file = logFile()
        repeat(MoltbookWitness.MAX_LINES + 20) { index ->
            MoltbookWitness.append(file, null, MoltbookWitness.KIND_EFFECT, "тик $index", startedAt + index)
        }

        val victimLine = 100
        val victimSeq = MoltbookWitness.readAll(file)[victimLine].seq
        val lines = file.readLines(Charsets.UTF_8).toMutableList()
        lines[victimLine] = lines[victimLine].replaceFirst("тик ", "ТИК ")
        file.writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)

        val result = MoltbookWitness.verify(file)
        assertEquals("ломается та запись, которую правили", victimSeq, result.brokenAt!!)
        assertTrue("причина называет хэш: ${result.reason}", result.reason.contains("hash"))
    }

    /** Зеркало обязано совпадать с журналом побайтно, а его отсутствие — не тик-фейл. */
    @Test
    fun `зеркало совпадает с журналом и его отсутствие не мешает`() {
        val file = logFile()
        val mirror = mirrorFile()
        MoltbookWitness.append(file, mirror, MoltbookWitness.KIND_WAKE, "пробуждение", startedAt)
        MoltbookWitness.append(file, mirror, MoltbookWitness.KIND_EFFECT, "ответили 2", startedAt + 60_000)

        assertEquals(file.readText(Charsets.UTF_8), mirror.readText(Charsets.UTF_8))
        assertEquals(MoltbookWitness.digestOf(file), MoltbookWitness.digestOf(mirror))

        // Зеркала нет (нет прав на внешнее хранилище) — тик обязан состояться.
        val third = MoltbookWitness.append(file, null, MoltbookWitness.KIND_EFFECT, "ответили 3", startedAt + 120_000)
        assertEquals(2L, third.seq)
        assertNull(MoltbookWitness.verify(file).brokenAt)
        assertFalse("нет файла зеркала — тихо, а не исключением", MoltbookWitness.mirrorSafely(file, null))
    }

    /** `digestOf` — то, чем телефон и ПК сверяются, не читая журнал построчно. */
    @Test
    fun `digest стабилен при том же содержимом и меняется при правке`() {
        val file = logFile()
        MoltbookWitness.append(file, null, MoltbookWitness.KIND_WAKE, "тик 1", startedAt)
        val first = MoltbookWitness.digestOf(file)
        MoltbookWitness.append(file, null, MoltbookWitness.KIND_EFFECT, "тик 1 закончен", startedAt + 1)

        assertNotEquals("новое содержимое — новый digest", first, MoltbookWitness.digestOf(file))
        assertEquals("тот же файл — тот же digest", MoltbookWitness.digestOf(file), MoltbookWitness.digestOf(file))
        assertEquals(64, MoltbookWitness.digestOf(file).length)

        val copy = File(folder.root, "copy.log")
        copy.writeText(file.readText(Charsets.UTF_8), Charsets.UTF_8)
        assertEquals("одинаковые файлы — одинаковый digest", MoltbookWitness.digestOf(file), MoltbookWitness.digestOf(copy))

        copy.appendText("ещё строка\n", Charsets.UTF_8)
        assertNotEquals("лишняя строка видна в digest", MoltbookWitness.digestOf(file), MoltbookWitness.digestOf(copy))
    }

    /** Пустой вид записи — ошибка вызова, и она обязана быть громкой. */
    @Test(expected = IllegalArgumentException::class)
    fun `пустой вид записи не проходит`() {
        MoltbookWitness.append(logFile(), null, "  ", "тик", startedAt)
    }
}
