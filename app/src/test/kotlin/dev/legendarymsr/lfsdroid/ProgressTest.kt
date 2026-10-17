package dev.legendarymsr.lfsdroid

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ProgressTest {
    @Test fun parsesMarkers() {
        assertEquals(Marker.Step(4, 113, "5", "gcc-pass1"), parseMarker("@@LFS step 4 113 5 gcc-pass1"))
        assertEquals(Marker.Fetch(3, 90, "bash-5.3.tar.gz"), parseMarker("@@LFS fetch 3 90 bash-5.3.tar.gz"))
        assertEquals(Marker.Fail("8:glibc", 2), parseMarker("@@LFS fail 8:glibc 2"))
        assertEquals(Marker.Ok("6:m4"), parseMarker("@@LFS ok 6:m4"))
        assertEquals(Marker.Done, parseMarker("@@LFS done"))
        assertNull(parseMarker("checking for gcc... gcc"))
        assertNull(parseMarker("@@LFS step x 1 5 m4"))
        assertNull(parseMarker("@@LFS bogus"))
    }

    @Test fun foldsIntoProgress() {
        val p = ProgressParser(tailSize = 3)
        p.feed("@@LFS step 10 100 6 ncurses")
        assertEquals(BuildProgress.Phase.Building, p.state.phase)
        assertEquals("Chapter 6 · ncurses · 10 of 100", p.state.headline)
        assertEquals(0.095f, p.state.fraction, 0.0001f)
        listOf("a", "b", "c", "d").forEach { p.feed(it) }
        assertEquals(listOf("b", "c", "d"), p.state.log)
        p.feed("@@LFS fail 6:ncurses 1")
        assertEquals(BuildProgress.Phase.Failed, p.state.phase)
        assertEquals("Failed at 6:ncurses (exit 1)", p.state.headline)
        p.feed("@@LFS step 10 100 6 ncurses")
        assertNull(p.state.failedStep)
        p.feed("@@LFS done")
        assertEquals(1f, p.state.fraction, 0f)
    }

    @Test fun carriageReturnKeepsLatestRedraw() {
        val p = ProgressParser()
        p.feed(" 10%\r 50%\r100%")
        assertEquals(listOf("100%"), p.state.log)
    }

    @Test fun downloadHeadline() {
        val p = ProgressParser()
        p.feed("@@LFS step 2 113 4 sources")
        p.feed("@@LFS fetch 7 92 gcc-16.2.0.tar.xz")
        assertEquals("Downloading sources · 7 of 92", p.state.headline)
    }

    @Test fun hostStepIsPreparing() {
        val p = ProgressParser()
        p.feed("@@LFS step 0 0 host alpine-packages")
        assertEquals(BuildProgress.Phase.Preparing, p.state.phase)
    }

    private val script = File("src/main/assets/lfs-build.sh").readText()
    private val plan = parsePlan(script.substringAfter("PLAN=\"").substringBefore("\""))

    @Test fun planFromTheRealScript() {
        assertTrue(plan.size in 20..40)
        assertEquals("4" to "layout", plan.first())
        assertEquals("9" to "fastfetch", plan[plan.size - 3])
        assertEquals("9" to "check", plan.last())
        assertEquals("plan keys must be unique", plan.size, plan.map { "${it.first}:${it.second}" }.toSet().size)
        // Every step function the plan names exists in the script.
        val fns = script.substringAfter("PLAN=\"").substringBefore("\"").lines().mapNotNull { it.split(':').getOrNull(3) }
        for (f in fns) assertTrue("missing $f()", Regex("(?m)^$f\\(\\)").containsMatchIn(script))
        // No BLFS / GUI.
        // No cross toolchain, no extras.
        for (bad in listOf("xorg", "mesa", "wayland", "gtk", "grub", "pass1", "pass2", "systemd", "perl", "python", "texinfo", "gettext", "man-pages")) assertFalse(bad, plan.any { it.second.contains(bad) })
    }

    @Test fun resumesAtFirstUnfinished() {
        val done = parseStateFile("#format 2\n4:layout\n4:sources\n5:toolwrap\ngarbage line\n")
        assertEquals(3, done.size)
        assertEquals(3, resumeIndex(plan, done))
        assertEquals(0, resumeIndex(plan, emptySet()))
        assertEquals(plan.size, resumeIndex(plan, plan.map { "${it.first}:${it.second}" }.toSet()))
    }

    @Test fun storage() {
        assertNull(Storage.check(5L shl 30, resuming = false))
        assertNotNull(Storage.check(3L shl 30, resuming = false))
        assertNull(Storage.check(3L shl 30, resuming = true))
        assertNotNull(Storage.check(1L shl 30, resuming = true))
    }

    @Test fun jobs() {
        assertEquals(1, Chroot.jobsFor(8, 1L shl 30))
        assertEquals(3, Chroot.jobsFor(8, 7_500_000_000L)) // an "8 GB" phone reports ~7.5
        assertEquals(4, Chroot.jobsFor(8, 16L shl 30))
        assertEquals(2, Chroot.jobsFor(2, 16L shl 30))
    }

    @Test fun alpineYaml() {
        val y = "-\n  title: \"Mini root filesystem\"\n  file: alpine-minirootfs-3.24.2-aarch64.tar.gz\n  sha256: x\n"
        assertEquals("alpine-minirootfs-3.24.2-aarch64.tar.gz", alpineMinirootfs(y))
        assertNull(alpineMinirootfs("file: alpine-standard-3.24.2-aarch64.iso"))
    }

    @Test fun launchScriptQuotes() {
        val s = Chroot.launchScript(File("/data/data/x/files/host"), "echo 'hi'")
        assertTrue(s.contains("unshare -m"))
        assertTrue(s.contains("m -t proc proc"))
        assertTrue(s.startsWith(Chroot.PICK_BUSYBOX))
        assertEquals("'it'\\''s'", shellQuote("it's"))
    }
}
