package org.opencode.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opencode.mobile.server.ApiAsk
import org.opencode.mobile.server.AskCard
import org.opencode.mobile.server.AskOption
import org.opencode.mobile.server.AskQuestion

private const val ASK_PLATE = 0xFF14161A
private const val ASK_PLATE_EDGE = 0xFF6B5326

/** Accent: the header of a question and the bar next to it. */
private const val ASK_ACCENT = 0xFFC79253

private const val ASK_TEXT = 0xFFE6E6E6
private const val ASK_DIM = 0xFF7E838C

/** The question itself: warm ivory italics, apart from the labels around it. */
private const val ASK_ASK_TEXT = 0xFFF5E9D8

/** Divider between the questions of one turn. */
private const val ASK_RULE = 0xFF262A31

private const val ASK_BODY = 0xFF1A1D23
private const val ASK_CHIP_BG = 0xFF232830
private const val ASK_CHIP_EDGE = 0xFF3A4351
private const val ASK_CHIP_TEXT = 0xFFDCE1E8

private const val ASK_FIELD_BG = 0xFF17191D
private const val ASK_FIELD_EDGE = 0xFF2C313B
private const val ASK_CLEAR = 0x00000000L

private const val ASK_ALLOW = 0xFF3F7FBE
private const val ASK_ALLOW_EDGE = 0xFF2F6EA8
private const val ASK_ALLOW_ON = 0xFF14324F
private const val ASK_REJECT = 0xFFD98A8A
private const val ASK_REJECT_EDGE = 0xFF7A3F3F
private const val ASK_ERROR = 0xFFE05B5B

private const val ASK_CORNER = 11
private const val ASK_INNER_CORNER = 8
private const val ASK_CHIP_CORNER = 6

/**
 * How many option chips fit in one row.
 *
 * Two: on a 363 dp screen three labels collide, and a wrapped row costs more
 * height than it saves.
 */
private const val CHIP_PER_ROW = 2

/**
 * Safety limit for the body of the card, not the design.
 *
 * Two questions with options come to about 240 dp, so in practice this is
 * never reached and nothing scrolls. It exists only so that a session with
 * a dozen questions cannot push the field and the buttons off the screen,
 * where the request could no longer be answered.
 */
private const val ASK_BODY_MAX_DP = 300

/**
 * Colors of a button.
 *
 * A separate type, not three parameters: with the text and the handler the
 * button already has six, and detekt refuses functions longer than five
 * arguments.
 */
private data class AskSkin(
    val accent: Long,
    val edge: Long,
    val fill: Long,
)

/** The main action: filled, to tell it apart from the reject and from the text. */
private val SKIN_ALLOW = AskSkin(ASK_ALLOW, ASK_ALLOW_EDGE, ASK_ALLOW_ON)

/** Reject: an outline without a fill, quieter than the main action. */
private val SKIN_REJECT = AskSkin(ASK_REJECT, ASK_REJECT_EDGE, ASK_CLEAR)

/**
 * The card of a request the server is waiting on.
 *
 * One composable for both kinds of request, a command and a question. Only
 * `action` tells them apart: a command has nothing to choose.
 *
 * WHY IT IS SMALL. An earlier version outlined the question, every option
 * and the answer field; on a 363 dp screen two questions took about 400 dp,
 * almost half the display. Now the options are chips two per row, their
 * descriptions are merged into one thin line, the question is marked with a
 * bar instead of a frame, and every gap is halved. Two questions with two
 * options each now come to roughly 240 dp and fit without scrolling.
 */
@Composable
internal fun AskCardView(
    card: AskCard,
    busy: Boolean,
    error: String?,
    onReply: (String?) -> Unit,
    onReject: () -> Unit,
) {
    val question = card.ask.action == ApiAsk.QUESTION
    var answer by remember(card.ask.id) { mutableStateOf("") }
    val many = card.questions.size > 1
    val corner = RoundedCornerShape(ASK_CORNER.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .background(Color(ASK_PLATE), corner)
            .border(1.dp, Color(ASK_PLATE_EDGE), corner)
            .padding(6.dp),
    ) {
        if (!many) {
            AskHeader(card.title)
            Spacer(Modifier.height(4.dp))
        }
        Column(
            Modifier
                .heightIn(max = ASK_BODY_MAX_DP.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            card.questions.forEachIndexed { index, block ->
                if (index > 0) {
                    Spacer(Modifier.height(5.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(ASK_RULE)),
                    )
                    Spacer(Modifier.height(5.dp))
                }
                AskQuestionBlock(block, busy) { picked -> onReply(answerOf(block, picked, many)) }
            }
        }
        if (question) {
            Spacer(Modifier.height(5.dp))
            AskAnswerField(answer, busy) { answer = it }
        }
        Spacer(Modifier.height(5.dp))
        AskActions(
            busy = busy,
            canReply = !question || answer.isNotBlank(),
            onReply = onReply,
            onReject = onReject,
            message = answer.trim().ifBlank { null },
        )
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(error, color = Color(ASK_ERROR), fontSize = 9.sp)
        }
    }
}

