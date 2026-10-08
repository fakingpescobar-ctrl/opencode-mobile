package org.opencode.mobile.social

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Сырое тело каждого ответа Moltbook, сохранённое на диск ДО разбора.
 *
 * Зачем это нужно, если ответ всё равно пришёл в память: разбор и проверка
 * читают одно и то же тело по-разному, а когда «я отправил» и «сервер не видит»
 * спорят друг с другом, решает не лог, а файл, который никто не переписывал.
 * Согласовано на Moltbook 10.10.2026 (merlintlp2): верификатор должен сам парсить
 * сырой ответ, не используя код писателя — иначе он повторяет ошибку писателя
 * вместо того, чтобы её найти.
 *
 * До записи на диск, а не после: файл нужен ровно тогда, когда процесс умер
 * между отправкой и разбором, и «после» в этом случае не наступает никогда.
 *
 * Подделывать содержимое здесь нечем и незачем: запись идёт в каталог приложения,
 * доступный только ему, а подлинность цепочки записей ведёт [MoltbookWitness].
 * Этот архив — про СЕЙЧАС и про память, а не про защиту от подделки.
 */
internal object MoltbookRawArchive {
    /** Каталог сырых ответов относительно `filesDir`. Рядом с журналом-свидетелем. */
    const val DIR_PATH: String = "moltbook/raw"

    /**
     * Сколько последних ответов храним.
     *
     * 300 — это примерно два-три тика полной ленты плюс ответы на них. Больше
     * не нужно: спор «я отправил / сервер не видит» разбирается в часах, а не в
     * неделях, а телефон не должен копить тела ленты вечно. Порог по числу, а
     * не по дням, потому что тик ходит по расписанию агента, а не по календарю.
     */
    const val KEEP_FILES: Int = 300

    /**
     * Потолок на тело одного ответа: 64 КБ.
     *
     * [KEEP_FILES] файлов по 64 КБ — это потолок 19 МБ, но в реальности тела
     * мелкие: ответ на комментарий меньше 4 КБ, потому что сам комментарий
     * урезан [MOLTBOOK_COMMENT_LIMIT] символами. Лента и скан комментариев — вот
     * где сотни килобайт, и хранить их целиком незачем: доказательством публикации
     * лента не является. Тело больше потолка пишется не целиком, а заменяется
     * отметкой `body_omitted_bytes` — сам факт запроса и ответа остаётся, вес не
     * растёт.
     */
    const val MAX_BODY_BYTES: Int = 64 * 1024

    /** Имя файла: время + короткий хеш запроса. Одинаковые повторы не сливаются. */
    fun fileName(
        at: Long,
        method: String,
        path: String,
    ): String = "$at-${sha8("$method $path")}.json"

    /**
     * Записать тело ответа. Отказ молчит и возвращает `null`: архив не имеет
     * права ронять тик, но тик имеет право узнать об отказе — [record] пишет
     * предупреждение в лог.
     */
    fun record(
        dir: File,
        method: String,
        path: String,
        status: Int,
        body: String,
        at: Long,
    ): File? {
        if (body.isBlank()) return null
        return try {
            dir.mkdirs()
            val envelope =
                JSONObject()
                    .put("method", method)
                    .put("path", path)
                    .put("status", status)
                    .put("at", at)
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            if (bodyBytes.size <= MAX_BODY_BYTES) {
                envelope.put("body", body)
            } else {
                envelope.put("body_omitted_bytes", bodyBytes.size)
            }
            val file = File(dir, fileName(at, method, path))
            file.writeText(envelope.toString(), Charsets.UTF_8)
            prune(dir)
            file
        } catch (e: Exception) {
            android.util.Log.w(TAG, "не сохранил сырой ответ $method $path: ${e.message}")
            null
        }
    }

    /**
     * Оставить последние [KEEP_FILES] файлов, остальные снести.
     *
     * Порядок — по имени, а не по времени изменения: имя начинается с метки
     * времени, поэтому сортировка по имени совпадает с хронологией и не зависит
     * от того, как файловая система расставила mtime при копировании.
     */
    fun prune(
        dir: File,
        keep: Int = KEEP_FILES,
    ) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: return
        if (files.size <= keep) return
        files.take(files.size - keep).forEach { it.delete() }
    }

    private const val TAG = "MoltbookRawArchive"

    private fun sha8(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(8)
}

/**
 * Разбор сохранённого ответа БЕЗ кода писателя.
 *
 * Отдельный объект по требованию merlintlp2 (10.10.2026) и по существу: если
 * верификатор зовёт `MoltbookClient.postedCommentId`, то при ошибке в разборе
 * писателя верификатор повторит её молча и подтвердит несуществующий успех.
 * Здесь своя независимая реализация на голом `org.json` — совпадение результатов
 * с клиентом и есть тот сигнал, который стоит показать.
 */
internal object MoltbookRawVerifier {

    /** Ключи, под которыми ответ называет id опубликованного комментария. */
    private val ID_KEYS = arrayOf("id", "comment_id", "commentId")

    /** Ключи статуса проверки; платформа использует оба написания. */
    private val STATUS_KEYS = arrayOf("verification_status", "verificationStatus")

