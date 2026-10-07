package org.opencode.mobile.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencode.mobile.social.MoltbookClient
import org.opencode.mobile.social.MoltbookLedger
import org.opencode.mobile.social.MoltbookScheduler
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Иконка «М» и панель статистики Moltbook в шапке чата.
 *
 * Отдельный файл, а не ещё 200 строк в [ChatOverlay]: он уже на 4000+ строк, и
 * наползающая шапка — ровно та причина, по которой его потом невозможно читать.
 */
private val MoltAccent = Color(0xFFFFB020)
private val MoltIdle = Color(0xFF8A8A8A)
private val MoltCard = Color(0xFF14110C)
private val MoltChipOff = Color(0xFF1E1B15)
private val MoltValue = Color(0xFFE8E8E8)

/**
 * Панель целиком нарисована с ЗАДАННЫМ lineHeight — без него Compose берёт
 * межстрочный интервал от шрифта и раздувает строку почти вдвое.
 *
 * Замер на реальном устройстве (OPPO CPH2747, 3.5 px/dp) показал 219 dp карточки
 * на 85 dp текста: 61% высоты уходило в воздух между строками сетки. Уплотнение
 * до 10-11 sp / lineHeight 13-15 sp съело эту пустоту, и в неё же влезла
 * настройка периодичности — без неё карточка всё равно была бы короче.
 */
private val TINY = 9.sp
private val TINY_LINE = 11.sp
private val SMALL = 10.sp
private val SMALL_LINE = 13.sp
private val BODY = 11.sp
private val BODY_LINE = 14.sp

/** Снимок статистики для панели: локальный снимок ledger плюс признак свежести. */
internal data class MoltbookSnapshot(
    val stats: MoltbookLedger.Stats,
    /** true — карма и непрочитанные пришли из сети, а не из базы. */
    val live: Boolean = false,
)

/**
 * Статистика Moltbook для панели: сначала мгновенный локальный снимок, поверх него —
 * одна попытка сети при открытии панели.
 *
 * Раньше читался ТОЛЬКО ledger, и цифры на карточке отставали на минуты: тик ходил
 * на Moltbook раз в полчаса, а счётчик «не прочитано» ждал следующего тика. Один
 * сетевой запрос при открытии панели дешевле, чем объяснение пользователю, что
 * агент «ещё не видел».
 *
 * Сеть — по [openToken], а не по [refreshKey]. Ключ обновляется по таймеру каждые
 * [org.opencode.mobile.ui.ChatOverlay] MOLTBOOK_PANEL_REFRESH_MS (15 секунд), и
 * замерено 08.10.2026: панель, открытая на час, ходила на `/api/v1/home` 240 раз —
 * при том что тикер на той же платформе между ними ограничен. Поэтому таймер
 * перечитывает только ledger (там цифры уже обновлены тиком), а сеть поднимает
 * [openToken], который [ChatOverlay] поднимает один раз на каждое открытие.
 *
 * Ключ читается заново на каждой попытке и нигде не кэшируется: файл ключа меняется
 * только вручную, а держать секрет в поле класса — плохая привычка, которая однажды
 * утечёт в дамп.
 *
 * SQLite и сеть — только в [Dispatchers.IO]: база общая с тиком, а сеть на
 * главном потоке в Android даёт NetworkOnMainThreadException.
 */
