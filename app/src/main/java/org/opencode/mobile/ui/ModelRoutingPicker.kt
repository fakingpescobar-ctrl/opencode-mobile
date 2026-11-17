package org.opencode.mobile.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencode.mobile.server.CatalogModel
import org.opencode.mobile.server.ModelCatalog
import org.opencode.mobile.server.ModelList
import org.opencode.mobile.server.ModelRouting
import org.opencode.mobile.server.ModelRoutingPrefs
import org.opencode.mobile.server.OpencodeServerService

/** Роль агента, за которой закрепляется модель. Список ролей конечен и известен. */
private enum class RoutingRole(
    val chip: String,
    val hint: String,
) {
    HEAVY("Тяжёлые", "build, plan — сложные многошаговые задачи"),
    LIGHT("Лёгкие", "explore, general — поиск и вспомогательные шаги"),
    SMALL("Служебные", "title, summary, compaction — короткие генерации"),
}

/**
 * Маршрутизация моделей по ролям — секция экрана диагностики, а не отдельная
 * карточка.
 *
 * Раньше здесь была своя карточка со своей обводкой, своим заголовком и сеткой
 * плашек в два столбца. Внутри экрана, где всё остальное — строки «подпись →
 * значение» под сворачиваемыми секциями, она читалась как вклеенный чужой
 * экран: другая обводка, другой заголовок, другой шаг сетки. Теперь это
 * [DiagSection] с тем же заголовком, точкой тона, бейджем и треугольником, а
 * выбор идёт строками: слева роль или модель, справа — что выбрано.
 *
 * Выбор применяется сразу по нажатию строки — отдельной кнопки «Сохранить»
 * нет намеренно: лишний шаг между решением и результатом в настройке, которую
 * открывают раз в пару месяцев, только мешает. Дальше — запись конфига (она
 * синхронная: чтение + tmp/rename + читающая проверка, поэтому уходит в IO) и
 * горячая смена модели текущей сессии через `POST /api/session/{id}/model`.
 *
 * Рестарт агента в этом пути не нужен: конфиг opencode перечитывается только
 * при старте процесса, но сессию можно переключить на лету, а новые сессии
 * приложение создаёт уже с нужной моделью. Рестарт остаётся отдельной явной
 * кнопкой на случай, если смена не прошла, и проверяется по живому серверу, а
 * не по факту записи.
 *
 * Выключение не стирает уже записанный блок: оно лишь перестаёт им управлять,
 * чтобы правка конфига вручную не пропадала из-под пользователя.
 *
 * Состояние и действия лежат в [RoutingSectionState] и [RoutingSectionActions],
 * а разметка — в [RoutingSection]: detekt считает длину и сложность функции, и
 * держать в одной все ветки разметки и весь опрос сервера он не разрешает.
 */
@Composable
internal fun ModelRoutingPicker(
    modifier: Modifier = Modifier,
    onSaved: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state =
        remember {
            RoutingSectionState(
                enabled = ModelRoutingPrefs.isEnabled(context),
                routing = ModelRoutingPrefs.current(context),
            )
        }
    val actions = remember(state) { RoutingSectionActions(context, scope, state, onSaved) }
    RoutingSync(context, state)
    RoutingSection(modifier, state, actions)
}

/**
 * Состояние секции: одно место, где живут все переключатели.
 *
 * Вынесено из композабла не ради красоты: у `remember { mutableStateOf(...) }`
 * на каждый флаг detekt видел одну длинную функцию и считал её ветки вместе с
 * ветками разметки.
 *
 * `pending` — конфиг на диске новый, но сервер ещё читает старый: подхватит он
 * его только после рестарта, а рестарт нельзя делать без спроса, он рвёт
 * стриминг текущей сессии. `applying` — рестарт уже идёт, чтобы два нажатия
 * подряд не поднимали второй поверх первого.
 */
