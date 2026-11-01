package com.harmonycam.app.ui

import android.content.Intent
import android.opengl.GLSurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.harmonycam.app.MainActivity
import com.harmonycam.app.Notes
import com.harmonycam.app.StudioViewModel
import com.harmonycam.app.audio.AudioRouting
import com.harmonycam.app.gl.EffectsRenderer
import com.harmonycam.app.gl.VisualStyle
import com.harmonycam.app.lyrics.LyricMatcher
import com.harmonycam.app.lyrics.LyricsTracker

@Composable
fun StudioScreen(vm: StudioViewModel) {
    val context = LocalContext.current
    val activity = context as MainActivity
    var granted by remember { mutableStateOf(activity.hasPermissions()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res.values.all { it } && activity.hasPermissions()
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(MainActivity.REQUIRED) }

    val glView = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(EffectsRenderer.configChooser())
            preserveEGLContextOnPause = true
        }
    }
    remember {
        EffectsRenderer(glView).also {
            glView.setRenderer(it)
            glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            vm.attachRenderer(it)
        }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, granted) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (granted) vm.onStart(owner)
                Lifecycle.Event.ON_RESUME -> glView.onResume()
                Lifecycle.Event.ON_PAUSE -> glView.onPause()
                Lifecycle.Event.ON_STOP -> vm.onStop()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    var showSettings by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())

        // top scrim for legibility
        Box(
            Modifier.fillMaxWidth().height(140.dp)
                .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
        )
        Box(
            Modifier.fillMaxWidth().height(260.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
        )

        if (!granted) {
            PermissionCard(Modifier.align(Alignment.Center)) { launcher.launch(MainActivity.REQUIRED) }
        } else {
            Column(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                TopBar(vm, onSettings = { showSettings = true }, onLyrics = { showLyrics = true })
                val r = vm.route
                if (r != null && r.output == AudioRouting.OutputKind.SPEAKER && !vm.settings.speakerMonitor) {
                    Spacer(Modifier.height(8.dp))
                    Banner(Icons.Rounded.Headphones, "Plug in headphones or your USB interface to hear the harmonies live. Speaker monitoring is off to prevent feedback.")
                }
                vm.engineError?.let {
                    Spacer(Modifier.height(8.dp))
                    Banner(Icons.Rounded.VolumeOff, it, Hc.Record)
                }
            }

            VoiceHud(vm, Modifier.align(Alignment.CenterEnd).padding(end = 10.dp))

            val song = vm.activeSong
            if (song != null && vm.prompterVisible) {
                Prompter(vm, song.lines, Modifier.align(Alignment.BottomCenter).padding(bottom = 178.dp, start = 12.dp, end = 12.dp))
            }

            BottomControls(vm, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp))
        }

        AnimatedVisibility(vm.exporting, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            ExportOverlay(vm.exportProgress, vm.exportLabel)
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 120.dp))
    }

    if (showSettings) SettingsSheet(vm) { showSettings = false }
    if (showLyrics) LyricsSheet(vm) { showLyrics = false }
}

@Composable
private fun PermissionCard(modifier: Modifier, onGrant: () -> Unit) {
    Column(
        modifier.padding(32.dp).clip(RoundedCornerShape(24.dp)).background(Hc.Panel).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("HarmonyCam needs your camera and microphone", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, color = Hc.Text)
        Spacer(Modifier.height(10.dp))
        Text("The camera tracks your hands to control the harmonies; the mic is what gets harmonised.", color = Hc.TextDim, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Button(onClick = onGrant) { Text("Allow access") }
    }
}

@Composable
private fun Banner(icon: ImageVector, text: String, tint: Color = Hc.Warn) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Hc.Panel).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Hc.Text, fontSize = 13.sp)
    }
}

@Composable
private fun Pill(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Hc.PanelSoft).border(1.dp, Hc.Line, RoundedCornerShape(50))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

