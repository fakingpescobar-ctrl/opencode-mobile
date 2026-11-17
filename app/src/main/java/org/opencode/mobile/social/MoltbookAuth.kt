@file:Suppress("MatchingDeclarationName") // файл назван по теме (авторизация), а не по классу ответа

package org.opencode.mobile.social

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.UUID
import org.opencode.mobile.server.MoltbookPaths
import org.opencode.mobile.server.keyFile

/**
 * Регистрация агента и хранилище ключа сайта Moltbook.
 *
 * Два разных токена не смешиваются намеренно. `mcp-token` из `filesDir` — локальный
 * bearer плагина, он меняется при каждом старте. Здесь речь о `moltkey`: ключе,
 * который сайт выдаёт агенту при регистрации и который переживает перезапуск
 * приложения. Он лежит рядом с остальным состоянием молтбука (см. [keyFile]).
 *
 * Чистые функции (валидация имени, разбор ответа, ссылка claim) вынесены сюда же,
 * чтобы тестировать их без сети и без Android: с сайта отсюда приходит 403, и
 * живая проверка регистрации невозможна, а разбор — проверяемый.
 */

/** Что сайт вернул на `POST /api/v1/agents/register`. */
internal data class MoltbookRegistration(
    val apiKey: String,
    val claimUrl: String,
    val verificationCode: String,
    val status: String,
)

private const val NAME_MIN = 2
private const val NAME_MAX = 32
private const val NAME_PREFIX = "oc_"
private const val NAME_SUFFIX = 12
private const val MASK_HEAD = 4
private const val MASK_DOTS = 12
private const val CLAIM_PATH = "/claim"
private const val NULL_TEXT = "null"
private const val STATUS_PENDING = "pending_claim"
private const val STATUS_UNKNOWN = "unknown"
private val NAME_RE = Regex("[a-z0-9_]{$NAME_MIN,$NAME_MAX}")

/** Ключ из [keyFile], если он есть. Отсутствие и пустой файл — одно и то же: «нет ключа». */
internal fun readMoltbookKey(context: Context): String? {
    val file = keyFile(context)
    val text = if (file.isFile) runCatching { file.readText().trim() }.getOrNull().orEmpty() else ""
    return text.takeIf { it.isNotEmpty() }
}

/**
 * Записать ключ. [MoltbookPaths.ensureDir] обязателен: каталог состояния может
 * отсутствовать (первый запуск или чистая установка), и запись в никуда не
 * пройдёт тихо: `writeText` бросит IOException, который покажет секция.
 */
internal fun saveMoltbookKey(context: Context, key: String) {
    val trimmed = key.trim()
    require(trimmed.isNotEmpty()) { "Moltbook: ключ пуст" }
    if (!MoltbookPaths.ensureDir(context)) {
        throw IOException("Moltbook: не могу создать каталог состояния")
    }
    keyFile(context).writeText(trimmed + "\n")
}

/** Маска для экрана: показываем только префикс и длину, никогда не весь ключ. */
internal fun maskedMoltbookKey(key: String): String {
    if (key.length <= MASK_HEAD) return "•".repeat(MASK_DOTS)
    return key.take(MASK_HEAD) + "…" + "•".repeat(MASK_DOTS)
}

/** Имя агента: 2–32 символа, только строчные латиница/цифры/подчёркивание. */
internal fun validAgentName(name: String): Boolean = NAME_RE.matches(name)

/** Имя по умолчанию: глобально уникальное и в разрешённом алфавите. */
internal fun suggestedAgentName(): String =
    NAME_PREFIX + UUID.randomUUID().toString().replace("-", "").take(NAME_SUFFIX)

/** `optString` на JSON null возвращает строку "null" — она не значение, а отсутствие. */
private fun textValue(json: JSONObject, name: String): String? =
    json.optString(name).takeIf { it.isNotEmpty() && it != NULL_TEXT }

/**
 * Разбор ответа регистрации. Ключ может лежать и наверху, и под `agent` — в
 * открытых реализациях встречаются оба вида, и отбрасывать один из них значит
 * сломать регистрацию на половине ответов.
 */
internal fun parseRegistration(text: String): MoltbookRegistration {
    val root = runCatching { JSONObject(text) }.getOrNull() ?: throw IOException("Moltbook: ответ регистрации не JSON")
    val agent = root.optJSONObject("agent") ?: root
    val key = textValue(agent, "api_key") ?: throw IOException("Moltbook: в ответе регистрации нет api_key")
    val claim = textValue(agent, "claim_url").orEmpty()
    val code = textValue(agent, "verification_code") ?: textValue(agent, "tweet_template").orEmpty()
    val status = textValue(agent, "status") ?: textValue(root, "status") ?: STATUS_PENDING
    return MoltbookRegistration(key, claim, code, status)
}

/** Статус ответа `GET /api/v1/agents/status`: `pending_claim` → `claimed`. */
internal fun parseAgentStatus(text: String): String {
    val root = runCatching { JSONObject(text) }.getOrNull() ?: return STATUS_UNKNOWN
    val agent = root.optJSONObject("agent") ?: root
    return textValue(agent, "status") ?: textValue(root, "status") ?: STATUS_UNKNOWN
}

/**
 * Claim-ссылку открываем только на moltbook.com. Ссылка приходит от сервера,
 * но это всё равно внешний ввод: без проверки кнопка в диагностике стала бы
 * переходом по чему угодно, включая фишинг.
 */
internal fun safeClaimUrl(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull()
    val ok =
        uri != null &&
            uri.scheme.equals("https", ignoreCase = true) &&
            (uri.host == "www.moltbook.com" || uri.host == "moltbook.com") &&
            uri.path.orEmpty().startsWith(CLAIM_PATH)
    return if (ok) url else null
}
