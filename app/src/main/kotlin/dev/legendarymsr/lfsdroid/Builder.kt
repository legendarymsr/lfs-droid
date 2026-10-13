package dev.legendarymsr.lfsdroid

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Picks the minirootfs file name out of Alpine's latest-releases.yaml. */
fun alpineMinirootfs(yaml: String): String? =
    Regex("""file:\s*(alpine-minirootfs-[0-9.]+-aarch64\.tar\.gz)""").find(yaml)?.groupValues?.get(1)

/** The whole build, end to end. Runs on the service's thread; state is shared with the UI. */
class Builder(private val context: Context) {
    val chroot = Chroot(context.filesDir)
    private val parser = ProgressParser()
    private val _state = MutableStateFlow(BuildProgress())
    val state: StateFlow<BuildProgress> get() = _state

    @Volatile var cancelled = false

    private fun emit(p: BuildProgress) { _state.value = p }
    private fun line(s: String) = emit(parser.feed(s))
    private fun prep(msg: String) = emit(parser.set { it.copy(phase = BuildProgress.Phase.Preparing, message = msg) })

    fun freeBytes(): Long = StatFs(context.filesDir.path).availableBytes

    /** Restores the step count from the state file, so the screen isn't blank after a reboot. */
    fun loadSaved() {
        if (_state.value.phase != BuildProgress.Phase.Idle) return
        val done = runCatching { parseStateFile(chroot.stateFile.readText()) }.getOrDefault(emptySet())
        if (done.isEmpty()) return
        val plan = runCatching { parsePlan(context.assets.open("lfs-build.sh").bufferedReader().readText().substringAfter("PLAN=\"").substringBefore("\"")) }.getOrDefault(emptyList())
        val i = resumeIndex(plan, done)
        emit(parser.set {
            if (i >= plan.size) it.copy(phase = BuildProgress.Phase.Done, step = plan.size, total = plan.size)
            else it.copy(step = i + 1, total = plan.size, chapter = plan[i].first, pkg = plan[i].second, message = "Paused; ${done.size} steps done, tap Resume")
        })
    }

    /** Runs to the end or the first failure. Returns true when LFS is complete. */
    fun run(): Boolean {
        cancelled = false
        parser.reset(_state.value.copy(phase = BuildProgress.Phase.Preparing, failedStep = null, exitCode = null, log = emptyList()))
        try {
            prep("Asking Magisk for root")
            if (!chroot.requestRoot()) return fail("No root: grant LFS Droid root in Magisk. This only works on a rooted phone.")
            Storage.check(freeBytes(), resuming = chroot.stateFile.exists())?.let { return fail(it) }
            if (!File(chroot.host, "bin/busybox").exists()) bootstrapHost()
            if (cancelled) return fail("Stopped")
            installScripts()
            val rc = chroot.runInHost("sh /root/host-setup.sh") { line(it) }
            if (rc != 0) return fail("Setting up the Alpine host failed (exit $rc)")
            if (cancelled) return fail("Stopped")
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val jobs = Chroot.jobsFor(Runtime.getRuntime().availableProcessors(), mem.totalMem)
            line("lfsdroid: building with $jobs make jobs")
            val script = Chroot.launchScript(chroot.host, "exec /bin/bash /root/lfs-build.sh", Chroot.defaultEnv(jobs))
            val code = chroot.rootShell(script) { line(it) }
            val s = _state.value
            if (code == 0 && s.phase == BuildProgress.Phase.Done) return true
            if (s.phase != BuildProgress.Phase.Failed) emit(parser.set { it.copy(phase = BuildProgress.Phase.Failed, exitCode = code, failedStep = it.failedStep ?: if (cancelled) "stop" else it.pkg) })
            return false
        } catch (e: Exception) {
            return fail(e.message ?: e.toString())
        }
    }

    fun stop() {
        cancelled = true
        chroot.killAll()
    }

    private fun fail(msg: String): Boolean {
        line("lfsdroid: $msg")
        emit(parser.set { it.copy(phase = BuildProgress.Phase.Failed, message = msg, failedStep = it.failedStep ?: msg) })
        return false
    }

    private fun installScripts() {
        val tmp = File(context.cacheDir, "scripts").apply { mkdirs() }
        for (n in listOf("lfs-build.sh", "host-setup.sh")) context.assets.open(n).use { i -> File(tmp, n).outputStream().use { i.copyTo(it) } }
        val dst = shellQuote(File(chroot.host, "root").path)
        check(chroot.rootShell("mkdir -p $dst && cp ${shellQuote(tmp.path)}/*.sh $dst/ && chmod 755 $dst/*.sh") == 0) { "couldn't copy the build scripts" }
    }

    private fun bootstrapHost() {
        val base = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64"
        prep("Finding the latest Alpine minirootfs")
        val name = alpineMinirootfs(URL("$base/latest-releases.yaml").readText()) ?: error("no minirootfs in Alpine's release list")
        val tar = File(context.cacheDir, name)
        if (!tar.exists() || tar.length() < 1_000_000) {
            val conn = URL("$base/$name").openConnection() as HttpURLConnection
            val len = conn.contentLengthLong
            val part = File(tar.path + ".part")
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var got = 0L; var last = 0L
                    while (true) {
                        val k = input.read(buf); if (k < 0) break
                        out.write(buf, 0, k); got += k
                        if (got - last > 256 * 1024) { last = got; prep("Downloading $name · ${got / 1024} of ${len / 1024} KB") }
                    }
                }
            }
            part.renameTo(tar)
        }
        prep("Unpacking the Alpine build host")
        val h = shellQuote(chroot.host.path)
        val rc = chroot.rootShell("${Chroot.PICK_BUSYBOX}\nmkdir -p $h && cd $h && { if [ -n \"\$BB\" ]; then \$BB tar -xzf ${shellQuote(tar.path)}; else tar -xzf ${shellQuote(tar.path)}; fi; }") { line(it) }
        check(rc == 0 && File(chroot.host, "bin/busybox").exists()) { "unpacking Alpine failed (exit $rc)" }
        tar.delete()
    }

    companion object {
        @Volatile private var instance: Builder? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: Builder(context.applicationContext).also { instance = it } }
    }
}
