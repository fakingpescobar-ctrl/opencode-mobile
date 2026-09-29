package org.opencode.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencode.mobile.account.YandexAccountController

// Палитра секции — та же, что у «Голосового распознавания» и валидации runtime, чтобы
// экран читался одним куском, а не сборным чертежом из разных экранов.
private const val ACCENT = 0xFF7BD88F
private const val LABEL = 0xFF8A8A8A
private const val VALUE = 0xFFE6E6E6
private const val ERROR = 0xFFFF6F5A
private const val MUTED = 0xFFBDBDBD
private const val PLATE = 0xFF1E1E1E
private const val TICK_MILLIS = 1000L
private const val MILLIS_PER_SECOND = 1000

/**
 * Что секция показывает прямо сейчас.
 *
 * Не переиспользуется [YandexAccountController.Outcome] напрямую: там «пользователь ещё не
 * подтвердил» — тактика опроса для агента, который сам решает, когда дёрнуть снова, а
 * здесь это состояние, в котором человек смотрит на экран и ждёт. Разделены два жанра:
 * «идёт работа с сетью» и «висит код, который надо ввести».
 */
private sealed interface YandexConnectUi {
    /** Аккаунта нет, кода нет. Начальное состояние. */
    data object Disconnected : YandexConnectUi

    /** Сеть в работе: выдаём код или опрашиваем подтверждение. Кнопки на это время глухие. */
    data object Working : YandexConnectUi

    /** Код выдан, ждём, пока человек введёт его на странице Яндекса. */
    data class Awaiting(
        val prompt: YandexAccountController.DevicePrompt,
    ) : YandexConnectUi

    /** Вход состоялся. [login] — кого впустили. */
    data class Connected(
        val login: String,
    ) : YandexConnectUi

    /** Вход не вышел. [reason] показываем как есть: там уже разобраны коды ошибок Яндекса. */
    data class Failed(
        val reason: String,
    ) : YandexConnectUi
}

/**
 * Подключение Яндекс Музыки: код, ожидание подтверждения, отключение.
 *
 * **Почему она на экране диагностики.** У приложения всего две поверхности — чат и
 * диагностика, и «подключи то, без чего музыка не работает» уже описано здесь же ровно
 * так же: секция «Голосовое распознавание» с настоящей кнопкой скачивания моделей.
 * Отдельный экран означал бы новую точку входа в навигации ради одного подключения, а
 * сюда человек, у которого музыка молчит, и так заглядывает.
 *
 * **Почему кнопки не было раньше.** Вход жил только в `AppInstallBridge`: агент дёргал
 * `/v1/account/yandex/connect` и сам показывал код. Формально подключение работало, но
 * человек, скачавший приложение ради музыки, должен был догадаться, что надо попросить
 * агента. Тишина вместо входа — не «нет функции», это неочевидный тупик.
 *
 * Опрос живёт здесь, а не в контроллере, по одной причине: контроллер намеренно не ждёт
 * ([YandexAccountController.pollConnect] делает ровно один запрос) — иначе метод съел бы
 * пятиминутное окно кода на сон, пока человек вводит код в стороннем браузере. Ожидание
 * и есть цикл этого UI: он зовёт контроллер по одному разу за такт.
 */
@Composable
fun yandexAccountSection() {
    val context = LocalContext.current
    // Инициализация до первого чтения статуса: контроллер без контекста не знает, где
    // лежит хранилище токена, и упал бы на `check`. Обычно он уже инициализирован
    // стартом моста, но «обычно» здесь означает «упадёт, если сервер не поднялся», а
    // экран диагностики как раз и открывают, когда что-то не поднялось. Вызов идемпотентен.
    YandexAccountController.initialize(context)
    val scope = rememberCoroutineScope()
    val state = remember { YandexConnectState(initialState()) }

    // Оба эффекта на ключе state.prompt, а не state.ui. Пока идёт опрос, на экране мигает
    // `Working`, и если бы от `ui` зависели циклы, смена этого состояния отменяла бы их
    // самих: только что стартовавший опрос читает `ui`, видит не `Awaiting` и выходит.
    // Снаружи это выглядит как «подтвердите код», который вечно не доходит до конца.
    LaunchedEffect(state.prompt) { state.countdown() }
    LaunchedEffect(state.prompt) { state.pollUntilDone() }

    sectionHeader("Яндекс Музыка")
    when (val shown = state.ui) {
        YandexConnectUi.Disconnected -> {
            accountPlate("Не подключён", WITHOUT_ACCOUNT_HINT)
            actionButton("Подключить Яндекс Музыку") { scope.launch { state.beginConnect() } }
        }
        YandexConnectUi.Working -> accountPlate("Подключение…", "Связь с Яндексом")
        is YandexConnectUi.Awaiting -> {
            deviceCodeBlock(shown.prompt.userCode, state.secondsLeft)
            actionButton("Открыть страницу Яндекса") {
                YandexAccountController.openBrowser(shown.prompt.verificationUrl)
            }
            actionButton("Скопировать код") { copyToClipboard(context, shown.prompt.userCode) }
            actionButton("Отменить", muted = true) { scope.launch { state.signOut() } }
        }
        is YandexConnectUi.Connected -> {
            accountPlate(shown.login, CONNECTED_HINT, ok = true)
            actionButton("Отключить", muted = true) { scope.launch { state.signOut() } }
        }
        is YandexConnectUi.Failed -> {
            accountPlate("Не получилось", shown.reason, ok = false)
            actionButton("Попробовать снова") { scope.launch { state.beginConnect() } }
        }
    }
}

