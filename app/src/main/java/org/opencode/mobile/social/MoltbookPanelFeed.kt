package org.opencode.mobile.social

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Снимок панели «М»: числа, которые показывает карточка в шапке.
 *
 * Раньше панель читала ledger сама и ходила на сайт за кармой. Теперь оба этих
 * читателя удаляются вместе со старым тикером, а источник правды один — файл
 * `panel.json`, который переписывает плагин после каждого своего вызова.
 *
 * Отсюда три правила, каждое из которых уже стоило обмана:
 *
 *  - Мусор вместо снимка это НЕ нули. `ok = false` и причина: юзер обязан видеть,
 *    что карточка ничего не знает, а не что агент ничего не сделал.
 *  - Карма и непрочитанные приходят `null`, когда их не видели. Ноль и «не знаю»
 *    выглядят одинаково, а означают противоположное, поэтому в UI это «—».
 *  - Время снимка возвращается вместе с числами. Панель открывают между тиками,
 *    и без метки «это данные от HH:mm» протухшие цифры выглядят свежими.
 */
internal data class MoltbookPanelState(
    val at: Long = 0L,
    val ok: Boolean = false,
    val reason: String = "",
    val awaiting: Int = 0,
    val answered: Int = 0,
    val verified: Int = 0,
    val reused: Int = 0,
    val failed: Int = 0,
    val unknown: Int = 0,
    val ourPosts: Int = 0,
    val repliesToOurs: Int = 0,
    val reposts: Int = 0,
    val upvotes: Int = 0,
    /** -1 значит «плагин этого не видел», а не «ноль непрочитанных». */
    val karma: Int = UNKNOWN_COUNT,
    val unread: Int = UNKNOWN_COUNT,
    /**
     * Когда карму видели на сайте, а не когда переписали снимок.
     *
     * Карма лежит в кэше состояния и может быть старее снимка на сутки. Снимок
     * обновляется после каждого вызова тула, поэтому карточка, подписанная его
     * временем, называла бы вчерашнюю карму «только что».
     */
    val karmaAt: Long = 0L,
    val pending: List<MoltbookQuestion> = emptyList(),
) {
    val stale: Boolean get() = at <= 0L
}

/** Вопрос из ленты: кто, где и что именно нас спросил. */
internal data class MoltbookQuestion(
    val id: String,
    val author: String,
    val title: String,
    val body: String,
    /**
     * Русский текст из кэша переводов плагина.
     *
     * Пустая строка — это «перевода ещё нет», а не «перевода нет»: очередь
     * длинная, плагин досылает её по мере сил. Карточка в этом случае показывает
     * оригинал целиком, а не пустую строку.
     */
    val bodyRu: String = "",
)

internal const val UNKNOWN_COUNT: Int = -1

/** Снимок по сырому JSON плагина. Мусор и пустота дают отказ, а не нули. */
internal fun parsePanel(raw: String?): MoltbookPanelState {
    if (raw.isNullOrBlank()) return MoltbookPanelState(reason = "снимка нет")
    return runCatching {
        val json = JSONObject(raw)
        if (!json.optBoolean("ok", false)) {
            // at из отказа не берём: там время ПОПЫТКИ, а не время данных.
            // С ним cadenceHint писал "только что" у снимка, которого нет.
            return@runCatching MoltbookPanelState(at = 0L, reason = textOf(json, "reason"))
        }
        MoltbookPanelState(
            at = json.optLong("at", 0L),
            ok = true,
            awaiting = numberOf(json, "awaiting"),
            answered = numberOf(json, "answered"),
            verified = numberOf(json, "verified"),
            reused = numberOf(json, "reused"),
            failed = numberOf(json, "failed"),
            unknown = numberOf(json, "unknown"),
            ourPosts = numberOf(json, "ourPosts"),
            repliesToOurs = numberOf(json, "repliesToOurs"),
            reposts = numberOf(json, "reposts"),
            upvotes = numberOf(json, "upvotes"),
            karma = countOf(json, "karma"),
            unread = countOf(json, "unread"),
            karmaAt = json.optLong("karmaAt", 0L),
            pending = questionsOf(json),
        )
    }.getOrElse { MoltbookPanelState(reason = "снимок не разобрать") }
}

private fun questionsOf(json: JSONObject): List<MoltbookQuestion> {
    val array = json.optJSONArray("pending") ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        val id = textOf(item, "id")
        if (id.isEmpty()) {
            null
        } else {
            MoltbookQuestion(
                id,
                textOf(item, "author"),
                textOf(item, "title"),
                textOf(item, "body"),
                textOf(item, "bodyRu"),
            )
        }
    }
}

/** Целое поле. Мусор и пропуск дают 0 — это счётчик, у него нет «не знаю». */
private fun numberOf(
    json: JSONObject,
    key: String,
): Int = json.optInt(key, 0)

/** Счётчик, у которого «не знаю» осмысленно: karma и unread. null остаётся -1. */
private fun countOf(
    json: JSONObject,
    key: String,
): Int = if (json.isNull(key)) UNKNOWN_COUNT else json.optInt(key, UNKNOWN_COUNT)

private fun textOf(
    json: JSONObject,
    key: String,
): String = json.optString(key, "").trim()

/** Плашка вместо цифры, когда плагин этого значения не видел. */
const val UNKNOWN: String = "—"

/** Длина подписи счётчика, чтобы «не прочитано» не съедало колонку. */
private const val THOUSAND: Int = 1000
private const val MILLION: Int = 1000000

/** Счёт идёт десятыми долями, поэтому делить на десять приходится трижды. */
private const val TENTHS: Long = 10L

