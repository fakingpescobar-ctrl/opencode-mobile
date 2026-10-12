package org.opencode.mobile.server

import android.content.Context
import android.content.ContextWrapper
import org.opencode.mobile.social.MoltbookLedger
import org.opencode.mobile.social.MoltbookWake
import java.io.File
import java.io.IOException

/**
 * Где физически лежит состояние Moltbook: журнал, ключ, снимок панели, отпечаток
 * будильника.
 *
 * Отдельный объект потому, что эти пути нужны в четырёх разных местах (старт
 * плагина, чтение панели, будильник, ledger), и раньше каждый брал свой путь
 * сам. Цена такого разнобоя была не косметическая: `filesDir` стирается целиком
 * при переустановке APK, поэтому состояние молтбука исчезало на ровном месте,
 * и плагин отвечал `ledger-missing` - то есть обещал пустоту там, где на самом
 * деле был просто не тот каталог.
 *
 * Правило выбора ровно то же, что у памяти и opencode ([OpencodeApp.ServerConfig]):
 * при наличии «Доступа ко всем файлам» состояние лежит снаружи и переживает
 * переустановку, без прав - в приватном `filesDir`, где оно тоже работает, но
 * стирается.
 */
internal object MoltbookPaths {
    /**
     * Каталог состояния: журнал плагина, ключ, `panel.json`, `wake.log`, `raw/`.
     *
     * Наружу кладём рядом с `opencode-home` и `opencode-data`, а не внутрь
     * `opencode-home`: там уже лежит конфиг, который плагин не должен видеть.
     *
     * Каталог НЕ создаётся. Функция отвечает на «где», а создание - это поступок,
     * за который отвечает [ensureDir]: иначе геттер пути писал бы в ФС, и проверка
     * «есть ли ключ» создавала бы каталог, которого до неё не было.
     */
    fun dir(context: Context): File = File(base(context), MoltbookMcp.DIR_NAME)

    /**
     * Создать каталог состояния, если его нет.
     *
     * Отдельная функция и возвращает `false`, потому что `isExternalStorageManager()`
     * возвращает `true` и на неподмонтированном томе: право есть, а места записи
     * нет. Раньше `mkdirs()` проверяли в четырёх местах и нигде не смотрели на
     * результат - то есть отказ выглядел как успех, и плагин получал в переменных
     * окружения путь в никуда.
     */
    fun ensureDir(context: Context): Boolean {
        val dir = dir(context)
        return (dir.isDirectory || dir.mkdirs()) && dir.isDirectory
    }

    /**
     * Каталог базы журнала.
     *
     * Наружу база уходит вместе с состоянием, иначе пережил бы переустановку
     * только журнал, а счётчики и отметки «прочитано» - нет: журнал без базы
     * молчит, база без журнала считает, и получились бы две правды.
     */
    fun databasesDir(context: Context): File =
        if (!external(context)) {
            // Без прав путь остаётся прежним: SQLiteOpenHelper по умолчанию ищет базу в
            // dataDir/databases, и смена пути без нужды ломала бы существующие установки.
            File(context.applicationInfo.dataDir, MoltbookMcp.DATABASES_DIR)
        } else {
            File(base(context), MoltbookMcp.DATABASES_DIR)
        }

    /** Состояние лежит снаружи и переживёт переустановку APK. */
    fun external(context: Context): Boolean = Workspace.usingExternal(context)

    /**
     * Создать каталог базы, если его нет. [ensureDir] и это - единственные места,
     * где создание каталогов вообще допустимо.
     */
    fun ensureDatabasesDir(context: Context): Boolean {
        val dir = databasesDir(context)
        return (dir.isDirectory || dir.mkdirs()) && dir.isDirectory
    }

    /**
     * Общий каталог: тот же, что у памяти и opencode-данных, - иначе «переживает
     * переустановку» значило бы разное для разных файлов одного плагина.
     */
    private fun base(context: Context): File = if (external(context)) File(Workspace.resolve(context), "opencode") else context.filesDir

    /**
     * Контекст, у которого база открывается по нашему пути, а не там, где решил
     * Android.
     *
     * [android.database.sqlite.SQLiteOpenHelper] берёт путь из [Context.getDatabasePath]
     * и открывает базу по нему - это единственное, что нужно переопределить.
     * Раньше здесь был ещё и `openOrCreateDatabase`, но он не вызывается ни в одной
     * из проверенных версий AOSP (`android-9.0.0_r1`, API 28, и текущий master, API 36):
     * `getDatabaseLocked` делает `SQLiteDatabase.openDatabase(mContext.getDatabasePath(mName), params)`.
     * Код, который выглядит как подстраховка, но ничего не делает, опаснее отсутствия
     * подстраховки - по нему потом чинят несущую часть, веря неверному инварианту.
     */
    fun ledgerContext(context: Context): Context =
        if (!external(context)) {
            context
        } else {
            LedgerContext(context, databasesDir(context))
        }