@Composable
internal fun rememberMoltbookSnapshot(
    refreshKey: Int,
    openToken: Int,
): MoltbookSnapshot {
    val context = LocalContext.current
    val ledger = remember { MoltbookLedger(context) }
    var snapshot by remember { mutableStateOf(MoltbookSnapshot(MoltbookLedger.Stats())) }
    // Равный на старте openToken — иначе первый же таймер-рефреш (а он приходит
    // раньше открытия панели) сходил бы в сеть без причины.
    var polledAt by remember { mutableIntStateOf(openToken) }
    LaunchedEffect(refreshKey) {
        // Упавший ledger гасим тихо: панель — украшение, а не прибор, и бросать
        // на неё экран ошибки незачем. Сеть ниже попробует достроить картину.
        val local = runCatching { withContext(Dispatchers.IO) { ledger.stats() } }.getOrNull()
        if (local != null) snapshot = MoltbookSnapshot(stats = local, live = snapshot.live)

        // Уже опросили при этом открытии — тик только что обновил ledger, идти в
        // сеть повторно незачем.
        if (polledAt == openToken) return@LaunchedEffect
        polledAt = openToken

        val fresh = runCatching { withContext(Dispatchers.IO) { fetchLiveCounts(context) } }.getOrNull()
        // Нет ключа, нет сети, 401 — молча оставляем то, что показали из ledger.
        // Рамка ошибки тут превращала бы украшение в прибор, который «не работает».
        if (fresh == null) return@LaunchedEffect
        // Свежие значения возвращаем в ledger: иначе «не прочитано» откатывалось бы
        // к старому через минуту, когда следующая перечитка перезапишет снимок.
        runCatching {
            withContext(Dispatchers.IO) {
                if (fresh.karma != local?.karma) ledger.putState(MoltbookLedger.KEY_KARMA, fresh.karma.toString())
                if (fresh.unread != local?.unread) ledger.putState(MoltbookLedger.KEY_UNREAD, fresh.unread.toString())
            }
        }
        val merged = (local ?: snapshot.stats).copy(karma = fresh.karma, unread = fresh.unread)
        snapshot = MoltbookSnapshot(stats = merged, live = true)
    }
    return snapshot
}

/**
 * Карма и непрочитанные прямо с сервера.
 *
 * [MoltbookClient.readKey] сам бросает [java.io.IOException], если файла ключа нет
 * или он пуст — это норма (ключ заводят вручную), и вызывающий гасит это молча.
 */
private fun fetchLiveCounts(context: Context): LiveCounts {
    val key = MoltbookClient.readKey(File(context.filesDir, KEY_FILE_PATH))
    val home = MoltbookClient(key).home()
    return LiveCounts(karma = home.karma, unread = home.unreadNotifications)
}

private data class LiveCounts(
    val karma: Int,
    val unread: Int,
)

/** Относительный путь файла ключа внутри приватного хранилища приложения. */
private const val KEY_FILE_PATH = "moltbook/moltkey"

