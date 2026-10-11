package org.opencode.mobile.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import org.opencode.mobile.social.MoltbookPanelSource
import org.opencode.mobile.social.MoltbookPanelState
import org.opencode.mobile.social.MoltbookQuestion
import org.opencode.mobile.social.UNKNOWN
import org.opencode.mobile.social.cadenceHint
import org.opencode.mobile.social.countLabel
import org.opencode.mobile.social.karmaLabel
import org.opencode.mobile.social.pendingHeadline

/**
 * Иконка «М» и панель Moltbook в шапке чата.
 *
 * Отдельный файл, а не ещё 200 строк в [ChatOverlay]: он уже на 4000+ строк, и
 * наползающая шапка — ровно та причина, по которой его потом невозможно читать.
 *
 * Источник цифр сменился целиком. Раньше панель сама открывала ledger и сама
 * ходила на сайт за кармой, а время визита брала из тикера. Теперь тикера нет,
 * и вместо него два файла, которые пишут те, кто действительно работает:
 * снимок `panel.json` от плагина и журнал будильника. Поэтому у панели ровно
 * одна обязанность — честно показать, что она знает, и не выдавать «не знаю»
 * за ноль.
 */
internal val MoltAccent = Color(0xFFFFB020)
internal val MoltIdle = Color(0xFF8A8A8A)
private val MoltCard = Color(0xFF14110C)
internal val MoltChipOff = Color(0xFF1E1B15)
private val MoltValue = Color(0xFFE8E8E8)
private val MoltWarn = Color(0xFFFF6B6B)

/**
 * Панель целиком нарисована с ЗАДАННЫМ lineHeight — без него Compose берёт
 * межстрочный интервал от шрифта и раздувает строку почти вдвое.
 *
 * Замер на реальном устройстве (OPPO CPH2747, 3.5 px/dp) показал 219 dp карточки
 * на 85 dp текста: 61% высоты уходило в воздух между строками сетки. Уплотнение
 * до 10-11 sp / lineHeight 13-15 sp съело эту пустоту, и в неё же влезла
 * настройка периодичности — без неё карточка всё равно была бы короче.
 */
internal val TINY = 9.sp
internal val TINY_LINE = 11.sp
private val SMALL = 10.sp
private val SMALL_LINE = 13.sp
internal val BODY = 11.sp
internal val BODY_LINE = 14.sp

/**
 * Снимок панели: файл, потом тик таймера.
 *
 * Опрос раз в [org.opencode.mobile.ui.ChatOverlay] MOLTBOOK_PANEL_REFRESH_MS,
 * а чтение файла дешевле сети на порядок — панель открывают посмотреть, а не
 * ждать. Плагин переписывает снимок после каждого своего вызова, так что между
 * тиками карточка спокойно показывает «данные от HH:mm», а не врёт свежестью.
 */
@Composable
internal fun rememberMoltbookState(refreshKey: Int): MoltbookPanelState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(MoltbookPanelState()) }
    LaunchedEffect(refreshKey) {
        // Упавшее чтение гасим тихо и оставляем предыдущее: панель — украшение,
        // и бросать на неё экран ошибки незачем. Причину всё равно видно в полях.
        val fresh = runCatching { withContext(Dispatchers.IO) { MoltbookPanelSource.read(context) } }.getOrNull()
        if (fresh != null) state = fresh
    }
    return state
}

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
 * Карточка Moltbook: сводка по журналу, кому именно мы не ответили, настройка
 * периодичности и причина, по которой агента не разбудили.
 *
 * [state.reason] заполняется только отказом — «сервер не ответил», «снимка нет»,
 * «все сессии заняты». Успешное буждение в неё не попадает намеренно: время
 * последней работы и так видно по снимку.
 */
@Composable
internal fun MoltbookPanel(
    state: MoltbookPanelState,
    modifier: Modifier = Modifier,
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
        PanelHeader(state)
        Spacer(Modifier.height(6.dp))
        Divider()
        MoltbookIntervalPicker()

        if (state.pending.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Divider()
            PendingList(state.pending, state.awaiting)
        }

        if (state.reason.isNotBlank()) RefusalNote(state.reason)
    }
}

/**
 * Заголовок карточки: имя, свежесть снимка и главная цифра очереди.
 *
 * Цифра крупная, но не во всю высоту: раньше она съедала треть экрана ради
 * одного числа.
 */
