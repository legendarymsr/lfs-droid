package dev.legendarymsr.lfsdroid

import kotlin.math.floor

/** The terminal's cell grid maths, kept free of Android so it's unit tested. */
object TermGrid {
    const val DEFAULT_FONT = 12f
    const val MIN_FONT = 7f
    const val MAX_FONT = 24f
    const val MIN_COLS = 10
    const val MIN_ROWS = 3

    /** Second cell of a two-cell character. Never drawn, never part of [TerminalBuffer.Line.text]. */
    const val WIDE_TAIL = '\uFFFF'

    data class Size(val rows: Int, val cols: Int)

    /** How many whole cells of [advance] x [lineHeight] px fit into [width] x [height] px. */
    fun fit(width: Float, height: Float, advance: Float, lineHeight: Float): Size {
        if (advance <= 0f || lineHeight <= 0f) return Size(24, 80)
        val cols = floor(width / advance + 1e-3f).toInt().coerceAtLeast(MIN_COLS)
        val rows = floor(height / lineHeight + 1e-3f).toInt().coerceAtLeast(MIN_ROWS)
        return Size(rows, cols)
    }

    /** Font size after a pinch by [zoom], clamped and kept to half-sp steps so the grid doesn't jitter. */
    fun zoom(sp: Float, zoom: Float): Float = clampFont(sp * zoom)

    fun clampFont(sp: Float): Float =
        if (sp.isNaN()) DEFAULT_FONT else (Math.round(sp.coerceIn(MIN_FONT, MAX_FONT) * 2f) / 2f)

    /** East Asian wide/fullwidth in the BMP: two cells, like wcwidth. */
    fun isWide(cp: Int): Boolean =
        cp in 0x1100..0x115F || cp in 0x2E80..0x303E || cp in 0x3041..0x33FF || cp in 0x3400..0x4DBF ||
            cp in 0x4E00..0x9FFF || cp in 0xA000..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF ||
            cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 || cp >= 0x10000

    /** Combining marks, joiners and variation selectors: no cell of their own. */
    fun isZeroWidth(cp: Int): Boolean =
        cp in 0x0300..0x036F || cp in 0x1AB0..0x1AFF || cp in 0x1DC0..0x1DFF || cp in 0x200B..0x200F ||
            cp in 0x20D0..0x20FF || cp in 0xFE00..0xFE0F || cp in 0xFE20..0xFE2F || cp == 0xFEFF ||
            cp in 0xE0100..0xE01EF || cp in 0x1F3FB..0x1F3FF

    /** Characters a monospace font is trusted to draw at exactly one cell, so they can be drawn as a run. */
    fun isPlain(c: Char): Boolean = c in ' '..'~' || c in '\u00a0'..'\u024f'
}
