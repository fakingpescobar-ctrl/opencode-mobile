package org.opencode.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.opencode.mobile.ui.MoltbookIndicator

// Панели шапки. Строкой, а не enum: открыта всегда максимум одна, и живёт она в
// одной переменной activePanel.
internal const val PANEL_MCP = "mcp"
internal const val PANEL_MOLTBOOK = "moltbook"
internal const val PANEL_COLOR = "color"
internal const val PANEL_FONT = "font"
internal const val PANEL_SETTINGS = "settings"
internal const val PANEL_STT = "stt"

// testTag-метки полос: якоря для проверки геометрии по дампу uiautomator.
private const val TAG_CTX_GAUGE = "ctx_gauge"
private const val TAG_ZEN_METER = "zen_meter"

/**
 * Что показывает шапка: связь с сервером, счётчики, какая панель открыта и сколько
 * занято контекста и квоты.
 */
internal data class ChatHeaderState(
    val reachable: Boolean?,
    val pingMs: Long,
    val rejected: Boolean,
    val mcpConnected: Int,
    val mcpTotal: Int,
    val moltbookAwaiting: Int,
    val activePanel: String?,
    val modelColor: Color,
    val creatingSession: Boolean,
    val diagnostics: Boolean,
    val contextTokens: Long,
    val zenUsed: Int,
    val zenExact: Boolean,
    val appearance: ChatAppearance,
)

/** Куда шапка отдаёт нажатия. */
internal class ChatHeaderActions(
    val onTogglePanel: (String) -> Unit,
    val onDiagnostics: () -> Unit,
    val onNewSession: () -> Unit,
    val onClearAll: () -> Unit,
)

// Шапка чата: строка статуса с иконками, под ней полоса контекста и расход
// дневной квоты. Панели, которые эти иконки открывают, лежат отдельно
// (ChatPanels/VoicePanels) — здесь только то, что видно всегда.
@Composable
internal fun ChatHeader(
    state: ChatHeaderState,
    actions: ChatHeaderActions,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Статус связи с сервером и пинг: «Online 42ms» зелёным, «Offline»
        // красным. Отдельная от ContextGauge строка — индикатор занятости
        // контекста уехал ниже, чтобы шапка не расползалась: раньше круг
        // занимал всю свободную ширину и отжимал индикаторы вправо.
        QuotaBadge(
            reachable = state.reachable,
            pingMs = state.pingMs,
            rejected = state.rejected,
            modifier = Modifier.weight(1f),
        )
        Indicators(state, actions)
        AppearanceIcons(state, actions)
        ServiceIcons(state, actions)
    }
    GaugeRows(state)
}

// Индикаторы подключённого: MCP-серверы и Moltbook.
@Composable
private fun Indicators(
    state: ChatHeaderState,
    actions: ChatHeaderActions,
) {
    // «N MCP» + мигающая точка. Зелёный — все N подключённых серверов работают;
    // красный — какой-то из них не работает (или их нет вовсе).
    MCPIndicator(
        connected = state.mcpConnected,
        total = state.mcpTotal,
        onClick = { actions.onTogglePanel(PANEL_MCP) },
        modifier = Modifier.padding(start = 4.dp),
    )
    // «М» — Moltbook: автономный агент. Буква с бейджем неотвеченных вместо
    // иконки намеренно: это не сервис, а сам агент, и цифра на нём — не
    // «сколько серверов сломано», а «кого мы ещё не ответили».
    MoltbookIndicator(
        awaiting = state.moltbookAwaiting,
        active = state.activePanel == PANEL_MOLTBOOK,
        onClick = { actions.onTogglePanel(PANEL_MOLTBOOK) },
    )
}

// Оформление ответов модели: цвет и шрифт. Иконка цвета тонируется ТЕКУЩИМ
// выбранным цветом, иконка шрифта — им же, но только когда панель открыта.
@Composable
private fun AppearanceIcons(
    state: ChatHeaderState,
    actions: ChatHeaderActions,
) {
    HeaderIcon(
        icon = Icons.Filled.InvertColors,
        description = "Цвет ответов модели",
        tint = state.modelColor,
        active = state.activePanel == PANEL_COLOR,
        onClick = { actions.onTogglePanel(PANEL_COLOR) },
    )
    HeaderIcon(
        icon = Icons.Filled.TextFormat,
        description = "Шрифт ответов модели",
        tint = if (state.activePanel == PANEL_FONT) state.modelColor else Color(0xFF8A8A8A),
        active = state.activePanel == PANEL_FONT,
        onClick = { actions.onTogglePanel(PANEL_FONT) },
    )
}

