package org.opencode.mobile.ui.chat

import org.opencode.mobile.ui.ChatNotice
import org.opencode.mobile.ui.ChatSnapshot

/**
 * Состояние цикла опроса чата между тиками.
 *
 * Тик опроса — это не «получить снапшот», а сразу несколько решений: завис ли ход,
 * не пора ли оборвать его самим, что из этого показать в UI, сдвинулся ли счётчик
 * завершённых ответов и с какой паузой опрашивать дальше. Каждое правило по
 * отдельности уже живёт своим файлом с тестом ([advanceStall], [advanceRetry],
 * [snapshotChanged], [nextStableRounds]), а вот их ПОРЯДОК и передача результатов
 * друг в друга были зашиты в Composable и не проверялись ничем.
 *
 * Здесь порядок зафиксирован данными: [advancePoll] берёт прошлое состояние и
 * снапшот, возвращает решение. Ничего не мутирует и ничего не делает в UI.
 */
internal data class ChatPollState(
    val stall: StallState = StallState(),
    val retry: RetryState = RetryState(),
    /** Сколько тиков подряд лента стоит без движения — для адаптивной паузы. */
    val stableRounds: Int = 0,
    /** Сколько завершённых ответов уже видел UI (для уведомления о новом). */
    val knownMsgs: Int = 0,
    /**
     * Базовая линия вибрации уже снята. Снимается один раз, на первом снапшоте:
     * старые ответы в непустой сессии не должны вибрировать при открытии чата.
     */
    val baselineDone: Boolean = false,
)

/**
 * Ход пора оборвать самим: бюджет повторов исчерпан.
 *
 * Сессия названа здесь, а не выбирается вызывающим, потому что выбор — часть того
 * же решения: обрывать нечего, если сервер не назвал активную сессию.
 */
internal data class ChatAbort(
    val sessionId: String,
    /** Номер исчерпанной попытки — только для журнала, в решении не участвует. */
    val attempt: Int?,
)

/**
 * Результат одного тика.
 *
 * [publish] отделён от «снапшота вообще» намеренно: `null` значит «в UI показывать
 * нечего», а не «сервер не ответил». Второй случай виден по [delayMs], который
 * растёт до idle-паузы, и по неизменному [knownMsgs].
 */
internal data class ChatPollDecision(
    val state: ChatPollState,
    /** Что поставить в состояние Compose. `null` — содержимое не изменилось. */
    val publish: ChatSnapshot?,
    /** Плашка причины, которую надо держать в UI (защёлка). */
    val latch: ChatNotice?,
    val abort: ChatAbort?,
    /** Счётчик завершённых ответов для UI. */
    val knownMsgs: Int,
    /** Индекс последнего завершённого ответа, если базовая линия снимается сейчас. */
    val baselineRendered: Int?,
    /** Пауза до следующего тика. */
    val delayMs: Long,
)

/**
 * Один тик опроса — чистая функция от прошлого состояния, показанного снапшота и
 * свежего ответа сервера.
 *
 * [shown] — то, что сейчас в UI (нужно для дельты и для «был ли прогресс»);
 * [fresh] — `null`, если сервер не ответил.
 *
 * Порядок важен и перенесён дословно из Composable:
 * 1. прогресс считается по СЫРОМУ снапшоту — иначе зависание не сбросится;
 * 2. зависание применяется к снапшоту до бюджета повторов — плашка «Нет ответа»
 *    должна попасть в тот же кадр, что и снятый спиннер;
 * 3. защёлка сравнивается до записи нового значения, иначе сравнение всегда даёт
 *    «не изменилось» и UI молча пропускает перерисовку;
 * 4. адаптивная пауза считается по уже принятому решению — активность после
 *    публикации сбрасывает счётчик стабильных тиков.
 */
