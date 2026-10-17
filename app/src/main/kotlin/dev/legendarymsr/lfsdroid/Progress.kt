package dev.legendarymsr.lfsdroid

/** Where the build stands, folded from the `@@LFS` lines lfs-build.sh prints. */
data class BuildProgress(
    val phase: Phase = Phase.Idle,
    val chapter: String = "",
    val pkg: String = "",
    val step: Int = 0,
    val total: Int = 0,
    val fetched: Int = 0,
    val fetchTotal: Int = 0,
    val failedStep: String? = null,
    val exitCode: Int? = null,
    val log: List<String> = emptyList(),
    val message: String = "",
) {
    enum class Phase { Idle, Preparing, Building, Failed, Done }

    /** 0..1 over the whole plan; a step in flight counts as half done. */
    val fraction: Float
        get() = when {
            phase == Phase.Done -> 1f
            total <= 0 -> 0f
            else -> ((step - 1).coerceAtLeast(0) + 0.5f * (if (phase == Phase.Building) 1 else 0)) / total
        }.coerceIn(0f, 1f)

    /** "Chapter 5 · glibc · 5 of 27", or the downloading state. */
    val headline: String
        get() = when (phase) {
            Phase.Idle -> "Not started"
            Phase.Preparing -> message.ifEmpty { "Preparing the build host" }
            Phase.Done -> "Done. You built Linux From Scratch on a phone. Why."
            Phase.Failed -> "Failed at ${failedStep ?: pkg} (exit ${exitCode ?: "?"})"
            Phase.Building ->
                if (fetchTotal > 0 && fetched < fetchTotal && pkg == "sources") "Downloading sources · $fetched of $fetchTotal"
                else "${chapterLabel(chapter)} · $pkg · $step of $total"
        }

    companion object {
        fun chapterLabel(ch: String) = when (ch) {
            "host" -> "Host"
            "9" -> "Extras"
            "" -> ""
            else -> "Chapter $ch"
        }
    }
}

/** Feeds output lines into a [BuildProgress]; non-marker lines go into the log tail. */
class ProgressParser(
    private val tailSize: Int = 14,
) {
    var state = BuildProgress()
        private set
    private val tail = ArrayDeque<String>()

    fun reset(to: BuildProgress = BuildProgress()) {
        tail.clear()
        state = to
    }

    fun set(transform: (BuildProgress) -> BuildProgress): BuildProgress {
        state = transform(state)
        return state
    }

    /** Returns the new state. */
    fun feed(raw: String): BuildProgress {
        val line = raw.substringAfterLast('\r').trimEnd()
        val marker = parseMarker(line)
        state = when (marker) {
            null -> {
                if (line.isBlank()) return state
                tail.addLast(line)
                while (tail.size > tailSize) tail.removeFirst()
                state.copy(log = tail.toList())
            }
            is Marker.Step ->
                if (marker.chapter == "host") state.copy(phase = BuildProgress.Phase.Preparing, message = "Installing the Alpine build host (${marker.name})")
                else state.copy(phase = BuildProgress.Phase.Building, step = marker.n, total = marker.total, chapter = marker.chapter, pkg = marker.name, failedStep = null, exitCode = null)
            is Marker.Fetch -> state.copy(fetched = marker.n, fetchTotal = marker.total, message = marker.file)
            is Marker.Ok -> state
            is Marker.Fail -> state.copy(phase = BuildProgress.Phase.Failed, failedStep = marker.key, exitCode = marker.code)
            Marker.Done -> state.copy(phase = BuildProgress.Phase.Done, step = state.total)
        }
        return state
    }
}

sealed interface Marker {
    data class Step(val n: Int, val total: Int, val chapter: String, val name: String) : Marker
    data class Fetch(val n: Int, val total: Int, val file: String) : Marker
    data class Ok(val key: String) : Marker
    data class Fail(val key: String, val code: Int) : Marker
    data object Done : Marker
}

/** Parses one `@@LFS ...` line; null for anything else, including malformed markers. */
fun parseMarker(line: String): Marker? {
    if (!line.startsWith("@@LFS ")) return null
    val p = line.removePrefix("@@LFS ").trim().split(Regex("\\s+"))
    return when (p.firstOrNull()) {
        "step" -> if (p.size >= 5) Marker.Step(p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: return null, p[3], p.drop(4).joinToString(" ")) else null
        "fetch" -> if (p.size >= 4) Marker.Fetch(p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: return null, p[3]) else null
        "ok" -> p.getOrNull(1)?.let { Marker.Ok(it) }
        "fail" -> if (p.size >= 3) Marker.Fail(p[1], p[2].toIntOrNull() ?: return null) else null
        "done" -> Marker.Done
        else -> null
    }
}

/** Finished step keys from the state file (`chapter:name` per line). */
fun parseStateFile(text: String): Set<String> = text.lineSequence().map { it.trim() }.filter { it.matches(Regex("[0-9a-z]+:[A-Za-z0-9+._-]+")) }.toSet()

/** The plan as `lfs-build.sh plan` prints it: chapter:name:tarball:function. */
fun parsePlan(text: String): List<Pair<String, String>> =
    text.lineSequence().mapNotNull { l -> l.trim().split(':').takeIf { it.size == 4 }?.let { it[0] to it[1] } }.toList()

/** Resume point: index of the first step not in [done], or plan.size when all are. */
fun resumeIndex(plan: List<Pair<String, String>>, done: Set<String>): Int =
    plan.indexOfFirst { (ch, n) -> "$ch:$n" !in done }.let { if (it < 0) plan.size else it }

object Storage {
    /** Sources (~0.3 GB), the Linux tree for headers, the glibc build tree and a ~0.3 GB stripped result. */
    const val NEEDED_BYTES = 4L * 1024 * 1024 * 1024
    const val NEEDED_RESUME_BYTES = 3L * 1024 * 1024 * 1024

    fun check(freeBytes: Long, resuming: Boolean): String? {
        val need = if (resuming) NEEDED_RESUME_BYTES else NEEDED_BYTES
        return if (freeBytes >= need) null
        else "Only ${gb(freeBytes)} free; LFS needs about ${gb(need)}${if (resuming) " to carry on" else ""}. Free some space, then try again."
    }

    fun gb(b: Long) = "%.1f GB".format(b / (1024.0 * 1024 * 1024))
}