    /**
     * Переносит состояние из приватного каталога в тот, где оно теперь живёт.
     *
     * Нужен один раз - при первом запуске после обновления. Без него каждый, кто
     * обновился, потерял бы накопленный журнал: старый файл остался бы лежать в
     * `filesDir`, а плагин уже смотрел бы в другое место. Копия, а не перемещение:
     * если новый путь по какой-то причине недоступен, старая копия остаётся
     * последним, что ещё можно прочитать.
     *
     * Возвращает не список, а [Migration] с двумя половинами, и разница между ними
     * определяет всё дальнейшее: [Migration.complete] решает, можно ли создавать
     * базу. Список «что переехало» для этого не годился - молчаливый частичный
     * перенос выглядел бы в нём так же, как полный.
     */
    fun migrateFromPrivate(context: Context): Migration {
        // Без прав переносить некуда: каталог и снаружи приватный, то есть тот же самый.
        if (!external(context)) return Migration(emptyList(), emptyList())
        val moved = mutableListOf<String>()
        val failed = mutableListOf<String>()
        if (!ensureDir(context)) {
            failed += dir(context).absolutePath
        } else {
            val targets = migrationTargets(context.filesDir, File(context.applicationInfo.dataDir), base(context))
            for (
            (from, to) in
            migrationPlan(
                oldState = targets.oldState,
                oldLedger = targets.oldLedger,
                newState = targets.newState,
                newLedger = targets.newLedger,
            )
            ) {
                copyMissing(from, to)
                // Проверяем результат, а не возвращаемое значение копирования:
                // copyRecursively молча пропускает уже существующие файлы и при этом
                // рапортует об успехе, так что единственный честный признак - файл на месте.
                if (to.exists()) {
                    moved += "${from.name} -> ${to.absolutePath}"
                } else {
                    failed += "${from.absolutePath} -> ${to.absolutePath}"
                }
            }
        }
        return Migration(moved, failed)
    }
}

/** Один файл состояния по имени: `panel.json`, `wake.log`, `last-wake`, `moltkey`. */
internal fun stateFile(
    context: Context,
    name: String,
): File = File(MoltbookPaths.dir(context), name)

/** Ключ API. Без него плагин не публикует ничего, но читать журнал может. */
internal fun keyFile(context: Context): File = stateFile(context, MoltbookMcp.KEY_FILE)

/** Снимок панели, который переписывает плагин и который читает UI. */
internal fun panelFile(context: Context): File = stateFile(context, MoltbookMcp.PANEL_NAME)

/** Журнал будильника: по нему панель объясняет, ПОЧЕМУ карточка молчит. */
internal fun wakeLog(context: Context): File = stateFile(context, MoltbookWake.FILE_NAME_WAKE_LOG)

/** Отпечаток последнего успешного бужения. */
internal fun wakeStamp(context: Context): File = stateFile(context, MoltbookWake.FILE_NAME_WAKE_STAMP)

/**
 * Итог переноса: что уехало и что осталось.
 *
 * [complete] - единственное, чем вызывающий интересуется по-настоящему: пока
 * перенос не доведён до конца, базу создавать нельзя. Пустая база по целевому пути
 * закрывает двери переносу навсегда - план по построению пропускает всё, что уже
 * существует, - и настоящий журнал из `filesDir` не переедет больше никогда, молча
 * и навсегда. Это самый вероятный способ потерять историю, а не экзотика: копия
 * одного файла может упасть, пока остальные копируются.
 */
internal data class Migration(
    val moved: List<String>,
    val failed: List<String>,
) {
    val complete: Boolean get() = failed.isEmpty()
}

/**
 * Что получилось при создании журнала: три разных исхода, а не два.
 *
 * Раньше это был `MoltbookLedger?`, где `null` значило сразу и «база уже была»,
 * и «создать не вышло». Вызывающий обязан различать: первый - норма, второй -
 * авария, о которой нужно сказать вслух, иначе плагин стартует, честно отвечает
 * `ledger-missing`, а карточка молчит, и диагностировать придётся по симптому
 * в другом месте.
 */
