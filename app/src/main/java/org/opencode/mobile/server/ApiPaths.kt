package org.opencode.mobile.server

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Контракт HTTP-путей локального opencode serve.
 *
 * Зачем: в opencode 2.x пути уехали под префикс `/api/`, а часть старых
 * эндпоинтов удалена вовсе. До этого изменения каждый вызов писал путь
 * строкой в месте использования — 9 файлов, 30+ мест, и смена версии
 * означала бы правку каждого по отдельности, с риском пропустить одно.
 *
 * Что здесь НЕ решается: форма тела и формы ответа. В v2 `POST /prompt`
 * ждёт `{"text":"…"}`, а не `{"parts":[…]}`, и `GET /api/session` завёрнут в
 * `{"data":[…]}`. Это отдельная работа (этап 4 плана `docs/MIGRATION-V2.md`).
 * Здесь только имя пути.
 *
 * Методы отдают `null` там, где в соответствующей версии пути НЕТ. Это
 * намеренно: вызывающий обязан обработать «нечем спросить» явно, вместо
 * того чтобы молча уехать в подстановку и узнать о поломке от 404.
 */
enum class ServerFlavor {
    /** opencode 1.x — пути без префикса, `/session/status` существует. */
    V1,

    /** opencode 2.x — пути под `/api/`, части эндпоинтов удалено. */
    V2,
}

/**
 * Пути одной версии serve. Реализации — [ApiPathsV1] и [ApiPathsV2];
 * выбирается через [active].
 *
 * Легаси-эндпоинты (вопросы и разрешения) вынесены в [Legacy] отдельно: в v2
 * их нет вовсе, там нативные form- и permission-эндпоинты (этап 5). Собраны
 * вместе они ещё и удерживают интерфейс в лимите сложности, но главное —
 * «этого пути может не быть» видно в самом имени, а не в возврате null на
 * обычном `sessions()`.
 */
interface ApiPaths {
    /** Список сессий. GET и POST по одному пути. */
    fun sessions(): String

    /** Одна сессия. GET — чтение, DELETE — удаление. */
    fun session(id: String): String

    /** Лента сообщений сессии. GET. */
    fun message(id: String): String

    /** Отправка сообщения/промпта. POST. */
    fun prompt(id: String): String

    /** Прерывание хода. POST. */
    fun interrupt(id: String): String

    /** Карта занятых сессий. GET. null — в этой версии пути нет. */
    fun status(): String?

    /** Живость сервера. GET. */
    fun health(): String

    /** Список MCP-серверов. GET. */
    fun mcp(): String

    /** Легаси-эндпоинты, которых в v2 не существует. */
    val legacy: Legacy

    /** Эндпоинты, удалённые в opencode 2.x. */
    interface Legacy {
        /** Список висящих вопросов. GET. */
        fun questionList(): String?

        /** Действие по вопросу (reply/reject). POST. */
        fun questionAction(
            requestId: String,
            action: String,
        ): String?

        /** Список висящих разрешений. GET. */
        fun permissionList(): String?

        /** Ответ на разрешение. POST. */
        fun permissionReply(permissionId: String): String?
    }

    companion object {
        /**
         * Активный контракт. По умолчанию V1: определение версии по ответу
         * сервера ставится на этапе 2 плана, а до него поведение приложения
         * обязано остаться прежним.
         */
        @Volatile
        private var flavor: ServerFlavor = ServerFlavor.V1

        val active: ApiPaths
            get() =
                when (flavor) {
                    ServerFlavor.V1 -> ApiPathsV1
                    ServerFlavor.V2 -> ApiPathsV2
                }

        /** Текущая версия — для диагностики и UI. */
        val activeFlavor: ServerFlavor
            get() = flavor

        fun install(next: ServerFlavor) {
            flavor = next
        }
    }
}

/** Пути opencode 1.x. Ровно те строки, что были вшиты в вызовы до этого файла. */
object ApiPathsV1 : ApiPaths {
    override fun sessions(): String = "/session"

    override fun session(id: String): String = "/session/$id"

    override fun message(id: String): String = "/session/$id/message"

    override fun prompt(id: String): String = "/session/$id/message"

    override fun interrupt(id: String): String = "/session/$id/abort"

    override fun status(): String = "/session/status"

    override fun health(): String = "/global/health"

    override fun mcp(): String = "/mcp"

    override val legacy: ApiPaths.Legacy = LegacyV1
}

/**
 * Пути opencode 2.x. Проверено на живом 2.0.27 (см. `docs/MIGRATION-V2.md`,
 * этап 0): `/api/session/status` не маршрут (уходит в `/api/session/{id}` с
 * id=`status` → 400), а `/api/experimental/session/{id}/wait`, который
 * обещают доки, отдаёт 404 — заменой служит псевдо-сообщение
 * `{"type":"idle"}` в ленте сообщений.
 */
object ApiPathsV2 : ApiPaths {
    override fun sessions(): String = "/api/session"

    override fun session(id: String): String = "/api/session/$id"

    override fun message(id: String): String = "/api/session/$id/message"

    override fun prompt(id: String): String = "/api/session/$id/prompt"

    override fun interrupt(id: String): String = "/api/session/$id/interrupt"

    /** В v2 карты занятых сессий нет: статус едет в ленте как `type:"idle"`. */
    override fun status(): String? = null

    override fun health(): String = "/api/info"

    override fun mcp(): String = "/api/mcp"

    override val legacy: ApiPaths.Legacy = LegacyV2
}

private object LegacyV1 : ApiPaths.Legacy {
    override fun questionList(): String = "/question"

    override fun questionAction(
        requestId: String,
        action: String,
    ): String = "/question/${encode(requestId)}/$action"

    override fun permissionList(): String = "/permission"

    override fun permissionReply(permissionId: String): String = "/permission/${encode(permissionId)}/reply"
}

/** В v2 легаси-путей нет — есть нативные form- и permission-эндпоинты. */
private object LegacyV2 : ApiPaths.Legacy {
    override fun questionList(): String? = null

    override fun questionAction(
        requestId: String,
        action: String,
    ): String? = null

    override fun permissionList(): String? = null

    override fun permissionReply(permissionId: String): String? = null
}

private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