@Composable
private fun TopBar(vm: StudioViewModel, onSettings: () -> Unit, onLyrics: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val r = vm.route
        Pill(onClick = onSettings) {
            Icon(if (r?.inputIsUsb == true) Icons.Rounded.Usb else Icons.Rounded.Mic, null, tint = Hc.Text, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(r?.inputName ?: "Mic", color = Hc.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 96.dp))
            Spacer(Modifier.width(8.dp))
            InputMeter(vm.meters.inputPeak)
        }
        Spacer(Modifier.width(8.dp))
        val s = vm.settings
        val m = vm.meters
        val keyText = if (s.keyRoot >= 0) Notes.key(s.keyRoot, s.minor)
        else if (m.keyRoot >= 0) "Auto · " + Notes.key(m.keyRoot, m.minor) else "Auto · listening"
        Pill(onClick = onSettings) {
            Text(keyText, color = Hc.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        RoundIcon(Icons.Rounded.MusicNote, "Lyrics", onLyrics)
        Spacer(Modifier.width(8.dp))
        RoundIcon(Icons.Rounded.Tune, "Settings", onSettings)
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, desc: String, onClick: () -> Unit, tint: Color = Hc.Text) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(Hc.PanelSoft).border(1.dp, Hc.Line, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(20.dp)) }
}

/** Input level LED bar: green → amber → red (clipping). Set your interface's gain so it peaks in the amber. */
@Composable
fun InputMeter(peak: Float, width: Int = 40) {
    val p by animateFloatAsState(peak.coerceIn(0f, 1f), label = "peak")
    val color = when {
        peak > 0.95f -> Hc.Record
        peak > 0.5f -> Hc.Warn
        else -> Hc.Ok
    }
    Box(Modifier.width(width.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF))) {
        Box(Modifier.fillMaxWidth(p).height(6.dp).background(color))
    }
}

