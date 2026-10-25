package org.opencode.mobile.ui.chat

import org.opencode.mobile.ui.ChatNotice
import org.opencode.mobile.ui.ChatSnapshot

/**
 * Ставить ли свежий снапшот в состояние Compose.
 *
 * Поллинг идёт несколько раз в секунду, а лента в покое не меняется — поэтому
 * каждый тик пересобирает `ChatSnapshot`, но в UI его пускаем только когда есть
 * что показать. Иначе Compose пересобирал бы всю ленту на каждом тике.
 *
 * Набор полей — не «всё, что есть в снапшоте», а «то, что человек увидит».
 * Единственное намеренное исключение — `pingMs`: он меняется каждый тик, и его
 * сравнение вернуло бы ровно ту перерисовку ленты, ради устранения которой гейт
 * и написан. `contextTokens` не сравнивается потому, что это производное от
 * ленты: тот же JSON — те же токены.
 *
 * Всё остальное, что рисуется, сравнивать обязательно — иначе шапка замерзает.
 * Так уже было с MCP: статус обновлялся только вместе с лентой, и в простое
 * шапка навсегда показывала «0 MCP» красным, хотя все серверы были подключены
 * (проверено на устройстве 25.10.2026: сервер отдавал три `connected`,
 * приложение рисовало ноль). По той же причине сравниваются `reachable`
 * (Online/Offline на пустом чате лента не меняется вовсе), `label` (имя сессии
 * в шапке) и `modelName` (переключение модели). Стоит это ничего: все они
 * меняются редко, а лента — постоянно.
 */
internal fun snapshotChanged(
    old: ChatSnapshot?,
    new: ChatSnapshot,
    latchBefore: ChatNotice?,
    latchAfter: ChatNotice?,
): Boolean =
    old == null ||
        feedChanged(old, new) ||
        headerChanged(old, new) ||
        // Защёлка: сервер может моргнуть статусом на один тик, а причина у
        // пользователя должна висеть, пока он её не закрыл.
        latchBefore != latchAfter

/** Лента и ход: из-за этого перерисовывается список сообщений. */
private fun feedChanged(
    old: ChatSnapshot,
    new: ChatSnapshot,
): Boolean =
    old.messages != new.messages ||
        old.thinking != new.thinking ||
        old.stalled != new.stalled ||
        old.stalledDead != new.stalledDead ||
        old.liveTool != new.liveTool ||
        old.notice != new.notice ||
        // ask обязателен: запрос разрешения может прийти позже той же tool-части,
        // что уже в ленте, и тогда ни лента, ни thinking, ни liveTool не меняются.
        // Без этого пункта карточка не доедет до UI и ход останется висеть.
        old.ask != new.ask

/**
 * Шапка: то, что видно над лентой. Меняется редко, поэтому сравнение бесплатное —
 * и именно поэтому пропускать его нельзя: пока лента стоит, шапка иначе замирает
 * на последнем известном значении.
 */
private fun headerChanged(
    old: ChatSnapshot,
    new: ChatSnapshot,
): Boolean =
    old.mcpConnected != new.mcpConnected ||
        old.mcpTotal != new.mcpTotal ||
        old.mcpServers != new.mcpServers ||
        // Online/Offline: на пустой сессии лента в обоих состояниях пустая, и без
        // этого пункта шапка застревала бы на последнем известном статусе.
        old.reachable != new.reachable ||
        old.label != new.label ||
        old.modelName != new.modelName
