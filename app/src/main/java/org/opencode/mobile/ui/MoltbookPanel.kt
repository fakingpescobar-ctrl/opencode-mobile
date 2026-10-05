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
import org.opencode.mobile.social.MoltbookLedger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Иконка «М» и панель статистики Moltbook в шапке чата.
 *
 * Отдельный файл, а не ещё 200 строк в [ChatOverlay]: он уже на 4000+ строк, и
 * наползающая шапка — ровно та причина, по которой его потом невозможно читать.
 *
 * Панель читает локальный ledger и НЕ ходит в сеть: счётчики должны быть видны
 * мгновенно и без будильника, иначе «сколько не отвечено» приходится угадывать.
 */
private val MoltAccent = Color(0xFFFFB020)
private val MoltIdle = Color(0xFF8A8A8A)
private val MoltCard = Color(0xFF14110C)

/**
 * Снимок статистики. SQLite трогаем только в [Dispatchers.IO]: база общая с тиком,
 * и чтение на главном потоке — это ровно тот способ получить ANR, который мы уже
 * один раз получили с сетью в onReceive.
 */
@Composable
internal fun rememberMoltbookStats(refreshKey: Int): MoltbookLedger.Stats {
    val context = LocalContext.current
    val ledger = remember { MoltbookLedger(context) }
    var stats by remember { mutableStateOf(MoltbookLedger.Stats()) }
    LaunchedEffect(refreshKey) {
        // Упавший ledger гасим тихо и пробуем на следующей перечитке: панель —
        // украшение, а не прибор, и бросать на неё экран ошибки незачем.
        stats = runCatching { withContext(Dispatchers.IO) { ledger.stats() } }.getOrElse { stats }
    }
    return stats
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
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
internal fun MoltbookPanel(
    stats: MoltbookLedger.Stats,
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
                .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MOLTBOOK", color = MoltAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.weight(1f))
            Text(text = cadenceHint(stats), color = MoltIdle, fontSize = 9.sp)
        }

        Spacer(Modifier.height(4.dp))

        // Главная цифра очереди — то, ради чего панель и открывают. Крупная, но не
        // во всю высоту: раньше она съедала треть экрана ради одного числа.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = stats.awaitingReply.toString(),
                color = if (stats.awaitingReply > 0) MoltAccent else Color(0xFF39FF88),
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.width(6.dp))
            Text("ждут ответа", color = MoltIdle, fontSize = 10.sp, modifier = Modifier.padding(bottom = 3.dp))
        }

        Spacer(Modifier.height(4.dp))

        StatGrid(
            listOf(
                stats.repliedTotal to "отвечено",
                stats.ourPosts to "наших постов",
                stats.repliesToOurPosts to "ответов на них",
                stats.repostsOfOurs to "репостов",
                stats.upvotesGiven to "апвоутов",
                stats.unread to "не прочитано",
                stats.karma to "карма",
                stats.failedTotal to "сорвалось",
            ),
        )

        if (stats.pending.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Divider()
            Text(
                text = "ЖДУТ ОТВЕТА · ${stats.pending.size}",
                color = MoltIdle,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 5.dp, bottom = 3.dp),
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
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "«${pending.postTitle.take(40)}»",
                                color = MoltIdle,
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        Text(
                            // Русский перевод из того же модельного вызова, что и ответы.
                            // Пока перевода нет — показываем оригинал, а не пустую строку.
                            text = pending.summaryRu.ifBlank { pending.body.replace('\n', ' ') },
                            color = Color(0xFFBDBDBD),
                            fontSize = 10.sp,
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
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

/** Список вопросов не выше этого: панель не должна занимать весь экран ради ленты. */
private val PENDING_LIST_MAX_HEIGHT = 128.dp

private const val MAX_PENDING_ROWS = 6
const val TAG_PANEL_MOLTBOOK = "panel_moltbook"

@Composable
private fun StatGrid(cells: List<Pair<Int, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        cells.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (value, label) ->
                    Column(Modifier.weight(1f)) {
                        Text("$value", color = Color(0xFFE8E8E8), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(label, color = MoltIdle, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
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