/** Header with a short accent bar: how a single question begins. */
@Composable
private fun AskHeader(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(3.dp)
                .height(10.dp)
                .background(Color(ASK_ACCENT), RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(6.dp))
        Text(title, color = Color(ASK_ACCENT), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * One question: an accent bar with a header, the question itself, options as
 * chips, and the descriptions merged into one line.
 *
 * The bar replaces the frame: a frame around the question cost twenty
 * pixels of height per question and read less than a bar does.
 */
@Composable
private fun AskQuestionBlock(
    block: AskQuestion,
    busy: Boolean,
    onPick: (AskOption) -> Unit,
) {
    val corner = RoundedCornerShape(ASK_INNER_CORNER.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(ASK_BODY), corner)
            .padding(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(9.dp)
                    .background(Color(ASK_ACCENT), RoundedCornerShape(2.dp)),
            )
            if (block.header.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(block.header, color = Color(ASK_ACCENT), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            text = block.text,
            color = Color(ASK_ASK_TEXT),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontStyle = FontStyle.Italic,
            modifier = Modifier.padding(start = 9.dp, top = 2.dp),
        )
        if (block.options.isEmpty()) return@Column
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            maxItemsInEachRow = CHIP_PER_ROW,
        ) {
            block.options.forEach { opt ->
                AskOptionChip(Modifier.weight(1f), opt, busy) { onPick(opt) }
            }
        }
        val hint = block.options
            .map { it.description }
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        if (hint.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = hint,
                color = Color(ASK_DIM),
                fontSize = 9.sp,
                fontStyle = FontStyle.Italic,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 9.dp),
            )
        }
    }
}

/**
 * An option, as a chip over half the width.
 *
 * The label is one line with an ellipsis: a label wider than half the screen
 * cannot be read whole anyway, and a wrap would break the height of the row.
 */
@Composable
private fun AskOptionChip(
    modifier: Modifier,
    opt: AskOption,
    busy: Boolean,
    onPick: () -> Unit,
) {
    val corner = RoundedCornerShape(ASK_CHIP_CORNER.dp)
    Text(
        text = opt.label,
        color = if (busy) Color(ASK_DIM) else Color(ASK_CHIP_TEXT),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(Color(ASK_CHIP_BG), corner)
            .border(1.dp, Color(ASK_CHIP_EDGE), corner)
            .clickable(enabled = !busy, onClick = onPick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    )
}

/**
 * The label that goes to the agent.
 *
 * With several questions the bare label is not enough: "continue" without a
 * question is ambiguous, so the header of the block is added.
 */
private fun answerOf(
    block: AskQuestion,
    opt: AskOption,
    many: Boolean,
): String = if (!many || block.header.isBlank()) opt.label else "${block.header}: ${opt.label}"

/** A free-form answer. Not drawn for a command, there is nothing to answer. */
@Composable
private fun AskAnswerField(
    value: String,
    busy: Boolean,
    onChange: (String) -> Unit,
) {
    val corner = RoundedCornerShape(ASK_INNER_CORNER.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(ASK_FIELD_BG), corner)
            .border(1.dp, Color(ASK_FIELD_EDGE), corner)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = !busy,
            textStyle = TextStyle(color = Color(ASK_TEXT), fontSize = 11.sp),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text("Свой ответ…", color = Color(ASK_DIM), fontSize = 11.sp, fontStyle = FontStyle.Italic)
                }
                inner()
            },
        )
    }
}

/**
 * The buttons, each over half the width. [message] goes to the server if the
 * user typed something.
 *
 * [canReply] turns "Allow" off for a question with no text and no option
 * picked: there is nothing to send, and a press with no answer would still
 * close the question in silence. Options go past the button and work as
 * before.
 */
@Composable
private fun AskActions(
    busy: Boolean,
    canReply: Boolean,
    onReply: (String?) -> Unit,
    onReject: () -> Unit,
    message: String?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        AskButton(
            modifier = Modifier.weight(1f),
            text = if (busy) "Отправляю…" else "Разрешить",
            enabled = !busy && canReply,
            skin = SKIN_ALLOW,
            onClick = { onReply(message) },
        )
        AskButton(
            modifier = Modifier.weight(1f),
            text = "Отклонить",
            enabled = !busy,
            skin = SKIN_REJECT,
            onClick = onReject,
        )
    }
}

/**
 * A button with a frame and, for the main action, a fill.
 *
 * The actions used to be plain colored text on the plate, where they could
 * not be told from an ordinary label and "Allow" read as part of the question.
 */
@Composable
private fun AskButton(
    modifier: Modifier,
    text: String,
    enabled: Boolean,
    skin: AskSkin,
    onClick: () -> Unit,
) {
    val corner = RoundedCornerShape(ASK_CHIP_CORNER.dp)
    Text(
        text = text,
        color = if (enabled) Color(skin.accent) else Color(ASK_DIM),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
            .background(if (enabled) Color(skin.fill) else Color(ASK_CLEAR), corner)
            .border(1.dp, if (enabled) Color(skin.edge) else Color(ASK_RULE), corner)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    )
}
