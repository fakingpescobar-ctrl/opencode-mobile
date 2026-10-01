package org.opencode.mobile.server

import android.content.Context
import org.opencode.mobile.OpencodeApp
import java.io.File

/**
 * Обрезка раздутого журнала событий opencode: тик раз в час.
 *
 * Вынесено из RuntimeManager отдельным объектом не из эстеты: класс и так у
 *первых в лимите detekt, а логика retention к жизненному циклу сервера
 * отношения не имеет вообще - это фоновая гигиена базы.
 *
 * ЗАЧЕМ
 * -----
 * `event` в opencode - append-only журнал изменений, `part` - итоговое
 * состояние. Журнал втрое больше итога, и разница копится с каждым стримом:
 * один вывод инструмента на 81 КБ при стриминге дал 1837 обновлений и
 * 37.5 МБ, то есть 58% всей базы за одну сессию. База растёт на 38 МБ в
 * сутки. Переносом каталогов это не лечится: задержка sdcardfs против
 * ext4 - 1.45x на коммите, а переезд стоил бы 32 МБ истории сессий.
 *
 * ПОЧЕМУ ИЗ ПРИЛОЖЕНИЯ, А НЕ ИЗ ADB
 * --------------------------------
 * Файл базы на sdcard принадлежит uid приложения с правами 0660, и процесс,
 * запущенный через `run-as`, получает на него EACCES - проверено. Внутри
 * приложения доступ есть, поэтому запуск живёт здесь.
 *
 * ДВА ПРОГОНА, И ЭТО НЕ ОПЕЧАТКА
 * -------------------------------
 * Сначала расчёт с порогом 3 МБ: ничего не удаляет, но в лог падает видно,
 * какая сессия раздулась. Затем обрезка с порогом 8 МБ. Разные пороги
 * намеренно: резать 4.76 МБ смысла нет - это ещё живая переписка, и потеря
 * истории не окупается, а вот 37.5 МБ из одного стрима на 81 КБ - аномалия,
 * и её надо гасить автоматически.
 *
 * ГРАНИЦА ПРИМЕНИМОСТИ
 * -------------------
 * Retention ограничивает ущерб, но не лечит причину: opencode пишет событие
 * на каждый чанк стрима, и троттлинга в библиотеке нет (проверено по
 * исходникам: throttle/debounce/coalesce - ноль вхождений). Настоящий фикс -
 * не отдавать в чат выводы такого размера.
 */
internal object Retention {
    /** Отложенный старт первого тика: дать БД прогреться после старта serve. */
    private const val FIRST_DELAY_MS = 10 * 60 * 1000L

    /** Период тика. Час - база растёт на 38 МБ в сутки, часа достаточно. */
    private const val INTERVAL_MS = 60 * 60 * 1000L

    /** Порог для расчёта: показываем в логе, но не режем. */
    const val DRYRUN_BYTES = 3L * 1024 * 1024

    /** Порог для реальной обрезки: режем только явный разрыв. */
    const val APPLY_BYTES = 8L * 1024 * 1024

    /** Живой тик, если он уже есть. См. [schedule]. */
    @Volatile private var ticker: Thread? = null

