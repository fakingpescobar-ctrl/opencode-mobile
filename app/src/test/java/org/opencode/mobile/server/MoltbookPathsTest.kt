package org.opencode.mobile.server

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Перенос состояния Moltbook из приватного каталога наружу.
 *
 * Проверяются только решения ([migrationPlan], [migrationTargets]) и результат
 * копирования ([copyMissing]). Выбор хранилища и создание базы завязаны на
 * `Context` и без Android не проверяются - зато именно план решает, потеряет ли
 * кто-нибудь журнал: план пустой - плагин честно скажет `ledger-missing`, план с
 * перезаписью - потеряет свежее состояние, а база, созданная при неполном
 * переносе, закрывает перенос навсегда.
 */
class MoltbookPathsTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** Имена файлов в плане - то, что копируется, а не откуда. */
    private fun names(pairs: List<Pair<File, File>>): List<String> = pairs.map { it.second.name }

    /** План без базы: большинство тестов интересует только каталог состояния. */
    private fun statePlan(
        oldState: File,
        newState: File,
    ): List<Pair<File, File>> = migrationPlan(oldState, File(temp.root, "нет-базы.db"), newState, File(temp.root, "нет-базы2.db"))

    @Test
    fun `состояние из приватного каталога переносится`() {
        val oldState = temp.newFolder("files-moltbook")
        oldState.resolve(MoltbookMcp.KEY_FILE).writeText("ключ")
        val oldLedger = temp.newFile("databases-moltbook.db")
        // Целевой каталог УЖЕ есть - его создал плагин. Это и есть причина разбирать
        // каталог рекурсивно: проверка «есть ли каталог» молча пропустила бы всё
        // содержимое, включая ключ.
        val newState = temp.newFolder("external-moltbook")
        val newLedger = File(temp.root, "external-databases/moltbook.db")

        val plan = migrationPlan(oldState, oldLedger, newState, newLedger)

        assertTrue("состояние едет поимённо", plan.any { it.first == oldState.resolve(MoltbookMcp.KEY_FILE) })
        assertTrue("база в плане", names(plan).contains("moltbook.db"))
    }

    @Test
    fun `нет целевого каталога - едет он целиком, а не россыпью файлов`() {
        val oldState = temp.newFolder("files-moltbook")
        oldState.resolve(MoltbookMcp.KEY_FILE).writeText("ключ")
        val oldLedger = temp.newFile("databases-moltbook.db")
        val newState = File(temp.root, "external-moltbook")
        val newLedger = File(temp.root, "external-databases/moltbook.db")

        val plan = migrationPlan(oldState, oldLedger, newState, newLedger)

        // Один каталог одной парой: нечего сравнивать поимённо, когда на цели
        // пусто, а копировать дерево файлов вместо дерева каталогов - значит
        // лишние системные вызовы на каждый файл raw/.
        assertEquals(
            "каталог едет целиком, база отдельно",
            listOf(oldState.absolutePath, oldLedger.absolutePath),
            plan.map { it.first.absolutePath },
        )
        assertEquals(
            "и на цели путь не раздувается: каталог ложится прямо в newState",
            listOf(newState.absolutePath, newLedger.absolutePath),
            plan.map { it.second.absolutePath },
        )
    }

    @Test
    fun `база едет вместе с WAL и SHM`() {
        val oldLedger = temp.newFile("moltbook.db")
        File(oldLedger.path + "-wal").writeText("wal")
        File(oldLedger.path + "-shm").writeText("shm")

        val plan =
            migrationPlan(
                File(temp.root, "no-state"),
                oldLedger,
                File(temp.root, "new-moltbook"),
                File(temp.root, "new-db/moltbook.db"),
            )

        // Без -wal база после переноса может не увидеть последние транзакции,
        // то есть молча откатится на пару записей назад.
        assertEquals(
            "wal и shm едут вместе с базой",
            listOf("moltbook.db", "moltbook.db-shm", "moltbook.db-wal"),
            names(plan).sorted(),
        )
    }

    @Test
    fun `уже перенесённое не переносится заново`() {
        val oldState = temp.newFolder("files-moltbook")
        val oldLedger = temp.newFile("moltbook.db")
        val newState = temp.newFolder("external-moltbook")
        val newLedger = temp.newFile("external-db-moltbook.db")
        File(newLedger.path + "-wal").writeText("уже есть")

        val plan = migrationPlan(oldState, oldLedger, newState, newLedger)

        assertTrue("перенос идёт один раз, а не на каждом запуске", plan.isEmpty())
    }

    @Test
    fun `если каталог уже есть, а базы нет - переносится только база`() {
        val oldState = temp.newFolder("files-moltbook")
        val oldLedger = temp.newFile("moltbook.db")
        val newState = temp.newFolder("external-moltbook")

        val plan = migrationPlan(oldState, oldLedger, newState, File(temp.root, "new-db/moltbook.db"))

        assertEquals("только база", listOf("moltbook.db"), names(plan))
    }

    @Test
    fun `пустое место не порождает ничего`() {
        val plan =
            migrationPlan(
                File(temp.root, "нет-каталога"),
                File(temp.root, "нет-базы.db"),
                File(temp.root, "новый-каталог"),
                File(temp.root, "новая-база.db"),
            )

        assertTrue("переносить нечего", plan.isEmpty())
    }

    @Test
    fun `существующий каталог не затирается целиком`() {
        // Каталог на новом месте может быть непустым, и это нормально: плагин уже
        // писал туда. Перезапись снесла бы его содержимое вместе с новым снимком.
        val oldState = temp.newFolder("files-moltbook")
        oldState.resolve("panel.json").writeText("старый")
        val newState = temp.newFolder("external-moltbook")
        newState.resolve("panel.json").writeText("новый")

        val plan = statePlan(oldState, newState)

        assertFalse("существующий каталог не трогаем", plan.any { it.second == newState })
        assertEquals("файл на новом месте остался", "новый", newState.resolve("panel.json").readText())
    }

    @Test
    fun `план не упоминает то, чего нет на диске`() {
        val oldLedger = temp.newFile("moltbook.db")
        val plan =
            migrationPlan(
                File(temp.root, "нет"),
                oldLedger,
                File(temp.root, "новый"),
                File(temp.root, "новый/moltbook.db"),
            )

        assertFalse("несуществующий источник не копируется", plan.any { it.first == File(temp.root, "нет") })
    }

    @Test
    fun `цель переноса не создаётся заранее, иначе перенос не сработает никогда`() {
        // Реальный баг, пойманный на устройстве: migrateFromPrivate брал цели через
        // dir()/databasesDir(), а они делают mkdirs(). План пропускает любую цель,
        // которая уже существует, - значит каталог «существует» всегда и перенос не
        // срабатывал НИ РАЗУ: ключ и сырые ответы молча оставались в том самом
        // filesDir, ради которого перенос и затевался.
        val privateFiles = temp.newFolder("files")
        privateFiles.resolve(MoltbookMcp.DIR_NAME).mkdirs()
        privateFiles.resolve(MoltbookMcp.DIR_NAME).resolve(MoltbookMcp.KEY_FILE).writeText("ключ")
        val privateData = temp.newFolder("data")
        val newBase = temp.newFolder("external-opencode")

        val targets = migrationTargets(privateFiles, privateData, newBase)
        val plan = migrationPlan(targets.oldState, targets.oldLedger, targets.newState, targets.newLedger)

        assertFalse("вычисление цели не создало каталог", targets.newState.exists())
        // Цели нет - значит план честно отдаёт каталог целиком. Проверка поимённо
        // здесь была бы подгонкой: поимённо бывает только когда каталог снаружи
        // уже кто-то создал (плагин делает это для raw/).
        assertTrue("перенос не пустой", plan.isNotEmpty())
        assertTrue(
            "каталог едет целиком, а не россыпью",
            plan.any { it.first == targets.oldState && it.second == targets.newState },
        )
        assertFalse("и план тоже ничего не создал", targets.newState.exists())
    }

    @Test
    fun `пустой каталог на новом месте не блокирует перенос файлов`() {
        // Второй настоящий баг, пойманный на устройстве: каталог снаружи создаётся
        // при первом же обращении к панели, то есть ДО переноса. План, который
        // пропускает «цель уже существует», на этом навсегда и останавливался - ключ
        // и сырые ответы молча лежали дальше в filesDir.
        val oldState = temp.newFolder("files-moltbook")
        oldState.resolve(MoltbookMcp.KEY_FILE).writeText("ключ")
        oldState.resolve("raw").mkdirs()
        oldState.resolve("raw/feed.json").writeText("{}")
        val newState = temp.newFolder("external-moltbook") // существует и ПУСТ

        val plan = statePlan(oldState, newState)

        assertTrue("ключ в плане", names(plan).contains(MoltbookMcp.KEY_FILE))
        assertTrue("сырые ответы в плане", names(plan).contains("raw"))
        assertFalse("сам каталог целиком не тащим", plan.any { it.second == newState })
    }

    @Test
    fun `файл уже перенесённый не тащится второй раз`() {
        val oldState = temp.newFolder("files-moltbook")
        oldState.resolve("panel.json").writeText("старый")
        oldState.resolve("moltkey").writeText("ключ")
        val newState = temp.newFolder("external-moltbook")
        newState.resolve("panel.json").writeText("новый")

        val plan = statePlan(oldState, newState)

        assertFalse("свежий файл не затираем", names(plan).contains("panel.json"))
        assertTrue("а недостающее едет", names(plan).contains("moltkey"))
    }

    @Test
    fun `уже существующий raw на новом месте не отменяет перенос накопленного`() {
        // Тот же механизм на уровень ниже: raw/ появляется снаружи раньше переноса
        // (его создаёт плагин при первом обращении), и проверка верхнего уровня
        // пропустила бы весь каталог вместе с накопленными сырыми ответами.
        val oldState = temp.newFolder("files-moltbook")
        oldState.resolve("raw").mkdirs()
        oldState.resolve("raw/feed.json").writeText("старый ответ")
        oldState.resolve("raw/comments.json").writeText("старые комментарии")
        val newState = temp.newFolder("external-moltbook")
        newState.resolve("raw").mkdirs() // плагин успел создать пустой
        newState.resolve("raw/feed.json").writeText("свежий ответ")

        val plan = statePlan(oldState, newState)

        assertEquals(
            "внутрь каталога едет только то, чего там нет",
            listOf("comments.json"),
            names(plan),
        )
    }

    @Test
    fun `вложенные каталоги raw разбираются вглубь`() {
        val oldState = temp.newFolder("files-moltbook")
        oldState.resolve("raw/2026-10-11").mkdirs()
        oldState.resolve("raw/2026-10-11/feed.json").writeText("{}")
        val newState = temp.newFolder("external-moltbook")
        newState.resolve("raw/2026-10-11").mkdirs()

        val plan = statePlan(oldState, newState)

        assertEquals("файл из вложенного каталога едет", listOf("feed.json"), names(plan))
        assertTrue("и едет именно оттуда", plan.all { it.first.path.contains("raw") })
    }

    @Test
    fun `копирование не затирает то, что появилось между планом и копированием`() {
        // Плагин пишет снимок панели в фоне постоянно, поэтому файл может появиться
        // на новом месте уже после того, как план его увидел.
        val from = temp.newFile("panel.json")
        from.writeText("старое")
        val to = temp.newFile("panel.json-снаружи")
        to.writeText("свежее")

        copyMissing(from, to)

        assertEquals("свежее не затёрто", "свежее", to.readText())
    }

    @Test
    fun `копирование везёт содержимое каталога`() {
        val from = temp.newFolder("raw")
        from.resolve("feed.json").writeText("{}")
        from.resolve("nested").mkdirs()
        from.resolve("nested/comments.json").writeText("[]")
        val to = File(temp.root, "наружу/raw")

        copyMissing(from, to)

        assertEquals("верхний уровень", "{}", to.resolve("feed.json").readText())
        assertEquals("вложенный уровень", "[]", to.resolve("nested/comments.json").readText())
    }

    @Test
    fun `незавершённый перенос виден по complete`() {
        // То, из-за чего незавершённый перенос опасен: база, созданная следом по
        // целевому пути, навсегда закрывает план переноса, и настоящий журнал из
        // filesDir не переедет больше никогда - молча.
        val ok = Migration(listOf("panel.json -> наружу"), emptyList())
        val broken = Migration(listOf("panel.json -> наружу"), listOf("moltbook.db -> наружу/moltbook.db"))

        assertTrue("полный перенос завершён", ok.complete)
        assertFalse("частичный перенос не завершён", broken.complete)
        assertEquals("и провалившийся файл назван", 1, broken.failed.size)
    }

    @Test
    fun `ничего не перенесённое не считается провалом`() {
        val empty = Migration(emptyList(), emptyList())

        assertTrue("пустой перенос завершён", empty.complete)
    }
}
