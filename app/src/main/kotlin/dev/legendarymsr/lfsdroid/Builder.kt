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

    val files = RunFiles(java.io.File(context.filesDir, "run"))
    private var tail: LogTail? = null

    fun isRunning() = files.isRunning()

    /**
     * Prepares the host (download + unpack, short) in-process, then hands the long build to a
     * detached root process. Returns once it's launched; [monitor] follows it from the files.
     */
    fun start(): Boolean {
        cancelled = false
        if (isRunning()) return true
        parser.reset(_state.value.copy(phase = BuildProgress.Phase.Preparing, failedStep = null, exitCode = null, log = emptyList()))
        try {
            prep("Asking Magisk for root")
            if (!chroot.requestRoot()) return fail("No root: grant LFS Droid root in Magisk. This only works on a rooted phone.")
            Storage.check(freeBytes(), resuming = chroot.stateFile.exists())?.let { return fail(it) }
            if (!java.io.File(chroot.host, "bin/busybox").exists()) bootstrapHost()
            if (cancelled) return fail("Stopped")
            installScripts()
            files.prepare()
            // A fresh run: empty the log and exit file (app-owned, so the app may truncate them).
            java.io.RandomAccessFile(files.log, "rw").use { it.setLength(0) }
            files.exit.writeText(""); files.pid.writeText("")
            val jobs = Settings.jobs(context)
            val inner = "sh /root/host-setup.sh && echo 'lfsdroid: building with $jobs make jobs' && exec /bin/bash /root/lfs-build.sh"
            val cmd = "/system/bin/sh -c " + shellQuote(Chroot.launchScript(chroot.host, inner, Chroot.defaultEnv(jobs)))
            files.runner.writeText(runnerScript(files, cmd))
            tail = LogTail(files.log)
            prep("Starting the detached build")
            val rc = chroot.rootShell(detachCommand(files.runner))
            if (rc != 0) return fail("Couldn't start the build as root (exit $rc)")
            // Wait for the runner to check in.
            repeat(30) { if (files.pidValue() != null) return true; Thread.sleep(500) }
            return fail("The build process didn't start; see the log")
        } catch (e: Exception) {
            CrashLog.write(context, "start failed", e)
            return fail(e.message ?: e.toString())
        }
    }

    /** Folds new log lines into the state. Returns the run state. Cheap; call every second or so. */
    fun poll(): RunState {
        val t = tail ?: LogTail(files.log).also { tail = it }
        for (l in t.poll()) {
            if (l.startsWith("@@LFS runner")) continue
            line(l)
        }
        // Keep the shared log from growing to gigabytes: per-step logs are kept by the script anyway.
        if (files.log.length() > MAX_LOG_BYTES) runCatching { java.io.RandomAccessFile(files.log, "rw").use { it.setLength(0) } }
        val rs = runState(files.pidValue(), files.exitCode(), files.heartbeat.lastModified(), System.currentTimeMillis())
        val s = _state.value
        when (rs) {
            RunState.Running -> if (s.phase == BuildProgress.Phase.Idle || s.phase == BuildProgress.Phase.Failed && s.failedStep == null)
                emit(parser.set { it.copy(phase = if (it.total > 0) BuildProgress.Phase.Building else BuildProgress.Phase.Preparing) })
            RunState.Exited -> {
                val code = files.exitCode()
                if (code != 0 && s.phase != BuildProgress.Phase.Failed)
                    emit(parser.set { it.copy(phase = BuildProgress.Phase.Failed, exitCode = code, failedStep = it.failedStep ?: if (cancelled) "stop" else it.pkg) })
            }
            RunState.Lost -> if (s.phase == BuildProgress.Phase.Building || s.phase == BuildProgress.Phase.Preparing)
                emit(parser.set { it.copy(phase = BuildProgress.Phase.Failed, message = "The build process vanished (phone rebooted?). Tap Resume.", failedStep = it.pkg) })
            RunState.NeverStarted -> {}
        }
        return rs
    }

    fun stop() {
        cancelled = true
        files.pidValue()?.let { pid -> runCatching { chroot.rootShell(stopCommand(pid)) } }
        chroot.killAll()
        runCatching { if (files.exitCode() == null) files.exit.writeText("143") }
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
        const val MAX_LOG_BYTES = 16L * 1024 * 1024
        @Volatile private var instance: Builder? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: Builder(context.applicationContext).also { instance = it } }
    }
}
