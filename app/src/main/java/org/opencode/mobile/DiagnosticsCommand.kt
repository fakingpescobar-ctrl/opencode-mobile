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

    private val focusState = MutableStateFlow<FocusRequest?>(null)
    private var focusSeq = 0L

    /**
     * Просьба показать секцию: какую искать, раскрывать ли её и номер попытки.
     *
     * `seq` нужен, чтобы повторная просьба про ту же секцию считалась новым
     * действием: `LaunchedEffect` в секции ключуется номером, а не текстом —
     * иначе второй вызов после ручной прокрутки не сработал бы, ключ не
     * изменился бы.
     */
    data class FocusRequest(
        val section: String,
        val expand: Boolean,
        val seq: Long,
    )

    /**
     * Последняя просьба показать секцию; `null` — просить ещё не просили.
     *
     * Секция сверяет её со своим заголовком сама: список секций живёт в
     * композабле, и перечислять их здесь значило бы держать вторую копию
     * заголовков, которая однажды разойдётся с первой.
     */
    val focusRequest: StateFlow<FocusRequest?> = focusState.asStateFlow()

    /** Номер последней ПРИМЕНЁННОЙ просьбы; 0 — ни одна ещё не применялась. */
    private var appliedFocusSeq = 0L

    /**
     * Последняя просьба, которую секция реально применила; `null` — ни одна.
     *
     * Отличается от [focusRequest] смыслом: там «что просили», здесь «что
     * приняли». Мост отдаёт это поле отдельным readback-запросом, чтобы агент
     * не выдавал «просьба принята» за «секция показана» (issue #1, совет из
     * Moltbook: verify by read-back, never by response shape). Видимость на
     * экране всё равно доказывает только кадр.
     */
    private val appliedFocusState = MutableStateFlow<FocusRequest?>(null)
    val appliedFocus: StateFlow<FocusRequest?> = appliedFocusState.asStateFlow()

    /**
     * Забрать просьбу под свою секцию: `true` — эта просьба ещё не применялась.
     *
     * Зачем: [focusRequest] живёт в синглтоне и переживает закрытие экрана.
     * `LaunchedEffect(focus?.seq)` в секции перезапускается при каждой новой
     * композиции, поэтому без этой отметки последняя просьба срабатывала снова
     * при каждом открытии диагностики и раскрывала свою секцию. Так была
     * раскрыта «Журнал событий» (17.11): стартовое раскрытие ей никто не
     * просил — сработала старая просьба с прошлого раза.
     *
     * Отметка ставится ПОСЛЕ сверки заголовка (это делает вызывающая секция):
     * иначе первая же секция съедала бы просьбу, адресованную другой.
     */
    fun claimFocus(request: FocusRequest): Boolean {
        if (request.seq <= appliedFocusSeq) return false
        appliedFocusSeq = request.seq
        appliedFocusState.value = request
        return true
    }

    /**
     * Попросить показать секцию, в заголовке которой есть [section].
     *
     * Возвращённая просьба — не доказательство, что секция нашлась: экран
     * собирается в другом потоке, и заголовок сверяется уже там. Доказательство
     * того, что секция видна, — кадр экрана.
     */
    fun focusSection(
        section: String,
        expand: Boolean,
    ): FocusRequest {
        val request = FocusRequest(section.trim(), expand, ++focusSeq)
        focusState.value = request
        return request
    }
}