/**
 * Единственный владелец состояния входа.
 *
 * Composable только рисует [ui] и зовёт три метода, а правила переходов живут здесь.
 * Держать их в composable было плохо не только длиной: `ui` и `prompt` — это два разных
 * состояния, и любое место, которое двигает одно, обязано двигать и другое. Когда ими
 * владели кнопка, отмена и опрос по отдельности, это правило держалось на честном слове.
 * Здесь оно выражено типами: [prompt] приватный set, а менять оба можно только через
 * [beginConnect] и [signOut], которые оба обновляют пару целиком.
 */
@Stable
private class YandexConnectState(
    initial: YandexConnectUi,
) {
    /** Что на экране. */
    var ui by mutableStateOf(initial)
        private set

    /** Висящий код, если он есть. Меняется ровно дважды: выдали и сняли. */
    var prompt by mutableStateOf(initial.pendingPrompt)
        private set

    /** Сколько секунд осталось до истечения кода. */
    var secondsLeft by mutableIntStateOf(0)
        private set

    /**
     * Обратный отсчёт. Считается от системных часов каждую секунду, а не накапливается:
     * после сворачивания приложения накопленный счётчик врёт, а часы не врут.
     */
    suspend fun countdown() {
        val current = prompt ?: return
        val deadline = current.expiresAtMillis
        while (true) {
            secondsLeft = ((deadline - System.currentTimeMillis()) / MILLIS_PER_SECOND).toInt()
            delay(TICK_MILLIS)
        }
    }

    /** Цикл опроса: пока жив код, спрашиваем Яндекс по одному разу за такт. */
    suspend fun pollUntilDone() {
        val current = prompt ?: return
        var waitSeconds = current.intervalSeconds
        // Все ветки решения живут в pollOnce, а у цикла остаётся ровно один выход. Иначе
        // каждая причина закончить опрос — свой `break`, и читается это не как «пока вход
        // жив, спрашиваем», а как поток инструкций, где надо глазами искать, куда выйти.
        while (true) {
            waitSeconds = pollOnce(current, waitSeconds) ?: break
        }
    }

    /**
     * Один такт опроса. Возвращает паузу до следующего или null, если опрос окончен.
     *
     * Паузу берём из [YandexAccountController.Outcome.Waiting], а не из
     * `intervalSeconds`: контроллер уже прибавил к интервалу надбавку за `slow_down`,
     * и дублируя это правило здесь, мы либо перестучим, либо проигнорируем требование
     * Яндекса отступить.
     */
    private suspend fun pollOnce(
        current: YandexAccountController.DevicePrompt,
        waitSeconds: Int,
    ): Int? {
        delay(waitSeconds * TICK_MILLIS)
        // Код истёк. Яндекс отозвал бы его и сам, но ждать отказа минутами после истёкшего
        // срока — плохо: человек видит «ожидание», которого уже не будет.
        if (System.currentTimeMillis() >= current.expiresAtMillis) {
            expire()
            return null
        }
        ui = YandexConnectUi.Working
        val outcome = withContext(Dispatchers.IO) { YandexAccountController.pollConnect() }
        return when (outcome) {
            is YandexAccountController.Outcome.Connected -> {
                finish(YandexConnectUi.Connected(outcome.identity.login))
                null
            }
            is YandexAccountController.Outcome.Rejected -> {
                finish(YandexConnectUi.Failed(outcome.reason))
                null
            }
            // Waiting не превращается в Failed: вход ещё живой, экран возвращает код,
            // и следующая итерация идёт с новой паузой.
            is YandexAccountController.Outcome.Waiting -> {
                ui = YandexConnectUi.Awaiting(current)
                outcome.retryAfterSeconds
            }
        }
    }

    /**
     * Начать вход: попросить код и показать его.
     *
     * Зовут две кнопки — «подключить» и «попробовать снова». Второй вызов, кстати,
     * сжигает прежний код на сервере Яндекса, поэтому [startConnect] обязан вызываться
     * только отсюда, а не из двух мест кнопок по отдельности.
     */
    suspend fun beginConnect() {
        ui = YandexConnectUi.Working
        prompt = null
        val next =
            runCatching { YandexAccountController.startConnect() }
                .fold(
                    onSuccess = { status ->
                        status.devicePrompt?.let { YandexConnectUi.Awaiting(it) } ?: YandexConnectUi.Disconnected
                    },
                    onFailure = { YandexConnectUi.Failed(it.readable()) },
                )
        finish(next)
    }

    /** Сбросить вход: стереть токен и код, вернуться к кнопке подключения. */
    suspend fun signOut() {
        withContext(Dispatchers.IO) { YandexAccountController.disconnect() }
        finish(YandexConnectUi.Disconnected)
    }

    /** Код истёк. Яндекс отозвал бы его и сам, но ждать отказа минутами после истёкшего
     *  срока — плохо: человек видит «ожидание», которого уже не будет. */
    private fun expire() = finish(YandexConnectUi.Failed(EXPIRED_PROMPT))

    /** Единственное место, где `ui` и `prompt` меняются вместе: снять код — это всегда
     *  вместе с новым состоянием экрана, а не само по себе. */
    private fun finish(next: YandexConnectUi) {
        ui = next
        prompt = next.pendingPrompt
    }
}

