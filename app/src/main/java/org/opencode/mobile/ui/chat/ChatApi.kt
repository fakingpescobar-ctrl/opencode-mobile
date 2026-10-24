package org.opencode.mobile.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.opencode.mobile.server.ApiAsk
import org.opencode.mobile.server.ApiMcp
import org.opencode.mobile.server.ApiPaths
import org.opencode.mobile.server.ApiShape
import org.opencode.mobile.server.ApiStatus
import org.opencode.mobile.server.AskCard
import org.opencode.mobile.server.LocalOpenCodeClient
import org.opencode.mobile.server.MemoryMcp
import org.opencode.mobile.server.YnisonMcp
import org.opencode.mobile.ui.ChatMsg
import org.opencode.mobile.ui.ChatNotice
import org.opencode.mobile.ui.ChatSnapshot
import org.opencode.mobile.ui.ChatTool
import org.opencode.mobile.ui.McpInfo
import org.opencode.mobile.ui.noticeFromStatus

private const val MAX_SHOWN = 120

// Контекст-лимит активной модели (входные токены) — порог, при котором opencode
// начнёт компакт. big-pickle (opencode free) имеет окно 200000 токенов.
// Если сменить модель с другим окном — поправить здесь. Круговой индикатор в
// шапке чата показывает current/limit зрительно.
internal const val CONTEXT_LIMIT = 200_000L

// Наносекунды в миллисекундах: замер пинга отдаёт наносекунды, в UI нужны мс.
private const val NS_PER_MS = 1_000_000L

// Лёгкий кэш MCP-статуса: /mcp меняется редко (только подключение/отключение
// серверов), но считывается каждый поллинг (~2.5 раза/с). Чтобы убрать этот
// HTTP-запрос из большинства опросов — кэшируем сырой JSON на короткое время.
// При просрочке фонем его на следующем поллинге. Индикатор «N MCP» обновится
// с задержкой ≤3с — некритично.
private const val MCP_CACHE_MS = 3_000L

/** Сколько символов текста реплики и сколько — причины отказа попадает в отладочную строку. */
private const val DIAG_TEXT_CHARS = 30
private const val DIAG_NOTICE_CHARS = 40

/** Оценка токенов по символам: ≈4 символа на токен и +15% на системный промпт/структуру. */
private const val CHARS_PER_TOKEN = 4L
private const val TOKEN_OVERHEAD_PCT = 115L
private const val PERCENT = 100L

private object McpCache {
    @Volatile var raw: String? = null

    @Volatile var at: Long = 0L
}

// Вернёт сырой JSON MCP из кэша, если он свежий (<MCP_CACHE_MS), иначе загрузит.
private fun getMcpCached(port: Int): String? {
    val now = System.currentTimeMillis()
    val cached = McpCache.raw
    if (cached != null && now - McpCache.at < MCP_CACHE_MS) return cached
    val fresh = LocalOpenCodeClient.get(port, ApiPaths.MCP)
    if (fresh != null) {
        McpCache.raw = fresh
        McpCache.at = now
    }
    return fresh
}

// Инкрементальный кэш ленты: самая дорогая операция поллинга — пересоздание
// списка ChatMsg + вычисление thinking/liveTool/ctxTokens из сырого JSON /message.
// При 400мс поллинге это происходит ~2.5 раза в секунду даже когда лента НЕ
// меняется (нет ответа, нет думания). Кэшируем результат парсинга, привязанный
// к (sessionId, hashCode(сырой /message)): если хэш тот же — лента битово
// идентична, переиспользуем готовые объекты и НЕ парсим снова. serverRevs держит
// отдельно, потому что заголовок/метки могут меняться независимо от ленты.
internal data class ChatParseResult(
    val messages: List<ChatMsg>,
    val thinking: Boolean,
    val liveTool: ChatTool?,
    val ask: List<AskCard>?,
    val contextTokens: Long,
)