internal fun advancePoll(
    previous: ChatPollState,
    shown: ChatSnapshot?,
    fresh: ChatSnapshot?,
    now: Long,
): ChatPollDecision {
    if (fresh == null) return noAnswerTick(previous)

    val stalled = stallTick(previous, shown, fresh, now)
    val retry =
        advanceRetry(
            previous = previous.retry,
            seen = stalled.snap.notice,
            turnFinished = !stalled.snap.thinking,
            now = now,
        )
    val latchAfter = retry.state.latch
    val publish =
        if (snapshotChanged(shown, stalled.snap, previous.retry.latch, latchAfter)) {
            stalled.snap
        } else {
            null
        }
    val known = knownMsgsAfter(previous.knownMsgs, fresh)
    val rounds = nextStableRounds(previous.stableRounds, active = fresh.thinking || publish != null)

    return ChatPollDecision(
        state =
            previous.copy(
                stall = stalled.state,
                retry = retry.state,
                stableRounds = rounds,
                knownMsgs = known,
                baselineDone = true,
            ),
        publish = publish,
        latch = latchAfter,
        abort = abortFor(retry, stalled.snap),
        knownMsgs = known,
        baselineRendered = if (previous.baselineDone) null else lastCompletedIndex(fresh),
        delayMs = pollDelayMs(rounds),
    )
}

/**
 * Сервер не ответил: показывать нечего, счётчики не двигаем. Пауза растёт — это и
 * есть защита от «молотим в пустоту» при лежащем serve.
 */
private fun noAnswerTick(previous: ChatPollState): ChatPollDecision {
    val rounds = nextStableRounds(previous.stableRounds, active = false)
    return ChatPollDecision(
        state = previous.copy(stableRounds = rounds),
        publish = null,
        latch = previous.retry.latch,
        abort = null,
        knownMsgs = previous.knownMsgs,
        baselineRendered = null,
        delayMs = pollDelayMs(rounds),
    )
}

/** Снапшот с пометкой зависания и новое состояние меток — они обновляются одним решением. */
private data class StalledTick(
    val snap: ChatSnapshot,
    val state: StallState,
)

private fun stallTick(
    previous: ChatPollState,
    shown: ChatSnapshot?,
    fresh: ChatSnapshot,
    now: Long,
): StalledTick {
    val verdict = advanceStall(previous.stall, progressMade(shown, fresh), fresh, now)
    val snap =
        if (verdict.stalled) {
            fresh.copy(stalled = true, stalledDead = verdict.dead)
        } else {
            fresh
        }
    return StalledTick(snap, verdict.state)
}

/**
 * Прогресс хода: сменилась лента, живой инструмент или плашка причины.
 * Сравнивается с тем, что УЖЕ в UI, поэтому первый тик прогрессом не считается.
 */
private fun progressMade(
    shown: ChatSnapshot?,
    fresh: ChatSnapshot,
): Boolean =
    shown != null &&
        (
            shown.messages != fresh.messages ||
                shown.liveTool != fresh.liveTool ||
                shown.notice != fresh.notice
        )

/**
 * База уведомлений двигается в ОБЕ стороны: провалившийся ход перестаёт считаться
 * ответом (у него появляется диагностика), и счётчик уменьшается. Если бы база
 * только росла, следующий настоящий ответ не дал бы completed > knownMsgs —
 * и уведомление пропало бы.
 */
private fun knownMsgsAfter(
    previous: Int,
    fresh: ChatSnapshot,
): Int {
    val completed = completedAnswers(fresh)
    return when {
        completed == previous -> previous
        completed > previous || fresh.messages.isNotEmpty() -> completed
        else -> previous
    }
}

private fun abortFor(
    retry: RetryVerdict,
    decided: ChatSnapshot,
): ChatAbort? =
    if (retry.abort) {
        decided.activeId?.let { ChatAbort(sessionId = it, attempt = retry.state.stop?.attempt) }
    } else {
        null
    }

/** Завершённый ответ: ассистент, непустой текст, без диагностики отказа. */
private fun completedAnswers(snap: ChatSnapshot): Int =
    snap.messages.count { it.role == "assistant" && it.text.isNotBlank() && it.notice == null }

/** Индекс последнего завершённого ответа; -1, если ответов нет. */
private fun lastCompletedIndex(snap: ChatSnapshot): Int =
    snap.messages.indexOfLast { it.role == "assistant" && it.text.isNotBlank() && it.notice == null }
