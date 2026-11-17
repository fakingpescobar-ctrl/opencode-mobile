package org.opencode.mobile.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opencode.mobile.DiagnosticsCommand

/**
 * Детали экрана «Диагностика», вынесенные из самого экрана.
 *
 * Зачем отдельным файлом: экран вырос до 1 386 строк, и в нём уже было не
 * разобрать, где кончается вёрстка строки и начинается вёрстка секции. Плитки,
 * сворачиваемые секции и «требует действия» — три кирпича, из которых теперь
 * собран весь экран; здесь они в одном месте, и правка вида не трогает логику
 * сбора фактов.
 *
 * Плотность намеренно оставлена прежней (9/10/11 sp): экран читают на телефоне
 * в 3,5 px/dp, и «покрасивее» здесь означает «короче и с акцентами», а не
 * «крупнее шрифт». Крупные шрифты уже один раз превратили карточку Moltbook
 * в 61% пустоты между строками.
 */

/** Цвета состояний: те же, что у статуса сервера и валидации, — зелёный значит одно и то же по всему экрану. */
private const val DIAG_OK_ARGB = 0xFF7BD88FL
private const val DIAG_WARN_ARGB = 0xFFFFC107L
private const val DIAG_BAD_ARGB = 0xFFFF6F5AL
private const val DIAG_IDLE_ARGB = 0xFF8A8A8AL

/** Сколько плиток в ряд. Две, потому что третья превращает подписи в нечитаемые обрубки. */
private const val TILES_PER_ROW = 2

/**
 * Тон факта. Отдельный тип, а не цвет напрямую: бейдж секции, точка в плитке и
 * значение строки обязаны меняться вместе, а `Color` в параметре позволял
 * покрасить одно и забыть другое.
 */
internal enum class DiagTone {
    OK,
    WARN,
    BAD,
    IDLE,
    ;

    fun color(): Color =
        when (this) {
            OK -> Color(DIAG_OK_ARGB)
            WARN -> Color(DIAG_WARN_ARGB)
            BAD -> Color(DIAG_BAD_ARGB)
            IDLE -> Color(DIAG_IDLE_ARGB)
        }

    /** Подпись для бейджа: короткая, потому что живёт в заголовке секции. */
    fun badge(): String? =
        when (this) {
            OK -> "ок"
            WARN -> "внимание"
            BAD -> "проблема"
            IDLE -> null
        }
}

/** Один факт для плитки: что проверяли и чем это кончилось. */
internal data class DiagFact(
    val label: String,
    val value: String,
    val tone: DiagTone,
)

/**
 * Сетка плиток по две в ряд.
 *
 * Плитки — ответ на «что сломалось» без прокрутки: шесть фактов, которые чаще
 * всего и чинят, видны сразу под шапкой. Нечётное число добивается пустым
 * весом, иначе последняя плитка растянулась бы на всю ширину и выглядела
 * ошибкой вёрстки.
 */
