package org.opencode.mobile.social

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.opencode.mobile.server.LocalOpenCodeClient
import java.io.File
import java.util.concurrent.Executors

/**
 * Будильник. Приёмник мгновенно уходит, вся работа — в отдельном потоке.
 *
 * Он больше НЕ ходит на сайт: ни ленты, ни публикаций, ни моделей. Всё это
 * делает агент через инструменты плагина, а отсюда осталось одно — не дать
 * автономии умереть тихо. Само решение «кого и когда будить» живёт в
 * [MoltbookWake], этот файл только доставляет вызов и ведёт наблюдение.
 *
 * Три требования Android, которые тут сталкиваются, и все три проверены на
 * живом прогоне, а не взяты из документации:
 *
 *  1. goAsync() нельзя держать на весь ход — Android даёт broadcast 10–60 секунд,
 *     а агент думает минутами. Реально это кончилось Broadcast Timeout → ANR →
 *     убийство процесса. Поэтому goAsync() здесь не используется вообще:
 *     broadcast завершается сразу, работа продолжается в своём потоке.
 *  2. Никаких проверок с сетью в onReceive — он идёт на главном потоке, где
 *     HttpURLConnection даёт NetworkOnMainThreadException, который молчаливый
 *     catch превращал в «opencode не отвечает» (а он отвечал).
 *  3. Процесс переживает работу, потому что его держит foreground service serve.
 *
 * Наблюдение (witness) осталось прежним намеренно: сторож смотрит на
 * witness.log и по отсутствию записей объявляет, что автономия сломалась.
 * Пока сторож жив, будильник обязан оставлять ту же пару wake → effect, что и
 * раньше, иначе сторож завёл бы тревогу по собственному будильнику.
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
                // любом Error, и будильник выглядит как «сработал и ничего не сделал».
                Log.w(TAG, "будильник упал: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    /**
     * Один проход целиком. Обёртка нужна ради одной строки в `finally`: сторож
     * обязан быть взведён после ЛЮБОГО исхода, включая отказ по готовности
     * (будильник отложен, потому что сервер ещё не поднят) — иначе единственный
     * способ взвести сторож зависел бы от того, что он сам и должен проверять.
     */
    private fun runTick(context: Context) {
        try {
            runTickWork(context)
        } finally {
            MoltbookWatchdog.ensureArmed(context)
        }
    }

    private fun runTickWork(context: Context) {
        val blocker = readinessBlocker(context)
        if (blocker != null) {
            Log.i(TAG, blocker)
            MoltbookScheduler.schedule(context, MoltbookScheduler.RETRY_MS)
            return
        }
        // Receipt ДО работы: будильник сработал, работа пошла. Стоит он после
        // проверки готовности, потому что «работа пошла» обязано означать
        // «работа действительно началась», а не «мы что-то попробовали».
        recordWitness(context, MoltbookWitness.KIND_WAKE, "wake")
        // Всё решение — кого, когда и писать ли вообще — внутри MoltbookWake.
        // Здесь нет ни сети, ни выбора сессии: приёмок обязан быть тупым,
        // иначе правило «будить только свободную» начнёт разъезжаться между
        // двумя местами с разными представлениями о занятости.
        val record = MoltbookWake.nudge(context, System.currentTimeMillis())
        Log.i(TAG, "будильник: $record")
        // Отпечаток ПОСЛЕ работы, и это ровно то, чем будильник является:
        // что он записал в журнал. Тик, начатый и не законченный, обязан
        // остаться в журнале парой wake без effect — это и есть «начался и не
        // дошёл», и замазывать её нельзя.
        recordWitness(context, MoltbookWitness.KIND_EFFECT, record)
        MoltbookScheduler.scheduleAfterVisit(context, nextVisitMs(context))
    }

    /**
     * Пауза до следующего будильника.
     *
     * Раньше её выбирал тикер по состоянию ленты (`report.nextVisitMinutes`), и
     * это была единственная причина, по которой тикер вообще что-то знал о
     * ленте. Теперь лентой занимается агент, а будильник знает ровно одно:
     * пользовательский интервал, если он задан, иначе обычный период.
     */
    private fun nextVisitMs(context: Context): Long =
        MoltbookScheduler
            .userIntervalMinutes(context)
            .takeIf { it > 0 }
            ?.times(60_000L)
            ?: MoltbookScheduler.INTERVAL_MS

    /**
     * Готовность будильника, а не тикера: тику был нужен ещё и ключ от
     * moltbook, будильнику достаточно поднятого сервера. Ключ проверяется
     * только потому, что без него агент всё равно не сможет ничего
     * опубликовать, и молчаливые нули в отчёте были бы хуже отложенного
     * будильника.
     */
    private fun readinessBlocker(context: Context): String? {
        if (!File(context.filesDir, KEY_PATH).isFile) {
            return "нет ключа $KEY_PATH — будильник отложен"
        }
        return if (isServerUp()) null else "opencode не отвечает — будильник отложен"
    }

    /**
     * Запись в журнал-свидетель. Best effort, и это обязательное свойство:
     * журнал — это наблюдение за будильником, а не условие его существования.
     * Если падение записи уводит автономию в ретрай, то битый диск отключает её
     * целиком, и никакой сторож этого уже не заметит — он тоже пишет туда.
     */
    private fun recordWitness(
        context: Context,
        kind: String,
        payload: String,
    ) {
        runCatching {
            MoltbookWitness.append(
                logFile = MoltbookWatchdog.logFile(context),
                mirrorFile = MoltbookWatchdog.mirrorFile(context),
                kind = kind,
                payload = payload,
                now = System.currentTimeMillis(),
            )
        }.onFailure { Log.w(TAG, "не записал свидетельство ($kind): ${it.message}") }
    }

    /** Проверка дешёвая и осмысленная: будить некого, если сервера нет. */
    private fun isServerUp(): Boolean = LocalOpenCodeClient.get(OPENCODE_PORT, "/session") != null

    private companion object {
        const val TAG = "MoltbookAlarm"
        const val OPENCODE_PORT = 4096
        const val KEY_PATH = "moltbook/moltkey"

        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "moltbook-wake").apply { isDaemon = true }
            }
    }
}
