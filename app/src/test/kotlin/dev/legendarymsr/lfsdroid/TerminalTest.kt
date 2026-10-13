package dev.legendarymsr.lfsdroid

import org.junit.Assert.*
import org.junit.Test

class TerminalTest {
    @Test fun printsAndWraps() {
        val t = TerminalBuffer(3, 5)
        t.write("hello world")
        assertEquals(listOf("hello", " worl", "d"), t.screenText())
    }

    @Test fun scrollsIntoScrollback() {
        val t = TerminalBuffer(2, 10)
        t.write("a\r\nb\r\nc")
        assertEquals(listOf("b", "c"), t.screenText())
        assertEquals("a", t.scrollback.last().text())
    }

    @Test fun cursorMovesAndErase() {
        val t = TerminalBuffer(3, 10)
        t.write("abcdef\u001b[3D\u001b[K")
        assertEquals("abc", t.screenText()[0])
        t.write("\u001b[2;4HX")
        assertEquals("   X", t.screenText()[1])
        t.write("\u001b[2J")
        assertTrue(t.screenText().all { it.isEmpty() })
    }

    @Test fun backspaceAndCr() {
        val t = TerminalBuffer(2, 10)
        t.write("abc\bX\rZ")
        assertEquals("ZbX", t.screenText()[0])
    }

    @Test fun colours() {
        val t = TerminalBuffer(1, 10)
        t.write("\u001b[1;34mA\u001b[0mB\u001b[38;2;247;118;142mC")
        assertEquals(12, t.line(0).colors[0])
        assertEquals(-1, t.line(0).colors[1])
        assertEquals(1, t.line(0).colors[2])
    }

    @Test fun ignoresOscTitles() {
        val t = TerminalBuffer(1, 20)
        t.write("\u001b]0;root@lfs\u0007ok\u001b]2;x\u001b\\!")
        assertEquals("ok!", t.screenText()[0])
    }

    @Test fun resizeKeepsCursorLine() {
        val t = TerminalBuffer(4, 10)
        t.write("1\r\n2\r\n3\r\n4")
        t.resize(2, 6)
        assertEquals(listOf("3", "4"), t.screenText())
        assertEquals(1, t.cursorRow)
    }

    @Test fun insertDeleteChars() {
        val t = TerminalBuffer(1, 10)
        t.write("abcd\u001b[3G\u001b[P")
        assertEquals("abd", t.screenText()[0])
        t.write("\u001b[2@")
        assertEquals("ab  d", t.screenText()[0])
    }

    @Test fun ctrlKeys() {
        assertEquals("\u0003", Keys.ctrl('c'))
        assertEquals("\u0004", Keys.ctrl('D'))
        assertEquals("\u001b", Keys.ctrl('['))
    }

    @Test fun keyboardDeltas() {
        assertEquals("a", keyboardDelta(" ", " a"))
        assertEquals("\u007f", keyboardDelta(" ", ""))
        assertEquals("\r", keyboardDelta(" ", " \n"))
        assertEquals("hi", keyboardDelta(" ", " hi"))
    }
}