/**
 * Счётчик, у которого «не знаю» осмысленно: карма и непрочитанные приходят
 * пустыми, пока плагин ни разу не заходил на сайт.
 *
 * Раньше здесь был ноль, и юзер видел «карма 0» у живого аккаунта. Разница между
 * «ноль» и «не видел» тут и есть разница между «мы посмотрели и там пусто» и
 * «мы не смотрели».
 */
internal fun countLabel(value: Int): String =
    when {
        value == UNKNOWN_COUNT -> UNKNOWN
        value < THOUSAND -> value.toString()
        else -> compactCount(value)
    }

/**
 * Счётчик, у которого нет верхней границы, нельзя обрезать: карма 10347,
 * показанная как «1034», - это число, которого не существует. Поэтому
 * тысячи сворачиваются в «1.2к», а дальше - в «1.2м». Цифра остаётся
 * читаемой и перестаёт врать.
 */
internal fun compactCount(value: Int): String {
    val abs = if (value < 0) -value.toLong() else value.toLong()
    val (unit, suffix) = if (abs >= MILLION) MILLION to "м" else THOUSAND to "к"
    val tenths = abs * TENTHS / unit
    val whole = tenths / TENTHS
    val rest = tenths % TENTHS
    val sign = if (value < 0) "-" else ""
    return if (rest == 0L) sign + whole + suffix else sign + whole + "." + rest + suffix
}

private val stampFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * Когда панель последний раз обновлялась — по времени снимка, а не по
 * обещанию тикера.
 *
 * Раньше здесь стояло «агент вернётся через в прошлый раз 22:53»: подпись
 * обещала будущее и тут же показывала прошлое. Теперь будущего нет, оно и не
 * нужно: карточка показывает, СКОЛЬКО ДАВНО агент работал, а когда проснётся
 * следующий раз — это и так решает будильник.
 */
internal fun cadenceHint(state: MoltbookPanelState): String =
    when {
        state.at <= 0L -> "нет данных"
        System.currentTimeMillis() - state.at < STALE_AFTER_MS -> "только что"
        else -> "данные " + stampFormat.format(Date(state.at))
    }

/**
 * Заголовок списка неотвеченных: «ЖДУТ ОТВЕТА» или «ЖДУТ ОТВЕТА · ЕЩЁ 61».
 *
 * На карточке две разные величины об одном: крупная цифра - сколько вопросов
 * ждут ВСЕГО, список под ней - сколько поместилось (плагин присылает первые
 * шесть). Без оговорки про остаток «67 наверху, 6 внизу» читается как ошибка:
 * либо в базе шесть, либо список врёт. Пользователь это и увидел, и правильно
 * спросил.
 *
 * [hidden] считается на вызывающей стороне не просто так: если бы он считался
 * здесь из `awaiting - shown`, то расхождение между «сколько прислал плагин» и
 * «сколько показали» стало бы невидимым - а это ровно тот случай, где молчать
 * вредно.
 */
internal fun pendingHeadline(hidden: Int): String =
    if (hidden > 0) "$PENDING_HEAD · ЕЩЁ $hidden" else PENDING_HEAD

private const val PENDING_HEAD = "ЖДУТ ОТВЕТА"

/**
 * Подпись клетки кармы: «карма» или «карма · вчера».
 *
 * Карма приходит из кэша и стареет отдельно от снимка. Раньше подпись была
 * всегда «карма», и число трёхдневной давности стояло под общей меткой
 * «только что» - то есть карточка врала дважды: и про возраст, и про свежесть.
 * Одна лишняя буква в подписи дешевле такого обмана.
 */
internal fun karmaLabel(
    state: MoltbookPanelState,
    nowMs: Long = System.currentTimeMillis(),
): String {
    if (state.karma == UNKNOWN_COUNT || state.karmaAt <= 0L) return KARMA
    val ageMs = nowMs - state.karmaAt
    // Возраст и его слова живут в одной функции намеренно: это один формат,
    // разорванный на две, существовал только ради лимита detekt, а разделение
    // спрятало условие «пусто = молчание» от того, кто читает подпись целиком.
    val age =
        when {
            ageMs < STALE_AFTER_MS -> ""
            ageMs < HOUR_MS -> (ageMs / MINUTE_MS).toString() + MINUTES_AGO
            ageMs < DAY_MS -> (ageMs / HOUR_MS).toString() + HOURS_AGO
            ageMs < YESTERDAY_UNTIL_MS -> "вчера"
            else -> (ageMs / DAY_MS).toString() + DAYS_AGO
        }
    return if (age.isEmpty()) KARMA else "$KARMA · $age"
}

/** Длина подписи счётчика, чтобы «не прочитано» не съедало колонку. */
private const val STALE_AFTER_MS = 2 * 60 * 1000L
private const val MINUTE_MS: Long = 60 * 1000L
private const val HOUR_MS: Long = 60 * MINUTE_MS
private const val DAY_MS: Long = 24 * HOUR_MS

/**
 * Граница слова «вчера».
 *
 * Слово, а не число дней: «N дн назад» для двухсотчасовой давности читалось бы
 * как «8 дн назад», хотя человек вчера ещё заходил. Верхняя граница 3, а не 2,
 * потому что «вчера» живёт до полуночи следующего дня, а не ровно 24 часа.
 */
private const val YESTERDAY_UNTIL_MS: Long = 3 * DAY_MS

/**
 * Хвосты подписи без слова «назад».
 *
 * Колонка сетки — 76 dp, туда влезает примерно двенадцать символов мелким
 * шрифтом. «карма · 40 мин назад» туда не влезает и обрезается многоточием,
 * поэтому время подписывается коротко: «карма · 40 мин». Смысл «когда» снимает
 * подпись снимка над карточкой, а не длина слова.
 */
private const val MINUTES_AGO = " мин"
private const val HOURS_AGO = " ч"
private const val DAYS_AGO = " дн"

private const val KARMA = "карма"
