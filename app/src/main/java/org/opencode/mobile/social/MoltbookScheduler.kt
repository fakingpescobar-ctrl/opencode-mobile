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
 *
 * Период повтора — не константа: это либо [INTERVAL_MS], либо периодичность, которую
 * задал юзер в карточке. И то, и другое свёрнуто в [effectiveIntervalMs], потому что
 * раньше период жёстко тянулся из константы, и будильник, взведённый на 30-минутной
 * паузе, звонил один раз через полчаса, а дальше снова раз в два часа — юзер выбирал
 * одно, а получал другое, молча.
 */
internal object MoltbookScheduler {
    private const val TAG = "MoltbookSched"

    /** Раз в два часа: чаще платформа всё равно упрётся в rate limit и лимит трафика. */
    const val INTERVAL_MS = 2 * 60 * 60 * 1000L

    /** Интервал при попытке раньше срока — когда сервер ещё не поднят. */
    const val RETRY_MS = 15 * 60 * 1000L

    /**
     * Варианты периодичности в карточке. Ноль — «агент выбирает сам», поэтому он стоит
     * первым и присутствует всегда: UI обязан различать «не задано» (0) и «два часа»
     * (120), а для этого оба значения обязаны быть в списке. Плюс список — единственный
     * валидатор: значение вне его (битое из prefs, отрицательное, руками подправленное)
     * читается как 0, а не молча приводится к дефолту, иначе UI показал бы юзеру
     * «два часа», которых он не выбирал.
     */
    val INTERVAL_CHOICES_MINUTES: List<Int> = listOf(0, 30, 60, 120, 360)

    /**
     * Периодичность, заданная юзером, в минутах. 0 = агент выбирает паузу сам.
     *
     * Наружу отдаём уже разобранное значение: внутри объекта мусор из prefs не ходит,
     * и проверок «а не битое ли» дальше по коду не нужно.
     */
    fun userIntervalMinutes(context: Context): Int = parseUserInterval(prefs(context).getInt(KEY_USER_INTERVAL_MIN, 0))

    /**
     * Задать периодичность и СРАЗУ перевзвести будильник на ней.
     *
     * Перевзведение здесь, а не «в следующий тик» — обязательное. Будильник живёт по
     * `setInexactRepeating`, и его период записывается в систему только в момент
     * взведения: если записать выбор и ждать, пока агент сам вызовет `schedule`, то
     * до этого тика (а он может быть и через два часа) смена видна только в UI, а
     * тики продолжают идти по старому периоду. Юзер выбрал «раз в полчаса» и получил
     * тик через два часа — без единой ошибки.
     *
     * Именно `schedule`, а не `ensureScheduled`. У `ensureScheduled` контракт — НЕ
     * сдвигать живой будильник (это его raison d'être, см. его KDoc: рестарт serve не
     * имеет права затирать выбранную паузу). Здесь сдвиг — ровно то, чего требует
     * действие юзера, а `ensureScheduled` на живом будильнике вышел бы рано и оставил
     * старый период. Это разные намерения, поэтому разные функции, а не флаг.
     *
     * Если периодичность не изменилась (в т.ч. мусор свёлся к тому же 0) — будильник
     * не трогаем: лишний взведённый будильник обнуляет метку взведения и тем самым
     * сдвигает тот самый порог протухания из [ensureScheduled].
     */
    fun setUserIntervalMinutes(
        context: Context,
        minutes: Int,
    ) {
        val chosen = parseUserInterval(minutes)
        val previous = userIntervalMinutes(context)
        prefs(context).edit().putInt(KEY_USER_INTERVAL_MIN, chosen).apply()
        if (chosen == previous) {
            Log.i(TAG, "периодичность не изменилась: ${describeInterval(chosen)} — будильник не трогаю")
            return
        }
        Log.i(TAG, "периодичность: ${describeInterval(previous)} → ${describeInterval(chosen)}")
        schedule(context, effectiveIntervalMs(context))
    }