@Composable
private fun VoiceHud(vm: StudioViewModel, modifier: Modifier) {
    val m = vm.meters
    val g = vm.gesture
    val muted = vm.settings.gestures && g.mute
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (muted) {
            Pill { Text("HARMONIES MUTED", color = Hc.Warn, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
        for (v in 0 until 4) {
            val env = m.voiceEnv[v]
            val alpha by animateFloatAsState(0.25f + 0.75f * env.coerceIn(0f, 1f), label = "env$v")
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Hc.PanelSoft).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (env > 0.05f) Notes.name(m.voiceMidi[v]) else "—", color = Hc.Text.copy(alpha = alpha), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(10.dp).clip(CircleShape).background(Hc.Voice[v].copy(alpha = alpha)))
            }
        }
        if (m.voiced && m.note > 0) {
            Text("you: " + Notes.name(m.note), color = Hc.TextDim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Prompter(vm: StudioViewModel, lines: List<String>, modifier: Modifier) {
    if (lines.isEmpty()) return
    val idx = vm.lyricLine.coerceIn(0, lines.size - 1)
    val prev = lines.getOrNull(idx - 1)
    val cur = lines[idx]
    val next = lines.getOrNull(idx + 1)
    val following = vm.lyricsStatus == LyricsTracker.Status.LISTENING

    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(Color(0xB30A0A12), Color(0xD90A0A12))))
            .border(1.dp, Hc.Line, RoundedCornerShape(22.dp))
            .pointerInput(lines) {
                detectTapGestures(onTap = { vm.nextLine() }, onLongPress = { vm.previousLine() })
            }
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dot by animateColorAsState(if (following) Hc.Ok else Hc.TextDim, label = "dot")
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
            Text(
                when (vm.lyricsStatus) {
                    LyricsTracker.Status.LISTENING -> "Following your voice · tap = next line"
                    LyricsTracker.Status.LOADING_MODEL -> "Loading lyric tracker…"
                    LyricsTracker.Status.ERROR -> "Manual mode · tap = next line"
                    else -> "Tap = next line · hold = back"
                },
                color = Hc.TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f)
            )
            Text("${idx + 1}/${lines.size}", color = Hc.TextDim, fontSize = 11.sp)
            Spacer(Modifier.width(4.dp))
            SmallIcon(Icons.Rounded.SkipPrevious) { vm.previousLine() }
            SmallIcon(Icons.Rounded.SkipNext) { vm.nextLine() }
            SmallIcon(Icons.Rounded.Replay) { vm.restartLyrics() }
            SmallIcon(Icons.Rounded.VisibilityOff) { vm.prompterVisible = false }
        }
        Spacer(Modifier.height(6.dp))
        if (prev != null) Text(prev, color = Color.White.copy(alpha = 0.35f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(highlight(cur, vm.lyricWord), fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        if (next != null) Text(next, color = Color.White.copy(alpha = 0.72f), fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun highlight(line: String, sung: Int) = buildAnnotatedString {
    val parts = line.split(Regex("(?<=\\s)"))
    var counted = 0
    for (p in parts) {
        val isWord = LyricMatcher.tokenize(p).isNotEmpty()
        val done = isWord && counted < sung
        if (isWord) counted++
        withStyle(SpanStyle(color = if (done) Hc.Accent else Color.White)) { append(p) }
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(icon, null, tint = Hc.TextDim, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun BottomControls(vm: StudioViewModel, modifier: Modifier) {
    val s = vm.settings
    val context = LocalContext.current
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (s.gestures) {
            val g = vm.gesture
            val hint = if (g.voiceHand == null) "✋ Show your hand: fingers = voices · height = harmony · fist = mute · left-hand pinch = reverb"
            else "${g.voices} voice${if (g.voices == 1) "" else "s"} · ${heightLabel(g.height)}${if (g.fxHand != null) " · FX ${(g.fx * 100).toInt()}%" else ""}"
            Text(hint, color = Hc.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center)
        } else {
            ManualHarmonyControls(vm)
        }
        if (vm.activeSong != null && !vm.prompterVisible) {
            Spacer(Modifier.height(6.dp))
            Pill(onClick = { vm.prompterVisible = true }) { Text("Show lyrics", color = Hc.Text, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            val last = vm.lastVideo
            RoundIcon(Icons.Rounded.Share, "Share last video", onClick = {
                if (last != null) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, last)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Share video"))
                } else vm.message = "Record a take first"
            }, tint = if (last != null) Hc.Text else Hc.TextDim)
            RecordButton(vm.recording, vm.meters.recSeconds) { vm.toggleRecording() }
            RoundIcon(Icons.Rounded.AutoAwesome, "Visual style", onClick = {
                val all = VisualStyle.entries
                val nextStyle = all[(all.indexOf(s.style) + 1) % all.size]
                vm.updateSettings { it.copy(style = nextStyle) }
                vm.message = "Look: ${nextStyle.label}"
            })
        }
    }
}

private fun heightLabel(h: Float) = when {
    h < 0.2f -> "harmonies below"
    h < 0.4f -> "mostly below"
    h < 0.6f -> "around you"
    h < 0.8f -> "mostly above"
    else -> "harmonies above"
}

@Composable
private fun ManualHarmonyControls(vm: StudioViewModel) {
    val s = vm.settings
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Hc.PanelSoft).padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Voices", color = Hc.TextDim, fontSize = 12.sp, modifier = Modifier.width(64.dp))
            for (n in 0..4) {
                val sel = s.manualVoices == n
                Box(
                    Modifier.padding(end = 6.dp).size(34.dp).clip(CircleShape)
                        .background(if (sel) Hc.Accent else Color(0x22FFFFFF))
                        .clickable { vm.updateSettings { it.copy(manualVoices = n) } },
                    contentAlignment = Alignment.Center
                ) { Text("$n", color = if (sel) Color.Black else Hc.Text, fontWeight = FontWeight.SemiBold) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Below", color = Hc.TextDim, fontSize = 12.sp, modifier = Modifier.width(64.dp))
            Slider(value = s.manualHeight, onValueChange = { v -> vm.updateSettings { it.copy(manualHeight = v) } }, modifier = Modifier.weight(1f))
            Text("Above", color = Hc.TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun RecordButton(recording: Boolean, seconds: Float, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val ring by animateColorAsState(if (recording) Hc.Record else Color.White, label = "ring")
        Box(
            Modifier.size(78.dp).clip(CircleShape).border(4.dp, ring, CircleShape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            val inner by animateFloatAsState(if (recording) 0.42f else 0.78f, label = "inner")
            Box(
                Modifier.fillMaxSize(inner).clip(if (recording) RoundedCornerShape(8.dp) else CircleShape).background(Hc.Record)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (recording) "● %d:%02d".format(seconds.toInt() / 60, seconds.toInt() % 60) else "REC",
            color = if (recording) Hc.Record else Hc.TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ExportOverlay(progress: Float, label: String) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(84.dp), strokeWidth = 6.dp, color = Hc.Accent, trackColor = Color(0x33FFFFFF))
                Text("${(progress * 100).toInt()}%", color = Hc.Text, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))
            Text(label, color = Hc.Text)
            Text("Studio-quality harmonies are rendered after each take", color = Hc.TextDim, fontSize = 12.sp)
        }
    }
}