private class RoutingSectionState(
    enabled: Boolean,
    routing: ModelRouting,
) {
    var enabled by mutableStateOf(enabled)
    var routing by mutableStateOf(routing)
    var role by mutableStateOf(RoutingRole.HEAVY)
    var status by mutableStateOf("")
    var pending by mutableStateOf(false)
    var applying by mutableStateOf(false)

    // Список моделей: сначала встроенный каталог, потом — живой, с сервера.
    // Провайдер меняет состав моделей без нашего ведома (модель переименовали,
    // часть убрали, часть добавили), поэтому источник по умолчанию — сервер, а
    // встроенный список остаётся рабочим вариантом на случай, когда сервер не
    // поднят или не ответил.
    var catalog by mutableStateOf(ModelCatalog.all)
    var fromServer by mutableStateOf(false)
}

/**
 * Действия секции: запись выбора и рестарт с проверкой.
 *
 * Запись уходит в IO (чтение конфига + tmp/rename + читающая проверка), рестарт
 * ходит в сеть и дёргает сервисы, поэтому оба — из корутин, а не из обработчика
 * нажатия.
 */
private class RoutingSectionActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val state: RoutingSectionState,
    private val onSaved: (Boolean) -> Unit,
) {
    fun commit(
        next: ModelRouting,
        on: Boolean,
    ) {
        // Правка модели посреди опроса рестарта ломает сверку: завершившийся
        // applyNow пришёл бы со старым routing и объявил «применено» для
        // модели, которую сервер ещё не обслуживает.
        if (state.applying) return
        state.routing = next
        state.enabled = on
        scope.launch {
            val result =
                runCatching { commitRoutingChoice(context, on, next, onSaved) }
                    .getOrElse { ChoiceResult(pending = false, message = FAILED_TEXT) }
            if (state.routing != next) return@launch
            state.status = result.message
            state.pending = result.pending
        }
    }

    /**
     * Рестарт с проверкой результата.
     *
     * Не «попросили — считаем, что сработало»: после рестарта спрашиваем живой
     * сервер и только по его ответу снимаем кнопку. Слепой рестарт оставлял бы
     * секцию врёт (или, наоборот, прятал неудачу), а кнопка «применить» на
     * неудаче должна остаться — иначе пользователь решает, что всё применено.
     */
    fun applyNow() {
        if (state.applying) return
        state.applying = true
        state.status = RESTARTING
        // Снимок, а не текущее значение: applyNow обязан сверяться с тем
        // выбором, ради которого рестарт и поднимался.
        val target = state.routing
        scope.launch {
            // finally, а не присваивание после: исключение рестарта (например,
            // запрет startForegroundService на фоне) иначе навсегда оставило бы
            // кнопку в состоянии «перезапускаю» с отключённым нажатием.
            try {
                val result = restartQuietly(context, target)
                if (state.routing == target) {
                    state.status = result.message
                    state.pending = result.pending
                }
            } finally {
                state.applying = false
            }
        }
    }
}

/**
 * Сверка с сервером при открытии секции: живой список моделей и настоящее
 * состояние выбора.
 *
 * Рассинхрон появляется не только от нажатий: перезапуск приложения, правка
 * конфига руками, убитый процесс. Поэтому спрашиваем сервер, а не полагаемся
 * на остаточное состояние `remember`. Ответ приходит асинхронно, поэтому
 * сверяем его со снимком выбора: пока пользователь не нажал ещё одну строку,
 * снимок совпадает и результат применяется; разошлись — это ответ прошлого
 * состояния, и он снял бы кнопку «применить» у только что записанного выбора.
 */
@Composable
private fun RoutingSync(
    context: Context,
    state: RoutingSectionState,
) {
    LaunchedEffect("models") {
        val live = liveCatalog() ?: return@LaunchedEffect
        state.catalog = live
        state.fromServer = true
    }
    LaunchedEffect(Unit) {
        val snapshot = state.routing
        val check = currentChoiceState(context, snapshot)
        if (state.routing != snapshot) return@LaunchedEffect
        state.pending = check.pending
        if (check.message.isNotEmpty()) state.status = check.message
    }
}

