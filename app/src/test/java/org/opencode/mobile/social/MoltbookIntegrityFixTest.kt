package org.opencode.mobile.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.opencode.mobile.social.MoltbookWitness.Entry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Тесты на целостность журнала и на честность классификации ошибок.
 *
 * Закрывает четыре дефекта, найденных ревью 08.10.2026:
 *
 *  1. `append` читал голову и писал строку без блокировки, при двух писателях
 *     (пулы `MoltbookAlarmReceiver` и `MoltbookWatchdogReceiver`) это давало две
 *     строки с одинаковыми `seq`/`prevHash`, и цепочка становилась `Broken`
 *     навсегда - неотличимо от реальной порчи.
 *  2. Боевой путь читал журнал через `readAll`, который отбрасывает нечитаемое
 *     через `mapNotNull`, поэтому удаление последних строк (в том числе
 *     доказательства `effect`) не обнаруживалось.
 *  3. Троттлинг отчёта смотрел только на записи `stale`, тогда как вердикт
 *     `Broken` пишет `tamper`: отчёт уходил в чат каждые 30 минут и вытеснял
 *     записи тика из окна в 500 строк.
 *  4. Любой не-2xx считался израсходованием кода проверки, и одна транзиентная
 *     ошибка (429/503) на ровном месте сжигала комментарий.
 */
class MoltbookIntegrityFixTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val startedAt = 1_757_000_000_000L

    private fun logFile(): File = File(folder.root, "moltbook/witness.log")

    /**
     * Два писателя - ровно та конфигурация, что в бою: тик пишет `wake`/`effect`
     * из своего пула, watchdog - `stale`/`tamper` из своего.
     *
     * Запуск синхронизирован стартовым залпом: без блокировки обе нити
     * гарантированно читали одну и ту же голову.
     */
    @Test
    fun `две нити пишущих не рвут цепочку`() {
        val log = logFile()
        val threads = 2
        val perThread = 60
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)

        repeat(threads) { worker ->
            pool.submit {
                start.await()
                repeat(perThread) { index ->
                    MoltbookWitness.append(
                        logFile = log,
                        mirrorFile = null,
                        kind = if (worker == 0) MoltbookWitness.KIND_WAKE else MoltbookWitness.KIND_STALE,
                        payload = "worker=$worker n=$index",
                        now = startedAt + index,
                    )
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue("нити не завершились за 30 с", pool.awaitTermination(30, TimeUnit.SECONDS))

        val entries = MoltbookWitness.readAll(log)
        assertEquals("ни одна строка не потеряна и не задвоена", threads * perThread, entries.size)
        // Ключевое: seq строго возрастает на 1, prevHash всегда предыдущий hash.
        entries.forEachIndexed { index, entry ->
            assertEquals("разрыв seq на позиции $index", index.toLong(), entry.seq)
            val expectedPrev =
                if (index == 0) {
                    MoltbookWitness.GENESIS_HASH
                } else {
                    entries[index - 1].hash
                }
            assertEquals("разрыв prevHash на позиции $index", expectedPrev, entry.prevHash)
        }
        val chain = MoltbookWitness.verifyChain(entries)
        assertNull("гонка записи не должна выглядеть как порча: ${chain.reason}", chain.brokenAt)
        assertNull("боевой путь тоже чист", MoltbookWitness.verify(log).brokenAt)
    }

    /**
     * Удаление хвоста журнала - самая дешёвая порча, и именно её боевой путь не
     * видел: `readAll` выкидывал битые строки, а не оборванный файл. Хвост с
     * `effect` и есть доказательство, что тик сделал работу, поэтому его потеря
     * обязана быть видна.
     */
    @Test
    fun `удалённый хвост журнала обнаруживается`() {
        val log = logFile()
        MoltbookWitness.append(log, null, MoltbookWitness.KIND_WAKE, "проснулся", startedAt)
        MoltbookWitness.append(log, null, MoltbookWitness.KIND_EFFECT, "ответил 12 символов", startedAt + 1)
        MoltbookWitness.append(log, null, MoltbookWitness.KIND_WAKE, "проснулся снова", startedAt + 2)
        assertNull(MoltbookWitness.verify(log).brokenAt)

        // Оборванная последняя строка - то, что даёт убитый процесс.
        log.writeText(log.readText() + "seq=3|at=${startedAt + 3}|kind=effect|")
        val broken = MoltbookWitness.verify(log)
        assertNotNull("оборванная строка обязана ломать цепочку", broken.brokenAt)
        assertTrue("в причине должен быть номер строки: ${broken.reason}", broken.reason.contains("4"))
    }

    /**
     * Битая середина: `readAll` её отбрасывал, и оставшиеся строки выглядели
     * связными. Боевой путь обязан называть дефект.
     */
    @Test
    fun `битая строка в середине обнаруживается`() {
        val log = logFile()
        MoltbookWitness.append(log, null, MoltbookWitness.KIND_WAKE, "раз", startedAt)
        MoltbookWitness.append(log, null, MoltbookWitness.KIND_EFFECT, "два", startedAt + 1)

        val lines = log.readLines().toMutableList()
        lines[1] = "мусор|не|формат|"
        log.writeText(lines.joinToString("\n", postfix = "\n"))

        val broken = MoltbookWitness.verify(log)
        assertNotNull("битая строка обязана ломать цепочку", broken.brokenAt)
    }

    /**
     * Порванная цепочка пишет `tamper`, а троттлинг искал только `stale` -
     * поэтому повторный отчёт проходил каждый цикл (48 раз в сутки при
     * получасовом тике watchdog'а) и вытеснял записи тика.
     */
    @Test
    fun `повторный отчёт о порче троттлится по своему виду записи`() {
        val now = startedAt
        val recentTamper = listOf(Entry(seq = 0, at = now - 10 * 60 * 1000L, kind = MoltbookWitness.KIND_TAMPER, payload = "цепь порвана", prevHash = MoltbookWitness.GENESIS_HASH, hash = "x"))
        val window = setOf(MoltbookWitness.KIND_TAMPER)

        assertFalse(
            "через 10 минут повторный отчёт о порче не должен проходить",
            MoltbookWatchdog.shouldReportStale(recentTamper, now, kinds = window),
        )
        val oldTamper = listOf(Entry(seq = 0, at = now - 61 * 60 * 1000L, kind = MoltbookWitness.KIND_TAMPER, payload = "цепь порвана", prevHash = MoltbookWitness.GENESIS_HASH, hash = "x"))
        assertTrue(
            "старая запись порчи окна не держит",
            MoltbookWatchdog.shouldReportStale(oldTamper, now, kinds = window),
        )
        assertTrue(
            "у тишины своя запись - отчёт о порче она не подавляет",
            MoltbookWatchdog.shouldReportStale(recentTamper, now, kinds = setOf(MoltbookWitness.KIND_STALE)),
        )
        assertFalse(
            "дефолтный троттлинг обязан держать и порчу, иначе вызывающий без kinds повторит исходный баг",
            MoltbookWatchdog.shouldReportStale(recentTamper, now),
        )
    }

    /**
     * Ключ проверки расходуется не всякой ошибкой: 429 и 5xx - это «попробуй
     * позже», код жив. Перманентные 4xx - «код мёртв», повтор бессмыслен.
     */
    @Test
    fun `транзиентные коды не считаются израсходованным кодом проверки`() {
        assertTrue(MoltbookHttpException(429, "slow down").isTransient)
        assertTrue(MoltbookHttpException(500, "boom").isTransient)
        assertTrue(MoltbookHttpException(502, "bad gateway").isTransient)
        assertTrue(MoltbookHttpException(503, "unavailable").isTransient)
        assertFalse(MoltbookHttpException(400, "bad request").isTransient)
        assertFalse(MoltbookHttpException(401, "unauthorized").isTransient)
        assertFalse(MoltbookHttpException(409, "already answered").isTransient)
        assertFalse(MoltbookHttpException(422, "unprocessable").isTransient)
    }

    /**
     * Усечение журнала с новым GENESIS неотличимо от свежей установки — если
     * нет второго свидетеля. Зеркало им и является (meridiansignal, 08.10.2026).
     *
     * Три случая, и каждый обязан читаться однозначно: зеркало впереди = порча;
     * зеркало позади = нормальная гонка, тревоги быть не должно; одинаковые
     * головы = тишина.
     */
    @Test
    fun `усечение внутреннего журнала ловится сверкой с зеркалом`() {
        val log = folder.newFile("witness.log")
        val mirror = folder.newFile("mirror.log")
        val now = 1_700_000_000_000L
        repeat(5) { i ->
            MoltbookWitness.append(log, null, MoltbookWitness.KIND_WAKE, "wake $i", now + i)
        }
        MoltbookWitness.mirrorSafely(log, mirror)

        // тишина: головы совпадают
        assertEquals("", MoltbookWitness.verifyMirror(log, mirror))

        // кто-то вырезал хвост и дописал новый GENESIS
        val truncated = log.readLines(Charsets.UTF_8).take(2).joinToString("\n", postfix = "\n")
        log.writeText(truncated, Charsets.UTF_8)
        MoltbookWitness.append(log, null, MoltbookWitness.KIND_WAKE, "новый genesis", now + 100)

        val defect = MoltbookWitness.verifyMirror(log, mirror)
        assertTrue("усечение должно ловиться сверкой голов, а не оставаться тишиной: $defect", defect.isNotEmpty())
        assertTrue("в сообщении должно быть названо расхождение seq: $defect", defect.contains("зеркало"))
        // локальная цепочка при этом «цела» — ради этого примера всё и затевалось
        assertNull(MoltbookWitness.verify(log).brokenAt)
    }

    /** Зеркало отстаёт — это гонка записи, а не порча. Тревога здесь была бы шумом. */
    @Test
    fun `отставшее зеркало не считается порчей`() {
        val log = folder.newFile("witness2.log")
        val mirror = folder.newFile("mirror2.log")
        val now = 1_700_000_000_000L
        repeat(3) { i ->
            MoltbookWitness.append(log, mirror, MoltbookWitness.KIND_WAKE, "wake $i", now + i)
        }
        // последняя запись в журнал ушла, а зеркало обновиться не успело
        MoltbookWitness.append(log, null, MoltbookWitness.KIND_WAKE, "ещё", now + 10)
        assertEquals("", MoltbookWitness.verifyMirror(log, mirror))
    }
}