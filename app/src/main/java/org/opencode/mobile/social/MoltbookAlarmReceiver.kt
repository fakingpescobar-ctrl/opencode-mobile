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

/**
     * Прошлый сбой больше не актуален — иначе панель вечно показывает ошибку вчерашнего
     * тика. Ключ просто удаляется: панель ждёт именно null.
     *
     * Пишем в ledger самого тикера, а не создаём свой MoltbookLedger: каждый такой
     * экземпляр — отдельный SQLiteOpenHelper, который никто не закрывает, а тик идёт
     * каждые 30-720 минут.
     */
    private fun clearLastError(ticker: MoltbookTicker) {
        runCatching { ticker.ledger().clearState(MoltbookLedger.KEY_LAST_ERROR) }
            .onFailure { Log.w(TAG, "не записал состояние тика: ${it.message}") }
    }

    /**
     * Запись в журнал-свидетель. Best effort, и это обязательное свойство:
     * журнал — это наблюдение за тиком, а не условие его существования. Если
     * падение записи уводит тик в ретрай, то битый диск отключает автономию
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

    /**
     * Receipt ДО работы тика.
     *
     * Ставит host (этот файл), а не сам тикер, и именно поэтому он бесполезен
     * после `ticker.runOnce()`: то, что пишет сам проверяемый код после своей
     * работы, может отсутствовать из-за того же сбоя, который мы проверяем.
     *
     * Монотонный номер записи — это `Entry.seq`, отдельное поле журнала, и в
     * payload он НЕ дублируется намеренно: чтобы подставить его в payload,
     * пришлось бы заранее прочитать голову цепочки (`head()` читает журнал
     * целиком), то есть завести гонку со сторожем и лишнее полное чтение файла
     * на каждом тике ради числа, которое уже лежит в поле. Платить за это
     * точностью доказательства нельзя.
     */
    private fun recordWake(context: Context) {
        recordWitness(context, MoltbookWitness.KIND_WAKE, "tick started")
    }

    /**
     * Отпечаток того, что тик оставил на диске — side-effect hash после работы.
     *
     * Отпечаток берётся у базы тикера, а не «считается тут же», и разница
     * принципиальна: хэш должен быть тем, что лежит на диске после тика, иначе
     * он ничего не доказывает. Само вычисление обёрнуто: база может быть
     * недоступна, и тогда отсутствие отпечатка — тоже факт, который обязан
     * попасть в журнал строкой `err:...`, а не молчанием.
     */
    private fun recordEffect(
        context: Context,
        ticker: MoltbookTicker,
    ) {
        val digest =
            runCatching { ticker.ledger().ledgerDigest() }
                .getOrElse { "err:" + it.javaClass.simpleName }
        recordWitness(context, MoltbookWitness.KIND_EFFECT, digest)
    }

    /**
     * Один тик целиком. Обёртка нужна ради одной строки в `finally`: сторож
     * обязан быть взведён после ЛЮБОГО исхода, включая отказ по готовности
     * (тик отложен, потому что сервер ещё не поднят) — иначе единственный
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
        val stamp = stampFile(context)
        val blocker = readinessBlocker(context, stamp)
        if (blocker != null) {
            Log.i(TAG, blocker)
            MoltbookScheduler.schedule(context, MoltbookScheduler.RETRY_MS)
            return
        }
        // Receipt ДО любой работы тика: будильник сработал, тик пошёл. Стоит он
        // после проверки готовности, потому что «тик пошёл» обязано означать
        // «тик действительно начал работу», а не «мы что-то попробовали».
        recordWake(context)
        val ticker = MoltbookTicker(context)
        try {
            val report = ticker.runOnce()
            Log.i(
                TAG,
                "тик: постов=${report.postsChecked} ответов=${report.repliesPosted} " +
                    "verification=${report.verificationsSolved} karma=${report.karma} " +
                    "ждёт=${report.deferredReplies} следующий визит через ${report.nextVisitMinutes} мин",
            )
            stamp.writeText(System.currentTimeMillis().toString())
            clearLastError(ticker)
            // Отпечаток ПОСЛЕ работы: он и есть side-effect hash тика. Именно
            // поэтому он здесь, а не в finally: тик, начатый и не законченный,
            // обязан остаться в журнале парой wake без effect — это и есть
            // «начался и не дошёл», и замазывать её нельзя.
            recordEffect(context, ticker)
            // Паузу выбирает агент по фактическому состоянию ленты, а не расписание:
            // жёсткие 2 часа означали либо простой, либо очередь отложенных ответов.
            // Но если периодичность задал юзер, его выбор главнее — иначе карточка в UI
            // была бы враньём. Приоритет и его цена описаны в scheduleAfterVisit.
            MoltbookScheduler.scheduleAfterVisit(context, report.nextVisitMinutes * 60_000L)
        } catch (e: Exception) {
            Log.w(TAG, "тик упал: ${e.message}")
            // KEY_LAST_ERROR читался панелью, но не писался НИГДЕ — строка «последняя
            // ошибка» не могла появиться вообще, и сбой тика был виден только в logcat.
            runCatching {
                ticker.ledger().putState(MoltbookLedger.KEY_LAST_ERROR, "${e.javaClass.simpleName}: ${e.message}")
            }.onFailure { Log.w(TAG, "не записал состояние тика: ${it.message}") }
            // Сбой тоже оставляет след: молчание и порча должны выглядеть
            // по-разному, и упавший тик обязан отличаться от не наступившего.
            recordWitness(context, MoltbookWitness.KIND_EFFECT, "err: ${e.javaClass.simpleName}: ${e.message}")
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
    private fun lastTickAt(stamp: File): Long = if (stamp.isFile) stamp.readText().trim().toLongOrNull() ?: 0L else 0L

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