/** Разметка секции: ровно та же карточка и те же строки, что у соседей. */
@Composable
private fun RoutingSection(
    modifier: Modifier,
    state: RoutingSectionState,
    actions: RoutingSectionActions,
) {
    val current = modelOf(state.routing, state.role)
    val shown = remember(state.catalog, current) { ModelCatalog.withCurrent(state.catalog, current) }
    Column(modifier) {
        DiagSection(
            title = "Модели агента",
            tone = if (state.pending) DiagTone.WARN else DiagTone.IDLE,
            initiallyExpanded = false,
        ) {
            RoutingToggle(state.enabled) { actions.commit(state.routing, !state.enabled) }
            RoutingRoleRows(state.routing, state.role, !state.applying) { state.role = it }
            Spacer(Modifier.height(4.dp))
            RoutingModelRows(shown, current, state.fromServer, !state.applying) {
                actions.commit(setModel(state.routing, state.role, it), true)
            }
            RoutingFooter(
                note = ModelCatalog.noteFor(current, shown),
                status = state.status,
                pending = state.pending,
                applying = state.applying,
                onApply = actions::applyNow,
            )
        }
    }
}

/** Строка «маршрутизация включена/выключена» и кнопка-переключатель. */
@Composable
private fun RoutingToggle(
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    InfoRow(
        "Маршрутизация",
        if (enabled) "включена" else "выключена",
        if (enabled) MoltValue else MoltIdle,
    )
    val label = if (enabled) "Выключить маршрутизацию" else "Включить маршрутизацию"
    downloadButton(label) { onToggle() }
}

/** Три строки ролей и подсказка к выбранной: какие агенты в неё входят. */
@Composable
private fun RoutingRoleRows(
    routing: ModelRouting,
    role: RoutingRole,
    enabled: Boolean,
    onRole: (RoutingRole) -> Unit,
) {
    Caption("РОЛЬ · нажми строку, чтобы выбрать модель для неё")
    RoutingRole.entries.forEach { each ->
        RoleRow(
            role = each,
            model = ModelCatalog.labelOf(modelOf(routing, each)),
            editing = each == role,
            enabled = enabled,
        ) { onRole(each) }
    }
    Caption(role.hint)
}

/** Список моделей для выбранной роли: маркер ● у той, что уже назначена. */
@Composable
private fun RoutingModelRows(
    shown: List<CatalogModel>,
    current: String,
    fromServer: Boolean,
    enabled: Boolean,
    onModel: (String) -> Unit,
) {
    Caption(if (fromServer) "МОДЕЛИ · с сервера" else "МОДЕЛИ · встроенный список")
    shown.forEach { model ->
        ModelRow(
            model = model,
            selected = model.id == current,
            enabled = enabled,
        ) { onModel(model.id) }
    }
}

/**
 * Строка роли: слева название роли, справа — модель, которая ей назначена.
 *
 * Роль, для которой сейчас выбирают, подсвечена акцентом: иначе три строки
 * одинаковы, и непонятно, к чему относится список моделей ниже.
 */
@Composable
private fun RoleRow(
    role: RoutingRole,
    model: String,
    editing: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onPick)
                .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            role.chip,
            color = if (editing) MoltAccent else MoltIdle,
            fontSize = TINY,
            lineHeight = TINY_LINE,
            fontWeight = if (editing) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(ROW_LABEL_WEIGHT),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            model,
            color = if (editing) MoltAccent else MoltValue,
            fontSize = SMALL,
            lineHeight = SMALL_LINE,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f - ROW_LABEL_WEIGHT),
        )
    }
}

/**
 * Строка модели: маркер выбора и подпись.
 *
 * Маркер текстом (`●`/`○`), а не галочка-иконка: глиф есть в любом системном
 * шрифте, а `material-icons` ради одного символа в эту сборку не тянется — тем
 * же приёмом нарисованы треугольники секций.
 */