    /**
     * Взвести будильник: первый тик через `delayMs`, далее повтор с периодом
     * [effectiveIntervalMs].
     *
     * Разделение этих двух величин — не украшение: `delayMs` это «когда прийти в
     * следующий раз» (пауза, выбранная агентом по состоянию ленты, либо [RETRY_MS] при
     * сбое), а период — «как часто звонить дальше». В режиме «авто» они расходятся
     * ровно так же, как раньше: пауза от агента, период [INTERVAL_MS].
     *
     * Пауза юзера (когда он её задал) — исключение из правила «от агента»: её
     * перебивает только восстановление после сбоя, потому что [RETRY_MS] существует
     * ровно затем, чтобы автономия вернулась как можно скорее, и 15 минут не противоречат
     * выбору «раз в шесть часов» — это разовая попытка, а не новый ритм.
     */
    fun schedule(
        context: Context,
        delayMs: Long = effectiveIntervalMs(context),
    ) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val periodMs = effectiveIntervalMs(context)
        manager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + delayMs,
            periodMs,
            pendingIntent(context),
        )
        markArmed(context, delayMs)
        Log.i(TAG, "следующий тик через ${delayMs / 60000} мин, далее раз в ${periodMs / 60000} мин")
    }

    /**
     * Пауза, предложенная агентом по итогам тика, с учётом выбора юзера.
     *
     * Здесь и живёт приоритет юзера над моделью: если периодичность задана — пауза от
     * модели не используется вообще, даже когда модель просит больше. Ни min(), ни
     * max(), ни «покажем пользователю конфликт» здесь сознательно нет:
     *
     *  - min() (модель просит меньше) вернул бы тик каждые 30 минут вопреки выбору юзера.
     *    Модель так просит из-за отложенных ответов, но её оценка очереди не видит
     *    rate limit платформы, а юзер выбрал период именно чтобы не упираться в него.
     *    На тонкой ленте это превращается в тик раз в полчаса навсегда — ровно то, от
     *    чего юзер отказался. Очередь при этом видна: она уже пишется в отчёт тика
     *    (deferredReplies) и в лог, а не исчезает молча.
     *  - max() (модель просит больше) молча превратил бы «раз в полчаса» в «раз в 12
     *    часов» — то есть ровно то расхождение с настройкой, ради которого выбор и
     *    делается руками.
     *
     * В режиме «авто» (0) поведение прежнее: пауза агента, как он её выбрал.
     */
    fun scheduleAfterVisit(
        context: Context,
        modelDelayMs: Long,
    ) {
        val userMinutes = userIntervalMinutes(context)
        if (userMinutes == 0) {
            schedule(context, modelDelayMs)
            return
        }
        val userDelayMs = userMinutes * 60_000L
        if (modelDelayMs != userDelayMs) {
            Log.i(
                TAG,
                "агент просил ${modelDelayMs / 60000} мин, у юзера задано $userMinutes — " +
                    "оставляю юзера, отложенные ответы дождутся своей очереди",
            )
        }
        schedule(context, userDelayMs)
    }

    /**
     * Период повтора: пользовательский интервал, если он задан, иначе [INTERVAL_MS].
     *
     * Единственный источник правды. И [schedule], и порог протухания в
     * [ensureScheduled] обязаны брать период отсюда: если они считают его по-разному,
     * будильник начинает сам себя объявлять мёртвым на живом (см. [stalenessLimitMs]).
     */
    private fun effectiveIntervalMs(context: Context): Long = userIntervalMinutes(context).takeIf { it > 0 }?.times(60_000L) ?: INTERVAL_MS

    /** Мусор и значения вне списка — это 0 («агент выбирает»), а не дефолт 120. */
    private fun parseUserInterval(minutes: Int): Int = if (minutes in INTERVAL_CHOICES_MINUTES) minutes else 0

    private fun describeInterval(minutes: Int): String = if (minutes == 0) "авто" else "$minutes мин"

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
     * Порог считается от той паузы, которую агент выбрал на последнем тике, и от
     * настоящего периода повтора. Это принципиально: агент вправе выбрать 720 минут, и
     * рестарт serve через семь часов после такого тика обязан её уважать. Настоящий
     * интервал между тиками — выбранная пауза плюс размытие неточного будильника (окно
     * срабатывания доходит до 1.5 интервала, и система вправе отложить inexact ещё
     * сильнее на просадке батареи), поэтому порог = пауза * 1.5 + период.
     * Фиксированные шесть часов были ошибкой: они меньше максимальной паузы агента, и
     * рестарт serve на седьмом часе сбрасывал бы её на дефолтные два часа — ровно тот
     * баг, ради которого ensureScheduled и написан.
     *
     * Период в пороге — именно живой период будильника ([effectiveIntervalMs]), а не
     * константа [INTERVAL_MS]. Это вторая половина того же правила: при выбранных
     * юзером шести часах хвост из константы дал бы 660 минут против настоящих ~720
     * минут максимального молчания живого будильника (inexact система вправе отложить
     * почти на период), и ensureScheduled объявил бы живой будильник мёртвым на 11
     * часе — после чего взвёл бы его заново и тихо сбил выбранный ритм. Порог врёт
     * в обе стороны, и обе дороги платные: ложное «мёртв» сбрасывает паузу юзера,
     * ложное «жив» не возвращает автономию после реального снятия alarm'а. Поэтому
     * берём верхнюю границу, а не усреднённую.
     *
     * Обратная сторона честная: если будильник всё-таки сняли, автономия
     * восстанавливается не мгновенно, а через паузу, выбранную агентом или юзером. Это
     * цена за то, что пауза не сбрасывается ложным «будильник мёртв» на живом.
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
        val stale = silence < 0 || silence > stalenessLimitMs(armedDelay, effectiveIntervalMs(context))
        if (token != null && !stale) {
            Log.i(TAG, "будильник уже взведён — выбранный период не трогаю")
            return
        }
        if (token != null) {
            Log.w(TAG, "токен будильника жив, но с последнего взведения не было ни одного тика — взвожу заново")
        }
        // schedule с дефолтом, который сам берёт эффективный интервал: перевзводя
        // мёртвый будильник, нельзя заодно вернуть юзеру дефолтные два часа.
        schedule(context)
    }

    /**
     * Сколько молчания терпим, прежде чем считать будильник убитым: пауза * 1.5
     * (размытие неточного alarm'а) плюс настоящий период повтора (первое срабатывание
     * после простоя — ровно через период, а не через паузу).
     *
     * Чистая функция без Context и без prefs — единственное место, где живёт формула
     * порога, чтобы её нельзя было переписать в двух местах с разными периодами.
     */
    fun stalenessLimitMs(
        armedDelayMs: Long,
        periodMs: Long,
    ): Long = armedDelayMs + armedDelayMs / 2 + periodMs

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

    /** Рядом с метками взведения, а не в отдельном файле: это состояние того же будильника. */
    private const val KEY_USER_INTERVAL_MIN = "user_interval_min"
}
