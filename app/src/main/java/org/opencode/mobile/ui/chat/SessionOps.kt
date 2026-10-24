package org.opencode.mobile.ui.chat

import android.content.Context
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.opencode.mobile.server.ApiPaths
import org.opencode.mobile.server.ApiShape
import org.opencode.mobile.server.ApiStatus
import org.opencode.mobile.server.LocalOpenCodeClient
import org.opencode.mobile.server.ModelRoutingPrefs
import org.opencode.mobile.server.SessionModel
import org.opencode.mobile.ui.purgeAllowed

/**
 * Операции над сессиями локального сервера.
 *
 * Вынесено из `ChatOverlay.kt` без изменения поведения: тело функций перенесено
 * дословно. Всё здесь — блокирующий HTTP через [LocalOpenCodeClient].
 */

/**
 * Новая сессия сразу на выбранной модели.
 *
 * `POST /api/session` принимает `model` в теле (проверено по OpenAPI и на живом
 * 2.0.27), а переключение через `POST /api/session/{id}/model` есть только у уже
 * родившейся сессии. Поэтому модель выбирается здесь, на создании: так новая
 * сессия не зависит от того, успел ли сервер перечитать конфиг (он читает его
 * только при старте процесса).
 *
 * Отказ по модели НЕ ломает создание: если сервер её не принял (протухла,
 * выпала из каталога), повторяем пустым телом — сообщение должно уйти любой
 * ценой, а выбор модели это настройка, не условие работы чата. Худший случай
 * такой попытки — лишняя пустая сессия, их и так чистит [pruneEmptySessions].
 */
internal fun createSession(
    port: Int,
    context: Context,
): String? {
    val model = if (ModelRoutingPrefs.isEnabled(context)) ModelRoutingPrefs.current(context).heavy else null
    val withModel = LocalOpenCodeClient.post(port, ApiPaths.SESSIONS, SessionModel.createBody(model))
    return createdSessionId(withModel)
        ?: createdSessionId(LocalOpenCodeClient.post(port, ApiPaths.SESSIONS, SessionModel.EMPTY_BODY))
}

internal fun createdSessionId(body: String?): String? = body?.let { ApiShape.session(it)?.optString("id") }?.takeIf(String::isNotBlank)

/**
 * Прерывает текущую генерацию модели в сессии. opencode serve принимает
 * POST /session/{id}/abort (200 + "true"). Ответ приходит сразу, блокировать
 * нечего — это не долгий стрим.
 *
 * Осторожно, bool здесь обманчив: на несуществующей сессии тот же эндпоинт
 * отдаёт 200 + HTML (SPA-fallback на любой путь), то есть true бывает даже
 * когда ничего не прервалось. Проверять надо через [sessionRunning].
 */
internal fun abortSession(
    port: Int,
    sessionId: String,
): Boolean = LocalOpenCodeClient.post(port, ApiPaths.interrupt(sessionId), "") != null

/**
 * Есть ли запись сессии в карте занятости, то есть считает ли сервер её ход
 * незавершённым.
 *
 * Нужна потому, что по коду ответа abort узнать «прервалось ли» нельзя (см.
 * [abortSession]). Единственный честный признак — уехала ли запись из карты
 * статусов после abort.
 */
internal fun sessionRunning(
    port: Int,
    sessionId: String,
): Boolean {
    val raw = ApiStatus.forSession(port, sessionId) ?: return false
    return try {
        JSONObject(raw).has(sessionId)
    } catch (_: Exception) {
        false
    }
}

/**
 * Откат только что созданной сессии. Нужен потому, что создание и отправка -
 * одна операция: если отправка не ушла, сессия остаётся сиротой (одно событие
 * session.created, ноль сообщений) и засоряет список сессий. На живом сервере
 * DELETE /session/{id} отвечает 200 и сессия исчезает.
 */
internal fun deleteSession(
    port: Int,
    sessionId: String,
): Boolean = LocalOpenCodeClient.delete(port, ApiPaths.session(sessionId))

/**
 * Есть ли в сессии хоть одно сообщение. Разделяет «сервер не получил запрос»
 * (тогда сессию можно откатить) и «сервер записал, но ответил ошибкой»
 * (тогда откат съел бы чужое сообщение).
 */
internal fun sessionHasMessages(
    port: Int,
    sessionId: String,
): Boolean =
    try {
        val raw = LocalOpenCodeClient.get(port, ApiPaths.message(sessionId))
        raw != null && (ApiShape.messages(raw)?.length() ?: 0) > 0
    } catch (_: Exception) {
        // Не смогли узнать — считаем, что сохранилось: на неопределённости не стираем.
        true
    }

/**
 * Множество сессий, занятых сервером прямо сейчас (значения из карты
 * занятости). null — статус не прочился, и это НЕ то же самое, что
 * «никто не занят»: удалять сессии вслепую нельзя (см. [purgeAllowed]).
 */
