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
}

private enum class Screen { Build, Terminal }

@Composable
private fun App(builder: Builder) {
    var screen by remember { mutableStateOf(Screen.Build) }
    var shellScript by remember { mutableStateOf<String?>(null) }
    var shellTitle by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { builder.loadSaved() } }
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
            else if (p.phase != BuildProgress.Phase.Done) Button(if (p.step > 0 || p.phase == BuildProgress.Phase.Failed) "Resume build" else "Start build") { BuildService.start(ctx) }
            Button("LFS shell", Tokyo.green, enabled = p.phase == BuildProgress.Phase.Done || builder.chroot.lfsFinished()) {
                openShell("LFS", builder.chroot.lfsShellScript())
            }
            Button("Build host", Tokyo.purple, enabled = builder.chroot.hostReady()) { openShell("Alpine build host", builder.chroot.hostShellScript()) }
        }
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