/** Висящий код, если он есть. У всех состояний, кроме [YandexConnectUi.Awaiting], — null. */
private val YandexConnectUi.pendingPrompt: YandexAccountController.DevicePrompt?
    get() = (this as? YandexConnectUi.Awaiting)?.prompt

/** Пояснение под «Не подключён»: почему музыки нет, а не что где-то сломалось. */
private const val WITHOUT_ACCOUNT_HINT = "Без аккаунта музыкальные инструменты вообще не появляются у модели"

private const val CONNECTED_HINT = "Подключено, музыкальные инструменты доступны"

/** Код протух, пока висел: Яндекс его уже не примет, нужен новый. */
private const val EXPIRED_PROMPT = "код истёк, начните заново"

/** Отказ без причины: `IOException` умеет прийти с пустым message. */
private const val NO_REASON = "Яндекс не ответил, попробуйте ещё раз"

/**
 * Первое состояние при входе на экран.
 *
 * Спрашиваем хранилище, а не начинаем с чистого листа: если вход уже состоялся раньше,
 * человек не должен увидеть кнопку «подключить» поверх работающего аккаунта. И если код
 * висит (юзер ушёл в браузер и вернулся на diagnostics), показываем его, а не «начать
 * заново» — выданный код ещё жив, и второй вызов сжёг бы его.
 */
private fun initialState(): YandexConnectUi =
    YandexAccountController.status().let { status ->
        status.identity?.login?.let { YandexConnectUi.Connected(it) }
            ?: status.devicePrompt?.let { YandexConnectUi.Awaiting(it) }
            ?: YandexConnectUi.Disconnected
    }

/**
 * Крупный моноширинный код — герой этого экрана.
 *
 * Моноширинный с разрядкой не из украшения: восемь символов вида `jq7ivm4b` в
 * пропорциональном шрифте на телефоне невозможно переписать не глядя, а в моноширинном
 * с разрядкой видно, где палец промахнулся. Терминальный облик приложения здесь не
 * натянут: код и есть строка из восьми знаков, которые вводят руками.
 */
@Composable
private fun deviceCodeBlock(
    code: String,
    secondsLeft: Int,
) {
    Surface(
        color = Color(PLATE),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text("Введите код на странице Яндекса", color = Color(LABEL), fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                code,
                color = Color(ACCENT),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 6.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (secondsLeft > 0) "Код живёт ещё $secondsLeft с" else "Код истёк — запросите новый",
                color = if (secondsLeft > 0) Color(MUTED) else Color(ERROR),
                fontSize = 12.sp,
            )
        }
    }
}

/** Плашка состояния: заголовок крупно, пояснение мелко. */
@Composable
private fun accountPlate(
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
                color = when (ok) {
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

/**
 * Действие в стиле кнопки скачивания моделей: тёмная плашка, зелёный текст, без обводки.
 *
 * [muted] — не «второстепенный стиль», а «с этим надо подумать»: отмена входа и
 * отключение уже работающего аккаунта приглушены, чтобы их не нажали не подумав. Плашка
 * не гасится явно: во время сетевого запроса кнопок нет вовсе, состояние `Working` их
 * заменяет, и второго нажатия поверх первого просто не физически возможно.
 */
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

/**
 * Кладёт код в буфер обмена.
 *
 * Фреймворковый `ClipboardManager`, а не `LocalClipboardManager` из Compose: во входящем
 * BOM он помечен deprecated в пользу API с suspend-функциями, который тут ничего не даёт —
 * копирование разовое и мгновенное, ждать там нечего.
 */
private fun copyToClipboard(
    context: Context,
    code: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Яндекс Музыка: код входа", code))
}

/**
 * Текст ошибки для человека, а не стек-трейс.
 *
 * Сообщения контроллера уже разобраны и переведены — там либо код ответа Яндекса, либо
 * наш отказ. Остаётся подстраховаться на пустом тексте: `IOException` умеет прийти без
 * message, и пустая плашка выглядела бы как «ошибка» без причины, то есть как поломка
 * приложения, а не как отказ со стороны Яндекса.
 */
private fun Throwable.readable(): String = message?.takeIf { it.isNotBlank() } ?: NO_REASON
