package dev.legendarymsr.lfsdroid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
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

@Composable
fun TerminalScreen(title: String, script: String, onClose: () -> Unit) {
    val term = remember { TerminalBuffer(24, 80) }
    var tick by remember { mutableLongStateOf(0L) }
    var pty by remember { mutableStateOf<Pty?>(null) }
    var exited by remember { mutableStateOf<Int?>(null) }
    var ctrl by remember { mutableStateOf(false) }
    val style = Tokyo.mono
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cell = remember { measurer.measure("M", style).size }
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(1))) }

    fun send(s: String) { pty?.write(s) }

    DisposableEffect(Unit) { onDispose { pty?.close() } }

    Column(Modifier.fillMaxSize().background(Tokyo.bgDark)) {
        Row(Modifier.fillMaxWidth().background(Tokyo.panel).padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText("$title${exited?.let { " · exited $it" } ?: ""}", style = Tokyo.body.copy(color = Tokyo.blue))
            BasicText("Close", style = Tokyo.body.copy(color = Tokyo.red), modifier = Modifier.clickable { onClose() })
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(4.dp).clickable { focus.requestFocus() }) {
            val cols = (constraints.maxWidth / cell.width.coerceAtLeast(1)).coerceAtLeast(20)
            val rows = (constraints.maxHeight / cell.height.coerceAtLeast(1)).coerceAtLeast(5)
            LaunchedEffect(rows, cols) {
                term.resize(rows, cols); pty?.resize(rows, cols); tick++
            }
            LaunchedEffect(Unit) {
                val p = withContext(Dispatchers.IO) { runCatching { Pty.spawn(script, rows, cols) }.getOrNull() }
                if (p == null) { term.write("failed to start the shell: is the phone rooted?\r\n"); tick++; return@LaunchedEffect }
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
            val lines = remember(tick) { (0 until term.rows).map { render(term, it) } }
            Column {
                for (l in lines) BasicText(l, style = style, softWrap = false, maxLines = 1)
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
            textStyle = style.copy(color = Color.Transparent),
            modifier = Modifier.fillMaxWidth().height(1.dp).focusRequester(focus),
        )
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

private fun render(t: TerminalBuffer, r: Int): AnnotatedString {
    val line = t.line(r)
    return buildAnnotatedString {
        var i = 0
        val n = line.chars.size
        while (i < n) {
            val c = line.colors[i]
            var j = i
            while (j < n && line.colors[j] == c) j++
            val text = String(line.chars, i, j - i).let { s ->
                if (r == t.cursorRow && t.cursorCol in i until j) s else s
            }
            val color = if (c in 0..15) Color(TerminalBuffer.PALETTE[c]) else Tokyo.fg
            withStyle(SpanStyle(color = color)) {
                if (r == t.cursorRow && t.cursorCol in i until j) {
                    val k = t.cursorCol - i
                    append(text.substring(0, k))
                    withStyle(SpanStyle(background = Tokyo.blue, color = Tokyo.bg)) { append(text[k]) }
                    append(text.substring(k + 1))
                } else append(text)
            }
            i = j
        }
    }
}