/** Кнопка «М» в шапке: буква + счётчик неотвеченных. */
@Composable
internal fun MoltbookIndicator(
    awaiting: Int,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit = {},
) {
    val tint = if (awaiting > 0) MoltAccent else MoltIdle
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .padding(start = 4.dp)
                .clip(CircleShape)
                .background(if (active) Color(0xFF3A3A3A) else Color.Transparent)
                .clickable { onClick() }
                .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Text(
            text = "М",
            color = tint,
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
            lineHeight = 16.sp,
        )
        if (awaiting > 0) {
            Spacer(Modifier.width(3.dp))
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(MoltAccent)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    text = awaiting.toString(),
                    color = Color(0xFF14110C),
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Карточка Moltbook: сводка по ленте, кому именно мы не ответили, настройка
 * периодичности тика и последняя ошибка тика.
 *
 * [live] — метка «цифры пришли из сети», а не из базы: без неё юзер не может
 * отличить «не прочитано 0» от «мы не спросили сервер и не знаем».
 */
@Composable
internal fun MoltbookPanel(
    stats: MoltbookLedger.Stats,
    modifier: Modifier = Modifier,
    live: Boolean = false,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 2.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MoltCard)
                .border(1.dp, MoltAccent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                .testTag(TAG_PANEL_MOLTBOOK)
                // Внешний Column намеренно НЕ скроллится: иначе вложенный список
                // вопросов нельзя прокрутить отдельно — колесо уходит в панель целиком.
                .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "MOLTBOOK",
                color = MoltAccent,
                fontSize = SMALL,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                lineHeight = SMALL_LINE,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            if (live) {
                // Точка, а не слово: на 339 dp «сеть» съело бы место у времени визита,
                // а смысл («цифры только что из сети») читается по цвету и форме.
                Box(Modifier.size(4.dp).clip(CircleShape).background(MoltAccent))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = cadenceHint(stats),
                color = MoltIdle,
                fontSize = TINY,
                lineHeight = TINY_LINE,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(3.dp))

        // Главная цифра очереди — то, ради чего панель и открывают. Крупная, но не
        // во всю высоту: раньше она съедала треть экрана ради одного числа.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = stats.awaitingReply.toString(),
                color = if (stats.awaitingReply > 0) MoltAccent else Color(0xFF39FF88),
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 24.sp,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "ждут ответа",
                color = MoltIdle,
                fontSize = SMALL,
                lineHeight = SMALL_LINE,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }

        Spacer(Modifier.height(5.dp))

        // 4×2, а не 3×3: восемь показателей заполняют сетку без пустой ячейки, а
        // лишний ряд — это ровно 25 dp высоты, ради которых карточка и была вдвое
        // выше нужного. Раскладка колонок при ширине 319 dp внутри карточки:
        // 4 × 76 + 3 × 5 = 319, подпись в 76 dp («ответов на них» ≈ 58 dp) влезает.
        StatGrid(
            listOf(
                stats.repliedTotal to "отвечено",
                stats.ourPosts to "наших постов",
                stats.repliesToOurPosts to "ответов на них",
                stats.repostsOfOurs to "репостов",
                stats.upvotesGiven to "апвоутов",
                stats.failedTotal to "сорвалось",
                stats.unread to "не прочитано",
                stats.karma to "карма",
            ),
        )

        Spacer(Modifier.height(6.dp))
        Divider()
        IntervalPicker()

        if (stats.pending.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Divider()
            Text(
                text = "ЖДУТ ОТВЕТА · ${stats.pending.size}",
                color = MoltIdle,
                fontSize = TINY,
                letterSpacing = 1.sp,
                lineHeight = TINY_LINE,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, bottom = 3.dp),
            )
            // Список ограничен по высоте и прокручивается: вопросы бывают длинные, а
            // панель открывают посмотреть счётчик, а не утонуть в стенке текста.
            Column(
                Modifier
                    .heightIn(max = PENDING_LIST_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                stats.pending.take(MAX_PENDING_ROWS).forEach { pending ->
                    Column(Modifier.padding(vertical = 2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = pending.author,
                                color = MoltAccent,
                                fontSize = SMALL,
                                lineHeight = SMALL_LINE,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(88.dp),
                            )
                            Text(
                                text = "«${pending.postTitle.take(40)}»",
                                color = MoltIdle,
                                fontSize = TINY,
                                lineHeight = TINY_LINE,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 5.dp),
                            )
                        }
                        Text(
                            // Русский перевод из того же модельного вызова, что и ответы.
                            // Пока перевода нет — показываем оригинал, а не пустую строку.
                            text = pending.summaryRu.ifBlank { pending.body.replace('\n', ' ') },
                            color = Color(0xFFBDBDBD),
                            fontSize = SMALL,
                            lineHeight = SMALL_LINE,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (stats.lastError != null) {
            Text(
                text = "последняя ошибка: ${stats.lastError}",
                color = Color(0xFFFF6B6B),
                fontSize = TINY,
                lineHeight = TINY_LINE,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

/** Список вопросов не выше этого: панель не должна занимать весь экран ради ленты. */
private val PENDING_LIST_MAX_HEIGHT = 112.dp

private const val MAX_PENDING_ROWS = 6
const val TAG_PANEL_MOLTBOOK = "panel_moltbook"

/** Колонок в сетке показателей: 8 / 4 = 2 ряда без хвоста. */
private const val STAT_COLUMNS = 4

/** Чипов в ряду: 5 вариантов / 3 = два ряда, третий чип третьего ряда не бывает. */
private const val INTERVAL_CHIPS_PER_ROW = 3

/** Ширина одного чипа: (319 - 2 × 6) / 3 = 102 dp — текст «30 мин» влезает с запасом. */
private val INTERVAL_CHIP_WIDTH = 102.dp

/** Зазор между чипами: ряд из трёх даёт 3 × 102 + 2 × 6 = 318 dp при 319 доступных. */
private val INTERVAL_CHIP_GAP = 6.dp

/**
 * Периодичность тика: как часто агент ходит на Moltbook.
 *
 * Ряды собираем вручную из [Row], а не через FlowRow: FlowRow в этой версии
 * Compose помечен ExperimentalLayoutApi, и карточка в шапке — не то место, где
 * стоит тащить opt-in предупреждение ради пяти кнопок.
 *
 * Значение перечитывается из prefs ПОСЛЕ [MoltbookScheduler.setUserIntervalMinutes],
 * а не берётся из нажатого чипа: prefs — единственный источник правды, там же
 * мусор из ручной правки сводится к 0, и показывать юзеру нужно ровно то, что
 * агент потом реально применит.
 */
@Composable
private fun IntervalPicker() {
    val context = LocalContext.current
    var chosen by remember { mutableIntStateOf(MoltbookScheduler.userIntervalMinutes(context)) }
    Column(Modifier.padding(top = 6.dp)) {
        Text(
            text = "ПЕРИОД ТИКА · 0 = авто, паузу выбирает агент",
            color = MoltIdle,
            fontSize = TINY,
            letterSpacing = 0.5.sp,
            lineHeight = TINY_LINE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(INTERVAL_CHIP_GAP)) {
            MoltbookScheduler.INTERVAL_CHOICES_MINUTES.chunked(INTERVAL_CHIPS_PER_ROW).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(INTERVAL_CHIP_GAP)) {
                    row.forEach { minutes ->
                        IntervalChip(
                            minutes = minutes,
                            selected = minutes == chosen,
                            onClick = {
                                MoltbookScheduler.setUserIntervalMinutes(context, minutes)
                                chosen = MoltbookScheduler.userIntervalMinutes(context)
                            },
                            modifier = Modifier.width(INTERVAL_CHIP_WIDTH),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IntervalChip(
    minutes: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Цвет обводки — отдельно, иначе цепочка модификаторов разъезжается на две строки
    // и ktlint требует переносить точку в конец предыдущей, ломая порядок рисования.
    val borderColor = if (selected) MoltAccent.copy(alpha = 0.7f) else Color.Transparent
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) MoltAccent.copy(alpha = 0.14f) else MoltChipOff)
                .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                .clickable { onClick() }
                .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = intervalLabel(minutes),
            color = if (selected) MoltAccent else MoltIdle,
            fontSize = BODY,
            lineHeight = BODY_LINE,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Подпись варианта периодичности. Ноль показываем как «авто», а не «0 мин»:
 * это не «каждую нулевую минуту», а «агент сам выбирает паузу» — и юзер должен
 * видеть разницу, а не гадать, что список начинается с нуля.
 */
private fun intervalLabel(minutes: Int): String =
    when {
        minutes <= 0 -> "авто"
        minutes % 60 == 0 -> "${minutes / 60} ч"
        else -> "$minutes мин"
    }

@Composable
private fun StatGrid(cells: List<Pair<Int, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        cells.chunked(STAT_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { (value, label) ->
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "$value",
                            color = MoltValue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = BODY_LINE,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = label,
                            color = MoltIdle,
                            fontSize = TINY,
                            lineHeight = TINY_LINE,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(STAT_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MoltAccent.copy(alpha = 0.15f)))
}

private val stampFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * Когда агент вернётся — из того, что он сам записал в базу. Раньше здесь стояло
 * «агент вернётся через в прошлый раз 22:53»: подпись обещала будущее и тут же
 * показывала прошлое.
 */
private fun cadenceHint(stats: MoltbookLedger.Stats): String {
    val next = stats.nextVisitAt
    if (next > System.currentTimeMillis()) return "вернётся в " + stampFormat.format(Date(next))
    if (stats.lastTickAt == 0L) return "ещё не был"
    return if (stats.nextVisitAt > 0L) {
        "пора: был в " + stampFormat.format(Date(stats.lastTickAt))
    } else {
        "был в " + stampFormat.format(Date(stats.lastTickAt))
    }
}
