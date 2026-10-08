package org.opencode.mobile.social

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.opencode.mobile.server.LocalOpenCodeClient
import org.opencode.mobile.social.MoltbookWatchdog.Verdict
import java.io.File
import java.util.concurrent.Executors

/**
 * Сторож во плоти: просыпается по своему будильнику, смотрит журнал тика и, если
 * тик не оставил следа дольше порога, один раз в час говорит об этом в чат
 * пользователю.
 *
 * Структура зеркалит [MoltbookAlarmReceiver] намеренно, и поэтому [onReceive]
 * делает ровно то же: мгновенно отдаёт работу отдельному потоку и возвращается.
 * Почему так — объяснено там, здесь только ссылка: `goAsync()` на весь тик
 * заканчивается Broadcast Timeout → ANR → убийством процесса (замерено на
 * живом прогоне), а любая сетевая проверка на главном потоке даёт
 * NetworkOnMainThreadException, который тихий catch превращал в «сервер не
 * отвечает», хотя он отвечал. Здесь сети тоже есть — доставка сообщения в чат,
 * — и она по тем же причинам идёт из своего потока.
 *
 * Порядок действий в проверке не случаен:
 *
 *  1. сначала журнал, потом чат. Обратный порядок дал бы «молчание без следа» —
 *     то есть ровно то состояние, которое сторож обязан исключить: если
 *     доставка в чат упала, в журнале всё равно остаётся запись о том, что тик
 *     мёртв, и внешний проверяющий с ПК её увидит;
 *  2. перевзвод себя — в [finally], а не в конце успешного ветки. Сторож,
 *     который сработал один раз и умер, хуже отсутствия сторожа: его молчание
 *     выглядит как «всё под контролем», а не как «контроля нет».
 *
 * Всё под `runCatching`/`catch (Throwable)`: ни одно исключение здесь не имеет
 * права убить поток executor'а — иначе следующего пробуждения не будет, и мы
 * получим ровно то молчание, ради которого этот класс написан.
 */
class MoltbookWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val app = context.applicationContext
        executor.execute {
            try {
                runCheck(app)
            } catch (e: Throwable) {
                // Throwable, а не Exception: поток executor'а умирает молча на любом
                // Error, и следующего пробуждения не будет — то есть мы получим
                // тишину, которую сами же и должны ловить.
                Log.w(TAG, "проверка упала: ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                MoltbookWatchdog.schedule(app)
            }
        }
    }

    /**
     * Одна проверка: прочитать журнал, понять вердикт, отдать его в журнал и в
     * чат. Ничего не взводит — это делает [onReceive] в `finally`, чтобы
     * перевзвод пережил и падение, и пустой вердикт.
     */
    private fun runCheck(context: Context) {
        val log = MoltbookWatchdog.logFile(context)
        val now = System.currentTimeMillis()

        // Порча проверяется по сырым строкам, а не по распарсенным: readAll
        // отбрасывает нечитаемое через mapNotNull, поэтому удаление последних
        // строк (в том числе доказательства effect) давало «целую» цепочку, и
        // порча не обнаруживалась. verify() честно останавливается на битой
        // строке и называет её номер.
        val raw = MoltbookWitness.verify(log)
        val entries = MoltbookWitness.readAll(log)
        // Порог — по наблюдаемому ритму тика, а не фиксированные три часа: паузу
        // выбирает агент, и она бывает и шесть, и двенадцать часов. Фиксированный
        // порог кричал бы «тик умер» на каждой законно длинной паузе, а тревога,
        // которую игнорируют, хуже отсутствия тревоги.
        val limitMs = MoltbookWatchdog.observedLimitMs(entries)
        val verdict =
            if (raw.brokenAt != null) {
                Verdict.Broken(raw.brokenAt, raw.reason)
            } else {
                MoltbookWatchdog.inspect(entries.lastOrNull(), now, limitMs)
            }
                ?: run {
                    Log.i(TAG, "тик жив: seq ${entries.lastOrNull()?.seq ?: MoltbookWatchdog.NO_ENTRY_SEQ}, порог ${limitMs / 3600000} ч")
                    return
                }
        report(context, log, entries, now, verdict)
    }

    /**
     * Отдать вердикт: запись в цепочку и сообщение в чат, но не чаще одного раза
     * в час.
     *
     * Ограничение частоты не оптимизация, а условие работоспособности журнала:
     * без него мёртвый тик даёт 48 записей [MoltbookWitness.KIND_STALE] в сутки,
     * и при [MoltbookWitness.MAX_LINES] в 500 строк реальная история тиков
     * вытесняется воплями о собственном молчании за считаные дни. Сторож,
     * который засоряет наблюдаемый журнал, разрушает ровно то доказательство,
     * ради которого он поднят.
     *
     * Частота считается по самим записям журнала ([MoltbookWatchdog.shouldReportStale]),
     * поэтому ограничение переживает перезапуск процесса и не плодит второе
     * состояние на диске.
     */
    /**
     * Вид записи журнала для вердикта: порванная цепочка - `tamper`, затишье - `stale`.
     *
     * Разделение существенно для троттлинга: `shouldReportStale` ищет в журнале
     * запись того же вида за окно повтора. Если бы throttle смотрел только на
     * `stale`, то вердикт `Broken` (пишущий `tamper`) не нашёл бы собственную
     * прошлую запись и отчёт ушёл бы в чат каждые 30 минут бесконечно, вытесняя
     * записи тика из окна в [MoltbookWitness.MAX_LINES] строк.
     */
    private fun reportKind(verdict: Verdict): String =
        if (verdict is Verdict.Broken) MoltbookWitness.KIND_TAMPER else MoltbookWitness.KIND_STALE

    private fun report(
        context: Context,
        log: File,
        entries: List<MoltbookWitness.Entry>,
        now: Long,
        verdict: Verdict,
    ) {
        val text = MoltbookWatchdog.describe(verdict)
        if (!MoltbookWatchdog.shouldReportStale(entries, now, kinds = setOf(reportKind(verdict)))) {
            Log.w(TAG, "уже говорил час назад — молчу: $text")
            return
        }
        // Порванная цепочка помечается как tamper, а не как stale: молчание —
        // это про тик, порча — про журнал, и свести их в один вид значило бы
        // потерять разницу, ради которой оба вида существуют.
        val kind = reportKind(verdict)
        runCatching {
            MoltbookWitness.append(
                logFile = log,
                mirrorFile = MoltbookWatchdog.mirrorFile(context),
                kind = kind,
                payload = text,
                now = now,
            )
        }.onFailure { Log.w(TAG, "не записал тревогу в журнал: ${it.message}") }
        Log.w(TAG, text)
        notifyChat(text)
    }

    /**
     * Сообщение в последнюю сессию opencode — паттерн взят из `MoltbookTicker.notifyChat`,
     * который приватный, и `MoltbookTicker.kt` трогать нельзя.
     *
     * Почему не «вынести общий помощник»: единственное, что тут повторяется с
     * тикером, это три строки пост-ассистента к локальному serve, а ценой
     * общего помощника был бы доступ тикера к приватному API тикера. Повторение
     * строк дешевле, чем сцепка двух несвязанных вещей через новую
     * зависимость; когда появится третий отправитель в чат, тогда и выносится.
     */
    private fun notifyChat(text: String) {
        val sessionId = lastTouchedUserSession() ?: run {
            Log.w(TAG, "нет сессии opencode — сообщение о молчании никто не прочитает")
            return
        }
        val body =
            JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                .toString()
        LocalOpenCodeClient.postAsync(OPENCODE_PORT, "/session/$sessionId/message", body)
    }

    /** Последняя сессия пользователя: её ChatOverlay обновляет сам раз в пару секунд. */
    private fun lastTouchedUserSession(): String? {
        val raw = LocalOpenCodeClient.get(OPENCODE_PORT, "/session") ?: return null
        val sessions = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        return (0 until sessions.length())
            .mapNotNull { sessions.optJSONObject(it) }
            .maxByOrNull { it.optJSONObject("time")?.optLong("updated") ?: 0L }
            ?.optString("id")
            ?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        /** Отличается от тега планировщика, чтобы в logcat их не спутать. */
        const val TAG = "MoltbookWatchdogRx"

        /** Порт локального serve — тот же, что у тикера: это один и тот же сервер. */
        const val OPENCODE_PORT = 4096

        /**
         * Свой поток, а не общий с тиком: тик ждёт генерацию модели минутами, и
         * общий single-thread executor означал бы, что сторож не в состоянии
         * увидеть смерть того самого тика, чью он работу стоит в этой очереди.
         */
        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "moltbook-watchdog").apply { isDaemon = true }
            }
    }
}
