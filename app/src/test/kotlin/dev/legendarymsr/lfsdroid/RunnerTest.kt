package dev.legendarymsr.lfsdroid

import android.app.ApplicationExitInfo
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RunnerTest {
    private fun tmp() = Files.createTempDirectory("lfsdroid").toFile()

    @Test fun runStates() {
        val now = 10_000_000L
        assertEquals(RunState.NeverStarted, runState(null, null, 0, now))
        assertEquals(RunState.Running, runState(42, null, now - 30_000, now))
        assertEquals(RunState.Lost, runState(42, null, now - RunFiles.STALE_MS - 1, now))
        assertEquals(RunState.Exited, runState(42, 0, 0, now))
        assertEquals(RunState.Exited, runState(42, 2, now, now))
    }

    @Test fun runFilesReadBack() {
        val f = RunFiles(tmp()); f.prepare()
        assertFalse(f.isRunning())
        f.pid.writeText("1234\n"); f.heartbeat.setLastModified(System.currentTimeMillis())
        assertTrue(f.isRunning())
        f.exit.writeText("0\n")
        assertFalse(f.isRunning())
        assertEquals(0, f.exitCode())
    }

    @Test fun runnerScriptIsDetachedAndRecordsExit() {
        val f = RunFiles(File("/data/x/run"))
        val s = runnerScript(f, "echo hi")
        assertTrue(s.contains("echo $$ > '/data/x/run/build.pid'"))
        assertTrue(s.contains("echo hi >> '/data/x/run/build.log' 2>&1 </dev/null"))
        assertTrue(s.contains("echo \$rc > '/data/x/run/build.exit'"))
        assertTrue(s.contains("oom_score_adj"))
        val d = detachCommand(f.runner)
        assertTrue(d.contains("setsid"))
        assertTrue(d.contains("nohup"))
        assertTrue(d.contains("</dev/null >/dev/null 2>&1 &"))
        assertEquals("kill -TERM -99 2>/dev/null; sleep 2; kill -KILL -99 2>/dev/null; true", stopCommand(99))
    }

    @Test fun runnerScriptActuallyRuns() {
        // Execute it with the host sh (paths swapped) to prove pid/exit/log handling works end to end.
        val f = RunFiles(tmp()); f.prepare()
        val s = runnerScript(f, "sh -c 'echo built; exit 3'").replace("#!/system/bin/sh", "#!/bin/sh")
        f.runner.writeText(s)
        val p = ProcessBuilder("sh", f.runner.path).start()
        assertEquals(0, p.waitFor())
        assertEquals(3, f.exitCode())
        assertNotNull(f.pidValue())
        val log = f.log.readText()
        assertTrue(log, log.contains("built"))
        assertTrue(log.contains("@@LFS runner exit 3"))
    }

    @Test fun logTailFollowsWithoutReadingEverything() {
        val file = File(tmp(), "log")
        file.writeText("x".repeat(200) + "\nold1\nold2\n")
        val t = LogTail(file, initialBytes = 8)
        // Starts near the end, drops the cut-off first line.
        assertEquals(listOf("old2"), t.poll())
        assertEquals(emptyList<String>(), t.poll())
        file.appendText("new1\npart")
        assertEquals(listOf("new1"), t.poll())
        file.appendText("ial\n")
        assertEquals(listOf("partial"), t.poll())
        // Truncated by the app: starts over.
        file.writeText("fresh\n")
        assertEquals(listOf("fresh"), t.poll())
    }

    @Test fun logTailCapsRunawayLines() {
        val file = File(tmp(), "log")
        file.writeText("y".repeat(LogTail.MAX_LINE * 3))
        val t = LogTail(file, initialBytes = Long.MAX_VALUE, chunk = 1024)
        val lines = t.poll()
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { it.length <= LogTail.MAX_LINE })
    }

    @Test fun parserLogStaysCapped() {
        val p = ProgressParser(tailSize = 14)
        repeat(10_000) { p.feed("line $it") }
        assertEquals(14, p.state.log.size)
        assertEquals("line 9999", p.state.log.last())
    }

    @Test fun crashFormat() {
        val s = CrashLog.format("boom", IllegalStateException("bad"), 0)
        assertTrue(s.startsWith("=== "))
        assertTrue(s.contains("boom"))
        assertTrue(s.contains("IllegalStateException: bad"))
    }

    @Test fun exitReasons() {
        assertTrue(ExitReason.describe(ApplicationExitInfo.REASON_LOW_MEMORY, null, 0, 512 * 1024).contains("low memory (lmkd) · 512 MB"))
        assertTrue(ExitReason.describe(ApplicationExitInfo.REASON_CRASH, "NPE", 0, 0).contains("crashed (Java) (NPE)"))
        assertTrue(ExitReason.describe(999, "", 0, 0).contains("reason 999"))
    }
}
