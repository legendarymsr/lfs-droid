package dev.legendarymsr.lfsdroid

import java.io.File
import java.io.RandomAccessFile

/**
 * Files the detached root build shares with the app. The app creates each one first, so they
 * carry the app's owner and SELinux label; root only appends or truncates, which keeps both.
 */
class RunFiles(dir: File) {
    val dir = dir
    val log = File(dir, "build.log")
    val pid = File(dir, "build.pid")
    val exit = File(dir, "build.exit")
    val heartbeat = File(dir, "build.heartbeat")
    val runner = File(dir, "runner.sh")

    fun prepare() {
        dir.mkdirs()
        for (f in listOf(log, pid, exit, heartbeat)) if (!f.exists()) f.createNewFile()
        for (f in listOf(log, pid, exit, heartbeat)) { f.setReadable(true, false); f.setWritable(true, false) }
    }

    fun exitCode(): Int? = runCatching { exit.readText().trim().toIntOrNull() }.getOrNull()
    fun pidValue(): Int? = runCatching { pid.readText().trim().toIntOrNull() }.getOrNull()

    /** Running = started, no exit code yet, heartbeat fresh. Survives the app being killed and relaunched. */
    fun isRunning(now: Long = System.currentTimeMillis()): Boolean =
        runState(pidValue(), exitCode(), heartbeat.lastModified(), now) == RunState.Running

    companion object {
        const val HEARTBEAT_SECS = 20
        const val STALE_MS = 3 * 60_000L
    }
}

enum class RunState { NeverStarted, Running, Exited, Lost }

/** What the build process is doing, from its pid/exit/heartbeat files. "Lost" = died without writing an exit code (e.g. phone rebooted). */
fun runState(pid: Int?, exit: Int?, heartbeatMs: Long, now: Long): RunState = when {
    pid == null -> RunState.NeverStarted
    exit != null -> RunState.Exited
    now - heartbeatMs <= RunFiles.STALE_MS -> RunState.Running
    else -> RunState.Lost
}

/**
 * The root script that runs the build detached from the app: its own session (setsid), stdin
 * from /dev/null, output appended to the log, a heartbeat, and its exit code written at the end.
 * Killing the app, the service or the activity doesn't touch it; Stop kills its process group.
 */
fun runnerScript(files: RunFiles, chrootCommand: String): String {
    val q = { f: File -> shellQuote(f.path) }
    return """
        |#!/system/bin/sh
        |# LFS Droid detached build runner. Started by the app via su; owned by nobody but root.
        |echo $$ > ${q(files.pid)}
        |: > ${q(files.exit)}
        |( while [ ! -s ${q(files.exit)} ]; do touch ${q(files.heartbeat)}; sleep ${RunFiles.HEARTBEAT_SECS}; done ) </dev/null >/dev/null 2>&1 &
        |hb=$!
        |# Keep the kernel's OOM killer off the runner itself; compilers stay killable.
        |echo -800 > /proc/$$/oom_score_adj 2>/dev/null
        |echo "@@LFS runner start $(date +%s)" >> ${q(files.log)}
        |$chrootCommand >> ${q(files.log)} 2>&1 </dev/null
        |rc=$?
        |echo "@@LFS runner exit ${'$'}rc" >> ${q(files.log)}
        |echo ${'$'}rc > ${q(files.exit)}
        |kill ${'$'}hb 2>/dev/null
        |touch ${q(files.heartbeat)}
        |""".trimMargin()
}

/** The `su -c` line that starts [runner] fully detached and returns at once. */
fun detachCommand(runner: File): String {
    val r = shellQuote(runner.path)
    return "${Chroot.PICK_BUSYBOX}\n" +
        "if [ -n \"\$BB\" ]; then S=\"\$BB setsid\"; NH=\"\$BB nohup\"; else S=setsid; NH=nohup; fi\n" +
        "\$S \$NH /system/bin/sh $r </dev/null >/dev/null 2>&1 &\n" +
        "exit 0"
}

/** Kills the runner's process group (setsid made its pid the pgid). */
fun stopCommand(pid: Int): String = "kill -TERM -$pid 2>/dev/null; sleep 2; kill -KILL -$pid 2>/dev/null; true"

/**
 * Reads a growing log incrementally, never the whole file: on attach it starts at most
 * [initialBytes] from the end, then follows new bytes. Handles truncation.
 */
class LogTail(private val file: File, private val initialBytes: Long = 64 * 1024, private val chunk: Int = 64 * 1024) {
    private var pos = -1L
    private var partial = StringBuilder()

    /** New complete lines since the last call. */
    fun poll(): List<String> {
        val len = file.length()
        if (pos < 0) pos = (len - initialBytes).coerceAtLeast(0)
        if (len < pos) { pos = 0; partial.setLength(0) }
        if (len == pos) return emptyList()
        val out = mutableListOf<String>()
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(pos)
            val buf = ByteArray(chunk)
            var skipFirst = pos > 0 && partial.isEmpty() && out.isEmpty() && firstPoll
            while (pos < len) {
                val n = raf.read(buf, 0, minOf(chunk.toLong(), len - pos).toInt())
                if (n <= 0) break
                pos += n
                partial.append(String(buf, 0, n, Charsets.UTF_8))
                var nl = partial.indexOf('\n')
                while (nl >= 0) {
                    val l = partial.substring(0, nl)
                    partial.delete(0, nl + 1)
                    if (skipFirst) skipFirst = false else out += l
                    nl = partial.indexOf('\n')
                }
                // A runaway line with no newline: flush it rather than grow forever.
                if (partial.length > MAX_LINE) { out += partial.substring(0, MAX_LINE); partial.setLength(0) }
            }
        }
        firstPoll = false
        return out
    }

    private var firstPoll = true

    companion object { const val MAX_LINE = 4096 }
}