@Composable
private fun ModelRow(
    model: CatalogModel,
    selected: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onPick)
                .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            if (selected) "\u25CF" else "\u25CB",
            color = if (selected) MoltAccent else MoltIdle,
            fontSize = TINY,
            lineHeight = TINY_LINE,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            model.label,
            color = if (selected) MoltAccent else MoltValue,
            fontSize = SMALL,
            lineHeight = SMALL_LINE,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Подвал секции: что умеет выбранная модель, что сделало последнее нажатие и —
 * когда сервер ещё не подтвердил новое расписание — кнопка «применить».
 * Отдельное действие, а не автоматика на каждое нажатие: рестарт обрывает
 * стриминг текущей сессии, и молча рвать его без спроса нельзя. Кнопка не
 * прячется, пока сервер реально не подтвердит новые модели (см.
 * [RoutingReadiness.awaitServed]).
 */
@Composable
private fun RoutingFooter(
    note: String,
    status: String,
    pending: Boolean,
    applying: Boolean,
    onApply: () -> Unit,
) {
    if (note.isNotEmpty()) {
        Text(
            note,
            color = MoltIdle,
            fontSize = TINY,
            lineHeight = TINY_LINE,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (pending) {
        val label =
            if (applying) "Перезапускаю агента…" else "Применить · перезапустить агента"
        downloadButton(label) { onApply() }
    }
    Text(
        status.ifEmpty { APPLY_HINT },
        color = if (status.isEmpty()) MoltIdle else MoltValue,
        fontSize = TINY,
        lineHeight = TINY_LINE,
        modifier = Modifier.padding(top = 5.dp),
    )
}

@Composable
private fun Caption(
    text: String,
    top: Int = 5,
) {
    Text(
        text,
        color = MoltIdle,
        fontSize = TINY,
        letterSpacing = 0.5.sp,
        lineHeight = TINY_LINE,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = top.dp),
    )
}

/**
 * Живой список моделей — или `null`, когда сервер не ответил.
 *
 * `null`, а не пустой список: у «не спросили» одно значение — остаться на
 * встроенном каталоге, — и оно не должно сливаться с «спросили, ответ пустой».
 */
private suspend fun liveCatalog(): List<CatalogModel>? =
    withContext(Dispatchers.IO) {
        ModelList.fetch(OpencodeServerService.state.value.port).ifEmpty { null }
    }

/**
 * Рестарт агента, который не валит приложение.
 *
 * `restartAndVerify` ходит в сеть и дёргает сервисы, а uncaught-исключение из
 * корутины `rememberCoroutineScope` — это падение всего экрана. Отказ должен
 * стать текстом в секции, где его прочитают, а не закрыть приложение.
 */
private suspend fun restartQuietly(
    context: Context,
    target: ModelRouting,
): ChoiceResult =
    runCatching { restartAndVerify(context, target) }
        .getOrElse { ChoiceResult(pending = true, message = FAILED_TEXT) }

private fun modelOf(
    routing: ModelRouting,
    role: RoutingRole,
): String =
    when (role) {
        RoutingRole.HEAVY -> routing.heavy
        RoutingRole.LIGHT -> routing.light
        RoutingRole.SMALL -> routing.small
    }

private fun setModel(
    routing: ModelRouting,
    role: RoutingRole,
    id: String,
): ModelRouting =
    when (role) {
        RoutingRole.HEAVY -> routing.copy(heavy = id)
        RoutingRole.LIGHT -> routing.copy(light = id)
        RoutingRole.SMALL -> routing.copy(small = id)
    }

/** Доля ширины подписи — та же, что у [InfoRow]: строки секции не должны
 *  «плясать» относительно остальных строк экрана. */
private const val ROW_LABEL_WEIGHT = 0.44f

/** Подпись под секцией до первого нажатия: без неё секция выглядит так,
 *  будто выбор уже действует, хотя конфиг ещё не патчен. */
private const val APPLY_HINT = "выбор применяется на лету; «применить» — запасной путь (перезапуск агента)"

/** Статус на время рестарта: кнопка «применить» уже нажата, ждём новый процесс. */
private const val RESTARTING = "перезапускаю агента…"

/** Отказ записи или рестарта: показываем вместо падения приложения. */
private const val FAILED_TEXT = "не получилось сохранить — попробуй ещё раз"