/**
 * Кэш разбора ленты активной сессии. internal, а не private: идентификатором
 * сессии пользуется ещё и карточка маршрутизации моделей — ей нужно знать,
 * какой именно сессии менять модель на лету (`POST /session/{id}/model`).
 */
internal object ChatCache {
    @Volatile var sessionId: String? = null

    @Volatile var rawHash: Int = 0

    @Volatile var result: ChatParseResult? = null
}

/**
 * Забирает статус хода у сервера. Разбор - в [noticeFromStatus], он чистый и
 * покрыт юнит-тестом; здесь только поход по сети.
 */
private suspend fun fetchNotice(
    port: Int,
    sessionId: String,
): ChatNotice? = noticeFromStatus(ApiStatus.forSession(port, sessionId), sessionId)

// Разбор ответа /session — один плотный проход по JSON (info+parts+сессии+ask+MCP).
// Дробить его на куски пока не стоит: это самый обкатанный в бою код (stall-live-чип,
// контекст, ask-карточки), и любая нарезка меняет порядок вычислений.
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal suspend fun fetchChatSnapshot(port: Int?): ChatSnapshot? =
    withContext(Dispatchers.IO) {
        val p = port ?: return@withContext null
        try {
            // Замер пинга: оборачиваем первый запрос, который и так уходит каждый
            // тик. Отдельный /health-запрос не делаем — это лишний трафик и лишняя
            // нагрузка на сервер ради цифры в шапке.
            val pingStart = System.nanoTime()
            val sessionsBody = LocalOpenCodeClient.get(p, ApiPaths.SESSIONS)
            val pingMs = (System.nanoTime() - pingStart) / NS_PER_MS
            if (sessionsBody == null) {
                // Сервер не ответил. Снапшот строить не из чего, но сам факт
                // «недоступен» важен шапке — возвращаем пустой снапшот с
                // reachable=false, чтобы в шапке горело Offline, а не вечный
                // спиннер. Иначе отказ сервера выглядит как «сообщение не отправляется».
                return@withContext ChatSnapshot(
                    messages = emptyList(),
                    label = "нет связи с сервером",
                    activeId = null,
                    pingMs = pingMs,
                    reachable = false,
                )
            }
            val sessionsRaw = sessionsBody
            val sessions = ApiShape.sessions(sessionsRaw) ?: JSONArray()
            var bestId: String? = null
            var bestTs = -1L
            var bestModelId = ""
            for (i in 0 until sessions.length()) {
                val s = sessions.getJSONObject(i)
                val t = s.optJSONObject("time")?.optLong("updated") ?: -1L
                if (t > bestTs) {
                    bestTs = t
                    bestId = s.optString("id").takeIf(String::isNotBlank)
                    bestModelId = s.optJSONObject("model")?.optString("id", "") ?: ""
                }
            }
            if (bestId == null) {
                return@withContext ChatSnapshot(
                    emptyList(),
                    "нет сессий",
                    null,
                    pingMs = pingMs,
                    reachable = true,
                )
            }
            val activeId = bestId
            val label = titleOf(sessions, activeId)
            // Лента — единственный блокирующий вызов, тянуть больше нечего.
            val msgDeferred = async { LocalOpenCodeClient.get(p, ApiPaths.message(activeId)) }
            // Статус хода от сервера: квота/ретрай провайдера. Отдельным async,
            // чтобы не добавлять его задержку к ленте — оба вызова блокирующие.
            // Опрашиваем ТОЛЬКО пока ход незавершён: в покое статус не может дать
            // новое (проверено — на успешном ходе он и так пустой), а лишний запрос
            // на каждом тике поллинга это чистый оверхед. Решение берём по thinking
            // прошлого тика; на первом тике после старта кэша ещё нет, догоняем
            // следующим (разница — доли секунды, статус виден только как плашка).
            val noticeDeferred = async {
                if (ChatCache.result?.thinking == true) fetchNotice(p, activeId) else null
            }
            // Запросы, на которых сервер ждёт пользователя: команда под «ask» либо
            // вопрос агента. Отдельным async, чтобы лента не ждала этот вызов.
            // Опрос всегда, а не по thinking: ждущий запрос держит ход открытым,
            // но thinking по ленте к этому моменту уже снят.
            val askDeferred = async { ApiAsk.askPending(p, activeId) }
            // MCP-серверы: GET /mcp → Record<name, McpServer{name,enabled,status,...}> (иначе пустой {}).
            // Читаем из кэша (обновляется раз в MCP_CACHE_MS), чтобы не дёргать сервис каждый поллинг.
            // Подключёнными считаем тех, у кого status == "connected". Показываем «N MCP».
            var mcpConnected = 0
            var mcpTotal = 0
            val mcpServers = ArrayList<McpInfo>()
            // Счётчики инструментов спрашиваем у наших локальных серверов (кэш 30с):
            // числа в коде разъехались бы с реальностью после обновления приложения.
            // Музыка жива только при подключённом Яндексе; нет токена — её toolCounts
            // вернёт пустую карту, и строка в списке просто не получит счётчик.
            val mcpToolCounts = MemoryMcp.toolCounts() + YnisonMcp.toolCounts()
            try {
                val mcpRaw = getMcpCached(p)
                if (mcpRaw != null) {
                    val marr = ApiMcp.servers(mcpRaw)
                    if (marr != null) {
                        mcpTotal = marr.length()
                        for (i in 0 until marr.length()) {
                            val ms = marr.optJSONObject(i)
                            val name = ms?.optString("name", "") ?: ""
                            val status = ms?.optString("status", "") ?: ""
                            if (status == "connected") mcpConnected++
                            if (name.isNotBlank()) mcpServers.add(McpInfo(name, status, mcpToolCounts[name]))
                        }
                    }
                }
            } catch (_: Exception) {
                // MCP недоступен — покажем 0 красным
            }
            val notice = noticeDeferred.await()
            val ask = askDeferred.await()
            // Лента не пришла, но запрос разрешения виден — карточку всё равно
            // показываем: текст вопроса соберётся следующим тиком, когда лента
            // вернётся. null из /permission означает «не ответил», а не «нет».
            val askAlone = ask?.map { ApiAsk.card(it, JSONArray()) }
                ?: ChatCache.result?.takeIf { ChatCache.sessionId == activeId }?.ask
            val msgRaw =
                msgDeferred.await()
                    ?: return@withContext ChatSnapshot(
                        emptyList(),
                        label,
                        activeId,
                        pingMs = pingMs,
                        ask = askAlone,
                        reachable = true,
                        notice = notice,
                    )
            // Инкрементальный кэш: если за этой сессией тот же самый сырой JSON /message
            // (hash совпал) — лента и все производные (thinking/liveTool/ctxTokens)
            // гарантированно идентичны. Переиспользуем готовые объекты, НЕ пересоздавая
            // их: это убирает самое тяжёлое — полный JSON-парсинг и построение строк —
            // на каждый тик поллинга (2.5 раза/с), пока контент чата статичен.
            val rawHash = msgRaw.hashCode()
            val cached = ChatCache.result
            if (ChatCache.sessionId == activeId && ChatCache.rawHash == rawHash && cached != null) {
                val thinking = cached.thinking
                val liveTool = cached.liveTool
                val take = cached.messages
                // Запросы разрешений в кэш не кладутся: карточка — это побочный
                // опрос, и запрос может появиться, когда лента не менялась ни
                // байтом (инструмент уже в ленте, разрешение доехало позже).
                // Тогда кэш молчал бы, а ход висел бы. Пересобираем карточки
                // только когда набор id реально изменился — на каждом тике
                // поллинга парсить ленту заново нельзя.
                val freshIds = ask?.map { it.id }.orEmpty()
                val cachedIds = cached.ask?.map { it.ask.id }.orEmpty()
                val askCards =
                    when {
                        ask == null -> cached.ask
                        freshIds == cachedIds -> cached.ask
                        else -> ApiShape.messages(msgRaw)?.let { arr -> ask.map { ApiAsk.card(it, arr) } }
                    }
                val snap =
                    ChatSnapshot(
                        take,
                        "$label",
                        activeId,
                        thinking,
                        prettyModel(bestModelId),
                        liveTool = liveTool,
                        contextTokens = cached.contextTokens,
                        // Пинг/доступность НЕ из кэша: это свойство текущего запроса,
                        // а не ленты. Из кэша тянуть нельзя — иначе в шапке застынет
                        // первое измерение и «Offline» не появится, когда сервер ляжет.
                        pingMs = pingMs,
                        ask = askCards,
                        reachable = true,
                        // Расход Zen из кэша ленты: если сырой JSON тот же, то и число
                        // завершённых ответов то же — пересчитывать незачем. Новый
                        // ответ меняет /message, значит меняет hash и кэш протухает.
                        mcpConnected = mcpConnected,
                        mcpTotal = mcpTotal,
                        mcpServers = mcpServers,
                        notice = notice,
                    )
                android.util.Log.d(
                    "ChatOverlay",
                    "FETCH(cached) out=${take.size} thinking=$thinking " +
                        "notice=${notice != null} ask=${snap.ask?.size ?: 0} " +
                        "label=$label model=$bestModelId",
                )
                return@withContext snap
            }
            val arr = ApiShape.messages(msgRaw) ?: JSONArray()
            // Текст вопроса лежит не в запросе (там resources:["*"]), а в части
            // ленты с тем же id вызова. Склеиваем здесь, где лента ещё в JSON.
            // null здесь означает «/permission не ответил», а не «запросов нет»:
            // прятать карточку на таком тике нельзя, иначе вопрос останется без
            // способа ответить. Поэтому при непрочитанном ответе держим прошлую.
            val previousAsk = cached?.takeIf { ChatCache.sessionId == activeId }?.ask
            val askCards = ask?.map { ApiAsk.card(it, arr) } ?: previousAsk
            val out = ArrayList<ChatMsg>(arr.length())
            // Состояние последнего сырого сообщения нужно отдельно от видимых строк:
            // assistant с одним tool-чатом и step-finish скрывается из ленты ниже, но всё
            // равно завершает ответ. Без этих полей последней видимой строкой остался бы
            // user, и UI после Stop продолжал бы показывать «Модель думает…».
            var latestRole: String? = null
            var latestActivity = false
            var latestFinish = false
            // Ход с причиной отказа закончен по определению: ни step-finish, ни
            // time.completed в v2 у него не бывает (finish:"error" это не завершение).
            // Без этого признака лента с провалившимся ответом светила бы «генерируется…»
            // до общего таймаута.
            var latestFailed = false
            var latestCompleted = false
            // Живой инструмент, вызываемый моделью в ТЕКУЩЕМ ответе. Накопительный по
            // assistant-шагам одного ответа (сбрасывается на новом user-сообщении), поэтому
            // чип НЕ моргает между tool-вызовами. На шагах с тулом запоминаем его; пустой
            // промежуточный шаг сохраняет предыдущий тул. Из UI показывается только пока
            // думает (thinking=true) — после завершения ответа гаснет.
            var lastTool: ChatTool? = null
            // ЧЕСТНАЯ оценка активного контекста сессии: сумма символов всех текущих
            // частей (text + tool output/input + reasoning). `tokens.input` из /session
            // кумулятивный (включает уже компактированные хвосты), поэтому для индикатора
            // считаем именно активное окно: символы -> токены (≈ /4) + overhead (×1.15).
            var ctxChars = 0L
            for (i in 0 until arr.length()) {
                val msg = arr.getJSONObject(i)
                val info = msg.optJSONObject("info")
                val parts = msg.optJSONArray("parts")
                if (info == null || parts == null) continue
                val role = info.optString("role", "system")
                val messageCompleted =
                    info.optJSONObject("time")?.optLong("completed", 0L)?.let { it > 0L } == true
                val sb = StringBuilder()
                // Причина отказа идёт отдельным полем, а не в текст: озвучка читает
                // только text, и диагностику «модель устарела» не должно произносить
                // вслух; счётчик ответов тоже смотрит на text. Здесь hasText всё
                // равно ставится — иначе ход с одной лишь причиной был бы выброшен
                // как пустой завершённый.
                val notice = StringBuilder()
                var hasText = false
                var finish = false
                var activity = false
                // Новый ВОПРОС (user-сообщение) — сбрасываем живой тул: начинается новый
                // ответ модели, чип должен отражать только инструменты ЭТОГО ответа.
                // На assistant-шагах НЕ сбрасываем: между tool-вызовами одного ответа есть
                // пустые промежуточные шаги, и live-чип не должен моргать (bash→пусто→bash).
                if (role == "user") {
                    lastTool = null
                }
                for (ph in 0 until parts.length()) {
                    val part = parts.getJSONObject(ph)
                    val type = part.optString("type", "")
                    // Часть типа error — это текст провалившийся хода (см.
                    // ApiShape.roleFor): рисуем как обычный текст, иначе ход
                    // выглядел бы пустым и был бы молча отфильтрован.
                    if (type == "text") {
                        hasText = true
                        val t = part.optString("text", "")
                        ctxChars += t.length
                        if (sb.isNotEmpty() && t.isNotEmpty()) sb.append("\n")
                        sb.append(t)
                    } else if (type == ApiShape.ERROR_PART) {
                        hasText = true
                        val t = part.optString("text", "")
                        ctxChars += t.length
                        if (notice.isNotEmpty() && t.isNotEmpty()) notice.append("\n")
                        notice.append(t)
                    } else if (type == "step-finish") {
                        finish = true
                    } else if (type == "tool") {
                        activity = true
                        ctxChars += (part.optJSONObject("state")?.optString("output", "") ?: "").length
                        // Запомнить имя инструмента + краткое действие (команда/запрос).
                        val st = part.optJSONObject("state")
                        val input = st?.optJSONObject("input")
                        val title = st?.optString("title", "") ?: ""
                        val cmd = input?.optString("command", "") ?: ""
                        val qry = input?.optString("query", "") ?: ""
                        val det =
                            when {
                                cmd.isNotBlank() -> cmd
                                qry.isNotBlank() -> qry
                                title.isNotBlank() -> title
                                else -> ""
                            }
                        lastTool = ChatTool(part.optString("tool", ""), det)
                    } else if (type == "step-start" || type == "reasoning") {
                        activity = true
                        ctxChars += part.optString("text", "").length
                    }
                }
                latestRole = role
                latestActivity = activity
                latestFinish = finish
                latestFailed = notice.isNotEmpty()
                latestCompleted = messageCompleted
                // Завершённый tool-only шаг (step-start->tool...->step-finish без text)
                // не должен отображаться как «… генерируется …» — это не зависание,
                // а просто шаг без текста. Фильтруем его из ленты. ДУМАЮЩИЙ assistant
                // (без step-finish) остаётся, чтобы UI показал «генерируется».
                val hiddenToolStep = role == "assistant" && !hasText && finish
                if (!hiddenToolStep) {
                    out.add(
                        ChatMsg(
                            role = role,
                            text = sb.toString(),
                            notice = notice.toString().ifBlank { null },
                            id = info.optString("id"),
                        ),
                    )
                }
            }
            val take = if (out.size > MAX_SHOWN) out.subList(out.size - MAX_SHOWN, out.size) else out
            val last = out.lastOrNull()
            // «Думает» = последнее сырое сообщение открывает новый ответ. У завершённого
            // assistant проверяем даже скрытый tool-only шаг: после Stop opencode может
            // завершить его без step-finish, но с info.time.completed.
            // Провал — тоже конец хода, даже если opencode не проставил ни того,
            // ни другого: иначе «генерируется…» горит до общего таймаута.
            val thinking =
                when (latestRole) {
                    null -> false
                    "user" -> true
                    "assistant" -> !latestFinish && !latestCompleted && !latestFailed
                    else -> false
                }

            // Live-чип показываем только пока модель ещё работает (thinking). Когда она
            // закончила (дала финальный ответ) — lastTool не показываем как «текущее».
            val liveTool = if (thinking) lastTool else null
            // Токены оцениваем через суммарную длину активных частей сессии (ctxChars):
            // ≈ символов/4 (ok для кода/HTML/русского в среднем), плюс небольшой
            // оверхед на системный промпт/структуру (×1.15). Это и есть ТЕКУЩИЙ
            // активный контекст, а не кумулятивный tokens.input.
            val ctxTokens = ctxChars / CHARS_PER_TOKEN * TOKEN_OVERHEAD_PCT / PERCENT
            // Записываем кэш ТОЛЬКО после успешного полного парсинга.
            ChatCache.sessionId = activeId
            ChatCache.rawHash = rawHash
            ChatCache.result =
                ChatParseResult(
                    take,
                    thinking,
                    liveTool,
                    ask = askCards,
                    ctxTokens,
                )
            val snap =
                ChatSnapshot(
                    take,
                    "$label",
                    activeId,
                    thinking,
                    prettyModel(bestModelId),
                    liveTool = liveTool,
                    contextTokens = ctxTokens,
                    pingMs = pingMs,
                    ask = askCards,
                    reachable = true,
                    // Расход Zen — из БАЗЫ СЕРВЕРА, который обновляется раз в
                    // ZEN_REFRESH_MS. Лента тут не годится: она покрывает только
                    // активную сессию, а день мог начаться в нескольких сессиях.
                    mcpConnected = mcpConnected,
                    mcpTotal = mcpTotal,
                    mcpServers = mcpServers,
                    notice = notice,
                )
            // notice тоже печатаем: он уехал из text, и без него в логе не видно, что
// причина отказа вообще доехала до экрана, а не потерялась по дороге.
            val lastDiag = last?.let {
                "role=${it.role} text='${it.text.take(DIAG_TEXT_CHARS)}' " +
                    "notice='${it.notice?.take(DIAG_NOTICE_CHARS) ?: ""}'"
            } ?: "null"
            android.util.Log.d(
                "ChatOverlay",
                "FETCH parse out=${out.size} take=${take.size} thinking=$thinking " +
                    "ask=${snap.ask?.size ?: 0} label=$label " +
                    "model=$bestModelId liveTool=${liveTool?.name} LAST=[$lastDiag] " +
                    "latestRole=$latestRole latestAct=$latestActivity latestFin=$latestFinish " +
                    "latestCompleted=$latestCompleted",
            )
            snap
        } catch (e: InterruptedException) {
            // Отмену корутины глотать нельзя — иначе экран не узнает, что опрос снят.
            throw e
        } catch (_: Exception) {
            // Любая другая ошибка одного опроса (битый JSON, сеть, гонка с остановкой
            // сервера) не должна ронять весь цикл — просто пропускаем этот тик.
            null
        }
    }

// «big-pickle» → «Big Pickle»; пусто/нет — «Модель»
private fun prettyModel(id: String?): String {
    val words =
        id
            ?.trim()
            ?.split('-', '_', '.', '/', ':')
            ?.filter { it.isNotBlank() }
            .orEmpty()
    return words
        .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercaseChar() } }
        .ifBlank { "Модель" }
}

private fun titleOf(
    sessions: JSONArray,
    id: String,
): String {
    for (i in 0 until sessions.length()) {
        val s = sessions.getJSONObject(i)
        if (s.optString("id") == id) {
            val t = s.optString("title", "")
            return if (t.isBlank()) "сессия" else t
        }
    }
    return "сессия"
}