    private const val ALREADY_EXISTED_KEY = "already_existed"

    /** Глубина вложенности `data`, в которой ещё ищется наш комментарий. */
    private const val SCAN_DEPTH = 3
    /**
     * Что удалось вытащить из тела ответа, и ничего больше.
     *
     * Поля nullable не из вежливости: отсутствие любого из них — это факт о
     * сервере, а не ошибка разбора, и путать их нельзя (см. [Verdict]).
     */
    data class Receipt(
        val commentId: String?,
        val verificationStatus: String?,
        val content: String?,
        val alreadyExisted: Boolean,
    )

    enum class Verdict {
        /** id есть, контент сходится с тем, что мы отправили. */
        Confirmed,

        /** id есть, но контент НЕ тот, что мы отправили: чужой ответ или правка сервера. */
        ContentMismatch,

        /** Сервер сказал `already_existed`: наш запрос вернул чужой (или наш же старый) id. */
        AlreadyExisted,

        /** Тело нечитаемо или не содержит ни id, ни признаков существования. */
        Unknown,
    }

    /**
     * Разобрать тело ответа. `null`, если тело не JSON — это [Verdict.Unknown],
     * а не исключение: нечитаемый ответ должен быть виден как «не знаю».
     *
     * Обход глубины и набор ключей здесь продублированы у клиента намеренно:
     * совет merlintlp2 — «верификатор должен сам парсить сырой ответ, не
     * используя код писателя». Общий код разбора был бы исполнением этого совета
     * наизнанку — проверяющий, построенный на парсере того, кого проверяет, не
     * находит расхождение, а воспроизводит его. Но формы ответов обязаны быть
     * перечислены одинаково: иначе клиент успешно опубликует, а верификатор
     * напишет «сервер не дал доказательства публикации» (ровно тот ложный
     * вывод, который ревью справедливо отметило).
     */
    fun receipt(
        rawBody: String,
    ): Receipt? {
        val root =
            try {
                JSONObject(rawBody)
            } catch (_: org.json.JSONException) {
                return null
            }
        val scopes = scopesOf(root)
        val id =
            scopes.firstNotNullOfOrNull { scope ->
                ID_KEYS.firstNotNullOfOrNull { key ->
                    scope.optString(key).takeIf { it.isNotEmpty() && !it.equals("null", true) }
                }
            }
        val status =
            scopes.firstNotNullOfOrNull { scope ->
                STATUS_KEYS.firstNotNullOfOrNull { key ->
                    scope.optString(key).takeIf { it.isNotEmpty() && !it.equals("null", true) }
                }
            }
        val content =
            scopes.firstNotNullOfOrNull { scope ->
                scope.optString("content").takeIf { it.isNotEmpty() && !it.equals("null", true) }
            }
        val already = root.optBoolean(ALREADY_EXISTED_KEY, false) ||
            root.optJSONObject("error")?.optBoolean(ALREADY_EXISTED_KEY, false) == true
        return Receipt(
            commentId = id,
            verificationStatus = status,
            content = content,
            alreadyExisted = already,
        )
    }

    /**
     * Все места ответа, где может лежать наш комментарий: корень, `comment`,
     * `data`, `data.comment` — до [SCAN_DEPTH] вложенностей `data`.
     */
    private fun scopesOf(root: JSONObject): List<JSONObject> {
        val scopes = ArrayList<JSONObject>()
        var cursor: JSONObject? = root
        repeat(SCAN_DEPTH) {
            val current = cursor ?: return scopes
            scopes += current
            current.optJSONObject("comment")?.let { scopes += it }
            cursor = current.optJSONObject("data")
        }
        return scopes
    }

    /** SHA-256 от того, что мы отправили: подпись версии, а не подпись успеха. */
    fun contentHash(
        text: String,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * Вердикт по паре «что отправили / что вернули».
     *
     * `sentContent` может быть `null` — тогда сверять нечего и единственный
     * доступный вывод это `already_existed` или `Unknown`. Молчать о сверке,
     * которой не было, нельзя: иначе в дайджесте появится «проверено» там, где
     * сверки не было никогда.
     */
    fun verdict(
        receipt: Receipt?,
        sentContent: String?,
    ): Verdict {
        if (receipt == null) return Verdict.Unknown
        if (receipt.alreadyExisted) return Verdict.AlreadyExisted
        if (receipt.commentId == null) return Verdict.Unknown
        // Сверять не с чем — это «не знаю», а не «подтверждено». Возвращать
        // здесь Confirmed значило бы заявить проверку, которой не было.
        if (sentContent == null) return Verdict.Unknown
        // Контента в ответе нет — это тоже отсутствие данных, а не расхождение:
        // пустая строка не равна отправленному тексту, но называть это mismatch
        // значит обвинить сервер в подмене там, где он просто ничего не прислал.
        val got = receipt.content ?: return Verdict.Unknown
        val sentHash = contentHash(sentContent)
        val gotHash = contentHash(got)
        return if (sentHash == gotHash) Verdict.Confirmed else Verdict.ContentMismatch
    }
}