package org.opencode.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Одна точка правды для экрана диагностики: показан он или нет.
 *
 * Зачем объект, а не `remember` в оверлее: экран должен открываться не только
 * пальцем по иконке «i», но и мостом приложения (`POST /v1/ui/diagnostics`).
 * Мост и оверлей живут в одном процессе, поэтому отдельный intent, broadcast или
 * deep link здесь были бы лишним слоем — достаточно общего состояния. Оно
 * переживает рекомпозицию, так что закрытие экрана человеком не «отскакивает»
 * обратно на следующей перерисовке.
 *
 * Android-зависимостей нет намеренно: это чистое состояние, и его правила
 * проверяются обычным unit-тестом.
 */
object DiagnosticsCommand {
    private val state = MutableStateFlow(false)

    /** Текущее состояние: `true` — экран диагностики показан. */
    val visible: StateFlow<Boolean> = state.asStateFlow()

    /**
     * Показать (`true`) или скрыть (`false`) экран; возвращает состояние после вызова.
     *
     * Возврат не «для красоты»: мост отвечает агенту тем же значением, и агент
     * видит, что команда дошла до состояния, а не только до HTTP-обработчика.
     */
    fun setOpen(open: Boolean): Boolean {
        state.value = open
        return state.value
    }

    /** Переключить — это действие иконки «i» в шапке чата. */
    fun toggle(): Boolean = setOpen(!state.value)
}
