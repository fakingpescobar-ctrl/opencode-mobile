package org.opencode.mobile.ui

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
 */
@Composable
internal fun ModelRoutingPicker(
    modifier: Modifier = Modifier,
    onSaved: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(ModelRoutingPrefs.isEnabled(context)) }
    var routing by remember { mutableStateOf(ModelRoutingPrefs.current(context)) }
    var role by remember { mutableStateOf(RoutingRole.HEAVY) }
    var status by remember { mutableStateOf("") }
    // true = конфиг на диске новый, но сервер ещё читает старый. Сервер
    // подхватит новое только после рестарта, а рестарт нельзя делать без
    // спроса: он рвёт стриминг текущей сессии. Поэтому это явное действие.
    var pending by remember { mutableStateOf(false) }
    // Флаг идущего рестарта: кнопка «применить» остаётся на экране всё время
    // опроса сервера, и без этого флага два нажатия подряд поднимали бы второй
    // рестарт поверх первого.
    var applying by remember { mutableStateOf(false) }
    // Список моделей: сначала встроенный каталог, потом — живой, с сервера.
    // Провайдер меняет состав моделей без нашего ведома (модель переименовали,
    // часть убрали, часть добавили), поэтому источник по умолчанию — сервер, а
    // встроенный список остаётся рабочим вариантом на случай, когда сервер не
    // поднят или не ответил.
    var catalog by remember { mutableStateOf(ModelCatalog.all) }
    var fromServer by remember { mutableStateOf(false) }

    // Живой список спрашиваем отдельно от сверки выбора: ответ сервера
    // приходит асинхронно и не должен трогать расписание.
    LaunchedEffect("models") {
        val live =
            withContext(Dispatchers.IO) {
                ModelList.fetch(OpencodeServerService.state.value.port)
            }
        if (live.isEmpty()) return@LaunchedEffect
        catalog = live
        fromServer = true
    }

    // Рассинхрон может появиться не только из нажатий: перезапуск приложения,
    // правка конфига руками, убитый процесс. Поэтому при открытии секции
    // спрашиваем сервер, а не полагаемся на остаточное состояние remember.
    // Ответ приходит асинхронно, поэтому сверяем его со снимком выбора: пока
    // пользователь не нажал ещё одну строку, снимок совпадает и результат
    // применяется. Разошлись — ответ прошлого состояния, выкидываем его,
    // иначе он снимет кнопку «применить» у только что записанного выбора.
    LaunchedEffect(Unit) {
        val snapshot = routing
        val state = currentChoiceState(context, snapshot)
        if (routing != snapshot) return@LaunchedEffect
        pending = state.pending
        if (state.message.isNotEmpty()) status = state.message
    }

    fun commit(
        next: ModelRouting,
        on: Boolean,
    ) {
        // Правка модели посреди опроса рестарта ломает сверку: завершившийся
        // applyNow пришёл бы со старым routing и объявил «применено» для модели,
        // которую сервер ещё не обслуживает. Поэтому на время рестарта секция
        // не принимает выбор — строки просто не жмутся.
        if (applying) return
        routing = next
        enabled = on
        scope.launch {
            val result =
                runCatching { commitRoutingChoice(context, on, next, onSaved) }
                    .getOrElse { ChoiceResult(pending = false, message = FAILED_TEXT) }
            if (routing != next) return@launch
            status = result.message
            pending = result.pending
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
        if (applying) return
        applying = true
        status = RESTARTING
        // Снимок, а не текущее значение: applyNow обязан сверяться с тем
        // выбором, ради которого рестарт и поднимался.
        val target = routing
        scope.launch {
            // finally, а не присваивание после: исключение рестарта (например,
            // запрет startForegroundService на фоне) иначе навсегда оставило бы
            // кнопку в состоянии «перезапускаю» с отключённым нажатием.
            try {
                // Рестарт и опрос — с сетью и сервисами, а uncaught-исключение
                // из корутины в rememberCoroutineScope валит приложение. Отказ
                // показываем текстом и снимаем блокировку, чтобы секция
                // осталась в рабочем состоянии.
                val result =
                    runCatching { restartAndVerify(context, target) }
                        .getOrElse { ChoiceResult(pending = true, message = FAILED_TEXT) }
                if (routing == target) {
                    status = result.message
                    pending = result.pending
                }
            } finally {
                applying = false
            }
        }
    }

    Column(modifier) {
        DiagSection(
            title = "Модели агента",
            tone = if (pending) DiagTone.WARN else DiagTone.IDLE,
            initiallyExpanded = false,
        ) {
            InfoRow(
                "Маршрутизация",
                if (enabled) "включена" else "выключена",
                if (enabled) MoltValue else MoltIdle,
            )
            val toggle = if (enabled) "Выключить маршрутизацию" else "Включить маршрутизацию"
            downloadButton(toggle) { commit(routing, !enabled) }

            Caption("РОЛЬ · нажми строку, чтобы выбрать модель для неё")
            RoutingRole.entries.forEach { each ->
                RoleRow(
                    role = each,
                    model = ModelCatalog.labelOf(modelOf(routing, each)),
                    editing = each == role,
                    enabled = !applying,
                ) { role = each }
            }
            Caption(role.hint)

            Spacer(Modifier.height(4.dp))
            val current = modelOf(routing, role)
            val shown = remember(catalog, current) { ModelCatalog.withCurrent(catalog, current) }
            Caption(if (fromServer) "МОДЕЛИ · с сервера" else "МОДЕЛИ · встроенный список")
            shown.forEach { model ->
                ModelRow(
                    model = model,
                    selected = model.id == current,
                    enabled = !applying,
                ) { commit(setModel(routing, role, model.id), true) }
            }

            RoutingFooter(
                note = ModelCatalog.noteFor(current, shown),
                status = status,
                pending = pending,
                applying = applying,
                onApply = ::applyNow,
            )
        }
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
 * шрифте, а `material-icons` ради одного символа в эту сборку не тянется —
 * тем же приёмом нарисованы треугольники секций.
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
