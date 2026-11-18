package org.opencode.mobile.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Действие над состоянием runtime. Все переходы идут через
 * [RuntimeStateHolder.dispatch] — единая точка сериализации.
 */
internal sealed interface RuntimeAction {
    /** Старт цикла: флаг running поднят, запрос рестарта снят, счётчик обнулён. */
    data object Begin : RuntimeAction

    /** Явный стоп от пользователя. */
    data object Stop : RuntimeAction

    /** Терминальный выход и finally: гасим флаг, даже если стоп не просили. */
    data object MarkStopped : RuntimeAction

    /** Мягкий рестарт: флаг перехватывает цикл в любой фазе. */
    data object Restart : RuntimeAction

    /** Снять флаг рестарта (цикл его обработал). */
    data object ConsumeRestart : RuntimeAction

    /** Атомарно обновить снимок. */
    data class Update(
        val transform: RuntimeState.() -> RuntimeState,
    ) : RuntimeAction
}

/**
 * Сериализованное состояние runtime-цикла (этап 4).
 *
 * Раньше `running`, `restartRequested` и `currentState` были тремя полями
 * [RuntimeManager], и писались из разных потоков: `requestStop`/`requestRestart`
 * приходят с main, цикл живёт в worker-корутине. Обновление
 * `currentState = currentState.transform()` — это read-modify-write без
 * синхронизации: два потока могли потерять одно из изменений, а `@Volatile`
 * на флагах не делал пару «флаг + снимок» согласованной.
 *
 * Здесь все переходы проходят через один монитор в [dispatch]: снимок и флаги
 * меняются вместе, а наблюдателям отдаётся [StateFlow] с последним
 * опубликованным значением. Внешний контракт сервиса не меняется — слушатель
 * получает тот же [RuntimeState], что и раньше.
 *
 * Слушатель вызывается под монитором: это гарантирует порядок публикаций
 * (иначе два потока могли бы уведомить в обратном порядке). `synchronized`
 * в JVM реентерабелен, поэтому слушателю не запрещено читать [state].
 */
internal class RuntimeStateHolder(
    initial: RuntimeState = RuntimeState(),
    private val listener: (RuntimeState) -> Unit = {},
) {
    private val lock = Any()
    private val mutable = MutableStateFlow(initial)

    /** Последний опубликованный снимок для подписчиков (UI, диагностика). */
    val state: StateFlow<RuntimeState> = mutable.asStateFlow()

    private var running = false
    private var restartRequested = false

    /** Текущий снимок без подписки. */
    fun snapshot(): RuntimeState = mutable.value

    /** Цикл жив (флаг не снят стопом или терминальным выходом). */
    val isRunning: Boolean get() = synchronized(lock) { running }

    /** Пора выходить из ожиданий: стоп или мягкий рестарт. */
    val isStopping: Boolean get() = synchronized(lock) { !running || restartRequested }

    /** Запрошен ли мягкий рестарт, без снятия флага. */
    val isRestartPending: Boolean get() = synchronized(lock) { restartRequested }

    /**
     * Единственная точка изменения. Возвращает `true`, если действие изменило
     * управляющий флаг: `Stop` — цикл был жив, `Restart`/`ConsumeRestart` —
     * флаг был поднят. `Begin`, `MarkStopped` и `Update` дают `false`.
     */
    fun dispatch(action: RuntimeAction): Boolean =
        synchronized(lock) {
            when (action) {
                RuntimeAction.Begin -> {
                    running = true
                    restartRequested = false
                    publishLocked { it.copy(restartCount = 0) }
                    true
                }
                RuntimeAction.Stop -> {
                    val wasRunning = running
                    running = false
                    wasRunning
                }
                RuntimeAction.MarkStopped -> {
                    running = false
                    false
                }
                RuntimeAction.Restart -> {
                    restartRequested = true
                    true
                }
                RuntimeAction.ConsumeRestart -> {
                    val pending = restartRequested
                    restartRequested = false
                    pending
                }
                is RuntimeAction.Update -> {
                    publishLocked(action.transform)
                    false
                }
            }
        }

    /** Вызывается только под [lock]: снимок и уведомление в одном порядке. */
    private fun publishLocked(transform: (RuntimeState) -> RuntimeState) {
        val next = transform(mutable.value)
        mutable.value = next
        listener(next)
    }
}
