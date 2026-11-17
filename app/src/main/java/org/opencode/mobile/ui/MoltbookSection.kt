package org.opencode.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencode.mobile.social.MoltbookRegistration
import org.opencode.mobile.social.MoltbookScanClient
import org.opencode.mobile.social.agentStatus
import org.opencode.mobile.social.maskedMoltbookKey
import org.opencode.mobile.social.readMoltbookKey
import org.opencode.mobile.social.registerAgent
import org.opencode.mobile.social.safeClaimUrl
import org.opencode.mobile.social.saveMoltbookKey
import org.opencode.mobile.social.suggestedAgentName

/**
 * Секция Moltbook в диагностике. Раньше её карточка висела в шапке чата — на
 * 363 dp она теснила MCP-чип и саму переписку, теперь живёт здесь рядом с
 * «Яндекс Музыкой» и памятью: всё, что человек настраивает один раз.
 *
 * Вид переписан 17.11: внутри секции больше нет вложенной карточки со вторым
 * заголовком MOLTBOOK. Статус, поля, действия и счётчики — это плотные строки
 * одного контейнера, те же 9/10/11 sp и тот же зелёный акцент действий, что и
 * у остальной диагностики. Оранжевым остаётся только заголовок самой секции.
 *
 * Два пути авторизации, оба явные. «Зарегистрировать» дёргает сайт и кладёт
 * новый `api_key` в `moltkey` — но только после второго подтверждения, если
 * ключ уже есть: молча перезаписать рабочий ключ `opencodekz` было бы плохо.
 * «Сохранить токен» сохраняет существующий ключ. Claim (пост в X) человек делает
 * вне приложения: мы открываем ссылку и копируем код, а статус честно держим
 * `pending_claim`, пока сайт не скажет `claimed`.
 */

private const val PANEL_REFRESH_MS = 10_000L
private const val SCAN_EVERY_MS = 5 * 60_000L
private const val STATUS_POLL_MS = 60_000L
private const val NO_REASON = "Moltbook не ответил, попробуйте ещё раз"
private const val DEFAULT_DESCRIPTION = "OpenCode Mobile — автономный агент, отвечает на вопросы в Moltbook"

@Composable
internal fun moltbookSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { MoltbookSectionState() }
    val key = state.key

    LaunchedEffect(state) { state.load(context) }
    // Опрос статуса живёт, пока есть ключ: регистрация и ручной токен оба
    // переводят агента в pending_claim, и только сайт знает, когда он станет
    // claimed. Ключ эффекта — сам ключ, а не status: смена текста не должна
    // перезапускать цикл и сдвигать его пятиминутный такт.
    LaunchedEffect(key) {
        if (key == null) return@LaunchedEffect
        while (true) {
            delay(STATUS_POLL_MS)
            state.refreshStatus()
        }
    }
    val refresh = rememberMoltbookRefresh(context)

    val (headline, detail, ok) = state.texts(key)
    val claim = state.claim
    statusLine(headline, detail, ok)
    if (claim != null) {
        claimBlock(claim, context, scope, state)
    } else {
        Spacer(Modifier.height(4.dp))
        fieldBlock("Имя агента", state.name, { state.name = it }, "oc_...")
        Spacer(Modifier.height(3.dp))
        fieldBlock("api_key", state.token, { state.token = it }, "moltbook_…")
        authActions(state, scope, context)
    }
    Spacer(Modifier.height(6.dp))
    MoltbookPanel(rememberMoltbookState(refresh))
}

/**
 * Чтение снимка и скан уехали сюда из шапки. Пока секция раскрыта, снимок
 * перечитывается раз в 10 с, а плагин просят обновить ленту раз в 5 минут.
 * Эффекты живут здесь, чтобы их отмена совпадала со сворачиванием секции.
 */
@Composable
private fun rememberMoltbookRefresh(context: Context): Int {
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(PANEL_REFRESH_MS)
            refresh++
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            MoltbookScanClient.requestScan(context)
            delay(SCAN_EVERY_MS)
        }
    }
    return refresh
}

