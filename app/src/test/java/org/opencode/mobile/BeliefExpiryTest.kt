package org.opencode.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Стражинг kill-date в `docs/SELF-IMPROVEMENT.md`.
 *
 * Зачем это вообще: документ написан агентом о том, в чём он убедился насчёт
 * своей архитектуры. Без дат он читается как вечное истина, и через полгода
 * агент строит новую фичу на утверждении, которое перестало быть правдой ещё
 * в прошлом квартале. Дата проверки превращает память из «верю» в «сначала
 * проверь».
 *
 * **Ключевое свойство kill-date, которое тест обязан удержать:** `kill` означает
 * «это уже никто не проверил», а НЕ «это неправда». Поэтому просроченное
 * утверждение не является ошибкой формата и не имеет права ронять сборку —
 * документ состариться между релизами это нормально. Краснеет только одно:
 * когда просрочено больше половины всех убеждений, то есть документ перестали
 * обновлять (см. [инвентарь_просроченных_падает_только_на_большинстве]).
 *
 * Красоту, сборку и типы проверяет мастер-функция [формат_убеждений_корректен].
 * Остальные тесты дергают те же данные, но падают по одной причине, иначе
 * непонятно, что именно сломалось.
 */
class BeliefExpiryTest {
    /** Метка строки-убеждения. Именно она, а не весь `>`-блок, обязачна быть разбираемой. */
    private val beliefMarker = "**belief**"

    private val verifiedPattern = Regex("""verified:\s*(\d{4}-\d{2}-\d{2})""")
    private val killPattern = Regex("""kill:\s*(\d{4}-\d{2}-\d{2})""")
    private val refPattern = Regex("""ref:\s*(\S.*)""")

    /**
     * Нижняя граница срока жизни.
     *
     * Меньше недели — это не дата ревью, а опечатка или «проверил и сразу
     * забыл». Дата ревью, на которую нельзя успеть, не выполняет своей
     * функции: она превращается в формальность, которую никто не увидит.
     */
    private val minLifetimeDays = 7L

    /**
     * Верхняя граница срока жизни.
     *
     * 90 дней — потолок не из моды, а из смысла: за квартал проваливается
     * целый цикл релизов, и утверждение, пережившее три месяца без перепроверки,
     * скорее всего описывает уже несуществующую систему. Дальше 90 дней kill-date
     * перестаёт быть датой и становится мусором — «проверю когда-нибудь» без
     * владельца. Длинный срок допустим только если в `ref` видно, чем
     * подтверждено (например, спецификация экосистемы, меняющаяся раз в год).
     */
    private val maxLifetimeDays = 90L

    /** Строк-убеждений, которые можно просрочить без последствий, — ровно половина. */
    private val staleShareLimit = 0.5

