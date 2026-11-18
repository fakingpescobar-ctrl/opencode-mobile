package org.opencode.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Какая панель шапки чата открыта — одна точка правды для пальца и для моста.
 *
 * Зачем объект, а не `remember` в оверлее: панель должна открываться не только
 * тапом по иконке, но и мостом приложения (`POST /v1/ui/panel`). Оболочке агента
 * тап недоступен: `input keyevent` отбивается `SecurityException: Injecting input
 * events requires the INJECT_EVENTS permission`, а у службы доступности есть
 * только снимок экрана. Пока состояния не было, агент просил человека нажать
 * «озвучку» руками — и разговор шёл о том, кто должен был нажать.
 *
 * Мост и оверлей живут в одном процессе, поэтому отдельный intent, broadcast или
 * deep link здесь были бы лишним слоем: достаточно общего состояния. Оно
 * переживает рекомпозицию, так что закрытие панели человеком не «отскакивает»
 * обратно на следующей перерисовке.
 *
 * Android-зависимостей нет намеренно: это чистое состояние, и его правила
 * проверяются обычным unit-тестом.
 */
object ChatUiCommand {
    /**
     * Имена панелей, которые понимает оверлей.
     *
     * Совпадают с `PANEL_*` в ChatHeader, кроме `tts`: панель озвучки живёт в той
     * же карточке, что и настройки STT (её рисует `showSttSettings`), но агенту
     * естественнее просить «tts», а не помнить устройство UI.
     */
    val PANELS = setOf("mcp", "color", "font", "settings", "stt", "tts")

    /** Просьба открыть панель; `panel = null` — закрыть текущую. */
    data class Request(
        val panel: String?,
        val seq: Long,
    )

    private val requestState = MutableStateFlow<Request?>(null)

    /** Что просили открыть; `null` — не просили ещё ни разу. */
    val request: StateFlow<Request?> = requestState.asStateFlow()

    private val appliedState = MutableStateFlow<Request?>(null)

    /**
     * Что оверлей реально применил.
     *
     * Отличается от [request] смыслом: там «что просили», здесь «что приняли».
     * Мост отдаёт это поле отдельным GET-запросом, чтобы агент не выдавал
     * «просьба записана» за «панель открыта»: видимость на экране доказывает
     * только кадр, а применение просьбы — этот readback.
     */
    val applied: StateFlow<Request?> = appliedState.asStateFlow()

    private var seq = 0L
    private var appliedSeq = 0L

    /**
     * Попросить открыть панель ([panel]) или закрыть текущую (`null`).
     *
     * Возвращённая просьба — не доказательство, что панель открылась: композиция
     * собирается в другом потоке. `seq` растёт на каждой просьбе, иначе повтор
     * «открой tts» после ручного закрытия выглядел бы тем же самым действием и
     * `LaunchedEffect` не перезапустился бы.
     */
    @Synchronized
    fun open(panel: String?): Request {
        val normalized = panel?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        require(normalized == null || normalized in PANELS) { "unknown panel: $panel" }
        val next = Request(normalized, ++seq)
        requestState.value = next
        return next
    }

    /**
     * Забрать просьбу под оверлей: `true` — эта просьба ещё не применялась.
     *
     * Отметка ставится ПОСЛЕ применения панели: иначе первая же композиция
     * съедала бы просьбу, ничего не показав.
     */
    @Synchronized
    fun claim(request: Request): Boolean {
        if (request.seq <= appliedSeq) return false
        appliedSeq = request.seq
        appliedState.value = request
        return true
    }
}