@Composable
private fun PanelHeader(state: MoltbookPanelState) {
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
        Text(
            text = cadenceHint(state),
            color = MoltIdle,
            fontSize = TINY,
            lineHeight = TINY_LINE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    Spacer(Modifier.height(3.dp))

    AwaitingCount(state)

    Spacer(Modifier.height(5.dp))

    // 4×2, а не 3×3: восемь показателей заполняют сетку без пустой ячейки, а
    // лишний ряд — это ровно 25 dp высоты, ради которых карточка и была вдвое
    // выше нужного. Раскладка колонок при ширине 319 dp внутри карточки:
    // 4 × 76 + 3 × 5 = 319, подпись в 76 dp («ответов на них» ≈ 58 dp) влезает.
    StatGrid(
        listOf(
            state.answered.toString() to "отвечено",
            state.ourPosts.toString() to "наших постов",
            state.repliesToOurs.toString() to "ответов на них",
            state.reposts.toString() to "репостов наших",
            state.upvotes.toString() to "апвоутов",
            state.failed.toString() to "сорвалось",
            countLabel(state.unread) to "не прочитано",
            countLabel(state.karma) to karmaLabel(state),
        ),
    )
}

/**
 * Крупная цифра «ждут ответа» — то, ради чего карточка и открывается.
 *
 * Вынесена отдельно не ради длины, а ради одного правила: снимка нет — цифры
 * нет. Ноль в этом месте означал бы «все ответили», хотя плагин ничего не
 * прочитал (см. MoltbookPanelState.stale), поэтому при отказе здесь прочерк и
 * цвет «мы не знаем», а не спокойный зелёный.
 */
@Composable
private fun AwaitingCount(state: MoltbookPanelState) {
    val text = if (state.ok) state.awaiting.toString() else UNKNOWN
    val color =
        when {
            !state.ok -> MoltIdle
            state.awaiting > 0 -> MoltAccent
            else -> Color(0xFF39FF88)
        }

    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = text,
            color = color,
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
}

/** Список вопросов: ограничен по высоте и прокручивается, панель не тонет в тексте. */
@Composable
private fun PendingList(
    pending: List<MoltbookQuestion>,
    awaiting: Int,
) {
    val hidden = (awaiting - pending.size).coerceAtLeast(0)
    Text(
        // Число ждущих - это сколько их ВСЕГО, крупной цифрой выше. Список под ним
        // присылает плагин (его предел больше любой реальной очереди), и если
        // когда-нибудь упрётся, заголовок обязан об этом сказать - иначе снова
        // получится «67 наверху, 6 внизу». См. pendingHeadline.
        text = pendingHeadline(hidden),
        color = MoltIdle,
        fontSize = TINY,
        letterSpacing = 1.sp,
        lineHeight = TINY_LINE,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp, bottom = 3.dp),
    )
    // Список длинный и скроллится сам: карточка живёт в неподвижной шапке
    // чата, поэтому без своей прокрутки семьдесят вопросов просто уедут за
    // нижний край экрана. Внутренний скролл вместо общего - сознательный
    // размен: жест в этом месте не прокручивает переписку.
    //
    // Именно LazyColumn, а не Column с verticalScroll: в снимке бывает до двухсот
    // неотвеченных, и обычный Column компонует все строки целиком при каждом
    // кадре прокрутки - то есть прокрутка дёргалась бы ровно так, как её не должно
    // быть видно. LazyColumn держит в памяти только видимое.
    LazyColumn(
        Modifier.heightIn(max = PENDING_LIST_MAX_HEIGHT),
    ) {
        items(pending, key = { it.id }) { question ->
            PendingRow(question)
        }
    }
}

/**
 * Красная строка отказа.
 *
 * Заполняется ТОЛЬКО отказом — «сервер не ответил», «снимка нет», «все сессии
 * заняты». Успешное буждение в неё не попадает намеренно: время последней
 * работы и так видно по подписи снимка.
 */
@Composable
private fun RefusalNote(reason: String) {
    Text(
        text = reason,
        color = MoltWarn,
        fontSize = TINY,
        lineHeight = TINY_LINE,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 5.dp),
    )
}

@Composable
private fun PendingRow(question: MoltbookQuestion) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = question.author,
                color = MoltAccent,
                fontSize = SMALL,
                lineHeight = SMALL_LINE,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(88.dp),
            )
            Text(
                text = "«${question.title.take(40)}»",
                color = MoltIdle,
                fontSize = TINY,
                lineHeight = TINY_LINE,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 5.dp),
            )
        }
        Text(
            // Перевода нет — показываем оригинал, а не пустую строку. Сжатая
            // одна строка: сырой текст из ленты здесь лёг бы на пол-экрана.
            text = question.body,
            color = Color(0xFFBDBDBD),
            fontSize = SMALL,
            lineHeight = SMALL_LINE,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Список вопросов не выше этого.
 *
 * 280 dp — примерно половина экрана: видно сразу десяток с половиной вопросов,
 * остальное крутится внутри списка. Раньше было 112 dp, и шесть строк выглядели
 * как конец данных, хотя всего их семьдесят с лишним.
 */
private val PENDING_LIST_MAX_HEIGHT = 280.dp

const val TAG_PANEL_MOLTBOOK = "panel_moltbook"

/** Колонок в сетке показателей: 8 / 4 = 2 ряда без хвоста. */
private const val STAT_COLUMNS = 4

@Composable
private fun StatGrid(cells: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        cells.chunked(STAT_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { (value, label) ->
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = value,
                            color = if (value == UNKNOWN) MoltIdle else MoltValue,
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
