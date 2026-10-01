package org.opencode.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GaugeStyleTest {
    @Test
    fun gaugeShape_defaults() {
        val d = GaugeShape.DEFAULT
        assertEquals(9, d.heightDp)
        assertEquals(90, d.cells)
        assertEquals(0.6f, d.cellGapDp, 0.0001f)
        assertEquals(0.40f, d.lean, 0.0001f)
    }

    @Test
    fun gaugeShape_from_nulls_use_defaults() {
        val s = GaugeShape.from(null, null, null, null)
        assertEquals(GaugeShape.DEFAULT, s)
    }

    @Test
    fun gaugeShape_from_partial_uses_fallbacks() {
        val s = GaugeShape.from(heightDp = 12, cells = null, cellGapDp = 1.2f, lean = null)
        assertEquals(12, s.heightDp)
        assertEquals(GaugeShape.DEFAULT.cells, s.cells)
        assertEquals(1.2f, s.cellGapDp, 0.0001f)
        assertEquals(GaugeShape.DEFAULT.lean, s.lean)
    }

    @Test
    fun gaugeShape_clamps_height() {
        val low = GaugeShape.from(2, 90, 0.5f, 0f)
        val high = GaugeShape.from(50, 90, 0.5f, 0f)
        assertEquals(6, low.heightDp)
        assertEquals(20, high.heightDp)
    }

    @Test
    fun gaugeShape_clamps_cells() {
        val low = GaugeShape.from(9, 10, 0.5f, 0f)
        val high = GaugeShape.from(9, 500, 0.5f, 0f)
        assertEquals(40, low.cells)
        assertEquals(120, high.cells)
    }

    @Test
    fun gaugeShape_clamps_cellGap() {
        val neg = GaugeShape.from(9, 90, -1f, 0f)
        val big = GaugeShape.from(9, 90, 5f, 0f)
        assertEquals(0f, neg.cellGapDp, 0.0001f)
        assertEquals(2.5f, big.cellGapDp, 0.0001f)
    }

    @Test
    fun gaugeShape_clamps_lean() {
        val neg = GaugeShape.from(9, 90, 0.5f, -5f)
        val pos = GaugeShape.from(9, 90, 0.5f, 5f)
        assertEquals(-0.40f, neg.lean, 0.0001f)
        assertEquals(0.40f, pos.lean, 0.0001f)
    }

    @Test
    fun gaugePalette_from_partial() {
        val p = GaugePalette.from(mapOf(GaugeColorKey.TRACK to 0xFF112233.toInt()))
        assertEquals(0xFF112233.toInt(), p.track)
        assertEquals(GaugePalette.DEFAULT.zenCalm, p.zenCalm)
        assertEquals(GaugePalette.DEFAULT.ctxHigh, p.ctxHigh)
    }

    @Test
    fun gaugePalette_getColor_and_copyWith() {
        var p = GaugePalette.DEFAULT
        p = p.copyWith(GaugeColorKey.CTX_LOW, 0xFFAA0000.toInt())
        p = p.copyWith(GaugeColorKey.ZEN_DANGER, 0xFF00AA00.toInt())
        assertEquals(0xFFAA0000.toInt(), p.getColor(GaugeColorKey.CTX_LOW))
        assertEquals(0xFF00AA00.toInt(), p.getColor(GaugeColorKey.ZEN_DANGER))
        assertEquals(GaugePalette.DEFAULT.getColor(GaugeColorKey.TRACK), p.getColor(GaugeColorKey.TRACK))
    }

    @Test
    fun gaugeBarGap_from_clamps() {
        assertEquals(GaugeBarGap.DEFAULT, GaugeBarGap.from(null))
        assertEquals(0, GaugeBarGap.from(-5))
        assertEquals(6, GaugeBarGap.from(100))
        assertEquals(3, GaugeBarGap.from(3))
    }
}
