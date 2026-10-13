package dev.legendarymsr.lfsdroid

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/** A root shell on a pseudo-terminal: `su -c [script]` with a real tty, so bash, less and Ctrl+C behave. */
class Pty private constructor(private val fd: Int, val pid: Int) {
    private val pfd = ParcelFileDescriptor.fromFd(fd)
    val input = FileInputStream(pfd.fileDescriptor)
    private val output = FileOutputStream(pfd.fileDescriptor)

    fun write(s: String) = runCatching { output.write(s.toByteArray()); output.flush() }
    fun resize(rows: Int, cols: Int) = nativeResize(fd, rows, cols)
    fun waitFor(): Int = nativeWait(pid)
    fun close() { runCatching { pfd.close() }; nativeHangup(fd, pid) }

    companion object {
        init { System.loadLibrary("lfspty") }

        fun spawn(script: String, rows: Int, cols: Int): Pty {
            val r = nativeSpawn(script, rows, cols) ?: error("forkpty failed")
            return Pty(r[0], r[1])
        }

        @JvmStatic private external fun nativeSpawn(script: String, rows: Int, cols: Int): IntArray?
        @JvmStatic private external fun nativeResize(fd: Int, rows: Int, cols: Int)
        @JvmStatic private external fun nativeWait(pid: Int): Int
        @JvmStatic private external fun nativeHangup(fd: Int, pid: Int)
    }
}