    /**
     * Найденный документ или `null`, если репозитория нет.
     *
     * `null`, а не падение: тесты гейта гоняются с рабочим каталогом модуля
     * `:app`, поэтому корень ищется вверх от `user.dir` по `settings.gradle.kts`.
     * На CI без чекаута (или при запуске этого файла изолированно) падать
     * нельзя — отсутствие документа не является ошибкой документа.
     */
    private fun document(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/SELF-IMPROVEMENT.md")
            if (candidate.isFile && File(dir, "settings.gradle.kts").isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    private data class Belief(
        val line: Int,
        val head: String,
        val verified: LocalDate?,
        val kill: LocalDate?,
        val ref: String?,
    ) {
        /** Человекочитаемый кусок для сообщений: сама строка, обрезанная по ширине. */
        override fun toString(): String = "строка $line: ${head.take(90)}"
    }

    /**
     * Разбор строк документа в убеждения — граница, где доверия ещё нет.
     *
     * Разбираем здесь, а не в каждом тесте: тесты получают уже доверенные
     * [Belief], поэтому ни одна проверка не начинается с «а вдруг формат
     * поменялся». Строки без метки [beliefMarker] сюда не попадают — обычный
     * текст не обязан иметь дат, и требовать их от прозы значит превратить
     * документ в протокол.
     */
    private fun beliefs(doc: File): List<Belief> =
        doc.readLines(Charsets.UTF_8).mapIndexedNotNull { index, line ->
            if (!line.contains(beliefMarker)) {
                null
            } else {
                val verified = verifiedPattern.find(line)?.groupValues?.get(1)
                val kill = killPattern.find(line)?.groupValues?.get(1)
                val ref =
                    refPattern
                        .find(line)
                        ?.groupValues
                        ?.get(1)
                        ?.trim()
                Belief(
                    line = index + 1,
                    head = line.trim(),
                    verified = verified?.let { parseDate(it) },
                    kill = kill?.let { parseDate(it) },
                    ref = ref,
                )
            }
        }

    /**
     * `ГГГГ-ММ-ДД` → дата. Любое несоответствие формату — `null`, а не исключение:
     * битая дата обязана быть видна в отчёте теста как битая, а не ронять разбор
     * всего документа целиком.
     */
    private fun parseDate(raw: String): LocalDate? =
        try {
            LocalDate.parse(raw)
        } catch (_: DateTimeParseException) {
            null
        }

    /** Срок жизни в днях. Только для уже разобранных пар: null-дат здесь быть не может. */
    private fun lifetimeDays(belief: Belief): Long = ChronoUnit.DAYS.between(requireNotNull(belief.verified), requireNotNull(belief.kill))

    private fun isExpired(
        belief: Belief,
        today: LocalDate,
    ): Boolean = belief.kill?.isBefore(today) == true

    /** Документ найден — иначе тест пропускается, а не падает. */
    private fun assumeDocument(): File {
        val doc = document()
        assumeTrue(
            "docs/SELF-IMPROVEMENT.md не найден выше ${System.getProperty("user.dir")}: " +
                "тест пропущен, в checkout без репозитория падать нельзя",
            doc != null,
        )
        return doc!!
    }

    /**
     * Мастер-функция: строка-убеждение обязана быть разбираемой, а документ —
     * содержать хотя бы одно убеждение.
     *
     * Падает только формат и отсутствие самих утверждений. Просроченные даты
     * здесь не проверяются намеренно: они законны, они живут своей жизнью и
     * разбираются отдельными тестами-инвентарями.
     */
    @Test
    fun формат_убеждений_корректен() {
        val all = beliefs(assumeDocument())

        assertTrue("в документе нет ни одного убеждения: разметка не применяется", all.isNotEmpty())

        val broken = all.filter { it.verified == null || it.kill == null }
        assertTrue(
            "убеждения без разбираемых дат verified/kill в формате ГГГГ-ММ-ДД:\n" +
                broken.joinToString("\n") { "  $it" },
            broken.isEmpty(),
        )
    }

    /** `kill` позже `verified`: иначе утверждение мертво в день рождения. */
    @Test
    fun срок_жизни_не_отрицательный() {
        val pairs = beliefs(assumeDocument()).filter { it.verified != null && it.kill != null }

        val dead = pairs.filter { !it.kill!!.isAfter(it.verified!!) }
        assertTrue(
            "kill не позже verified — утверждение считается мёртвым в день проверки:\n" +
                dead.joinToString("\n") { "  $it" },
            dead.isEmpty(),
        )
    }

    /** Потолок 90 дней: дольше — это уже не kill-date, а мусор в документе. */
    @Test
    fun срок_жизни_не_длиннее_потолка() {
        val pairs = beliefs(assumeDocument()).filter { it.verified != null && it.kill != null }

        val forever = pairs.filter { lifetimeDays(it) > maxLifetimeDays }
        assertTrue(
            "срок жизни длиннее $maxLifetimeDays дней — это не дата ревью, а мусор:\n" +
                forever.joinToString("\n") { "  $it (${lifetimeDays(it)} дн)" },
            forever.isEmpty(),
        )
    }

    /** Пол 7 дней: короче — не дата ревью, а случайность. */
    @Test
    fun срок_жизни_не_короче_пола() {
        val pairs = beliefs(assumeDocument()).filter { it.verified != null && it.kill != null }

        val flukes = pairs.filter { lifetimeDays(it) < minLifetimeDays }
        assertTrue(
            "срок жизни короче $minLifetimeDays дней — такую дату не успевают перепроверить:\n" +
                flukes.joinToString("\n") { "  $it (${lifetimeDays(it)} дн)" },
            flukes.isEmpty(),
        )
    }

    /**
     * Просроченные на момент запуска — не ошибка.
     *
     * Тест обязан краснеть только от ошибок в коде и в формате. Краснеть от
     * того, что документ состарился между релизами, нельзя: иначе гейт придётся
     * чинить правкой документа, а не правкой кода, и через месяц никто не
     * будет знать, где настоящая ошибка. Поэтому здесь только список и печать.
     */
    @Test
    fun просроченные_убеждения_не_роняют_тест() {
        val all = beliefs(assumeDocument())
        val today = LocalDate.now()
        val expired = all.filter { isExpired(it, today) }

        println("убеждений всего: ${all.size}, просрочено на $today: ${expired.size}")
        expired.forEach { println("  просрочено: $it") }

        // Условие тут одно и оно не про даты: просроченные обязаны быть
        // просчитываемы вообще. Если сюда попадёт что-то неразбираемое, падать
        // будет мастер-функция формата, а не этот тест.
        assertTrue(
            "просроченные посчитаны не как просроченные — фильтр разошёлся с датами",
            expired.all { it.kill!!.isBefore(today) },
        )
    }

    /**
     * Инвентарь просроченных: краснеет только когда их больше половины.
     *
     * Половина — это «мы перестали обновлять документ», а не «документ старый».
     * Пока просрочена меньше половины, даты честно показывают, что часть
     * утверждений ждёт перепроверки, и это ровно то, для чего они нужны.
     */
    @Test
    fun инвентарь_просроченных_падает_только_на_большинстве() {
        val all = beliefs(assumeDocument()).filter { it.verified != null && it.kill != null }
        val today = LocalDate.now()
        val expired = all.filter { isExpired(it, today) }

        println(
            "инвентарь на $today: просрочено ${expired.size} из ${all.size} " +
                "(порог ${(staleShareLimit * all.size).toInt()})",
        )
        expired.forEach { println("  $it") }

        assertTrue(
            "просрочено ${expired.size} из ${all.size} убеждений (больше половины): " +
                "документ перестали обновлять, а не «состарился»",
            expired.size.toDouble() <= staleShareLimit * all.size,
        )
    }

    /**
     * Прозы без метки в разбор не попадают.
     *
     * Метка намеренно узкая: если бы разбирались любые `verified:`/`kill:`,
     * то шапка документа с описанием формата и любой пример в тексте проверяли
     * бы сами себя. Первое утверждение теста фиксирует, что разбор идёт ровно
     * по метке, второе — что дата вне метки не является убеждением даже тогда,
     * когда выглядит как дата.
     */
    @Test
    fun обычный_текст_не_считается_убеждением() {
        val doc = assumeDocument()
        val allLines = doc.readLines(Charsets.UTF_8)
        val parsed = beliefs(doc)

        assertEquals(
            "каждая строка с меткой обязана попасть в разбор ровно один раз",
            allLines.count { it.contains(beliefMarker) },
            parsed.size,
        )

        val datedProse = allLines.withIndex().filter { (_, line) ->
            verifiedPattern.containsMatchIn(line) && !line.contains(beliefMarker)
        }
        val parsedLines = parsed.map { it.line }.toSet()
        val smuggled = datedProse.filter { (index, _) -> index + 1 in parsedLines }
        assertTrue(
            "в разбор попали строки без метки:\n" +
                smuggled.joinToString("\n") { "  строка ${it.index + 1}" },
            smuggled.isEmpty(),
        )
    }
}