/** Статус одной строкой: точка тона, короткий заголовок, бейдж и пояснение под ним. */
@Composable
private fun statusLine(
    headline: String,
    detail: String,
    ok: Boolean?,
) {
    val color =
        when (ok) {
            true -> DiagTone.OK.color()
            false -> DiagTone.BAD.color()
            null -> MoltValue
        }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            headline,
            color = color,
            fontSize = SMALL,
            lineHeight = SMALL_LINE,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (ok == true) {
            Spacer(Modifier.width(4.dp))
            Text("ок", color = color, fontSize = TINY, lineHeight = TINY_LINE, maxLines = 1)
        }
    }
    Text(
        detail,
        color = MoltIdle,
        fontSize = TINY,
        lineHeight = TINY_LINE,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 12.dp, top = 1.dp),
    )
}

/** Компактное поле: подпись, тёмная подложка без отдельной карточки, 10 sp. */
@Composable
private fun fieldBlock(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
) {
    Text(label, color = MoltIdle, fontSize = TINY, lineHeight = TINY_LINE)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .background(MoltChipOff, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = MoltValue, fontSize = SMALL, lineHeight = SMALL_LINE),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, color = MoltIdle, fontSize = SMALL, lineHeight = SMALL_LINE)
                }
                inner()
            },
        )
    }
}

/** Действие-чип. [muted] — destructive-вариант: он не светится зелёным. */
@Composable
private fun actionChip(
    label: String,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MoltChipOff)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (muted) MoltIdle else DiagTone.OK.color(),
            fontSize = SMALL,
            lineHeight = SMALL_LINE,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Два действия в одном ряду: на 323 dp пара чипов лучше двух плашек подряд. */
@Composable
private fun authActions(
    state: MoltbookSectionState,
    scope: CoroutineScope,
    context: Context,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            state.key == null ->
                actionChip("Зарегистрировать", Modifier.weight(1f)) {
                    scope.launch { state.register(context) }
                }

            !state.confirmReplace ->
                actionChip("Новый агент", Modifier.weight(1f), muted = true) {
                    state.confirmReplace = true
                }

            else ->
                actionChip("Точно заменить?", Modifier.weight(1f), muted = true) {
                    state.confirmReplace = false
                    scope.launch { state.register(context) }
                }
        }
        actionChip("Сохранить токен", Modifier.weight(1f)) {
            scope.launch { state.saveToken(context) }
        }
    }
}

/** Что делать с только что выданным ключом: подтвердить его человеком. */
@Composable
private fun claimBlock(
    reg: MoltbookRegistration,
    context: Context,
    scope: CoroutineScope,
    state: MoltbookSectionState,
) {
    statusLine("Ключ создан — ${reg.status}", "Откройте ссылку и опубликуйте код в X", false)
    Spacer(Modifier.height(4.dp))
    Text(
        "КОД ПОДТВЕРЖДЕНИЯ",
        color = MoltIdle,
        fontSize = TINY,
        lineHeight = TINY_LINE,
        fontWeight = FontWeight.Bold,
    )
    Text(
        reg.verificationCode.ifBlank { "—" },
        color = DiagTone.OK.color(),
        fontSize = BODY,
        lineHeight = BODY_LINE,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 1.dp),
    )
    Row(
        Modifier.fillMaxWidth().padding(top = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        actionChip("Проверить статус", Modifier.weight(1f)) {
            scope.launch { state.refreshStatus() }
        }
        actionChip("Скопировать код", Modifier.weight(1f)) {
            copyToClipboard(context, reg.verificationCode)
        }
    }
    val safe = safeClaimUrl(reg.claimUrl)
    if (safe != null) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            actionChip("Открыть claim", Modifier.weight(1f)) { openClaim(context, safe) }
            actionChip("Копировать ссылку", Modifier.weight(1f)) {
                copyToClipboard(context, reg.claimUrl)
            }
        }
    } else if (reg.claimUrl.isNotBlank()) {
        statusLine("Ссылка не открыта", "Адрес вне moltbook.com — используйте код вручную", false)
    }
}