    /**
     * Запускает фоновый тик, если он ещё не работает.
     *
     * Идемпотентность здесь не микрооптимизация, а защита от лавины: [schedule]
     * вызывается на каждом успешном старте сервера, а поток переживает рестарт
     * и продолжает тикать. Без проверки десять перезапусков дали бы десять
     * обрезок в час — десять процессов на одной живой базе, где каждый второй
     * упирается в блокировку WAL, а VACUUM с честным намерением «подчистить»
     * становится источником задержек для сервера.
     *
     * Поток-демон: он не должен держать процесс живым. Если он всё же умер,
     * следующий [schedule] создаст новый.
     */
    @Synchronized
    fun schedule(
        context: Context,
        logFile: File,
    ) {
        val alive = ticker
        if (alive != null && alive.isAlive) {
            android.util.Log.i("Retention", "тик уже тикает, второй не создаём")
            return
        }
        val thread =
            Thread(
                {
                    try {
                        Thread.sleep(FIRST_DELAY_MS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    while (true) {
                        // runOnce глотает свои ошибки, но plan() внутри него
                        // бросает наружу всё, что не поймал. Падение тика из-за
                        // одной неудачной подготовки молча отключило бы обрезку
                        // до следующего рестарта сервера, поэтому ловим здесь.
                        // Широкий catch - осознанно: пережить должен любой сбой,
                        // а не только те, что мы догадались перечислить.
                        @Suppress("TooGenericExceptionCaught")
                        try {
                            runOnce(context, logFile, apply = false, thresholdBytes = DRYRUN_BYTES)
                            runOnce(context, logFile, apply = true, thresholdBytes = APPLY_BYTES)
                        } catch (e: Exception) {
                            android.util.Log.e("Retention", "тик не отработал: ${e.message}")
                        }
                        try {
                            Thread.sleep(INTERVAL_MS)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                    }
                },
                "opencode-retention",
            )
        thread.isDaemon = true
        ticker = thread
        thread.start()
    }

    /**
     * Разовый прогон. Синхронный и короткий: на 33 МБ базе VACUUM занимает
     * ~80 мс, так что блокировать тик нечем.
     *
     * @return вывод скрипта (null, если не удалось запустить)
     */
    fun runOnce(
        context: Context,
        logFile: File,
        apply: Boolean,
        thresholdBytes: Long,
    ): String? {
        val plan = plan(context, apply, thresholdBytes) ?: return null
        val mode = if (apply) "APPLY" else "DRY-RUN"
        return runCatching {
            val pb = ProcessBuilder(plan.cmd)
            plan.env.forEach { (k, v) -> pb.environment()[k] = v }
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            // Ждём конца: без waitFor() процесс могут убить вместе с нами
            // посреди VACUUM, и WAL останется в промежуточном состоянии.
            val code = proc.waitFor()
            logFile.parentFile?.mkdirs()
            logFile.appendText("=== retention $mode (${plan.thresholdBytes} B, exit=$code) ===\n$output\n")
            android.util.Log.i("Retention", "$mode exit=$code threshold=${plan.thresholdBytes}")
            output
        }.onFailure {
            android.util.Log.e("Retention", "прогон не удался: ${it.message}")
        }.getOrNull()
    }

    /** Готовая команда и окружение. null, если скрипт или musl-библиотеки недоступны. */
    private class Plan(
        val cmd: List<String>,
        val env: Map<String, String>,
        val thresholdBytes: Long,
    )

    private fun plan(
        context: Context,
        apply: Boolean,
        thresholdBytes: Long,
    ): Plan? {
        val script = OpencodeRuntime.ensureRetentionScript(context)
        val muslDir = runCatching { OpencodeRuntime.ensureMuslLibs(context).absolutePath }.getOrNull()
        if (script == null || muslDir == null) {
            // Без musl-библиотек процесс не стартует (не найдёт libc), а без
            // скрипта нечего запускать. Молча пропускаем тик и пишем причину:
            // потерять обрезку из-за невнятного лога дороже самой обрезки.
            android.util.Log.e(
                "Retention",
                "подготовка не удалась: script=${script != null} musl=${muslDir != null}",
            )
            return null
        }
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val cfg = OpencodeApp.ServerConfig
        val cmd =
            listOf(
                File(nativeDir, "libldmusl.so").absolutePath,
                File(nativeDir, "libbun-musl.so").absolutePath,
                script.absolutePath,
                "--db",
                File(cfg.opencodeData, "opencode/opencode.db").absolutePath,
                "--bytes",
                thresholdBytes.toString(),
            ) + if (apply) listOf("--apply") else emptyList()
        val env =
            mapOf(
                "HOME" to cfg.opencodeHome.absolutePath,
                "TMPDIR" to cfg.opencodeCache.absolutePath,
                "XDG_DATA_HOME" to cfg.opencodeData.absolutePath,
                "XDG_CACHE_HOME" to cfg.opencodeCache.absolutePath,
                "LD_LIBRARY_PATH" to "$muslDir:$nativeDir",
                "NO_COLOR" to "1",
            )
        return Plan(cmd, env, thresholdBytes)
    }
}
