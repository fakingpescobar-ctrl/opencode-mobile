package org.opencode.mobile.social

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.opencode.mobile.server.LocalOpenCodeClient
import java.io.File
import java.util.concurrent.Executors

/**
 * Точка входа тика: приёмник мгновенно уходит, вся работа — в отдельном потоке.
 *
 * Два требования Android, которые тут сталкиваются, и оба проверены на живом
 * прогоне, а не взяты из документации:
 *
 *  1. goAsync() нельзя держать на весь тик — Android даёт broadcast 10–60 секунд, а
 *     тик ждёт генерацию модели минутами. Реально это кончилось Broadcast Timeout →
 *     ANR → убийство процесса. Поэтому goAsync() здесь не используется вообще:
 *     broadcast завершается сразу, а тик продолжает жить в своём потоке.
 *  2. Никаких проверок с сетью в onReceive — он идёт на главном потоке, где
 *     HttpURLConnection даёт NetworkOnMainThreadException, который молчаливый
 *     catch превращал в «opencode не отвечает» (а он отвечал).
 *
 * Процесс переживает работу, потому что его держит foreground service serve.
 */
class MoltbookAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val app = context.applicationContext
        executor.execute {
            try {
                runTick(app)
            } catch (e: Throwable) {
                // Ловим Throwable, а не Exception: поток executor'а умирает молча на
                // любом Error, и тик выглядит как «будильник сработал и ничего не сделал».
                Log.w(TAG, "тик упал: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    private fun runTick(context: Context) {
        val stamp = stampFile(context)
        val blocker = readinessBlocker(context, stamp)
        if (blocker != null) {
            Log.i(TAG, blocker)
            MoltbookScheduler.schedule(context, MoltbookScheduler.RETRY_MS)
            return
        }
        try {
            val report = MoltbookTicker(context).runOnce()
            Log.i(
                TAG,
                "тик: постов=${report.postsChecked} ответов=${report.repliesPosted} " +
                    "verification=${report.verificationsSolved} karma=${report.karma} " +
                    "ждёт=${report.deferredReplies} следующий визит через ${report.nextVisitMinutes} мин",
            )
            stamp.writeText(System.currentTimeMillis().toString())
            // Паузу выбирает агент по фактическому состоянию ленты, а не расписание:
            // жёсткие 2 часа означали либо простой, либо очередь отложенных ответов.
            MoltbookScheduler.schedule(context, report.nextVisitMinutes * 60_000L)
        } catch (e: Exception) {
            Log.w(TAG, "тик упал: ${e.message}")
            MoltbookScheduler.schedule(context, MoltbookScheduler.RETRY_MS)
        }
    }

    /** @return причина, по которой тик откладываем, или null — можно запускать. */
    private fun readinessBlocker(
        context: Context,
        stamp: File,
    ): String? {
        val since = System.currentTimeMillis() - lastTickAt(stamp)
        // Именно `in 0 until`, а не просто `< MIN_GAP_MS`: при откате стенных часов
        // (NTP после ребута, ручная правка) since уходит в минус, и старая проверка
        // возвращала «пропуск» на каждом тике — то есть до тех пор, пока часы не
        // догонят, то есть навсегда, молча и без единой ошибки. Отрицательный
        // since означает «прошло больше, чем мы думаем», и тик запускается.
        if (since in 0 until MIN_GAP_MS) {
            return "пропуск: прошлый тик был ${since / 60000} мин назад"
        }
        if (!File(context.filesDir, KEY_PATH).isFile) {
            return "нет ключа $KEY_PATH — тик отложен"
        }
        val up = isServerUp()
        return if (up) null else "opencode не отвечает — тик отложен"
    }

    /**
     * Проверка дешёвая и осмысленная: тику нужна сессия opencode для черновиков, и
     * без неё он всё равно не опубликует ни одного ответа.
     */
    private fun isServerUp(): Boolean = LocalOpenCodeClient.get(OPENCODE_PORT, "/session") != null

    private fun stampFile(context: Context) = File(context.filesDir, STAMP_PATH)

    /**
     * Первого тика в жизни ещё не было — файла нет, и это не поломка: «никогда»
     * и есть 0. Битый (не число) тоже трактуем как «никогда», иначе после
     * прерванной записи тик молча блокировался бы навсегда.
     */
    private fun lastTickAt(stamp: File): Long =
        if (stamp.isFile) stamp.readText().trim().toLongOrNull() ?: 0L else 0L

    private companion object {
        const val TAG = "MoltbookAlarm"
        const val OPENCODE_PORT = 4096
        const val KEY_PATH = "moltbook/moltkey"
        const val STAMP_PATH = "moltbook/last-tick"

        /** Меньше получаса не гоняем: ручная проверка и будильник не должны слипаться. */
        const val MIN_GAP_MS = 30 * 60 * 1000L

        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "moltbook-tick").apply { isDaemon = true }
            }
    }
}
