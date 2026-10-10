package org.opencode.mobile.social

import android.content.Context
import org.opencode.mobile.server.MoltbookMcp
import org.opencode.mobile.server.panelFile
import org.opencode.mobile.server.wakeLog
import java.io.File
import java.io.RandomAccessFile

/**
 * Чтение панели с диска: всё, что нельзя проверить без телефона.
 *
 * Разбор снимка живёт в [MoltbookPanelFeed] и проверяется обычным тестом.
 * Здесь только два действия с файлами, и оба гасят свои ошибки: панель —
 * украшение, и бросать на неё экран ошибки из-за недоступного файла незачем.
 * Причина при этом не теряется — она попадает в `reason` и видна красной строкой.
 */
internal object MoltbookPanelSource {
    /**
     * Снимок панели плюс последняя строка журнала будильника.
     *
     * Будильник пишет свой журнал отдельно от плагина, и панели он нужен
     * отдельно: по нему видно, ПОЧЕМУ агента не разбудили, когда карточка молчит.
     */
    fun read(context: Context): MoltbookPanelState {
        val panel = panelFile(context)
        val state =
            runCatching { parsePanel(if (panel.isFile) panel.readText() else null) }
                .getOrElse { MoltbookPanelState(reason = "снимок не прочитать") }
        return withWakeRefusal(state, lastWakeRefusal(context, System.currentTimeMillis()))
    }

    /**
     * Последний отказ из `wake.log`, если он не про «разбудили» и не простоял.
     *
     * Успешное буждение интереса не представляет: карточка и так покажет время
     * последнего снимка. Штатная пауза после недавнего буждения - тоже: будильник
     * здоров, просто рано. А вот отказ - это ровно то, что юзер ищет, когда
     * карточка молчит: «все сессии заняты», «сервер не ответил».
     *
     * Время передаётся наружу, а не берётся внутри, чтобы свежесть проверялась
     * обычным тестом: иначе «давний отказ не должен красить карточку» пришлось
     * бы проверять часами ожидания.
     */
    private fun lastWakeRefusal(
        context: Context,
        now: Long,
    ): WakeRefusal? {
        val log = wakeLog(context)
        if (!log.isFile) return null
        return wakeRefusalOf(tailText(log, WAKE_LOG_TAIL_BYTES), now)
    }
}

/** Хвост файла в байтах: столько журнала будильника читает панель за раз. */
private const val WAKE_LOG_TAIL_BYTES = 8L * 1024L

/**
 * Последние [maxBytes] файла, а не весь файл.
 *
 * `wake.log` append-only и нигде не ротируется: он живёт дольше приложения.
 * `readText()` на каждом опросе панели (раз в 15 секунд, пока карточка
 * открыта) тащил в память весь журнал, чтобы выбросить из него одну строку, -
 * и память росла вместе с журналом.
 *
 * Первая строка хвоста отбрасывается: файл читается с середины строки, и её
 * начало - это хвост настоящей строки из середины файла. Разрезанный
 * многобайтный символ на границе тоже возможен, поэтому берётся только то,
 * что после первого перевода строки.
 */
internal fun tailText(
    file: File,
    maxBytes: Long,
): String? =
    runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length <= 0L) return@use ""
            val from = (length - maxBytes).coerceAtLeast(0L)
            val bytes = ByteArray((length - from).toInt())
            raf.seek(from)
            raf.readFully(bytes)
            val text = String(bytes, Charsets.UTF_8)
            if (from > 0L) text.substringAfter('\n', "") else text
        }
    }.getOrNull()

/**
 * Склейка снимка с последней строкой журнала будильника.
 *
 * Отказ будильника - это тоже отказ. Оставить при нём `ok = true` было бы
 * враньём: потребитель, который читает только `ok`, решил бы, что агента
 * будили штатно, хотя на деле будильник его не разбудил. Причина снимка при
 * этом не теряется - она уже была и остаётся на своём месте.
 *
 * Но отказ стареет не по часам, а по снимку: журнал append-only, и строка
 * `hold` лежит в нём навсегда. Если снимок СВЕЖЕЕ отказа, значит агент после
 * этого отказа всё-таки поработал (он же и пишет снимок), и прошлая ошибка
 * не может говорить «агента не разбудили» про текущий момент.
 */
internal fun withWakeRefusal(
    state: MoltbookPanelState,
    refusal: WakeRefusal?,
): MoltbookPanelState {
    val stale = refusal != null && state.at <= refusal.at
    return if (stale) state.copy(reason = state.reason.ifEmpty { refusal?.reason.orEmpty() }, ok = false) else state
}
