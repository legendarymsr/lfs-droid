package dev.legendarymsr.lfsdroid

/**
 * A deliberately small VT100/xterm subset: enough for bash, less, a
 * progress bar and fastfetch's colours. Not vim-grade; for that, use ssh.
 * Pure Kotlin so it's unit tested off-device.
 */
class TerminalBuffer(
    rows: Int = 24,
    cols: Int = 80,
    private val scrollbackMax: Int = 1000,
) {
    var rows = rows; private set
    var cols = cols; private set
    var cursorRow = 0; private set
    var cursorCol = 0; private set

    /** One cell: the character and an SGR colour index (-1 default, 0..15 ANSI, bit 16 = bold). */
    class Line(cols: Int) {
        var chars = CharArray(cols) { ' ' }
        var colors = IntArray(cols) { -1 }
        fun text() = String(chars).replace(TermGrid.WIDE_TAIL.toString(), "").trimEnd()
        fun resize(c: Int) {
            chars = chars.copyOf(c).also { for (i in chars.size until c) it[i] = ' ' }
            colors = colors.copyOf(c).also { for (i in colors.size until c) it[i] = -1 }
        }
        fun clear(from: Int = 0, to: Int = chars.size) { for (i in from until to.coerceAtMost(chars.size)) { chars[i] = ' '; colors[i] = -1 } }
    }

    private var screen = MutableList(rows) { Line(cols) }
    val scrollback = ArrayDeque<Line>()
    private var color = -1
    private var bold = false
    private var savedRow = 0
    private var savedCol = 0
    private var wrapPending = false
    private var highSurrogate: Char? = null

    private enum class S { Normal, Esc, Csi, Osc, OscEsc, Charset }
    private var state = S.Normal
    private val params = StringBuilder()

    /** Bumped on every change so the UI knows to redraw. */
    var version = 0L; private set

    fun line(r: Int): Line = screen[r]
    fun screenText(): List<String> = screen.map { it.text() }

    fun resize(newRows: Int, newCols: Int) {
        if (newRows < 1 || newCols < 1) return
        if (newCols != cols) screen.forEach { it.resize(newCols) }
        while (screen.size > newRows) {
            // Drop blank lines below the cursor first, then push the top into scrollback.
            if (screen.size - 1 > cursorRow && screen.last().text().isEmpty()) screen.removeAt(screen.size - 1)
            else { pushScrollback(screen.removeAt(0)); cursorRow-- }
        }
        while (screen.size < newRows) screen.add(Line(newCols))
        rows = newRows; cols = newCols
        cursorRow = cursorRow.coerceIn(0, rows - 1); cursorCol = cursorCol.coerceIn(0, cols - 1)
        version++
    }

    fun write(s: CharSequence) {
        for (ch in s) put(ch)
        version++
    }

    private fun pushScrollback(l: Line) {
        scrollback.addLast(l)
        while (scrollback.size > scrollbackMax) scrollback.removeFirst()
    }

    private fun lineFeed() {
        if (cursorRow == rows - 1) {
            pushScrollback(screen.removeAt(0)); screen.add(Line(cols))
        } else cursorRow++
    }

    private fun put(ch: Char) {
        when (state) {
            S.Normal -> when (ch) {
                '\u001b' -> state = S.Esc
                '\r' -> { cursorCol = 0; wrapPending = false }
                '\n', '\u000b', '\u000c' -> { lineFeed(); wrapPending = false }
                '\b' -> { if (cursorCol > 0) cursorCol--; wrapPending = false }
                '\t' -> cursorCol = ((cursorCol / 8 + 1) * 8).coerceAtMost(cols - 1)
                '\u0007', '\u0000', '\u000e', '\u000f' -> {}
                else -> if (ch >= ' ') printable(ch)
            }
            S.Esc -> {
                state = S.Normal
                when (ch) {
                    '[' -> { params.clear(); state = S.Csi }
                    ']' -> state = S.Osc
                    '(', ')' -> state = S.Charset
                    '7' -> { savedRow = cursorRow; savedCol = cursorCol }
                    '8' -> { cursorRow = savedRow; cursorCol = savedCol }
                    'M' -> if (cursorRow > 0) cursorRow-- else { screen.removeAt(rows - 1); screen.add(0, Line(cols)) }
                    'D' -> lineFeed()
                    'c' -> { screen.forEach { it.clear() }; cursorRow = 0; cursorCol = 0 }
                }
            }
            S.Charset -> state = S.Normal
            S.Osc -> when (ch) { '\u0007' -> state = S.Normal; '\u001b' -> state = S.OscEsc }
            S.OscEsc -> state = if (ch == '\\') S.Normal else S.Osc
            S.Csi -> if (ch in '0'..'9' || ch == ';' || ch == '?' || ch == '>' || ch == '=') params.append(ch) else { csi(ch); state = S.Normal }
        }
    }

    private fun printable(ch: Char) {
        if (ch.isHighSurrogate()) { highSurrogate = ch; return }
        val hi = highSurrogate; highSurrogate = null
        if (ch.isLowSurrogate()) {
            // Astral chars (emoji, mostly) are two cells wide, which is also what bash/wcwidth assume.
            if (hi != null && !TermGrid.isZeroWidth(Character.toCodePoint(hi, ch))) place(hi, ch)
            return
        }
        when {
            TermGrid.isZeroWidth(ch.code) -> {}
            TermGrid.isWide(ch.code) -> place(ch, TermGrid.WIDE_TAIL)
            else -> place(ch, null)
        }
    }

    /** Puts one character at the cursor: [second] is its other half for two-cell chars. */
    private fun place(ch: Char, second: Char?) {
        val w = if (second == null || cols < 2) 1 else 2
        if (wrapPending) { cursorCol = 0; lineFeed(); wrapPending = false }
        if (w == 2 && cursorCol == cols - 1) {
            screen[cursorRow].clear(cursorCol); cursorCol = 0; lineFeed()
        }
        val l = screen[cursorRow]
        val c = if (bold && color in 0..7) color + 8 else color
        l.chars[cursorCol] = ch; l.colors[cursorCol] = c
        if (w == 2) { l.chars[cursorCol + 1] = second!!; l.colors[cursorCol + 1] = c }
        if (cursorCol + w >= cols) { cursorCol = cols - 1; wrapPending = true } else cursorCol += w
    }

    private fun csi(final: Char) {
        val priv = params.startsWith("?")
        val nums = params.toString().trimStart('?', '>', '=').split(';').map { it.toIntOrNull() }
        fun n(i: Int, d: Int = 1) = nums.getOrNull(i)?.takeIf { it > 0 } ?: d
        wrapPending = false
        when (final) {
            'A' -> cursorRow = (cursorRow - n(0)).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + n(0)).coerceAtMost(rows - 1)
            'C' -> cursorCol = (cursorCol + n(0)).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - n(0)).coerceAtLeast(0)
            'G' -> cursorCol = (n(0) - 1).coerceIn(0, cols - 1)
            'd' -> cursorRow = (n(0) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> { cursorRow = (n(0) - 1).coerceIn(0, rows - 1); cursorCol = (n(1) - 1).coerceIn(0, cols - 1) }
            'J' -> when (nums.getOrNull(0) ?: 0) {
                0 -> { screen[cursorRow].clear(cursorCol); for (r in cursorRow + 1 until rows) screen[r].clear() }
                1 -> { for (r in 0 until cursorRow) screen[r].clear(); screen[cursorRow].clear(0, cursorCol + 1) }
                else -> screen.forEach { it.clear() }
            }
            'K' -> when (nums.getOrNull(0) ?: 0) {
                0 -> screen[cursorRow].clear(cursorCol)
                1 -> screen[cursorRow].clear(0, cursorCol + 1)
                else -> screen[cursorRow].clear()
            }
            'P' -> { val l = screen[cursorRow]; val k = n(0).coerceAtMost(cols - cursorCol)
                for (i in cursorCol until cols) { val src = i + k; l.chars[i] = if (src < cols) l.chars[src] else ' '; l.colors[i] = if (src < cols) l.colors[src] else -1 } }
            '@' -> { val l = screen[cursorRow]; val k = n(0).coerceAtMost(cols - cursorCol)
                for (i in cols - 1 downTo cursorCol + k) { l.chars[i] = l.chars[i - k]; l.colors[i] = l.colors[i - k] }
                l.clear(cursorCol, cursorCol + k) }
            'X' -> screen[cursorRow].clear(cursorCol, cursorCol + n(0))
            'L' -> repeat(n(0)) { screen.removeAt(rows - 1); screen.add(cursorRow, Line(cols)) }
            'M' -> repeat(n(0)) { screen.removeAt(cursorRow); screen.add(Line(cols)) }
            's' -> { savedRow = cursorRow; savedCol = cursorCol }
            'u' -> { cursorRow = savedRow; cursorCol = savedCol }
            'm' -> if (!priv) sgr(nums)
        }
    }

    private fun sgr(nums: List<Int?>) {
        var i = 0
        val list = nums.ifEmpty { listOf(0) }
        while (i < list.size) {
            when (val v = list[i] ?: 0) {
                0 -> { color = -1; bold = false }
                1 -> bold = true
                22 -> bold = false
                39 -> color = -1
                in 30..37 -> color = v - 30
                in 90..97 -> color = v - 90 + 8
                38 -> { // 256/truecolour: approximate to the nearest of the 16
                    if (list.getOrNull(i + 1) == 5) { color = (list.getOrNull(i + 2) ?: 7).let { if (it < 16) it else 7 }; i += 2 }
                    else if (list.getOrNull(i + 1) == 2) { color = nearest(list.getOrNull(i + 2) ?: 0, list.getOrNull(i + 3) ?: 0, list.getOrNull(i + 4) ?: 0); i += 4 }
                }
                48 -> i += if (list.getOrNull(i + 1) == 5) 2 else if (list.getOrNull(i + 1) == 2) 4 else 0
            }
            i++
        }
    }

    companion object {
        /** Tokyo Night's 16 colours. */
        val PALETTE = longArrayOf(
            0xFF15161E, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68, 0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFA9B1D6,
            0xFF414868, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68, 0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFC0CAF5,
        )

        fun nearest(r: Int, g: Int, b: Int): Int = PALETTE.indices.minBy { i ->
            val c = PALETTE[i]
            val dr = ((c shr 16) and 0xFF) - r; val dg = ((c shr 8) and 0xFF) - g; val db = (c and 0xFF) - b
            dr * dr + dg * dg + db * db
        }
    }
}

/** Bytes a key sends: what the extra-keys row and the soft keyboard map to. */
object Keys {
    const val ESC = "\u001b"
    const val TAB = "\t"
    const val UP = "\u001b[A"
    const val DOWN = "\u001b[B"
    const val RIGHT = "\u001b[C"
    const val LEFT = "\u001b[D"

    /** Ctrl+[c]: a..z and a few symbols to their control codes; anything else unchanged. */
    fun ctrl(c: Char): String = when (val l = c.lowercaseChar()) {
        in 'a'..'z' -> (l.code - 'a'.code + 1).toChar().toString()
        '[' -> ESC
        '\\' -> "\u001c"
        ']' -> "\u001d"
        ' ', '@' -> "\u0000"
        else -> c.toString()
    }
}
