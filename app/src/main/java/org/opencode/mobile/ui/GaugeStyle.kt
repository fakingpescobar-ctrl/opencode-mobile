package org.opencode.mobile.ui

import android.content.SharedPreferences
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Настройки внешнего вида полос расхода. Хранятся в `SharedPreferences("chat_overlay")`
 * и применяются на лету: ползунок в панели шестерёнки меняет полосу в ту же
 * секунду, без перезапуска приложения.
 *
 * Раньше геометрия жила константами `GAUGE_*` прямо в `ChatOverlay.kt`, и любая
 * правка требовала пересборки APK. Теперь это данные, а не код.
 *
 * Файл намеренно без Compose-стаби-компонентов, кроме `Dp`: парсинг и клампинг
 * должны тестироваться на JVM без телефона.
 */

/** Границы значений. Защищают от нулевых/безумных полей из битой базы. */
object GaugeLimits {
    const val MIN_HEIGHT_DP = 3
    const val MAX_HEIGHT_DP = 120
    const val MIN_CELLS = 5
    const val MAX_CELLS = 200
    const val MIN_CELL_GAP_DP = 0f
    const val MAX_CELL_GAP_DP = 8f
    const val MIN_LEAN = 0f
    const val MAX_LEAN = 1.2f
    const val MIN_BAR_GAP_DP = 0
    const val MAX_BAR_GAP_DP = 24
}

/** Геометрия одной полосы-ленты. Своя у полосы контекста и у полосы Zen-квоты. */
data class GaugeShape(
    val heightDp: Int,
    val cells: Int,
    val cellGapDp: Float,
    val lean: Float,
) {
    val height: Dp get() = heightDp.dp

    /** Возвращает копию с значениями, зажатыми в допустимые границы. */
    fun clamped(): GaugeShape =
        GaugeShape(
            heightDp = heightDp.coerceIn(GaugeLimits.MIN_HEIGHT_DP, GaugeLimits.MAX_HEIGHT_DP),
            cells = cells.coerceIn(GaugeLimits.MIN_CELLS, GaugeLimits.MAX_CELLS),
            cellGapDp = cellGapDp.coerceIn(GaugeLimits.MIN_CELL_GAP_DP, GaugeLimits.MAX_CELL_GAP_DP),
            lean = lean.coerceIn(GaugeLimits.MIN_LEAN, GaugeLimits.MAX_LEAN),
        )

    companion object {
        /**
         * Значение по умолчанию подобрано замером по скриншону телефона: цифры в
         * «105ms» занимают 32px при плотности 3.5, то есть ровно 9dp.
         */
        val DEFAULT = GaugeShape(heightDp = 9, cells = 90, cellGapDp = 0.6f, lean = 0.45f)

        /**
         * Собирает форму из сырых значений хранилища. `null` означает «ключа нет» —
         * берётся умолчание. Всё равно клампится: значение из prefs может быть
         * любым, и 0 в высоте нарисовал бы полосу в ноль, а -5 — вообще ничего.
         */
        fun from(
            heightDp: Int?,
            cells: Int?,
            cellGapDp: Float?,
            lean: Float?,
        ): GaugeShape =
            GaugeShape(
                heightDp = heightDp ?: DEFAULT.heightDp,
                cells = cells ?: DEFAULT.cells,
                cellGapDp = cellGapDp ?: DEFAULT.cellGapDp,
                lean = lean ?: DEFAULT.lean,
            ).clamped()
    }
}

/**
 * Цвета полос. Общие для обеих полос — трек один, а заливка своя у каждой и
 * своя на каждом пороге. Значения — ARGB `Int`, в prefs пишутся как число,
 * чтобы не разбирать и не проверять hex при каждом чтении.
 */
data class GaugePalette(
    val track: Int,
    val zenCalm: Int,
    val zenWarn: Int,
    val zenDanger: Int,
    val ctxLow: Int,
    val ctxMid: Int,
    val ctxHigh: Int,
) {
    companion object {
        val DEFAULT =
            GaugePalette(
                track = GAUGE_TRACK,
                zenCalm = ZEN_CALM,
                zenWarn = ZEN_WARN_COLOR,
                zenDanger = DANGER_COLOR,
                ctxLow = CTX_LOW,
                ctxMid = ZEN_WARN_COLOR,
                ctxHigh = DANGER_COLOR,
            )

        fun from(raw: Map<GaugeColorKey, Int>): GaugePalette {
            fun pick(
                k: GaugeColorKey,
                fallback: Int,
            ): Int = raw[k] ?: fallback
            return GaugePalette(
                track = pick(GaugeColorKey.TRACK, GAUGE_TRACK),
                zenCalm = pick(GaugeColorKey.ZEN_CALM, ZEN_CALM),
                zenWarn = pick(GaugeColorKey.ZEN_WARN, ZEN_WARN_COLOR),
                zenDanger = pick(GaugeColorKey.ZEN_DANGER, DANGER_COLOR),
                ctxLow = pick(GaugeColorKey.CTX_LOW, CTX_LOW),
                ctxMid = pick(GaugeColorKey.CTX_MID, ZEN_WARN_COLOR),
                ctxHigh = pick(GaugeColorKey.CTX_HIGH, DANGER_COLOR),
            )
        }
    }

    fun getColor(key: GaugeColorKey): Int =
        when (key) {
            GaugeColorKey.TRACK -> track
            GaugeColorKey.ZEN_CALM -> zenCalm
            GaugeColorKey.ZEN_WARN -> zenWarn
            GaugeColorKey.ZEN_DANGER -> zenDanger
            GaugeColorKey.CTX_LOW -> ctxLow
            GaugeColorKey.CTX_MID -> ctxMid
            GaugeColorKey.CTX_HIGH -> ctxHigh
        }

    fun copyWith(
        key: GaugeColorKey,
        argb: Int,
    ): GaugePalette =
        when (key) {
            GaugeColorKey.TRACK -> copy(track = argb)
            GaugeColorKey.ZEN_CALM -> copy(zenCalm = argb)
            GaugeColorKey.ZEN_WARN -> copy(zenWarn = argb)
            GaugeColorKey.ZEN_DANGER -> copy(zenDanger = argb)
            GaugeColorKey.CTX_LOW -> copy(ctxLow = argb)
            GaugeColorKey.CTX_MID -> copy(ctxMid = argb)
            GaugeColorKey.CTX_HIGH -> copy(ctxHigh = argb)
        }
}

