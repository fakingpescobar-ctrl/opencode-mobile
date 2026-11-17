package org.opencode.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
 * Два пути авторизации, оба явные. «Зарегистрировать» дёргает сайт и кладёт
 * новый `api_key` в `moltkey` — но только после второго подтверждения, если
 * ключ уже есть: молча перезаписать рабочий ключ `opencodekz` было бы плохо.
 * «Вставить токен» сохраняет существующий ключ. Claim (пост в X) человек делает
 * вне приложения: мы открываем ссылку и копируем код, а статус честно держим
 * `pending_claim`, пока сайт не скажет `claimed`.
 */

private const val ACCENT = 0xFF7BD88F
private const val LABEL = 0xFF8A8A8A
private const val VALUE = 0xFFE6E6E6
private const val ERROR = 0xFFFF6F5A
private const val MUTED = 0xFFBDBDBD
private const val PLATE = 0xFF1E1E1E
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

    val (headline, detail, ok) = sectionTexts(state, key)
    statusPlate(headline, detail, ok)
    val claim = state.claim
    if (claim != null) {
        claimBlock(claim, context, scope, state)
    } else {
        registrationBlock(state, scope, context)
        Spacer(Modifier.height(4.dp))
        tokenBlock(state, scope, context)
    }
    Spacer(Modifier.height(8.dp))
    MoltbookPanel(rememberMoltbookState(refresh))
}

/** Заголовок, пояснение и цвет плашки считаются вместе, чтобы не раздувать функцию. */
private fun sectionTexts(state: MoltbookSectionState, key: String?): Triple<String, String, Boolean?> {
    val headline =
        when {
            state.busy -> "Работаю…"
            state.error != null -> "Не получилось"
            key == null -> "Агент не зарегистрирован"
            else -> "Ключ: ${maskedMoltbookKey(key)}"
        }
    val detail =
        when {
            state.busy -> "Связь с Moltbook…"
            state.error != null -> "Ошибка: ${state.error}"
            state.message.isNotBlank() -> state.message
            else -> "Ключ и статус агента"
        }
    val ok =
        when {
            state.error != null -> false
            state.status == "claimed" -> true
            else -> null
        }
    return Triple(headline, detail, ok)
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

/** Регистрация всегда явная: есть ключ — сначала подтверждение, потом вызов. */
@Composable
private fun registrationBlock(
    state: MoltbookSectionState,
    scope: CoroutineScope,
    context: Context,
) {
    Text("Новый агент", color = Color(LABEL), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    fieldBlock("Имя агента", state.name, { state.name = it }, "oc_...")
    if (state.key == null) {
        actionButton("Зарегистрировать и сохранить ключ") { scope.launch { state.register(context) } }
    } else if (!state.confirmReplace) {
        actionButton("Создать другого агента (заменит ключ)", muted = true) {
            state.confirmReplace = true
        }
    } else {
        actionButton("Точно заменить ключ? Старый перестанет работать", muted = true) {
            state.confirmReplace = false
            scope.launch { state.register(context) }
        }
    }
}

/** Готовый `api_key` — пути без сайта, работает даже при geo-block. */
@Composable
private fun tokenBlock(
    state: MoltbookSectionState,
    scope: CoroutineScope,
    context: Context,
) {
    Text("Готовый токен", color = Color(LABEL), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    fieldBlock("api_key", state.token, { state.token = it }, "moltbook_…")
    actionButton("Сохранить токен") { scope.launch { state.saveToken(context) } }
}

/** Что делать с только что выданным ключом: подтвердить его человеком. */
@Composable
private fun claimBlock(
    reg: MoltbookRegistration,
    context: Context,
    scope: CoroutineScope,
    state: MoltbookSectionState,
) {
    statusPlate(
        "Ключ создан — ${reg.status}",
        "Подтвердите агента: откройте ссылку и опубликуйте код в X",
        ok = false,
    )
    Surface(
        color = Color(PLATE),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("Код подтверждения", color = Color(LABEL), fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                reg.verificationCode.ifBlank { "—" },
                color = Color(ACCENT),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
    actionButton("Проверить статус") { scope.launch { state.refreshStatus() } }
    actionButton("Скопировать код") { copyToClipboard(context, reg.verificationCode) }
    val safe = safeClaimUrl(reg.claimUrl)
    if (safe != null) {
        actionButton("Открыть claim-ссылку") { openClaim(context, safe) }
        actionButton("Скопировать ссылку") { copyToClipboard(context, reg.claimUrl) }
    } else if (reg.claimUrl.isNotBlank()) {
        statusPlate(
            "Ссылка не открыта",
            "Сервер вернул адрес вне moltbook.com — используйте код вручную",
            ok = false,
        )
    }
}

/** Плашка состояния в стиле «Яндекс Музыки»: заголовок крупно, пояснение мелко. */
@Composable
private fun statusPlate(
    headline: String,
    detail: String,
    ok: Boolean? = null,
) {
    Surface(
        color = Color(PLATE),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                headline,
                color =
                    when (ok) {
                        true -> Color(ACCENT)
                        false -> Color(ERROR)
                        null -> Color(VALUE)
                    },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(detail, color = Color(LABEL), fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

/** Тёмная плашка-кнопка, как у скачивания моделей. [muted] — destructive-действия. */
@Composable
private fun actionButton(
    label: String,
    muted: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        color = Color(PLATE),
        shape = RoundedCornerShape(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clickable(onClick = onClick),
    ) {
        Text(
            label,
            color = if (muted) Color(MUTED) else Color(ACCENT),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/** Поле ввода в стиле ответа на вопрос агента: Box + BasicTextField с плейсхолдером. */
@Composable
private fun fieldBlock(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
) {
    Text(label, color = Color(LABEL), fontSize = 12.sp)
    Spacer(Modifier.height(3.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(PLATE), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = Color(VALUE), fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, color = Color(LABEL), fontSize = 13.sp)
                inner()
            },
        )
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
            }
            .onFailure { cause -> error = cause.message?.takeIf { it.isNotBlank() } ?: NO_REASON }
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
            }
            .onFailure { cause -> error = cause.message?.takeIf { it.isNotBlank() } ?: NO_REASON }
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
            }
            .onFailure { cause -> error = cause.message?.takeIf { it.isNotBlank() } ?: NO_REASON }
    }
}
