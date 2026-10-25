package org.opencode.mobile.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opencode.mobile.social.MoltbookPanelState
import org.opencode.mobile.ui.GaugeSettings
import org.opencode.mobile.ui.McpInfo
import org.opencode.mobile.ui.MoltbookPanel

// Панели шапки чата: каждая открывается своим флагом и живёт сама по себе.
// Вынесены из ChatOverlay как есть — это деревья UI, которые ничего не решают
// и ничего не опрашивают. Всё, что панель меняет снаружи себя, приходит
// колбэком (onFontApplied, onRunTtsTest) или лежит в объекте настроек
// (appearance, voice, tts, elevenKey).
//
// Разбито по одной панели на функцию, а не одной функцией на все: у каждой свой
// набор настроек, и общий список из девятнадцати параметров скрывал бы, что
// цвету нужны два, а озвучке пять.

// Выпадающий список подключённых MCP-серверов (тап по индикатору «N MCP»).
// У каждого имени — мигающая точка: зелёная (работает) / красная (нет).
@Composable
internal fun McpPanel(servers: List<McpInfo>) {
    McpServerList(
        servers = servers,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 2.dp)
                .testTag(TAG_PANEL_MCP),
    )
}

// Сводка Moltbook: сколько комментов ждут ответа, сколько репостов на наши
// посты, кому именно мы не ответили. Тот же тёмный кард, что и MCP.
@Composable
internal fun MoltbookCard(state: MoltbookPanelState) {
    MoltbookPanel(
        state = state,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
    )
}

// Цветовой пикер ответов модели: квадрат-градиент (X — оттенок, Y — яркость),
// тап и драг точкой.
@Composable
internal fun ModelColorPanel(
    appearance: ChatAppearance,
    modelColor: Color,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp)
            .background(Color(0xFF1C1C1C), RoundedCornerShape(12.dp))
            .testTag(TAG_PANEL_COLOR)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text("Цвет ответов модели (тап/тяни точку):", color = Color(0xFF8A8A8A), fontSize = 11.sp)
        ColorPickerSquare(appearance, modelColor)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(modelColor)
                    .border(1.dp, Color.White, CircleShape),
            )
            Text(
                "#${appearance.colorHex}",
                color = Color(0xFFBDBDBD),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
    }
}

// Квадрат-градиент пикера: X — оттенок, Y — яркость, точка отмечает текущий цвет.
// Тап и драг делают одно и то же, поэтому координаты считает одна функция: три
// копии одного расчёта разъезжались при любой правке.
@Composable
private fun ColorPickerSquare(
    appearance: ChatAppearance,
    modelColor: Color,
) {
    val pickSize = with(LocalDensity.current) { 300.dp.toPx() }

    fun pick(
        x: Float,
        y: Float,
    ) {
        val h = (x / pickSize).coerceIn(0f, 1f) * 360f
        val v = 1f - (y / pickSize).coerceIn(0f, 1f)
        appearance.chooseColor(
            "%06X".format(android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, v)) and 0xFFFFFF),
        )
    }
    Canvas(
        Modifier
            .padding(top = 6.dp)
            .size(300.dp)
            .pointerInput(Unit) {
                detectTapGestures { off -> pick(off.x, off.y) }
            }.pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { off -> pick(off.x, off.y) },
                    onDrag = { change, _ -> pick(change.position.x, change.position.y) },
                )
            },
    ) {
        drawRect(
            Brush.horizontalGradient(
                listOf(
                    Color(0xFFFF0000),
                    Color(0xFFFFFF00),
                    Color(0xFF00FF00),
                    Color(0xFF00FFFF),
                    Color(0xFF0000FF),
                    Color(0xFFFF00FF),
                    Color(0xFFFF0000),
                ),
                startX = 0f,
                endX = size.width,
            ),
        )
        drawRect(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0f), Color.Black),
                startY = 0f,
                endY = size.height,
            ),
        )
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(modelColor.toArgb(), hsv)
        val mark = Offset(hsv[0] / 360f * size.width, (1f - hsv[2]) * size.height)
        drawCircle(Color.Black, radius = 10.dp.toPx(), center = mark, style = Stroke(width = 1.dp.toPx()))
        drawCircle(Color.White, radius = 10.dp.toPx(), center = mark, style = Stroke(width = 2.dp.toPx()))
    }
}

// Панель настроек: геометрия полос гейджа и диагностика озвучки.
@Composable
internal fun GaugePanel(
    appearance: ChatAppearance,
    ttsTestRunning: Boolean,
    onRunTtsTest: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp)
            .background(Color(0xFF1C1C1C), RoundedCornerShape(12.dp))
            .testTag(TAG_PANEL_SETTINGS)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        GaugeSettings(
            ctxShape = appearance.ctxShape,
            onCtxShapeChange = { appearance.ctxShape = it },
            onCtxShapePersist = { appearance.persistCtxShape() },
            zenShape = appearance.zenShape,
            onZenShapeChange = { appearance.zenShape = it },
            onZenShapePersist = { appearance.persistZenShape() },
            palette = appearance.palette,
            onColorChange = { k, c -> appearance.choosePaletteColor(k, c) },
            onPalettePersist = { /* цвета уже записаны по клику */ },
            barGap = appearance.barGap,
            onBarGapChange = { appearance.barGap = it },
            onBarGapPersist = { appearance.persistBarGap() },
            onResetDefaults = { appearance.resetGauges() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (ttsTestRunning) "TTS-тест: синтезирую и распознаю…" else "Диагностика: синтез → распознавание (см. лог VOICE)",
            color = Color(0xFF5A8DEE),
            fontSize = 11.sp,
            modifier =
                Modifier
                    .padding(top = 6.dp)
                    .clickable(enabled = !ttsTestRunning) { onRunTtsTest() },
        )
    }
}

// Палитра шрифтов (настройка): тап по варианту — мгновенно применяется и
// сохраняется, после чего панель закрывается сама (onFontApplied).
@Composable
internal fun FontPanel(
    appearance: ChatAppearance,
    modelColor: Color,
    onFontApplied: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp)
            .background(Color(0xFF1C1C1C), RoundedCornerShape(12.dp))
            .testTag(TAG_PANEL_FONT)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text("Шрифт ответов модели (тап — применить):", color = Color(0xFF8A8A8A), fontSize = 11.sp)
        fontEntries.forEach { (key, name, ff) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            appearance.chooseFont(key)
                            onFontApplied()
                        }.padding(vertical = 6.dp),
            ) {
                Text(
                    if (key == appearance.fontKey) "● " else "○ ",
                    color = modelColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    name,
                    color = Color(0xFF9E9E9E),
                    fontSize = 10.sp,
                    modifier = Modifier.width(88.dp),
                )
                Text(
                    "Привет, я модель!",
                    color = modelColor,
                    fontFamily = ff,
                    fontSize = 16.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

// testTag-метки панелей: якоря для проверки геометрии по дампу uiautomator
// (resource-id). В коде не читаются.
private const val TAG_PANEL_MCP = "panel_mcp"
private const val TAG_PANEL_COLOR = "panel_color"
private const val TAG_PANEL_FONT = "panel_font"
private const val TAG_PANEL_SETTINGS = "panel_settings"
