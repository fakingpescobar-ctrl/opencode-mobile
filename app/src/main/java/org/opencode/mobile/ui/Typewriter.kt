package org.opencode.mobile.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/**
 * Оформление печатаемого текста.
 *
 * Отдельный класс не ради красоты: у [TypewriterText] и у обычного текста
 * реплики должно быть ровно одно и то же оформление, а перечислять его
 * двумя наборами параметров — верный способ однажды развести их значения.
 */

/**
 * Печать ответа по буквам.
 *
 * Зачем она, если opencode отдаёт сообщение целиком: ответ на короткую фразу
 * появлялся в ленте между двумя кадрами, и это читалось как «приложение
 * зависло и выдало результат разом». Медленное проявление текста даёт глазу
 * следить за речью, а мгновенный блок текста такой возможности не даёт.
 *
 * Это КЛИЕНТСКАЯ анимация, а не стрим: сервер присылает готовый текст, мы
 * показываем его по частям. Поэтому у анимации нет смысла подстраиваться под
 * скорость модели — она подстраивается только под длину ответа.
 *
 * [animate] гасится вызывающим кодом через [onTyped] ровно один раз на текст:
 * перепечатывать старые реплики при возврате в чат нельзя, а лента
 * пересобирается опросом сервера каждые доли секунды.
 */
@Composable
internal fun TypewriterText(
    text: String,
    animate: Boolean,
    style: TextStyle,
    /**
     * Устойчивый ключ реплики (идентификатор сообщения). Именно он, а не текст,
     * держит прогресс печати: сервер дописывает ход по мере генерации, и
     * `remember(text)` обнулял бы печать на каждом обновлении — она не
     * успевала бы закончиться. Новый ключ = новая реплика = печать с начала.
     */
    key: String,
    onTyped: () -> Unit,
) {
    val typing = animate && text.length <= TYPEWRITER_MAX_CHARS
    // Показывать по частям нельзя длиннее TYPEWRITER_MAX_CHARS, и для такого
    // текста animate приходит уже погашенным, поэтому здесь shown сразу равен
    // длине — печатать нечего.
    var shown by remember(key, typing) { mutableIntStateOf(if (typing) 0 else text.length) }
    // text в ключах намеренно: сервер может дописать ход позже, и без него
    // цикл печати не увидит новый текст и остановится на старой длине.
    // Перезапуск эффекта при этом НЕ обнуляет прогресс — прогресс живёт в
    // remember(key, typing), а не в этом эффекте.
    //
    // Эффект запускается один раз на реплику, а не каждый кадр: перезапуск
    // возможен только при смене key, typing или text. Он же гасит ключ
    // печати, без чего длинный ответ (больше TYPEWRITER_MAX_CHARS) входил бы
    // в анимацию при каждой пересборке ленты.
    LaunchedEffect(key, typing, text) {
        if (!typing) {
            shown = text.length
            onTyped()
            return@LaunchedEffect
        }
        val step = stepFor(text.length)
        while (shown < text.length) {
            delay(TYPEWRITER_STEP_MS)
            shown = min(text.length, shown + step)
        }
        onTyped()
    }
    // Курсор мигает, только пока текст проявляется. Без этой проверки
    // rememberInfiniteTransition крутился бы для каждой реплики в ленте (их
    // десятки, у каждой своя анимация) — постоянная работа ради невидимого
    // курсора в уже напечатанном тексте.
    val alpha = cursorAlpha(animate)
    SelectionContainer {
        Text(
            text = rendered(text, shown, style.color, alpha),
            color = style.color,
            fontFamily = style.fontFamily,
            fontSize = style.fontSize,
            lineHeight = style.lineHeight,
        )
    }
}

/**
 * Текст с мигающим курсором на конце, пока он не проявлен целиком.
 *
 * Курсор рисуется последним символом, а не отдельным элементом: иначе он
 * всегда оказывался бы на своей строке и ломался бы перенос длинного ответа.
 * Но вставка «как есть» прыгала бы на новую строку, если проявленный текст
 * кончается переводом строки, поэтому курсор вставляется ПЕРЕД хвостовыми
 * переводами — он остаётся на последней напечатанной строке.
 */
private fun rendered(
    text: String,
    shown: Int,
    color: Color,
    alpha: Float,
): AnnotatedString {
    val body = text.take(shown)
    if (shown >= text.length) return AnnotatedString(body)
    val tail = body.length - body.trimEnd('\n', ' ').length
    val head = body.dropLast(tail)
    return AnnotatedString
        .Builder()
        .apply {
            append(head)
            append(TYPEWRITER_CURSOR)
            addStyle(SpanStyle(color = color.copy(alpha = alpha)), head.length, head.length + 1)
            append(body.drop(head.length))
        }.toAnnotatedString()
}

/**
 * Мигание курсора: 1 — печатать нечего, курсор не рисуется вовсе.
 *
 * Возврат до `rememberInfiniteTransition` — осознанный вызов composable в
 * условии по неизменяемому для данного сообщения параметру: альтернатива
 * (крутить анимацию всегда) означала бы десятки бесконечных перерисовок ленты.
 */
@Composable
private fun cursorAlpha(animate: Boolean): Float {
    if (!animate) return 1f
    val alpha by
        rememberInfiniteTransition(label = "typewriter-cursor")
            .animateFloat(
                initialValue = CURSOR_MIN_ALPHA,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = CURSOR_BLINK_MS),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "typewriter-cursor-alpha",
            )
    return alpha
}

/**
 * Сколько символов показывать за один тик.
 *
 * Скорость в символах/секунду фиксировать бессмысленно: у ответа в 10 слов и
 * у ответа в 300 слов одна и та же длительность читается по-разному. Поэтому
 * длительность постоянна, а шаг растёт вместе с длиной.
 */
private fun stepFor(length: Int): Int =
    if (length <= 0) {
        1
    } else {
        max(1, length / (TYPEWRITER_TARGET_MS / TYPEWRITER_STEP_MS).toInt())
    }

/** Значок курсора — тонкая вертикальная черта, принятая в терминалах. */
private const val TYPEWRITER_CURSOR = "▌"

/** Длительность проявления, к которой подгоняется шаг. */
private const val TYPEWRITER_TARGET_MS = 1400

/** Период одного шага: меньше 60 мс — уже не «печать», а дёрганье. */
private const val TYPEWRITER_STEP_MS = 24L

/** Свыше этого ответа печатать нельзя: два экрана текста за две секунды — не эффект, а ожидание. */
private const val TYPEWRITER_MAX_CHARS = 6000

private const val CURSOR_BLINK_MS = 520

private const val CURSOR_MIN_ALPHA = 0.2f
