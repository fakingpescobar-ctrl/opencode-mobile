package org.opencode.mobile.ui.chat

import org.opencode.mobile.ui.ChatNotice
import org.opencode.mobile.ui.ChatSnapshot
import org.opencode.mobile.ui.nextRetryLatch
import org.opencode.mobile.ui.retryExhausted
import org.opencode.mobile.ui.stoppedRetryNotice

// Вариант B: если модель "думает" (последнее — user, или assistant с пустым текстом)
// дольше этого времени без какого-либо прогресса в сессии — считаем зависание
// и снимаем вечный индикатор «… генерируется …». 120_000 = 2 минуты паузы.
internal const val STALL_TIMEOUT_MS = 120_000L

// «Пустой» открытый assistant-шаг (нет ни activity, ни текста, ни выполняемого тула) —
// модель явно работает (предикт/ранний tool), но молчит БЕЗ частей. Провайдер может
// держать такую паузу перед первым токеном/тулом достаточно долго (замер ~25-30с на
// big-pickle перед bash-диагностикой), поэтому таймаут НЕ ставим слишком маленьким,
// иначе помечаем «Нет ответа» при реально работающей модели. 90с = 1.5 мин паузы.
internal const val STALL_EMPTY_MS = 90_000L

// Мёртвый ход: сервер принял сообщение, но ассистент не создал НИ ОДНОГО шага.
// Утверждение сильнее, чем «долго думает», поэтому и порог жёстче: сразу после
// отправки assistant-часть появляется не мгновенно (очередь у провайдера, холодный
// старт), и 90с тут давали бы ложное «не ответила» на живой сессии. 180с = 3 мин.
internal const val STALL_DEAD_MS = 180_000L

// Интервал опроса serve. КРАЙНЕ ВАЖНО для скорости появления ответа: serve пишет
// полный ответ мгновенно, но приложение узнаёт о нём только на следующем поллинге.
// Раз поллинг стоил 2_000мс — ответ «задерживался» на 0..2с (в среднем ~1с), что
// ощущалось как «рендер через 1.5-2с». Снижено до 400мс: ответ появляется почти
// сразу (≤400мс). Сам запрос лёгкий (~30-70мс), частое опрашивание безопасно.
// При 400мс работает дельта-поллинг: snapshot ставится только при реальных
// изменениях, чтобы не реконсилить LazyColumn на каждый тик.
internal const val POLL_INTERVAL_MS = 400L

// Адаптивный поллинг: в простое (лента статична, нет думания, никто не отвечает)
// serve-опрос растягивается до POLL_IDLE_MS, чтобы не создавать 3 TCP-соединения
// каждые 400мс вхолостую (жрёт ~30% CPU UI в idle). Как только детектим активность
// (thinking=true | новый user-part | сменился liveTool/question) — мгновенно
// возвращаемся к быстрому 400мс, чтобы отклик на ответ модели не проседал.
// Idle-интервал → ввод: стоит 900мс (≈1.1 поллинга/с вместо 2.5).
internal const val POLL_IDLE_MS = 900L

// Сколько последовательных «стабильных» поллингов нужно, чтобы перейти в idle.
// Небольшое значение, чтобы не дёргаться на единичных флапсах (скролл не влияет —
// поллинг не зависит от видимости). Только лента + флаги.
internal const val STABLE_POLL_ROUNDS = 3

/**
 * Метки зависания хода. Держим их ВМЕСТЕ, потому что обновляются они одним
 * решением: любой прогресс в ленте обнуляет обе, а взаимоисключающие ветки
 * (пустой шаг / мёртвый ход / общий таймаут) только одну из них.
 *
 * [since] — с какого момента идёт обычное «думание» при непустом шаге.
 * [emptySince] — с какого момента открыт ПУСТОЙ assistant-шаг или ход мёртв.
 * Ноль означает «отсчёт не идёт».
 */
internal data class StallState(
    val since: Long = 0L,
    val emptySince: Long = 0L,
)

/**
 * Итог одного тика: новое состояние меток и признак зависания.
 *
 * [dead] отдельно от [stalled], потому что UI показывает разные тексты: «Нет
 * ответа» для зависшего думания и «Ход не начался» для мёртвого хода.
 */
internal data class StallVerdict(
    val state: StallState,
    val stalled: Boolean,
    val dead: Boolean,
)

/**
 * Шаг детектора зависания — чистая функция от предыдущего состояния и снапшота.
 *
 * [progressed] — лента/тул/плашка изменились с прошлого тика. Любой прогресс
 * сбрасывает отсчёт: это и есть «модель работает, просто медленно».
 *
 * Вынесено из Composable ради теста: живьём такие таймауты проверяются минутами
 * на устройстве, а здесь — за наносекунды. Поведение перенесено дословно.
 */
