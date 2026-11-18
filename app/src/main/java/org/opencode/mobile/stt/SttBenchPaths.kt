package org.opencode.mobile.stt

import java.io.File

/**
 * Откуда отладочному входу STT разрешено брать файл.
 *
 * [SttBenchReceiver] в debug-сборке принимает путь из broadcast. Раньше
 * проверялась только сборка — и любое приложение на телефоне могло попросить
 * приложение прочитать произвольный файл (результат уходил в logcat, то есть
 * на debug-сборке с adb читался снаружи). Теперь проверяются ещё и корни:
 * свои каталоги приложения и `/data/local/tmp`, куда `tools/stt-accuracy.ps1`
 * кладёт клипы (`chmod 755` на каталог, `644` на файлы — приложению читаемо).
 *
 * Сравнение идёт по каноническому пути, поэтому `..` и относительные пути не
 * выходят за корень. Сравнение по границе каталога: `/data/local/tmp2` не
 * считается частью `/data/local/tmp`.
 */
internal object SttBenchPaths {
    /** Каталог, куда adb-бенч кладёт клипы (`tools/stt-accuracy.ps1:20`). */
    const val ADB_BENCH_ROOT: String = "/data/local/tmp"

    /** Лежит ли [path] внутри одного из [roots] (или совпадает с корнем). */
    fun isAllowed(
        path: String,
        roots: List<String>,
    ): Boolean {
        val target = canonical(path) ?: return false
        return roots.any { root ->
            val base = canonical(root) ?: return@any false
            target == base || target.startsWith("$base/")
        }
    }

    private fun canonical(path: String): String? {
        if (path.isBlank()) return null
        return runCatching { File(path).canonicalPath }.getOrNull()
    }
}
