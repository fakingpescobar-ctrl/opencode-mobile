package org.opencode.mobile.server

import java.io.File

/**
 * Правила, которым обязан удовлетворять путь рабочей папки.
 *
 * Отдельный объект без Android намеренно: «какой путь годится» — это чистая
 * функция от строки и файловой системы, и её надо закреплять тестом, а не
 * проверкой на телефоне. `Context` остаётся в [Workspace], где он нужен
 * (настройки, внешние каталоги).
 *
 * Проверка двухступенчатая, и это не дублирование:
 *  - [syntaxProblem] — форма строки, без диска. Годится для подсказки в поле
 *    ввода на каждое нажатие;
 *  - [prepare] — то же плюс диск: каталог создаётся и в него реально пишется.
 *    «Каталог есть» не значит «можно писать»: на внешнем хранилище бывает
 *    ровно наоборот — папка видна, а запись отбита EACCES.
 */
object WorkspacePath {
    /** Предел длины пути в Linux: длиннее ядро всё равно не примет. */
    private const val MAX_PATH = 4096

    /** Признак ссылки вместо пути: `content://…` из системного пикера папок. */
    private const val URI_MARK = "://"

    /** Имя пробного файла: точка — чтобы не мусорить в чужом каталоге. */
    private const val PROBE_NAME = ".opencode-workspace-probe"

    /**
     * Итог проверки: либо годный каталог, либо причина отказа.
     *
     * Одно поле из двух всегда null — так вызывающий не может «применить»
     * путь, у которого есть причина отказа, забыв её проверить.
     */
    data class Prepared(
        val dir: File?,
        val problem: String?,
    ) {
        val ok: Boolean get() = dir != null
    }

    /**
     * Проблема в форме пути — или null, если строка выглядит как путь.
     *
     * Ссылку (`content://…`) отбиваем отдельной строкой: системный диалог
     * «выбрать папку» отдаёт именно её, и без объяснения человек решил бы,
     * что приложение не приняло правильную папку.
     */
    fun syntaxProblem(raw: String): String? {
        val path = raw.trim()
        return when {
            path.isEmpty() -> "путь пустой"
            path.contains(URI_MARK) -> "нужен путь вида /storage/…, а не ссылка"
            path.length > MAX_PATH -> "путь длиннее $MAX_PATH символов"
            !path.startsWith("/") -> "путь должен начинаться с /"
            path == "/" -> "корень файловой системы рабочей папкой быть не может"
            path.split('/').any { it == ".." } -> "в пути есть «..» — выбери папку без переходов вверх"
            else -> null
        }
    }

    /**
     * Проверка без записи в каталог: форма, каталог (создаётся при нужде) и
     * право писать по флагам файловой системы.
     *
     * Этим пользуется резолвер при каждом старте serve: пробный файл там был бы
     * лишним следом в чужом проекте (в git появился бы untracked-файл, пусть и
     * на мгновение).
     */
    fun check(raw: String): Prepared {
        val syntax = syntaxProblem(raw)
        if (syntax != null) return Prepared(null, syntax)
        return onDisk(File(raw.trim()), writeProbe = false)
    }

    /** Полная проверка: то же плюс пробная запись. Нужна при применении настройки. */
    fun prepare(raw: String): Prepared {
        val syntax = syntaxProblem(raw)
        if (syntax != null) return Prepared(null, syntax)
        return onDisk(File(raw.trim()), writeProbe = true)
    }

    private fun onDisk(
        dir: File,
        writeProbe: Boolean,
    ): Prepared =
        when {
            dir.exists() && !dir.isDirectory -> Prepared(null, "по этому пути лежит файл")
            !dir.isDirectory && !dir.mkdirs() -> Prepared(null, "каталог не создаётся: нет прав или путь занят")
            writeProbe && !writable(dir) -> Prepared(null, "в каталог нельзя писать")
            !writeProbe && !dir.canWrite() -> Prepared(null, "в каталог нельзя писать")
            else -> Prepared(dir, null)
        }

    private fun writable(dir: File): Boolean =
        runCatching {
            val probe = File(dir, PROBE_NAME)
            probe.writeText("ok")
            probe.delete()
            !probe.exists()
        }.getOrDefault(false)
}