internal fun advanceStall(
    previous: StallState,
    progressed: Boolean,
    snap: ChatSnapshot,
    now: Long,
): StallVerdict {
    val state = if (progressed) StallState() else previous
    // Ход закончился — оба отсчёта больше не нужны.
    if (!snap.thinking) return StallVerdict(StallState(), stalled = false, dead = false)
    val lastMsg = snap.messages.lastOrNull()
    // Ход МЁРТВ, если последнее сообщение — наше собственное user'ское:
    // ассистент не создал ни одного шага. Проверено на живой сессии —
    // сервер принял запрос, отдал HTTP 500 и оставил висящий user-part,
    // поэтому /session/status пуст и плашка причины тут не поможет.
    val dead = lastMsg?.role == "user"
    // «Пустой» = открыт assistant-шаг, никакого текста И никакого выполняемого
    // тула (liveTool == null). Если модель реально гоняет websearch/тул,
    // liveTool не null → идём в общий таймаут 120с (тул может работать долго).
    val empty =
        !dead &&
            lastMsg != null &&
            lastMsg.role == "assistant" &&
            lastMsg.text.isBlank() &&
            snap.liveTool == null
    return if (dead || empty) stallOnEmptyStep(state, dead, now) else stallOnThinking(state, now)
}

/** Пустой assistant-шаг или мёртвый ход: у них свои, более короткие пороги. */
private fun stallOnEmptyStep(
    state: StallState,
    dead: Boolean,
    now: Long,
): StallVerdict {
    val since = if (state.emptySince == 0L) now else state.emptySince
    val elapsed = now - since
    val limit = if (dead) STALL_DEAD_MS else STALL_EMPTY_MS
    android.util.Log.d("ChatOverlay", "STALL dead=$dead since=${elapsed}ms")
    return StallVerdict(
        state = StallState(since = state.since, emptySince = since),
        stalled = elapsed >= limit,
        dead = dead,
    )
}

/** Обычное «думание» с непустым шагом: единый порог на весь ход. */
private fun stallOnThinking(
    state: StallState,
    now: Long,
): StallVerdict {
    val since = if (state.since == 0L) now else state.since
    val elapsed = now - since
    android.util.Log.d("ChatOverlay", "STALL check thinking=true since=${elapsed}ms")
    return StallVerdict(
        state = StallState(since = since, emptySince = 0L),
        stalled = elapsed >= STALL_TIMEOUT_MS,
        dead = false,
    )
}

/**
 * Состояние бюджета повторов: висящая плашка, наша собственная плашка «Ход
 * остановлен» и момент, с которого бюджет отсчитывается.
 *
 * [stop] отделён от [latch] намеренно: если держать «Ход остановлен» в той же
 * переменке, что и серверный retry, то следующий тик опроса безо всякого нашего
 * участия вернёт серверную плашку и тихо затрёт нашу.
 */
internal data class RetryState(
    val latch: ChatNotice? = null,
    val stop: ChatNotice? = null,
    val since: Long = 0L,
)

/**
 * Итог тика: новое состояние бюджета и нужно ли самим оборвать ход.
 *
 * [abort] не содержит sessionId: выбор сессии — дело вызывающего, здесь только
 * решение «бюджет исчерпан, хватит ждать».
 */
internal data class RetryVerdict(
    val state: RetryState,
    val abort: Boolean,
)

/**
 * Шаг бюджета повторов — чистая функция от предыдущего состояния.
 *
 * Без бюджета плашка «Модель повторяет запрос» может висеть вечно — намеряно
 * 47 раз подряд за 2.5 часа, пока провайдер рвал сокет. Исчерпали попытки или
 * время → сами останавливаем ход и честно говорим об этом в плашке.
 *
 * Условие «наша плашка уже стоит» — не украшение, а страховка от повторного
 * abort: без неё остановленный ход проверялся бы снова на каждом тике опроса,
 * а `stoppedRetryNotice` обнуляет `since`, из-за чего `now - since` всегда больше
 * бюджета. Итог был — abortSession на сессии по 400 мс, пока висит плашка.
 */
internal fun advanceRetry(
    previous: RetryState,
    seen: ChatNotice?,
    turnFinished: Boolean,
    now: Long,
): RetryVerdict {
    val latch = nextRetryLatch(previous.latch, previous.stop, seen, turnFinished)
    // Первая плашка после тишины — старт отсчёта бюджета.
    val fresh = previous.latch == null && previous.stop == null
    val state =
        when {
            latch == null -> RetryState()
            fresh -> RetryState(latch = latch, since = now)
            else -> previous.copy(latch = latch)
        }
    val held = state.latch
    val elapsed = now - state.since
    val stop =
        when {
            state.stop != null -> null
            held == null -> null
            !retryExhausted(held, elapsed) -> null
            else -> stoppedRetryNotice(held, elapsed)
        }
    return RetryVerdict(
        state = if (stop != null) RetryState(latch = stop, stop = stop, since = state.since) else state,
        abort = stop != null,
    )
}

/**
 * Счётчик «стабильных» итераций поллинга.
 *
 * Растёт, пока лента статична и не думается; по достижении [STABLE_POLL_ROUNDS]
 * поллинг растягивается до [POLL_IDLE_MS]. При малейшем прогрессе сбрасывается
 * в ноль, чтобы отклик не проседал.
 */
internal fun nextStableRounds(
    previous: Int,
    active: Boolean,
): Int = if (active) 0 else previous + 1

/** Пауза между опросами для текущего числа стабильных итераций. */
internal fun pollDelayMs(stableRounds: Int): Long = if (stableRounds >= STABLE_POLL_ROUNDS) POLL_IDLE_MS else POLL_INTERVAL_MS