internal sealed interface LedgerReady {
    /** База уже на месте, создавать нечего. */
    data object Exists : LedgerReady

    /** Создана. [ledger] открыт, вызывающий обязан его закрыть. */
    data class Created(
        val ledger: MoltbookLedger,
    ) : LedgerReady

    /** Не вышло. [cause] - причина, её показывают, а не прячут. */
    data class Failed(
        val cause: Throwable,
    ) : LedgerReady
}

/**
 * Создать журнал молтбука, если его ещё нет, и сказать, чем кончилось.
 *
 * Живёт здесь, а не в объекте [MoltbookPaths], потому что это не путь, а поступок:
 * объект отвечает на «где лежит», а этот вопрос — «а кто вообще её заведёт».
 *
 * Зачем это здесь, а не в тикере: схему таблиц создаёт только Kotlin
 * ([MoltbookLedger] как `SQLiteOpenHelper`), а плагин на отсутствующем файле честно
 * отдаёт `ledger-missing`. Создавать базу было поручено тикеру, который давно мёртв -
 * будильник и сторож базу только читают, так что на чистой установке её не создаёт
 * уже никто, и плагин отказывается навсегда.
 */
internal fun openLedgerForPlugin(context: Context): LedgerReady {
    val dir = MoltbookPaths.databasesDir(context)
    val file = File(dir, MoltbookMcp.LEDGER_NAME)
    val outcome =
        when {
            // Каталог недоступен - это про отказ, а не про «нечего создавать»:
            // разница между этими двумя случаями и есть весь смысл LedgerReady.
            !MoltbookPaths.ensureDatabasesDir(context) -> LedgerReady.Failed(IOException("каталог базы недоступен: ${dir.absolutePath}"))
            file.isFile -> LedgerReady.Exists
            else -> {
                val ledger = MoltbookLedger(context)
                // readableDatabase доходит до onCreate, а writableDatabase - ещё и до
                // проверки версии: и то и другое создаёт файл, а первое достаточно и
                // ничего не пишет.
                runCatching { ledger.readableDatabase }.fold(
                    onSuccess = { LedgerReady.Created(ledger) },
                    onFailure = { cause ->
                        runCatching { ledger.close() }
                        LedgerReady.Failed(cause)
                    },
                )
            }
        }
    return outcome
}

/**
 * Четыре пути переноса: откуда каталог состояния, откуда база, куда то и другое.
 *
 * Вынесено отдельно от [MoltbookPaths.migrateFromPrivate] ради одного правила,
 * которое иначе терялось: **цели вычисляются без `mkdirs`**. [migrationPlan]
 * пропускает любую цель, которая уже существует - значит с `mkdirs` каталог
 * «существует» всегда, и перенос не сработал бы ни разу: ключ и сырые ответы
 * молча остались бы в том самом `filesDir`, ради которого перенос и затевался.
 *
 * Поля названы словами, а не `first/second/third/fourth`: план ниже читается
 * по именам, и `first`/`second` там ничего не говорили.
 *
 * Android здесь нет, поэтому проверяется обычным тестом.
 */
internal fun migrationTargets(
    privateFiles: File,
    privateData: File,
    newBase: File,
): QuadrupleFiles {
    val oldDatabases = File(privateData, MoltbookMcp.DATABASES_DIR)
    return QuadrupleFiles(
        oldState = File(privateFiles, MoltbookMcp.DIR_NAME),
        oldLedger = File(oldDatabases, MoltbookMcp.LEDGER_NAME),
        newState = File(newBase, MoltbookMcp.DIR_NAME),
        newLedger = File(File(newBase, MoltbookMcp.DATABASES_DIR), MoltbookMcp.LEDGER_NAME),
    )
}

/** Четыре файла переноса: [migrationTargets] не должен тащить за собой data-класс ради читаемости. */
internal data class QuadrupleFiles(
    val oldState: File,
    val oldLedger: File,
    val newState: File,
    val newLedger: File,
)

/**
 * Что именно переносить: пары «откуда → куда», которые ещё не существуют.
 *
 * Вынесено отдельно от [MoltbookPaths.migrateFromPrivate], потому что там всё
 * завязано на `Context`, а проверять надо сами решения: не перезаписывать
 * существующее, везти `-wal` и `-shm` вместе с базой, не тащить то, чего нет.
 * Здесь нет Android вовсе, поэтому проверяется обычным тестом.
 *
 * WAL и SHM едут вместе с базой намеренно: база без них может не увидеть
 * последние транзакции, то есть молча откатиться на пару записей назад.
 *
 * Каталоги разбираются ПОИМЁННО И РЕКУРСИВНО. Только верхний уровень не хватило:
 * каталог `raw/` на новом месте появляется раньше переноса (его создаёт плагин
 * при первом же обращении), и проверка «существует ли `raw/`» пропускала бы его
 * целиком - вместе со всем накопленным внутри. Ровно эта дыра и была в первой
 * версии, и она молча не переносила сырые ответы никогда.
 */
