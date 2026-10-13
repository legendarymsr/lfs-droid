package dev.legendarymsr.lfsdroid

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** [s] as one single-quoted shell word. */
fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

/**
 * The Alpine build host and the LFS tree inside it, entered as root through Magisk `su`.
 * Same approach as Larry Launcher's Gentoo guest: every command is a root child that unshares
 * its mount namespace (so nothing leaks into Android), mounts with explicit sources through
 * the root manager's busybox, then chroots. No init, no daemons.
 */
class Chroot(private val filesDir: File) {
    /** Alpine aarch64 minirootfs: the "host system" from LFS chapter 2. */
    val host = File(filesDir, "host")

    /** $LFS, as the build host sees it at /mnt/lfs. */
    val lfs = File(host, "mnt/lfs")
    val stateFile = File(lfs, "sources/.lfsdroid-state")
    val logDir = File(lfs, "sources/.logs")
    val tmpDir = File(filesDir, "tmp")

    fun hostReady() = File(host, "etc/.lfsdroid-host").exists()
    fun lfsFinished() = File(lfs, "usr/bin/fastfetch").exists() || (stateFile.exists() && "9:strip" in parseStateFile(stateFile.readText()))

    /** Whether `su` works; blocks on Magisk's dialog, so call it off the main thread. */
    fun requestRoot(): Boolean = try {
        val p = ProcessBuilder(SU, "-c", "id").redirectErrorStream(true).start()
        val out = StringBuffer()
        val t = Thread { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }.apply { isDaemon = true; start() }
        val done = p.waitFor(60, TimeUnit.SECONDS)
        if (!done) p.destroyForcibly()
        t.join(2000)
        done && p.exitValue() == 0 && "uid=0" in out
    } catch (e: IOException) {
        false
    }

    /** Runs [script] as root outside any chroot, feeding output lines to [onLine]. */
    fun rootShell(script: String, onLine: (String) -> Unit = {}): Int {
        val p = ProcessBuilder(SU, "-c", script).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().forEachLine(onLine)
        return p.waitFor()
    }

    /** Runs [script] inside the build host chroot. */
    fun runInHost(script: String, onLine: (String) -> Unit = {}): Int = rootShell(launchScript(host, script), onLine)

    /** Kills every process rooted in the host or the LFS tree (a cancelled build leaves gcc running otherwise). */
    fun killAll() {
        val roots = listOf(host.path, lfs.path).joinToString(" ") { shellQuote(it) }
        runCatching {
            rootShell("for p in /proc/[0-9]*; do r=\$(readlink \$p/root 2>/dev/null); for t in $roots; do [ \"\$r\" = \"\$t\" ] && kill -9 \${p#/proc/} 2>/dev/null; done; done; true")
        }
    }

    /** Deletes the host and LFS tree as root (their files are root's). */
    fun wipe(): Int {
        killAll()
        return rootShell("rm -rf ${shellQuote(host.path)} ${shellQuote(tmpDir.path)}")
    }

    /** A login shell in the finished LFS. */
    fun lfsShellScript(): String = launchScript(host, "exec /bin/bash /root/lfs-build.sh shell")

    /** A login shell in the Alpine build host (works mid-build). */
    fun hostShellScript(): String = launchScript(host, "exec /bin/bash -l 2>/dev/null || exec /bin/sh -l")

    companion object {
        private const val SU = "su"
        const val MOUNT_FAILED = 125

        const val PICK_BUSYBOX =
            "BB=; for b in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do " +
                "[ -x \"\$b\" ] && { BB=\$b; break; }; done; export BB"

        /**
         * The `su -c` script: busybox, a private mount namespace, /dev /proc /sys and a fresh
         * /dev/shm and /tmp for [root], then chroot into it with a clean environment. Every mount
         * names its source, target and type; a failing one prints itself and exits 125.
         */
        fun launchScript(root: File, script: String, env: List<String> = defaultEnv()): String {
            val r = shellQuote(root.path)
            val mounts = listOf(
                "\$MOUNT --make-rprivate / 2>/dev/null || true",
                "mkdir -p $r/dev $r/proc $r/sys $r/tmp $r/mnt/lfs",
                "m -o rbind /dev $r/dev",
                "m -t proc proc $r/proc",
                "m -t sysfs sysfs $r/sys",
                // Not the host's /dev/shm (the rbind would share Android's): a private tmpfs.
                "mkdir -p $r/dev/shm && m -t tmpfs -o mode=1777,nosuid,nodev tmpfs $r/dev/shm",
                // The build's scratch dirs; on disk, not in RAM: gcc's build tree is gigabytes.
                "mkdir -p $r/var/tmp/lfs && chmod 1777 $r/tmp $r/var/tmp",
                "{ [ -s $r/etc/resolv.conf ] || printf 'nameserver 1.1.1.1\\nnameserver 8.8.8.8\\n' > $r/etc/resolv.conf; }",
            )
            val chroot = "exec \$CHROOT $r /usr/bin/env -i ${env.joinToString(" ") { shellQuote(it) }} /bin/sh -c ${shellQuote("cd /root 2>/dev/null; $script")}"
            val inner = listOf(
                "if [ -n \"\$BB\" ]; then MOUNT=\"\$BB mount\"; CHROOT=\"\$BB chroot\"; else MOUNT=mount; CHROOT=chroot; fi",
                "m() { err=\$(\$MOUNT \"\$@\" 2>&1) || { echo \"lfsdroid: mount \$* failed: \$err\"; exit $MOUNT_FAILED; }; }",
                mounts.joinToString("\n"),
                chroot,
            ).joinToString("\n")
            return "$PICK_BUSYBOX\n" +
                "if [ -n \"\$BB\" ]; then exec \$BB unshare -m /system/bin/sh -c ${shellQuote(inner)}; " +
                "else exec unshare -m /system/bin/sh -c ${shellQuote(inner)}; fi"
        }

        fun defaultEnv(jobs: Int? = null) = listOfNotNull(
            "HOME=/root",
            "USER=root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "TZ=${java.util.TimeZone.getDefault().id}",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR=/var/tmp/lfs",
            "LFS=/mnt/lfs",
            jobs?.let { "JOBS=$it" },
        )

        /** Parallel make jobs: all cores is a fine way to cook a phone and run out of RAM. */
        fun jobsFor(cores: Int, ramBytes: Long): Int {
            val byRam = (ramBytes / (1536L * 1024 * 1024)).toInt() // ~1.5 GB per g++ job on the big files
            return minOf(cores, byRam, 6).coerceAtLeast(1)
        }
    }
}
