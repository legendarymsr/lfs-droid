package dev.legendarymsr.lfsdroid

import org.junit.Assert.*
import org.junit.Test

class TermGridTest {
    @Test fun fitsWholeCells() {
        // 1080 px wide at a 15.4 px advance is 70.1 cells: 70, never 71 (rounding the advance down to 15 gave 72).
        assertEquals(TermGrid.Size(rows = 50, cols = 70), TermGrid.fit(1080f, 1700f, 15.4f, 34f))
        // Exact fits aren't lost to float error.
        assertEquals(TermGrid.Size(rows = 20, cols = 80), TermGrid.fit(80 * 7.3f, 20 * 17f, 7.3f, 17f))
    }

    @Test fun fitHasAFloorAndSurvivesZeroMetrics() {
        assertEquals(TermGrid.Size(TermGrid.MIN_ROWS, TermGrid.MIN_COLS), TermGrid.fit(40f, 10f, 10f, 20f))
        assertEquals(TermGrid.Size(24, 80), TermGrid.fit(1000f, 1000f, 0f, 20f))
    }

    @Test fun keyboardShrinksRows() {
        val open = TermGrid.fit(1080f, 900f, 15f, 30f)
        val closed = TermGrid.fit(1080f, 1800f, 15f, 30f)
        assertEquals(30, open.rows); assertEquals(60, closed.rows); assertEquals(open.cols, closed.cols)
    }

    @Test fun zoomClampsAndSnaps() {
        assertEquals(24f, TermGrid.zoom(20f, 3f), 0f)
        assertEquals(7f, TermGrid.zoom(8f, 0.1f), 0f)
        assertEquals(13.5f, TermGrid.zoom(12f, 1.13f), 0f)
        assertEquals(TermGrid.DEFAULT_FONT, TermGrid.clampFont(Float.NaN), 0f)
    }

    @Test fun widths() {
        assertTrue(TermGrid.isWide('漢'.code)); assertTrue(TermGrid.isWide(0x1F600))
        assertFalse(TermGrid.isWide('█'.code)); assertFalse(TermGrid.isWide('a'.code))
        assertTrue(TermGrid.isZeroWidth(0xFE0F)); assertTrue(TermGrid.isZeroWidth(0x0301))
    }

    @Test fun wideCharsTakeTwoCells() {
        val t = TerminalBuffer(2, 6)
        t.write("a漢b")
        assertEquals("a漢b", t.screenText()[0])
        assertEquals(4, t.cursorCol)
        assertEquals(TermGrid.WIDE_TAIL, t.line(0).chars[2])
    }

    @Test fun emojiIsTwoCellsAndWrapsWhole() {
        val t = TerminalBuffer(2, 4)
        t.write("abc\uD83D\uDE00x")
        assertEquals(listOf("abc", "\uD83D\uDE00x"), t.screenText())
        assertEquals(3, t.cursorCol)
    }

    @Test fun combiningMarksAndVariationSelectorsTakeNoCell() {
        val t = TerminalBuffer(1, 10)
        t.write("e\u0301|\u2764\uFE0F|")
        assertEquals("e|\u2764|", t.screenText()[0])
    }

    @Test fun boxDrawingStaysOneCell() {
        val t = TerminalBuffer(1, 10)
        t.write("█▀─│x")
        assertEquals(4, t.screenText()[0].indexOf('x'))
    }
}