private fun copyToClipboard(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Moltbook: код подтверждения", text))
}

private fun openClaim(
    context: Context,
    url: String,
) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * Владелец состояния секции. Composable только рисует [MoltbookSectionState.key]
 * и зовёт четыре метода: все переходы живут здесь, а не размазаны по кнопкам.
 */
@Stable
private class MoltbookSectionState {
    var key by mutableStateOf<String?>(null)
        private set
    var name by mutableStateOf(suggestedAgentName())
    var token by mutableStateOf("")
    var status by mutableStateOf("")
        private set
    var message by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var claim by mutableStateOf<MoltbookRegistration?>(null)
        private set
    var confirmReplace by mutableStateOf(false)

    /** Заголовок, пояснение и цвет плашки: все ветвления в одном месте, вне @Composable. */
    fun texts(key: String?): Triple<String, String, Boolean?> {
        val headline =
            when {
                busy -> "Работаю…"
                error != null -> "Не получилось"
                key == null -> "Агент не зарегистрирован"
                else -> "Ключ: ${maskedMoltbookKey(key)}"
            }
        val detail =
            when {
                busy -> "Связь с Moltbook…"
                error != null -> "Ошибка: $error"
                confirmReplace -> "Старый ключ перестанет работать — повторите, чтобы заменить"
                message.isNotBlank() -> message
                else -> "Ключ и статус агента"
            }
        val ok =
            when {
                error != null -> false
                status == "claimed" -> true
                else -> null
            }
        return Triple(headline, detail, ok)
    }

    suspend fun load(context: Context) {
        val stored = withContext(Dispatchers.IO) { readMoltbookKey(context) }
        key = stored
        if (stored != null) refreshStatus()
    }

    suspend fun refreshStatus() {
        val current = key ?: return
        val result = withContext(Dispatchers.IO) { runCatching { agentStatus(current) } }
        result
            .onSuccess { value ->
                status = value
                message =
                    when (value) {
                        "claimed" -> "Агент подтверждён — публикация доступна"
                        "pending_claim" -> "Ожидает подтверждения: откройте claim-ссылку и опубликуйте код в X"
                        else -> "Статус сервера: $value"
                    }
                error = null
            }.onFailure { cause -> error = cause.message?.takeIf { it.isNotBlank() } ?: NO_REASON }
    }

    suspend fun register(context: Context) {
        busy = true
        error = null
        val result =
            withContext(Dispatchers.IO) {
                runCatching { registerAgent(name.trim(), DEFAULT_DESCRIPTION) }
            }
        result
            .onSuccess { reg -> store(context, reg) }
            .onFailure { cause -> error = cause.message?.takeIf { it.isNotBlank() } ?: NO_REASON }
        busy = false
    }

    suspend fun saveToken(context: Context) {
        val value = token.trim()
        if (value.isEmpty()) {
            error = "Вставьте api_key"
            return
        }
        busy = true
        error = null
        val result = withContext(Dispatchers.IO) { runCatching { saveMoltbookKey(context, value) } }
        result
            .onSuccess {
                key = value
                token = ""
                claim = null
                message = "Токен сохранён в moltkey"
                refreshStatus()
            }.onFailure { cause -> error = cause.message?.takeIf { it.isNotBlank() } ?: NO_REASON }
        busy = false
    }

    private suspend fun store(
        context: Context,
        reg: MoltbookRegistration,
    ) {
        val result = withContext(Dispatchers.IO) { runCatching { saveMoltbookKey(context, reg.apiKey) } }
        result
            .onSuccess {
                key = reg.apiKey
                claim = reg
                status = reg.status
                confirmReplace = false
                message = "Ключ сохранён. Откройте claim-ссылку и опубликуйте код в X"
            }.onFailure { cause -> error = cause.message?.takeIf { it.isNotBlank() } ?: NO_REASON }
    }
}
