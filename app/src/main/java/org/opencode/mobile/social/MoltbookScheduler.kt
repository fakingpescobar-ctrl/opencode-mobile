package org.opencode.mobile.social

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Будильник для тиков. AlarmManager, а не WorkManager: WorkManager в проекте нет, а
 * тянуть новую зависимость ради одного периодического вызова — лишний вес в APK и
 * в оффлайн-сборке.
 *
 * `setInexactRepeating`, а не exact: два часа не требуют попадания в секунду, зато
 * система не будит процесс из Doze точно в срок — на батарее это честнее, а для
 * «заглянуть раз в пару часов» точность не нужна.
 */
internal object MoltbookScheduler {
    private const val TAG = "MoltbookSched"

    /** Раз в два часа: чаще платформа всё равно упрётся в rate limit и лимит трафика. */
    const val INTERVAL_MS = 2 * 60 * 60 * 1000L

    /** Интервал при попытке раньше срока — когда сервер ещё не поднят. */
    const val RETRY_MS = 15 * 60 * 1000L

    fun schedule(
        context: Context,
        delayMs: Long = INTERVAL_MS,
    ) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        manager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + delayMs,
            INTERVAL_MS,
            pendingIntent(context),
        )
        markArmed(context, delayMs)
        Log.i(TAG, "следующий тик через ${delayMs / 60000} мин")
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = pendingIntent(context)
        manager.cancel(intent)
        // Токен тоже уничтожаем. AlarmManager.cancel() снимает только запись
        // будильника, а сам PendingIntent остаётся зарегистрирован в AMS — и
        // ensureScheduled потом увидит живой токен и решит, что будильник взведён.
        intent.cancel()
        disarm(context)
    }

    /**
     * Забыть, что будильник когда-то был взведён.
     *
     * Обязательно на перезагрузке и обновлении APK: alarm'ы система сносит, а
     * SharedPreferences с меткой — нет. Без этого ensureScheduled после
     * BOOT_COMPLETED видел бы свежую метку и вышел рано, оставив автономию
     * выключенной до позднего старта serve.
     */
    fun disarm(context: Context) {
        prefs(context)
            .edit()
            .remove(KEY_ARMED_AT)
            .remove(KEY_ARMED_DELAY_MS)
            .apply()
    }

    /**
     * Взвести будильник, только если он ещё нужен — в отличие от [schedule], который
     * переносит следующий тик на `now + delayMs` безусловно.
     *
     * Именно эта разница была багом: старт serve вызывал `schedule()` с дефолтными
     * двумя часами и затирал паузу, которую агент выбрал сам (30–720 минут по
     * фактическому состоянию ленты). Если serve поднимается и падает часто, каждый
     * его старт отодвигал тик всё дальше, и агент голодал, не показывая ни одной
     * ошибки.
     *
     * Но одного «токен PendingIntent жив» мало, и это вторая половина того же бага.
     * Токен и запись будильника живут отдельно: AlarmManager.cancel() убирает запись,
     * а токен остаётся, и на Android 12+ система сама снимает inexact-будильники по
     * бюджету. В обоих случаях FLAG_NO_CREATE возвращал не-null, ensureScheduled
     * радостно писал «будильник уже взведён» — и автономия выключалась навсегда,
     * без единой ошибки: тик просто больше никогда не приходит.
     *
     * Поэтому сверяемся ещё и с меткой «когда мы последний раз точно взвели, и с
     * какой паузой». Если с тех пор не пришло ни одного тика, будильник мёртв (его
     * сняли), даже если токен жив, — и взводим заново. Метка обновляется в каждом
     * [schedule], то есть на каждом успешном тике, поэтому здоровый будильник
     * метку не протухает и паузу агента не сбивает.
     *
     * Порог считается от той паузы, которую агент выбрал на последнем тике, а не от
     * константы. Это принципиально: агент вправе выбрать 720 минут, и рестарт serve
     * через семь часов после такого тика обязан её уважать. Настоящий интервал между
     * тиками — выбранная пауза плюс размытие неточного будильника (окно
     * срабатывания доходит до 1.5 интервала, и система вправе отложить inexact ещё
     * сильнее на просадке батареи), поэтому порог = пауза * 1.5 + INTERVAL_MS.
     * Фиксированные шесть часов были ошибкой: они меньше максимальной паузы
     * агента, и рестарт serve на седьмом часе сбрасывал бы её на дефолтные два
     * часа — ровно тот баг, ради которого ensureScheduled и написан.
     *
     * Обратная сторона честная: если будильник всё-таки сняли, автономия
     * восстанавливается не мгновенно, а через паузу, выбранную агентом. Это цена
     * за то, что пауза агента не сбрасывается ложным «будильник мёртв» на живом.
     */
    fun ensureScheduled(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val token =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                tickIntent(context),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
        val prefs = prefs(context)
        val armedAt = prefs.getLong(KEY_ARMED_AT, 0L)
        val armedDelay = prefs.getLong(KEY_ARMED_DELAY_MS, INTERVAL_MS)
        val silence = System.currentTimeMillis() - armedAt
        // Разность отрицательная = часы перевели назад. Считаем метку протухшей:
        // взвести лишний раз безопасно, а поверить часам после перевода — нет.
        val stale = silence < 0 || silence > armedDelay + armedDelay / 2 + INTERVAL_MS
        if (token != null && !stale) {
            Log.i(TAG, "будильник уже взведён — интервал, выбранный агентом, не трогаю")
            return
        }
        if (token != null) {
            Log.w(TAG, "токен будильника жив, но с последнего взведения не было ни одного тика — взвожу заново")
        }
        schedule(context)
    }

    private fun markArmed(
        context: Context,
        delayMs: Long,
    ) {
        prefs(context)
            .edit()
            .putLong(KEY_ARMED_AT, System.currentTimeMillis())
            .putLong(KEY_ARMED_DELAY_MS, delayMs)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun tickIntent(context: Context): Intent = Intent(context, MoltbookAlarmReceiver::class.java).setAction(ACTION_TICK)

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            tickIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    const val ACTION_TICK = "org.opencode.mobile.social.TICK"

    /** Код должен совпадать в [schedule] и в receiver'е — иначе alarm не сработает. */
    const val REQUEST_CODE = 4417

    private const val PREFS = "moltbook_schedule"
    private const val KEY_ARMED_AT = "armed_at"
    private const val KEY_ARMED_DELAY_MS = "armed_delay_ms"
}