internal fun busySessionIds(port: Int): Set<String>? =
    try {
        val raw = ApiStatus.record(port) ?: return null
        val obj = JSONObject(raw)
        val out = LinkedHashSet<String>()
        for (k in obj.keys()) {
            if (k.isNotBlank()) out.add(k)
        }
        out
    } catch (_: Exception) {
        null
    }

/**
 * Сколько раз переспрашиваем /session/status, прежде чем признать ход незаглушенным.
 */
private const val PURGE_CONFIRM_TRIES = 5

/**
 * Пауза между попытками подтвердить, что abort дождался сервера.
 */
private const val PURGE_CONFIRM_STEP_MS = 200L

/**
 * Остановить сессию и только потом удалить её.
 *
 * Прежний порядок (abort → delete, без паузы и без проверки) давал
 * FOREIGN KEY constraint failed: сервер держит сессию в памяти и продолжает
 * писать message/part уже после удаления строки. abort отвечает мгновенно, но
 * это не значит, что цикл записи остановился, поэтому здесь ждём ухода записи
 * из /session/status.
 *
 * Если статус недоступен или ход так и не ушёл — сессия остаётся. Это осознанный
 * размен: лишняя сессия в ленте её удалит сам позже, а удалённая насильно
 * ломает базу насмерть (см. [purgeAllowed]).
 */
internal suspend fun stopAndPurge(
    port: Int,
    sessionId: String,
) {
    if (sessionRunning(port, sessionId)) {
        abortSession(port, sessionId)
        var tries = 0
        while (sessionRunning(port, sessionId) && tries < PURGE_CONFIRM_TRIES) {
            delay(PURGE_CONFIRM_STEP_MS)
            tries++
        }
    }
    val reachable = ApiStatus.reachable(port)
    if (!purgeAllowed(reachable, sessionRunning(port, sessionId))) {
        android.util.Log.w(
            "ChatOverlay",
            "PURGE skipped session=$sessionId: ход не подтверждённо остановлен, удаление сломало бы внешние ключи",
        )
        return
    }
    deleteSession(port, sessionId)
}

/**
 * Подчистить сессии-сироты: созданные, но так и не использованные.
 *
 * Откуда они берутся: создание сессии и отправка первого сообщения — две
 * операции. Между ними остаётся окно, в котором сессия уже есть в списке, а
 * сообщений в ней ещё нет. Если отправка не ушла (сеть, сервер, неизвестная
 * модель), сессия навсегда остаётся пустой мусором в ленте.
 *
 * Удаляются ТОЛЬКО сессии без единого сообщения: потерять нечего. Сессии с
 * перепиской, текущая и любая busy не трогаются. Если список отдать не удалось
 * или у сессии не удалось прочитать сообщения — тоже не стираем.
 *
 * Отдельный запрос на каждую сессию: список обычно короткий, а лезть в
 * сообщения всех подряд ради пустых — лишняя работа на каждый чих.
 */
internal fun pruneEmptySessions(
    port: Int,
    keep: Set<String>,
) {
    val raw = LocalOpenCodeClient.get(port, ApiPaths.SESSIONS) ?: return
    val ids =
        try {
            val arr = ApiShape.sessions(raw) ?: JSONArray()
            (0 until arr.length())
                .mapNotNull { arr.getJSONObject(it).optString("id").takeIf(String::isNotBlank) }
        } catch (_: Exception) {
            // Список не разобрали — идти не по чему, но это не повод заглянуть
            // в pruneAllowed или логировать: ниже всё равно ничего не удалится.
            emptyList()
        }
    // Список busy берём ОДИН раз на весь проход: /session/status отдаёт объект,
    // где ключи — занятые сессии. Проверять надо обязательно: у busy-сессии
    // сообщений может быть ноль (генерация только началась), и без этой
    // проверки такая сессия попала бы под нож вместе с живым ответом.
    val busy = busySessionIds(port)
    if (busy == null) {
        // Статус не прочитали — значит, «занята ли сессия», мы не знаем.
        // Мусор в ленте дешевле, чем удаление бегущей сессии: см. [purgeAllowed].
        android.util.Log.w("ChatOverlay", "PRUNE skipped: /session/status недоступен")
        return
    }
    var removed = 0
    for (sid in ids) {
        // Текущую и те, что мы сами бережём, не трогаем.
        val isKept = sid in keep
        // Занятые сервером — не мусор, это идущая генерация.
        val isBusy = sid in busy
        if (isKept || isBusy || sessionHasMessages(port, sid)) continue
        if (deleteSession(port, sid)) {
            removed++
            android.util.Log.d("ChatOverlay", "подчистил пустую сессию $sid")
        }
    }
    if (removed > 0) {
        android.util.Log.i("ChatOverlay", "подчищено пустых сессий: $removed")
    }
}
