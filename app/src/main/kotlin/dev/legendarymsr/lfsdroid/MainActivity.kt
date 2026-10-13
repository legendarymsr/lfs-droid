package dev.legendarymsr.lfsdroid

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Tokyo {
    val bg = Color(0xFF1A1B26)
    val bgDark = Color(0xFF16161E)
    val panel = Color(0xFF24283B)
    val line = Color(0xFF414868)
    val fg = Color(0xFFC0CAF5)
    val dim = Color(0xFF565F89)
    val blue = Color(0xFF7AA2F7)
    val purple = Color(0xFFBB9AF7)
    val green = Color(0xFF9ECE6A)
    val red = Color(0xFFF7768E)
    val yellow = Color(0xFFE0AF68)
    val mono = TextStyle(color = fg, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    val body = TextStyle(color = fg, fontSize = 16.sp)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        val builder = Builder.get(this)
        setContent { App(builder) }
    }

    override fun onResume() {
        super.onResume()
        // The build outlives the app; reattach the monitor if it's still going.
        Thread { runCatching { if (Builder.get(this).isRunning() && !BuildService.active) BuildService.attach(this) } }.start()
    }

    /** Asks once to be left out of battery optimisation, so Doze doesn't stop the monitor and wake lock. */
    fun askBatteryExemption() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName")))
        }
    }
}

private enum class Screen { Build, Terminal }

@Composable
private fun App(builder: Builder) {
    var screen by remember { mutableStateOf(Screen.Build) }
    var shellScript by remember { mutableStateOf<String?>(null) }
    var shellTitle by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { builder.loadSaved(); runCatching { builder.poll() } } }
    Box(Modifier.fillMaxSize().background(Tokyo.bg).safeDrawingPadding()) {
        when (screen) {
            Screen.Build -> BuildScreen(builder) { title, script -> shellTitle = title; shellScript = script; screen = Screen.Terminal }
            Screen.Terminal -> shellScript?.let { s -> TerminalScreen(shellTitle, s) { screen = Screen.Build } }
        }
    }
}

@Composable
fun Button(text: String, color: Color = Tokyo.blue, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = if (enabled) Tokyo.bg else Tokyo.dim, fontWeight = FontWeight.Bold, fontSize = 15.sp),
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(if (enabled) color else Tokyo.panel)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun BuildScreen(builder: Builder, openShell: (String, String) -> Unit) {
    val p by builder.state.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var free by remember { mutableStateOf("") }
    LaunchedEffect(p.phase) { free = withContext(Dispatchers.IO) { Storage.gb(builder.freeBytes()) } }
    val running = p.phase == BuildProgress.Phase.Preparing || p.phase == BuildProgress.Phase.Building
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText("LFS Droid", style = TextStyle(color = Tokyo.blue, fontSize = 26.sp, fontWeight = FontWeight.Bold))
        BasicText("Linux From Scratch 13.1 · aarch64 · in a chroot · for no reason", style = TextStyle(color = Tokyo.dim, fontSize = 13.sp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tokyo.panel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val headColor = when (p.phase) { BuildProgress.Phase.Failed -> Tokyo.red; BuildProgress.Phase.Done -> Tokyo.green; else -> Tokyo.fg }
            BasicText(p.headline, style = Tokyo.body.copy(color = headColor, fontWeight = FontWeight.Bold))
            if (p.message.isNotEmpty() && p.phase != BuildProgress.Phase.Done) BasicText(p.message, style = TextStyle(color = Tokyo.dim, fontSize = 13.sp))
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Tokyo.line)) {
                Box(Modifier.fillMaxWidth(p.fraction).fillMaxHeight().background(if (p.phase == BuildProgress.Phase.Failed) Tokyo.red else Tokyo.purple))
            }
            BasicText("${(p.fraction * 100).toInt()}% · $free free", style = TextStyle(color = Tokyo.dim, fontSize = 12.sp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) Button("Stop", Tokyo.red) { BuildService.stop(ctx) }
            else if (p.phase != BuildProgress.Phase.Done) Button(if (p.step > 0 || p.phase == BuildProgress.Phase.Failed) "Resume build" else "Start build") {
                (ctx as? MainActivity)?.askBatteryExemption(); BuildService.start(ctx)
            }
            Button("LFS shell", Tokyo.green, enabled = p.phase == BuildProgress.Phase.Done || builder.chroot.lfsFinished()) {
                openShell("LFS", builder.chroot.lfsShellScript())
            }
            Button("Build host", Tokyo.purple, enabled = builder.chroot.hostReady()) { openShell("Alpine build host", builder.chroot.hostShellScript()) }
        }
        JobsRow(running)
        Diagnostics()
        BasicText("Log", style = TextStyle(color = Tokyo.dim, fontSize = 13.sp))
        Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp)).background(Tokyo.bgDark).padding(10.dp)) {
            for (l in p.log) BasicText(l, style = Tokyo.mono, maxLines = 2)
        }
        BasicText(
            "Runs as real root through Magisk. Expect a day or two of compiling, a warm phone and ~18 GB of storage. Leave it on a charger.",
            style = TextStyle(color = Tokyo.dim, fontSize = 12.sp),
        )
    }
}

@Composable
private fun JobsRow(running: Boolean) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var jobs by remember { mutableIntStateOf(Settings.jobs(ctx)) }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText("make -j", style = Tokyo.body)
        Button("−", Tokyo.line, enabled = !running && jobs > 1) { jobs--; Settings.setJobs(ctx, jobs) }
        BasicText("$jobs", style = Tokyo.body.copy(color = Tokyo.purple, fontWeight = FontWeight.Bold))
        Button("+", Tokyo.line, enabled = !running && jobs < Settings.MAX_JOBS) { jobs++; Settings.setJobs(ctx, jobs) }
        BasicText("~2 GB RAM each; fewer = cooler", style = TextStyle(color = Tokyo.dim, fontSize = 12.sp))
    }
}

@Composable
private fun Diagnostics() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var crash by remember { mutableStateOf<String?>(null) }
    var exit by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { crash = CrashLog.read(ctx); exit = ExitReason.last(ctx) } }
    exit?.let { BasicText("Last app exit: $it", style = TextStyle(color = Tokyo.dim, fontSize = 12.sp)) }
    crash?.let { text ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tokyo.panel).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText("LFS Droid crashed last time", style = Tokyo.body.copy(color = Tokyo.red, fontWeight = FontWeight.Bold))
            BasicText(text.takeLast(1200), style = Tokyo.mono.copy(fontSize = 10.sp), maxLines = 12)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Copy") {
                    ctx.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("LFS Droid crash", text))
                }
                Button("Dismiss", Tokyo.line) { CrashLog.clear(ctx); crash = null }
            }
        }
    }
}
