package org.opencode.mobile.server

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Токен доступа к плагину молтбука, копия для вызова из приложения.
 *
 * Токен живёт в окружении процесса плагина и в конфиге opencode как `{env:...}`.
 * Приложению он тоже нужен: панель сама подсказывает плагину, что пора перечитать
 * ленту, иначе карточка показывает один и тот же снимок до следующего тика агента.
 *
 * Отдельным объектом, а не ещё одной функцией в [MoltbookPaths], по существу:
 * токен - это не путь состояния молтбука и не лежит рядом с ним. Всё остальное
 * ([MoltbookPaths.keyFile], [MoltbookPaths.panelFile]) уехало на внешний том,
 * потому что обязано переживать переустановку APK, а внешний том читает любое
 * приложение с доступом к хранилищу. Токен так переживать не должен и не может:
 * он меняется при каждом старте. Приватный `filesDir` - единственное место,
 * где ему и место.
 *
 * Именно `filesDir` целиком, а не каталог `moltbook` внутри него: этот каталог
 * [MoltbookPaths.migrateFromPrivate] переносит на внешний том поимённо, всем
 * содержимым. Токен в `filesDir/moltbook` уехал бы наружу вместе с ключом -
 * то есть лежал бы там, где его может прочитать любое приложение с доступом к
 * хранилищу, и больше никогда не обновлялся бы: читает-то приложение
 * приватную копию. Поэтому рядом, но снаружи переносимого каталога.
 */
internal object MoltbookToken {
    private const val FILE_NAME = "mcp-token"
    private const val TAG = "MoltbookToken"

    /**
     * Запомнить токен для [read]. Отказ не фатален: без копии панель просто не
     * сможет попросить плагин обновиться, а сам плагин, агент и все его тулы
     * продолжат работать. Молча упасть здесь нельзя - тогда отказ панели будет
     * выглядеть как «плагин не отвечает», а это совсем другая поломка.
     */
    fun remember(
        context: Context,
        token: String,
    ): Boolean {
        val file = file(context)
        return runCatching {
            file.writeText(token)
            dropExternalCopy(context)
            true
        }.getOrElse {
            Log.w(TAG, "токен плагина не сохранить: ${it.message}")
            false
        }
    }

    /** Токен из приватной копии или `null`, если плагин ещё ни разу не запускали. */
    fun read(context: Context): String? =
        runCatching {
            file(context)
                .takeIf { it.isFile }
                ?.readText()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

/**
 * Убрать копию токена с внешнего тома, если она там есть.
 *
 * Сборка, где токен лежал в `filesDir/moltbook/`, успела утечь его на внешний
 * том: перенос каталога копирует всё содержимое поимённо, а ключ рядом
 * переезжать должен. Копия там бесполезна (приложение читает приватную) и
 * вредна (внешний том читает любое приложение с доступом к хранилищу), так
 * что удаляем сами, а не ждём ручной уборки.
 */
    private fun dropExternalCopy(context: Context) {
        if (!MoltbookPaths.external(context)) return
        val stray = File(MoltbookPaths.dir(context), FILE_NAME)
        if (!stray.isFile || !stray.delete()) return
        Log.i(TAG, "токен плагина убран с внешнего тома: ${stray.absolutePath}")
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}