// Служебные: настройки гейджа, голосовой ввод, диагностика, новая сессия.
@Composable
private fun ServiceIcons(
    state: ChatHeaderState,
    actions: ChatHeaderActions,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    HeaderIcon(
        icon = Icons.Filled.Settings,
        description = "Настройки гейджа",
        tint = if (state.activePanel == PANEL_SETTINGS) Color.White else Color(0xFFBDBDBD),
        active = state.activePanel == PANEL_SETTINGS,
        onClick = { actions.onTogglePanel(PANEL_SETTINGS) },
    )
    HeaderIcon(
        icon = Icons.Filled.Mic,
        description = "Настройки голосового ввода (STT)",
        tint = if (state.activePanel == PANEL_STT) Color.White else Color(0xFFBDBDBD),
        active = state.activePanel == PANEL_STT,
        onClick = { actions.onTogglePanel(PANEL_STT) },
    )
    // Диагностика: состояние сервера, STT-модели и хвост лога serve. Полноэкранный
    // оверлей рисуется поверх всего, поэтому клавиатуру и фокус прячем здесь:
    // корневой Surface сдвинут imePadding(), иначе оверлей «съехал» бы вверх.
    HeaderIcon(
        icon = Icons.Filled.Info,
        description = "Диагностика",
        tint = if (state.diagnostics) Color.White else Color(0xFFBDBDBD),
        active = state.diagnostics,
        onClick = {
            keyboard?.hide()
            focusManager.clearFocus()
            actions.onDiagnostics()
        },
    )
    // Тап — создать свежую сессию (старые остаются в списке); long-press —
    // «очистить все сессии» (диалог подтверждения).
    NewSessionIcon(
        tint = if (state.creatingSession) Color(0xFF7BD88F) else Color(0xFFBDBDBD),
        onClick = { actions.onNewSession() },
        onLongClick = { actions.onClearAll() },
    )
}

// Полоса заполнения контекста и расход дневной Zen-квоты. Обе — под шапкой, в её
// же стиле: в ряд иконок они не влезают и ломают выравнивание по центру.
@Composable
private fun GaugeRows(state: ChatHeaderState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ContextGauge(
            filled = state.contextTokens,
            limit = CONTEXT_LIMIT,
            shape = state.appearance.ctxShape,
            palette = state.appearance.palette,
            modifier = Modifier.weight(1f).testTag(TAG_CTX_GAUGE),
        )
    }
    // Зазор настраивается пользователем, чтобы полосы читались как блок, но не
    // слипались.
    ZenMeter(
        used = state.zenUsed,
        exact = state.zenExact,
        shape = state.appearance.zenShape,
        palette = state.appearance.palette,
        modifier = Modifier.padding(top = state.appearance.barGap.dp).testTag(TAG_ZEN_METER),
    )
}

// Иконка шапки. Один и тот же размер, зазор и подсветка открытой панели — шесть
// копий этих девяти строк расходились бы при любой правке.
@Composable
private fun HeaderIcon(
    icon: ImageVector,
    description: String,
    tint: Color,
    active: Boolean,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = tint,
        modifier = iconModifier(active).clickable(onClick = onClick).padding(3.dp),
    )
}

// «Новая сессия» — единственная иконка с двумя действиями, поэтому отдельно:
// combinedClickable вместо clickable, и подсветки открытой панели у неё нет.
@Composable
private fun NewSessionIcon(
    tint: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Icon(
        imageVector = Icons.Filled.Add,
        contentDescription = "Новая сессия (long-press — очистить все сессии)",
        tint = tint,
        modifier =
            iconModifier(active = false)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(3.dp),
    )
}

// @Composable не ради состояния, а ради того же правила detekt: dp и цвет внутри
// вёрстки — значения дизайна, а не забытая математика.
@Composable
private fun iconModifier(active: Boolean): Modifier =
    Modifier
        .padding(start = 4.dp)
        .size(22.dp)
        .clip(CircleShape)
        .background(if (active) Color(0xFF3A3A3A) else Color.Transparent)