@Composable
internal fun DiagTiles(facts: List<DiagFact>) {
    Column(Modifier.fillMaxWidth()) {
        facts.chunked(TILES_PER_ROW).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (fact in pair) {
                    DiagTile(fact, Modifier.weight(1f))
                }
                if (pair.size < TILES_PER_ROW) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DiagTile(
    fact: DiagFact,
    modifier: Modifier,
) {
    Surface(
        color = MoltChipOff,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(fact.tone.color()),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    fact.label.uppercase(),
                    color = MoltIdle,
                    fontSize = TINY,
                    lineHeight = TINY_LINE,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                fact.value,
                // Тон красит значение только когда он что-то значит: серое
                // «нет данных» не должно читаться как приговор.
                color = if (fact.tone == DiagTone.IDLE) MoltValue else fact.tone.color(),
                fontSize = SMALL,
                lineHeight = SMALL_LINE,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Секция-карточка со сворачиванием.
 *
 * Все секции открываются **свёрнутыми**, всегда. Раньше было «закрыта, если в
 * ней всё в порядке, и раскрыта, если есть проблема» — и это плохо
 * масштабировалось: стоило покраснеть трём секциям, как экран открывался
 * наполовину развёрнутым, и это читалось как «само открылось» (17.11).
 * Признак проблемы и так стоит бейджем в заголовке («проблема» / «внимание»),
 * а подробности человек открывает сам — тем же тапом, что и любую другую
 * карточку. Ни один факт не удалён, изменился только стартовый вид.
 *
 * Кликабелен заголовок целиком, а не треугольник: попасть в глифу 10 sp
 * пальцем нереально, и промах раньше означал «ничего не произошло».
 */
@Composable
internal fun DiagSection(
    title: String,
    tone: DiagTone,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val focus by DiagnosticsCommand.focusRequest.collectAsState()
    val intoView = remember { BringIntoViewRequester() }
    // Просьба моста «покажи секцию»: раскрыть и подтянуть в кадр. Сверяем
    // заголовок здесь, а не в команде: список секций — это композабл, и второй
    // список заголовков в состоянии однажды разошёлся бы с первым.
    //
    // Просьба применяется ОДИН раз на `seq`, а не на каждую новую композицию:
    // `focusRequest` живёт в синглтоне и переживает закрытие экрана, поэтому
    // старая просьба иначе срабатывала бы снова при каждом открытии и
    // раскрывала свою секцию (17.11 — так была раскрыта «Журнал событий»,
    // у которой стартовое раскрытие вообще не запрашивалось).
    LaunchedEffect(focus?.seq) {
        val request = focus ?: return@LaunchedEffect
        if (!title.coversSection(request.section)) return@LaunchedEffect
        if (!DiagnosticsCommand.claimFocus(request)) return@LaunchedEffect
        if (request.expand) expanded = true
        // Ждём кадр: у раскрытой секции высота другая, и до перестройки
        // bringIntoView считал бы границы старой, свёрнутой карточки.
        // Возврат не нужен, но и пустой лямбдой его не оставить: detekt ругает
        // пустые блоки, а комментарий внутри блока пустоту не отменяет.
        withFrameNanos { it }
        intoView.bringIntoView()
    }
    Surface(
        color = MoltCard,
        shape = RoundedCornerShape(10.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(intoView)
                .padding(bottom = 6.dp),
    ) {
        Column {
            DiagSectionHeader(title, tone, expanded) { expanded = !expanded }
            if (expanded) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) { content() }
            }
        }
    }
}

/**
 * Похож ли заголовок секции на просьбу моста.
 *
 * Сравнение без регистра и по вхождению: агенту проще сказать «модели», чем
 * угадывать точный заголовок «Модели агента», а `contains` терпит и хвост в
 * скобках у секций вроде «Озвучка (Supertonic, offline)».
 */
private fun String.coversSection(query: String): Boolean {
    val needle = query.trim().lowercase()
    return needle.isNotEmpty() && lowercase().contains(needle)
}

/**
 * Заголовок секции: точка тона, капс-название, бейдж и треугольник.
 *
 * Вынесен из [DiagSection], чтобы та не упиралась в лимит длины функции detekt:
 * вся логика раскрытия — три строки, а разметки на заголовок уходит полсотни.
 */
@Composable
private fun DiagSectionHeader(
    title: String,
    tone: DiagTone,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(tone.color()),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            title.uppercase(),
            color = MoltAccent,
            fontSize = TINY,
            lineHeight = TINY_LINE,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val badge = tone.badge()
        if (badge != null) {
            Text(
                badge,
                color = tone.color(),
                fontSize = TINY,
                lineHeight = TINY_LINE,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
        }
        // Треугольник текстом, а не иконкой: та же глифа есть во всех системных
        // шрифтах и не тянет за собой material-icons.
        Text(
            if (expanded) "\u25BE" else "\u25B8",
            color = MoltIdle,
            fontSize = SMALL,
            lineHeight = SMALL_LINE,
        )
    }
}

/**
 * Пункт «требует действия»: что не так, чем это грозит и одна кнопка починки.
 *
 * Отдельно от секций, потому что это единственное место, где экран не
 * рассказывает, а предлагает нажать. Раньше такие кнопки были размазаны по
 * пяти секциям, и «что мне сейчас сделать» требовало прочитать весь экран.
 */
@Composable
internal fun DiagActionRow(
    title: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        color = MoltChipOff,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(DIAG_BAD_ARGB)),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    title,
                    color = MoltValue,
                    fontSize = SMALL,
                    lineHeight = SMALL_LINE,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                detail,
                color = MoltIdle,
                fontSize = TINY,
                lineHeight = TINY_LINE,
                modifier = Modifier.padding(top = 1.dp),
            )
            downloadButton(actionLabel, onClick = onAction)
        }
    }
}

/**
 * Включена ли служба доступности — по системе, а не по нашему представлению.
 *
 * Нужны обе стороны: «включена в настройках» (этот список) и «система её
 * привязала к процессу» (`MediaUiAutomation.isEnabled()`). Между ними бывает
 * зазор, и именно он один раз дал 409 «служба не включена» при включённом
 * тумблере.
 */
internal fun accessibilityServiceEnabled(context: Context): Boolean =
    runCatching {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo?.serviceInfo?.packageName == context.packageName
        }
    }.getOrDefault(false)
