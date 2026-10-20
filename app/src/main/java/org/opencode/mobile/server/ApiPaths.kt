package org.opencode.mobile.server

/**
 * Пути локального opencode serve — единственной поддерживаемой версии.
 *
 * Зачем объект существует: в opencode 2.x пути уехали под префикс `/api/`, а
 * часть старых эндпоинтов удалена вовсе. Когда каждый вызов писал путь строкой
 * в месте использования, это было 9 файлов и 30+ мест, и смена версии означала
 * правку каждого по отдельности с риском пропустить одно.
 *
 * Версии 1.x здесь больше нет. Пока поддерживались обе, контракт был
 * интерфейсом с двумя реализациями и переключателем `ServerFlavor`: для
 * отката. Откат не случился — в APK лежит v2, собранная из этого же
 * репозитория, и вернуться на v1 без смены бинаря в `jniLibs` всё равно
 * нельзя. Поэтому переключатель удалён, а с ним и мёртвая ветка: любой
 * вызов, который раньше спрашивал «какая версия», теперь получает
 * единственно возможный ответ.
 *
 * Что здесь НЕ решается: форма тела и формы ответа. В v2 `POST /prompt`
 * ждёт `{"text":"…"}`, а не `{"parts":[…]}`, и `GET /api/session` завёрнут в
 * `{"data":[…]}`. Это работа этапа 4 плана `docs/MIGRATION-V2.md`, слой
 * `ApiShape`/`ApiStatus`. Здесь только имя пути.
 *
 * Проверено на живом 2.0.27: `/api/session/status` не маршрут (уезжает в
 * `/api/session/{id}` с id=`status` → 400), а `/api/experimental/session/{id}/wait`,
 * который обещают доки, отдаёт 404 — заменой служит псевдо-сообщение
 * `{"type":"idle"}` в ленте сообщений.
 */
object ApiPaths {
    /** Список сессий. GET и POST по одному пути. */
    const val SESSIONS = "/api/session"

    /** Одна сессия. GET — чтение, DELETE — удаление. */
    fun session(id: String): String = "$SESSIONS/$id"

    /** Лента сообщений сессии. GET. */
    fun message(id: String): String = "$SESSIONS/$id/message"

    /** Отправка сообщения/промпта. POST. Тело — `{text}`, см. `ApiShape.promptBody`. */
    fun prompt(id: String): String = "$SESSIONS/$id/prompt"

    /** Прерывание хода. POST. */
    fun interrupt(id: String): String = "$SESSIONS/$id/interrupt"

    /**
     * Горячая смена модели сессии. POST, 204 без тела.
     *
     * Взят из OpenAPI самого opencode (`operationId: session.switchModel`,
     * «Switch the model used by subsequent provider turns»), проверен на живом
     * 2.0.27: ответ следующего хода приходит уже от новой модели, без рестарта
     * процесса. Раньше единственным рычагом был рестарт serve, а он рвёт
     * стриминг текущей сессии.
     */
    fun sessionModel(id: String): String = "$SESSIONS/$id/model"

    /**
     * Запросы, на которые сервер ждёт ответа пользователя. GET.
     *
     * Это единственный источник вопросов: отдельной формы на вопросы в v2 нет,
     * см. KDoc у `ApiAsk`. Проверено на живом 2.0.27 — `/api/session/{id}/form`
     * при висящем вопросе остаётся пустым, а вопрос приходит сюда с
     * `action:"question"`.
     */
    fun permissions(id: String): String = "$SESSIONS/$id/permission"

    /**
     * Ответ на запрос: POST, тело `{decision:"once"|"always"|"reject"}`,
     * для вопроса дополнительно `message`. Ключ называется именно `decision` —
     * проверено: `{reply:…}` и пустое тело дают 400.
     */
    fun permissionReply(
        id: String,
        requestId: String,
    ): String = "$SESSIONS/$id/permission/$requestId/reply"

    /** Список MCP-серверов. GET. */
    const val MCP = "/api/mcp"

    /**
     * Список моделей, которые сервер реально обслуживает. GET.
     *
     * Ответ — `{location, data:[…]}`, где в элементе `data` лежит id модели,
     * провайдер, лимиты и модальности. Разбор — в `ModelList`.
     */
    const val MODELS = "/api/model"
}
