package org.opencode.mobile.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Сводка Moltbook внутри секции диагностики.
 *
 * Раньше это была отдельная оранжевая карточка с собственным заголовком MOLTBOOK,
 * вложенная в карточку DiagSection, — «карточка в карточке» с двумя одинаковыми
 * шапками. С 17.11 фон, обводка и второй заголовок убраны: это обычные плотные
 * строки одной секции, те же цвета и размеры, что у её статуса и полей.
 *
 * Источник цифр: снимок `panel.json` от плагина и журнал будильника. У панели
 * ровно одна обязанность — честно показать, что она знает, и не выдавать
 * «не знаю» за ноль.
 */
internal val MoltAccent = Color(0xFFFFB020)
internal val MoltIdle = Color(0xFF8A8A8A)
internal val MoltCard = Color(0xFF14110C)
internal val MoltChipOff = Color(0xFF1E1B15)
internal val MoltValue = Color(0xFFE8E8E8)
private val MoltWarn = Color(0xFFFF6B6B)

/**
 * Единые размеры диагностики. Замер на реальном устройстве (OPPO CPH2747,
 * 3.5 px/dp) показал, что 11 sp текста с явным lineHeight дают плотную строку,
 * а шрифт без lineHeight раздувает её почти вдвое. Крупные числа здесь не нужны:
 * главная цифра «ждут ответа» теперь того же порядка, что и остальные показатели.
 */
internal val TINY = 9.sp
internal val TINY_LINE = 11.sp
internal val SMALL = 10.sp
internal val SMALL_LINE = 13.sp
internal val BODY = 11.sp
internal val BODY_LINE = 14.sp

/**
 * Снимок панели: файл, потом тик таймера.
 *
 * Опрос задаёт владелец экрана (секция Moltbook в диагностике): чтение файла
 * дешевле сети на порядок — панель открывают посмотреть, а не ждать. Плагин
 * переписывает снимок после каждого своего вызова, так что между тиками
 * панель спокойно показывает «данные от HH:mm», а не врёт свежестью.
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

/**
 * Сводка: очередь, сетка показателей, будильник, период и список неотвеченных.
 *
 * [state.reason] заполняется только отказом — «сервер не ответил», «снимка нет»,
 * «все сессии заняты». Успешное буждение в неё не попадает намеренно: время
 * последней работы и так видно по подписи снимка.
 */
@Composable
internal fun MoltbookPanel(
    state: MoltbookPanelState,
    modifier: Modifier = Modifier,
) {
    // Состояние рубильника живёт здесь, а не внутри кнопки: его читает ещё и
    // заголовок, который иначе продолжал бы обещать «раз в 2 часа» при
    // выключенном будильнике — то есть две строки одной секции врали бы
    // по-разному.
    val alarmOn = rememberAlarmEnabled()
    Column(
        modifier
            .fillMaxWidth()
            .testTag(TAG_PANEL_MOLTBOOK)
            .padding(top = 2.dp),
    ) {
        PanelHeader(state, alarmOn.value)
        Divider()
        MoltbookAlarmSwitch(alarmOn.value) { alarmOn.value = it }
        MoltbookIntervalPicker()
        if (state.pending.isNotEmpty()) {
            Divider()
            PendingList(state.pending, state.awaiting)
        }
        if (state.reason.isNotBlank()) RefusalNote(state.reason)
    }
}

/**
 * Очередь одной строкой, без крупного числа и без второго заголовка.
 *
 * Снимка нет — цифры нет: прочерк и серый цвет, а не ноль «все ответили».
 */
@Composable
private fun PanelHeader(
    state: MoltbookPanelState,
    alarmOn: Boolean,
) {
    val count = if (state.ok) state.awaiting.toString() else UNKNOWN
    val color =
        when {
            !state.ok -> MoltIdle
            state.awaiting > 0 -> MoltAccent
            else -> DiagTone.OK.color()
        }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            "ОЧЕРЕДЬ",
            color = MoltIdle,
            fontSize = TINY,
            lineHeight = TINY_LINE,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            count,
            color = color,
            fontSize = BODY,
            lineHeight = BODY_LINE,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "ждут ответа",
            color = MoltIdle,
            fontSize = TINY,
            lineHeight = TINY_LINE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Text(
        // Свежесть снимка и ритм тика — одно и то же поле, пока будильник
        // включён; при выключенном там обязано стоять «выключен», иначе
        // секция обещала бы тики, которых не будет.
        text = if (alarmOn) cadenceHint(state) else "будильник выключен",
        color = MoltIdle,
        fontSize = TINY,
        lineHeight = TINY_LINE,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 1.dp),
    )
    Spacer(Modifier.height(3.dp))
    // 4×2, а не 3×3: восемь показателей заполняют сетку без пустой ячейки, а
    // лишний ряд — это ровно 25 dp высоты, ради которых карточка и была вдвое
    // выше нужного. Подпись в 76 dp («ответов на них» ≈ 58 dp) влезает.
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

/** Список вопросов: ограничен по высоте и прокручивается, секция не тонет в тексте. */
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
        modifier = Modifier.padding(top = 3.dp, bottom = 2.dp),
    )
    // LazyColumn, а не Column с verticalScroll: в снимке бывает до двухсот
    // неотвеченных, и обычный Column компонует все строки целиком при каждом
    // кадре прокрутки — прокрутка дёргалась бы именно так, как её не должно
    // быть видно. Высота уменьшена с 280 dp: в диагностике секция и так длинная.
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
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun PendingRow(question: MoltbookQuestion) {
    val translated = question.bodyRu.isNotBlank()
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
                modifier = Modifier.width(78.dp),
            )
            Text(
                // Заголовок ветки - навигация, а не чтение: он указывает, ОТКУДА
                // пришёл вопрос. Обрезать можно.
                text = "«${question.title.take(40)}»",
                color = MoltIdle,
                fontSize = TINY,
                lineHeight = TINY_LINE,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 5.dp),
            )
        }
        // Русский текст, если плагин уже перевёл. Оригинал - только если
        // перевода ещё нет: показывать вместо него пустую строку значило бы
        // сказать юзеру «здесь нечего читать».
        if (translated) {
            Text(
                text = question.bodyRu,
                color = MoltValue,
                fontSize = SMALL,
                lineHeight = SMALL_LINE,
            )
        }
        // Оригинал показываем только когда перевода нет. Переведённый текст —
        // единственный, который нужен читателю: английская копия под ним не
        // «контекст», а второй абзац на чужом языке, и она отнимает место у
        // следующего вопроса.
        if (showOriginal(question.bodyRu, question.body)) {
            Text(
                text = question.body,
                color = MoltIdle,
                fontSize = SMALL,
                lineHeight = SMALL_LINE,
            )
        }
    }
}

/** Список вопросов не выше этого: ~6 строк вместо десяти с половиной раньше. */
private val PENDING_LIST_MAX_HEIGHT = 180.dp

const val TAG_PANEL_MOLTBOOK = "panel_moltbook"

/** Колонок в сетке показателей: 8 / 4 = 2 ряда без хвоста. */
private const val STAT_COLUMNS = 4

@Composable
private fun StatGrid(cells: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        cells.chunked(STAT_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { (value, label) ->
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = value,
                            color = if (value == UNKNOWN) MoltIdle else MoltValue,
                            fontSize = SMALL,
                            lineHeight = SMALL_LINE,
                            fontWeight = FontWeight.Bold,
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
    Box(Modifier.fillMaxWidth().height(1.dp).background(MoltIdle.copy(alpha = 0.12f)))
}