/** Именованные цвета: чтобы UI не россыпал строковые литералы по панели. */
enum class GaugeColorKey(
    val prefsKey: String,
    val title: String,
) {
    TRACK("gauge_track", "Трек (пусто)"),
    ZEN_CALM("gauge_zen_calm", "Zen: спокойно"),
    ZEN_WARN("gauge_zen_warn", "Zen: внимание"),
    ZEN_DANGER("gauge_zen_danger", "Zen: опасно"),
    CTX_LOW("gauge_ctx_low", "Контекст: много места"),
    CTX_MID("gauge_ctx_mid", "Контекст: середина"),
    CTX_HIGH("gauge_ctx_high", "Контекст: конец"),
    ;

    fun argb(palette: GaugePalette): Int =
        when (this) {
            TRACK -> palette.track
            ZEN_CALM -> palette.zenCalm
            ZEN_WARN -> palette.zenWarn
            ZEN_DANGER -> palette.zenDanger
            CTX_LOW -> palette.ctxLow
            CTX_MID -> palette.ctxMid
            CTX_HIGH -> palette.ctxHigh
        }
}

// `0xFF242424` — это Long (не влезает в Int), поэтому литерал конвертируется.
// Именованные val, а не const: `const` требует вычисления на этапе компиляции,
// а `.toInt()` — вызов функции.
private val GAUGE_TRACK = 0xFF242424.toInt()
private val ZEN_CALM = 0xFF5BC0EB.toInt()
private val ZEN_WARN_COLOR = 0xFFFFC107.toInt()
private val DANGER_COLOR = 0xFFE53935.toInt()
private val CTX_LOW = 0xFF4CAF50.toInt()

/** Зазор между полосой контекста и полосой Zen в dp. */
object GaugeBarGap {
    val DEFAULT = 4
    const val PREFS_KEY = "gauge_bar_gap_dp"

    fun from(raw: Int?): Int = (raw ?: DEFAULT).coerceIn(GaugeLimits.MIN_BAR_GAP_DP, GaugeLimits.MAX_BAR_GAP_DP)
}

const val CTX_PREFIX = "ctx"
const val ZEN_PREFIX = "zen"

private fun shapeKey(
    prefix: String,
    field: String,
) = "gauge_${prefix}_$field"

fun SharedPreferences.readGaugeShape(prefix: String): GaugeShape =
    GaugeShape.from(
        heightDp = getInt(shapeKey(prefix, "h"), -1).takeIf { it >= 0 },
        cells = getInt(shapeKey(prefix, "cells"), -1).takeIf { it > 0 },
        cellGapDp = getFloat(shapeKey(prefix, "gap"), -1f).takeIf { it >= 0f },
        lean = getFloat(shapeKey(prefix, "lean"), -1f).takeIf { it >= 0f },
    )

fun SharedPreferences.writeGaugeShape(
    prefix: String,
    shape: GaugeShape,
) {
    edit()
        .putInt(shapeKey(prefix, "h"), shape.heightDp)
        .putInt(shapeKey(prefix, "cells"), shape.cells)
        .putFloat(shapeKey(prefix, "gap"), shape.cellGapDp)
        .putFloat(shapeKey(prefix, "lean"), shape.lean)
        .apply()
}

fun SharedPreferences.readGaugePalette(): GaugePalette {
    val raw =
        buildMap {
            for (k in GaugeColorKey.entries) {
                val v = getInt(k.prefsKey, 0)
                if (v != 0) put(k, v)
            }
        }
    return GaugePalette.from(raw)
}

fun SharedPreferences.writeGaugeColor(
    key: GaugeColorKey,
    argb: Int,
) {
    edit().putInt(key.prefsKey, argb).apply()
}

fun SharedPreferences.readGaugeBarGap(): Int = GaugeBarGap.from(getInt(GaugeBarGap.PREFS_KEY, -1).takeIf { it >= 0 })

fun SharedPreferences.writeGaugeBarGap(dp: Int) {
    edit().putInt(GaugeBarGap.PREFS_KEY, dp).apply()
}
