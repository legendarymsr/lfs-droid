package dev.legendarymsr.lfsdroid

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the soft keyboard typed, as bytes for the pty: the field keeps one sentinel char so Backspace is visible. */
fun keyboardDelta(old: String, new: String): String {
    if (new.length < old.length) return "\u007f".repeat(old.length - new.length)
    val added = if (new.startsWith(old)) new.substring(old.length) else new.drop(1)
    return added.replace('\n', '\r')
}

private const val SENTINEL = " "

/** Pixel metrics of one terminal cell for a monospace Paint. */
private class Cell(val paint: Paint) {
    val advance: Float = paint.measureText("M".repeat(100)) / 100f
    val ascent: Float = -paint.fontMetrics.ascent
    val height: Float = kotlin.math.ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent)
}

@Composable
fun TerminalScreen(title: String, script: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val term = remember { TerminalBuffer(24, 80) }
    var tick by remember { mutableLongStateOf(0L) }
    var pty by remember { mutableStateOf<Pty?>(null) }
    var exited by remember { mutableStateOf<Int?>(null) }
    var ctrl by remember { mutableStateOf(false) }
    var fontSp by remember { mutableFloatStateOf(Settings.termFont(ctx)) }
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    // One Paint, measured once per size: every cell is exactly advance x height px, rows sit at
    // integer multiples of height, so the grid the pty is told about is the grid that's drawn.
    val cell = remember(fontSp, density) {
        Cell(Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = with(density) { fontSp.sp.toPx() }
        })
    }
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(1))) }

    fun send(s: String) { pty?.write(s) }

    DisposableEffect(Unit) { onDispose { pty?.close() } }

    Column(Modifier.fillMaxSize().background(Tokyo.bgDark)) {
        Row(Modifier.fillMaxWidth().background(Tokyo.panel).padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText("$title${exited?.let { " · exited $it" } ?: ""}", style = Tokyo.body.copy(color = Tokyo.blue), maxLines = 1,
                modifier = Modifier.weight(1f, fill = false))
            BasicText("${fontSp.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }}sp", style = Tokyo.body.copy(color = Tokyo.dim),
                modifier = Modifier.padding(horizontal = 12.dp))
            BasicText("Close", style = Tokyo.body.copy(color = Tokyo.red), modifier = Modifier.clickable { onClose() })
        }
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().padding(4.dp).clipToBounds()
                .pointerInput(Unit) {
                    // Pinch to zoom, tap to bring the keyboard back.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var pinched = false
                        do {
                            val ev = awaitPointerEvent()
                            if (ev.changes.count { it.pressed } >= 2) {
                                val z = ev.calculateZoom()
                                if (z != 1f) fontSp = TermGrid.zoom(fontSp, z).let { if (kotlin.math.abs(it - fontSp) >= 0.5f) it else fontSp }
                                pinched = true
                                ev.changes.forEach { it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                        if (pinched) Settings.setTermFont(ctx, fontSp)
                        else { runCatching { focus.requestFocus() }; keyboard?.show() }
                    }
                },
        ) {
            val size = TermGrid.fit(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), cell.advance, cell.height)
            val rows = size.rows
            val cols = size.cols
            val latest by rememberUpdatedState(size)
            LaunchedEffect(rows, cols) {
                // The keyboard animating in and a pinch both change the size every frame;
                // settle first so the shell gets one SIGWINCH, not thirty.
                if (term.rows != rows || term.cols != cols) delay(120)
                term.resize(rows, cols); pty?.resize(rows, cols); tick++
            }
            LaunchedEffect(Unit) {
                val p = withContext(Dispatchers.IO) { runCatching { Pty.spawn(script, latest.rows, latest.cols) }.getOrNull() }
                if (p == null) { term.write("failed to start the shell: is the phone rooted?\r\n"); tick++; return@LaunchedEffect }
                // The size may have changed while su was starting up.
                term.resize(latest.rows, latest.cols); p.resize(latest.rows, latest.cols)
                pty = p
                launch(Dispatchers.IO) {
                    val reader = p.input.reader(Charsets.UTF_8)
                    val buf = CharArray(8192)
                    while (isActive) {
                        val n = runCatching { reader.read(buf) }.getOrDefault(-1)
                        if (n < 0) break
                        withContext(Dispatchers.Main) { term.write(String(buf, 0, n)); tick = term.version }
                    }
                    val code = p.waitFor()
                    withContext(Dispatchers.Main) { exited = code }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val redraw = tick // read so new output redraws
                drawIntoCanvas { drawGrid(it.nativeCanvas, term, cell) }
            }
        }
        val keys = listOf("ESC" to Keys.ESC, "TAB" to Keys.TAB, "CTRL" to "", "^C" to Keys.ctrl('c'), "^D" to Keys.ctrl('d'), "←" to Keys.LEFT, "↑" to Keys.UP, "↓" to Keys.DOWN, "→" to Keys.RIGHT)
        Row(Modifier.fillMaxWidth().background(Tokyo.panel), horizontalArrangement = Arrangement.SpaceEvenly) {
            for ((label, seq) in keys) {
                val on = label == "CTRL" && ctrl
                BasicText(label, style = Tokyo.body.copy(color = if (on) Tokyo.purple else Tokyo.fg),
                    modifier = Modifier.clickable { if (label == "CTRL") ctrl = !ctrl else send(seq) }.padding(horizontal = 6.dp, vertical = 10.dp))
            }
        }
        BasicTextField(
            value = field,
            onValueChange = { v ->
                var d = keyboardDelta(field.text, v.text)
                if (ctrl && d.length == 1 && d[0].isLetter()) { d = Keys.ctrl(d[0]); ctrl = false }
                if (d.isNotEmpty()) send(d)
                field = TextFieldValue(SENTINEL, TextRange(1))
            },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.None),
            textStyle = Tokyo.mono.copy(color = Color.Transparent),
            modifier = Modifier.fillMaxWidth().height(1.dp).focusRequester(focus),
        )
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

private val FG = Tokyo.fg.toArgb()
private val CURSOR_BG = Tokyo.blue.toArgb()
private val CURSOR_FG = Tokyo.bg.toArgb()

/**
 * Draws the buffer cell by cell on a fixed grid: x = col * advance, y = row * height.
 * Plain ASCII/Latin runs go out in one drawText; anything a fallback font might draw
 * wider or narrower (box drawing, blocks, CJK, emoji) is drawn alone in its own cells
 * and squeezed to fit, so it can't push the rest of the line out of alignment.
 */
private fun drawGrid(c: android.graphics.Canvas, t: TerminalBuffer, cell: Cell) {
    val p = cell.paint
    val adv = cell.advance
    val sb = StringBuilder()
    for (r in 0 until t.rows) {
        val line = t.line(r)
        val n = minOf(line.chars.size, t.cols)
        val top = r * cell.height
        val base = top + cell.ascent
        val cursorHere = r == t.cursorRow
        if (cursorHere && t.cursorCol < n) {
            val w = if (t.cursorCol + 1 < n && (line.chars[t.cursorCol + 1] == TermGrid.WIDE_TAIL || line.chars[t.cursorCol].isHighSurrogate())) 2 else 1
            p.color = CURSOR_BG
            c.drawRect(t.cursorCol * adv, top, (t.cursorCol + w) * adv, top + cell.height, p)
        }
        fun colorAt(i: Int): Int = when {
            cursorHere && i == t.cursorCol -> CURSOR_FG
            line.colors[i] in 0..15 -> TerminalBuffer.PALETTE[line.colors[i]].toInt()
            else -> FG
        }
        var i = 0
        while (i < n) {
            val ch = line.chars[i]
            if (ch == ' ' || ch == TermGrid.WIDE_TAIL) { i++; continue }
            val color = colorAt(i)
            if (TermGrid.isPlain(ch)) {
                sb.setLength(0)
                var j = i
                while (j < n && TermGrid.isPlain(line.chars[j]) && colorAt(j) == color) { sb.append(line.chars[j]); j++ }
                p.color = color
                c.drawText(sb, 0, sb.length, i * adv, base, p)
                i = j
                continue
            }
            // A lone glyph: one cell, or two for wide chars and surrogate pairs.
            val pair = ch.isHighSurrogate() && i + 1 < n && line.chars[i + 1].isLowSurrogate()
            if (ch.isSurrogate() && !pair) { i++; continue }
            val text = if (pair) String(line.chars, i, 2) else ch.toString()
            val cells = if (pair || (i + 1 < n && line.chars[i + 1] == TermGrid.WIDE_TAIL)) 2 else 1
            val want = cells * adv
            val got = p.measureText(text)
            p.color = color
            if (got > want + 0.5f && got > 0f) {
                c.save(); c.scale(want / got, 1f, i * adv, base)
                c.drawText(text, i * adv, base, p)
                c.restore()
            } else c.drawText(text, i * adv + (want - got) / 2f, base, p)
            i += cells
        }
    }
}
