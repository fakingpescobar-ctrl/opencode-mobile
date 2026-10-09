package org.opencode.mobile.social

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.opencode.mobile.server.LocalOpenCodeClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Будильник. Не ходит на сайт и ничего не публикует — будит агента.
 *
 * Старый тикер был логикой: он сам читал ленту, сам звал модель за черновиком,
 * сам публиковал и сам считал. Теперь всё это делает агент через инструменты
 * плагина, а отсюда остаётся ровно одно: не дать автономии умереть тихо.
 *
 * Решение «кого и когда» живёт в [MoltbookWakePlan], потому что принимается
 * из ответов сервера и проверяется без телефона. Здесь только доставка.
 */
internal object MoltbookWake {
    const val STAMP_PATH: String = "moltbook/last-wake"

    const val LOG_PATH: String = "moltbook/wake.log"

    /**
     * Что именно сказать агенту.
     *
     * Текст намеренно не вводит логики: что ответить, решает агент и только он.
     * Перечислены инструменты и порядок, а также то, что отказ — это результат.
     * Последнее важнее остального: раньше тикер умел только публиковать, и
     * «нечего отвечать» выглядело как сбой, который надо чинить повтором.
     */
    fun wakeText(now: Long): String =
        """
        Будильник Молтбука (тик $now, эпоха в мс). Сам на сайт я не хожу — это твоя работа, у тебя есть инструменты.

        Порядок:
        1. moltbook_scan — посмотри ленту. Он ничего не публикует.
        2. Если answerable > 0 — ответь через moltbook_publish, по одному кандидату. Если придёт challenge, реши его сам и добей moltbook_verify: код одноразовый, второй попытки не будет.
        3. moltbook_report — собери MOLTBOOK_REPORT.md.

        Что считать нормальным результатом:
        - candidates пусто или refusals не пусто — это ответ, а не сбой. refusals это отказ инструмента, и не повторяй тот же вызов ради «успеха».
        - outcome reused значит, что ветка уже закрыта твоим же ответом. Публиковать снова не надо и нельзя.
        - outcome misparented или unconfirmed — разошлись родитель или чтение. Оставь это в отчёте и не выдумывай успех.

        Чего не делать: не править файлы репозитория, не запускать старый тикер, не публиковать один и тот же текст повторно.
        """.trimIndent()

    /** Тело сообщения — ровно то, что уже умеет отправлять UI. */
    fun messageBody(text: String): String = "{\"parts\":[{\"type\":\"text\",\"text\":${JSONObject.quote(text)}}]}"

    fun messagePath(sessionId: String): String = "/session/$sessionId/message"

    /**
     * Строка журнала: одна попытка — одна строка.
     *
     * Причину схлопываем целиком, а не только `\n` и `\r`: причина приходит из
     * ответа сервера, где перенос строки может оказаться и `\r\n`, и такой
     * заменой получится две пробела. Запись, которая расползлась на две, через
     * месяц читается как два события, а был одно.
     */
    fun line(
        at: Long,
        plan: MoltbookWakePlan.Plan,
    ): String =
        "$at ${MoltbookWakePlan.tag(plan)} ${MoltbookWakePlan.reason(plan).replace(Regex("\\s+"), " ")}"

    /**
     * Одна попытка разбудить. Возвращает строку журнала — она же попадает в
     * logcat, и по ней видно, что произошло, не открывая файл.
     */
    fun nudge(
        context: Context,
        now: Long,
    ): String {
        val record = deliver(context, plan(context, now), now)
        append(context, record)
        Log.i(TAG, record)
        return record
    }

    /**
     * Отправка. Метка двигается только после успеха: `postAsync` возвращает
     * правду о том, что тело ушло в сокет, а не о том, что агент его прочитал,
     * поэтому в журнале это «отправили», а не «разбудили».
     */
    private fun deliver(
        context: Context,
        plan: MoltbookWakePlan.Plan,
        now: Long,
    ): String {
        val target = plan as? MoltbookWakePlan.Plan.Wake
        val sent =
            target != null &&
                LocalOpenCodeClient.postAsync(
                    OPENCODE_PORT,
                    messagePath(target.sessionId),
                    messageBody(wakeText(now)),
                )
        val record =
            when {
                target == null -> line(now, plan)
                !sent -> line(now, MoltbookWakePlan.Plan.Hold("не отправили в ${target.sessionId}"))
                else -> {
                    stamp(context, now)
                    line(now, plan)
                }
            }
        return record
    }

    /** Чистая часть: собрать решение из живых ответов сервера. */
    private fun plan(
        context: Context,
        now: Long,
    ): MoltbookWakePlan.Plan {
        val sessions = MoltbookWakePlan.parseSessions(LocalOpenCodeClient.get(OPENCODE_PORT, "/session"))
        if (sessions.isEmpty()) return MoltbookWakePlan.Plan.Hold("сервер не ответил или сессий нет")
        val busy = MoltbookWakePlan.parseBusy(LocalOpenCodeClient.get(OPENCODE_PORT, "/session/status"))
        return MoltbookWakePlan.choosePlan(sessions, busy, now, lastWakeAt(context))
    }

    /**
     * Метка последнего УСПЕШНОГО будильника. На отказе она не двигается:
     * иначе серия отказов каждые RETRY_MS выглядела бы в отчёте как работа.
     */
    private fun stamp(
        context: Context,
        now: Long,
    ) {
        runCatching { File(context.filesDir, STAMP_PATH).writeText(now.toString()) }
            .onFailure { Log.w(TAG, "не записал метку будильника: ${it.message}") }
    }

    /** Первого будильника в жизни не было — файла нет, и это не поломка: «никогда» и есть 0. */
    private fun lastWakeAt(context: Context): Long {
        val file = File(context.filesDir, STAMP_PATH)
        return if (file.isFile) file.readText().trim().toLongOrNull() ?: 0L else 0L
    }

    /**
     * Журнал только дописывается, и сбой записи не имеет права отменить
     * будильник: журнал — наблюдение, а не условие автономии.
     */
    private fun append(
        context: Context,
        record: String,
    ) {
        try {
            val target = File(context.filesDir, LOG_PATH)
            target.parentFile?.mkdirs()
            FileOutputStream(target, true).use { out ->
                out.write((record + "\n").toByteArray())
                out.flush()
                out.fd.sync()
            }
        } catch (e: IOException) {
            Log.w(TAG, "не записал журнал будильника: ${e.message}")
        }
    }

    private const val TAG: String = "MoltbookWake"
    private const val OPENCODE_PORT: Int = 4096
}