internal fun migrationPlan(
    oldState: File,
    oldLedger: File,
    newState: File,
    newLedger: File,
): List<Pair<File, File>> {
    // mutableListOf, а не результат pendingCopies: список тут ещё дополняется базой,
    // и `+=` на неизменяемом val - это попытка переприсвоить его, а не добавить.
    val pairs = pendingCopies(oldState, newState).toMutableList()
    for (suffix in listOf("", "-wal", "-shm")) {
        val source = File(oldLedger.path + suffix)
        val target = File(newLedger.path + suffix)
        if (source.exists() && !target.exists()) pairs += source to target
    }
    return pairs
}

/**
 * Разбор одного каталога на пары «чего нет на новом месте».
 *
 * [to] - уже полный путь, куда кладём САМ [from]: не «куда класть его содержимое» и не
 * «куда класть его родителя». Раньше здесь добавлялось `from.name`, и каталог
 * целиком уезжал в каталог внутри каталога - то есть путь на устройстве отличался от
 * того, о котором думает вызывающий.
 *
 * Рекурсивный намеренно: `raw/` наполняется ответами API, то есть подкаталогами и
 * файлами на несколько уровней вглубь, и проверка верхнего уровня пропустила бы всё
 * содержимое, если каталог снаружи уже есть.
 *
 * Каталог, а не файл, тоже попадает в результат - если на новом месте его нет.
 * Если он есть, разбор уходит внутрь, и переносится ровно то, чего там не хватает.
 */
private fun pendingCopies(
    from: File,
    to: File,
): List<Pair<File, File>> {
    val pairs = mutableListOf<Pair<File, File>>()
    when {
        // Чего нет - того не переносим: план про то, что есть, а не про то, что могло бы быть.
        !from.exists() -> Unit
        !from.isDirectory -> if (!to.exists()) pairs += from to to
        !to.exists() -> pairs += from to to
        else -> {
            // Каталог снаружи уже есть (плагин создаёт raw/ сам) - идём внутрь и
            // берём ровно то, чего там не хватает.
            val children = from.listFiles()?.sortedBy { it.name }.orEmpty()
            for (child in children) pairs += pendingCopies(child, File(to, child.name))
        }
    }
    return pairs
}

/**
 * Копирует [from] в [to] без перезаписи: [to] на момент плана не существует,
 * а `overwrite = false` — вторая страховка на случай, если файл появился между
 * планированием и копированием (плагин в фоне пишет снимок панели постоянно).
 *
 * Возвращать признак успеха здесь незачем: он был бы ложью на каталогах, где
 * `copyRecursively` молча пропускает уже существующие файлы и при этом рапортует
 * об успехе. Единственный честный признак - файл на месте, и проверяет его
 * вызывающий ([MoltbookPaths.migrateFromPrivate]).
 */
internal fun copyMissing(
    from: File,
    to: File,
) {
    if (!from.exists() || to.exists()) return
    to.parentFile?.mkdirs()
    try {
        if (from.isDirectory) {
            from.copyRecursively(to, overwrite = false)
        } else {
            from.copyTo(to, overwrite = false)
        }
    } catch (_: IOException) {
        // Причина не пишется: вызывающий всё равно смотрит на результат по факту
        // (файл на месте или нет) и сам положит путь в failed. Сообщение здесь было бы
        // вторым источником правды о том, что удалось, а расходиться они будут ровно
        // на тех провалах, где расходиться нельзя.
    }
}

/**
 * Контекст, у которого все базы открываются в одном каталоге.
 *
 * Переопределён только [getDatabasePath] - единственный метод, который
 * SQLiteOpenHelper действительно использует для выбора пути (см. KDoc
 * [MoltbookPaths.ledgerContext]). `openOrCreateDatabase` здесь не нужен и был бы
 * мёртвым кодом, который завтра кто-нибудь будет чинить, веря, что он работает.
 */
private class LedgerContext(
    base: Context,
    private val dir: File,
) : ContextWrapper(base) {
    override fun getDatabasePath(name: String): File = File(dir, name)
}
