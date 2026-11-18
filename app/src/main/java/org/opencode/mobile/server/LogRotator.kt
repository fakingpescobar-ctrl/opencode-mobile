package org.opencode.mobile.server

import java.io.File

/**
 * Ротация opencode.log по размеру и общий бюджет цепочки.
 *
 * Вынесена из RuntimeManager без изменения поведения: пороги приходят снаружи,
 * чтобы тесты проверяли сдвиг на маленьких файлах, не создавая настоящие 8 МБ.
 * Вызывается перед стартом serve — процесс пишет в открытый fd, поэтому
 * «живая» ротация без перезапуска невозможна.
 */
internal class LogRotator(
    private val maxLogBytes: Long,
    private val maxFiles: Int,
    private val totalBudget: Long,
) {
    /**
     * Сдвиг хвостов от старшего к младшему: .2→.3, .1→.2, .log→.1.
     * Бюджет прунится при КАЖДОМ вызове, не только после сдвига: хвосты могли
     * раздуть извне, а .log ещё не дорос до порога.
     */
    fun rotate(logFile: File) {
        prune(logFile)
        if (!logFile.exists() || logFile.length() < maxLogBytes) return
        val parent = logFile.parentFile ?: return
        for (i in maxFiles - 1 downTo 1) {
            val cur = File(parent, "opencode.log.$i")
            val next = File(parent, "opencode.log.${i + 1}")
            if (!cur.exists()) continue
            // Не затираем next заранее: при неудаче renameTo пробуем delete+ретрай —
            // иначе потеряли бы и данные next, и (при неудаче) содержимое cur.
            if (!cur.renameTo(next)) {
                next.delete()
                if (!cur.renameTo(next)) {
                    android.util.Log.w(TAG, "сдвиг ${cur.name} -> ${next.name} не удался")
                }
            }
        }
        val rotated = File(parent, "opencode.log.1")
        if (logFile.renameTo(rotated)) {
            android.util.Log.i(TAG, "rotated opencode.log -> opencode.log.1")
        } else {
            android.util.Log.w(TAG, "rotate opencode.log не удался (renameTo=false)")
        }
    }

    /**
     * Пока сумма цепочки превышает бюджет, удаляем самые старые файлы (.N с конца).
     * Длину берём ДО удаления и вычитаем только при успехе: неудачный delete
     * (файл занят) не должен ложно «соблюдать» бюджет.
     */
    fun prune(logFile: File) {
        val parent = logFile.parentFile ?: return
        var total = logFile.length()
        // Простой for вместо (1..maxFiles).map{}.filter{}.forEach{}: у последнего
        // detekt ForEachOnRange, и в baseline он был подавлен только для RuntimeManager.kt.
        for (i in 1..maxFiles) {
            val f = File(parent, "opencode.log.$i")
            if (f.exists()) total += f.length()
        }
        var idx = maxFiles
        while (total > totalBudget && idx > 0) {
            val f = File(parent, "opencode.log.$idx")
            if (f.exists()) {
                val len = f.length()
                if (f.delete()) {
                    total -= len
                    android.util.Log.i(TAG, "pruned ${f.name} (total ${total / BYTES_PER_KB / BYTES_PER_KB}MB)")
                } else {
                    android.util.Log.w(TAG, "не удалось удалить ${f.name}")
                }
            }
            idx--
        }
        if (total > totalBudget) {
            android.util.Log.w(TAG, "бюджет логов всё ещё превышен: $total байт")
        }
    }

    private companion object {
        const val TAG = "OpencodeServer"
        const val BYTES_PER_KB = 1024
    }
}
