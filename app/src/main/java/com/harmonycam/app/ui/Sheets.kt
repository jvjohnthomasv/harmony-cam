package com.harmonycam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmonycam.app.Notes
import com.harmonycam.app.StudioSettings
import com.harmonycam.app.StudioViewModel
import com.harmonycam.app.audio.AudioRouting
import com.harmonycam.app.lyrics.LyricsStore
import com.harmonycam.app.lyrics.Song

// ─────────────────────────────── Settings ───────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: StudioViewModel, onPickTrack: () -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Color(0xFF12121B)) {
        val s = vm.settings
        val set: ((StudioSettings) -> StudioSettings) -> Unit = { vm.updateSettings(it) }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()
        ) {
            Section("Key")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Auto", s.keyRoot < 0) { set { it.copy(keyRoot = -1) } }
                for (pc in 0 until 12) Chip(Notes.pitchClass(pc), s.keyRoot == pc) { set { it.copy(keyRoot = pc) } }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Major", !s.minor, enabled = s.keyRoot >= 0) { set { it.copy(minor = false) } }
                Chip("Minor", s.minor, enabled = s.keyRoot >= 0) { set { it.copy(minor = true) } }
            }
            Hint(if (s.keyRoot < 0) "Auto listens for ~3 s of singing, then locks harmonies to the detected key. Pick the key yourself for the most accurate harmonies from the first note." else "Harmonies stay inside ${Notes.key(s.keyRoot, s.minor)}.")

            Section("Harmony style")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Digital chord (reel)", s.chordStyle) { set { it.copy(chordStyle = true) } }
                Chip("Classic intervals", !s.chordStyle) { set { it.copy(chordStyle = false) } }
            }
            Hint(if (s.chordStyle) "A full chord from the key around every note you sing — deep bass on the chord root plus voices above and below, each locked to its exact note (the glassy “digital harmonies” sound)." else "Natural 3rds/5ths that follow your voice, vibrato included.")

            Section("Harmony sound")
            LabeledSlider("Harmony level", s.harmonyLevel) { v -> set { it.copy(harmonyLevel = v) } }
            LabeledSlider("Lead vocal level", s.leadLevel) { v -> set { it.copy(leadLevel = v) } }
            LabeledSlider("Natural feel (timing, drift, timbre)", s.humanize) { v -> set { it.copy(humanize = v) } }
            LabeledSlider("Harmony tuning (tight ↔ loose)", s.tightness, left = "loose", right = "tight") { v -> set { it.copy(tightness = v) } }
            LabeledSlider("Stereo width", s.width) { v -> set { it.copy(width = v) } }

            Section("Hand gestures")
            Toggle("Control harmonies with hands", s.gestures) { v -> set { it.copy(gestures = v) } }
            Toggle("Swap hands (lower hand = voices)", s.swapHands) { v -> set { it.copy(swapHands = v) } }
            Toggle("Keep harmonies when hand leaves frame", s.holdWhenLost) { v -> set { it.copy(holdWhenLost = v) } }
            Toggle("Show hand skeleton glow", s.showSkeleton) { v -> set { it.copy(showSkeleton = v) } }
            Hint("Raised hand: 1–4 fingers = number of voices (open palm = full chord) · raise/lower = voicing above/below · fist = mute. Other hand: spread thumb and index for more reverb. A low fist holding the mic is ignored.")

            Section("Microphone & live monitoring")
            val r = vm.route
            Text(
                "In: ${r?.inputName ?: "—"}   ·   Out: ${r?.outputName ?: "—"}",
                color = Hc.Text, fontSize = 14.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Input level", color = Hc.TextDim, fontSize = 13.sp, modifier = Modifier.width(96.dp))
                InputMeter(vm.meters.inputPeak, width = 200)
            }
            if (r?.inputIsUsb == true) {
                Spacer(Modifier.height(10.dp))
                Text("Interface input", color = Hc.TextDim, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Input 1 (XLR mic)", s.inputChannel == 0) { set { it.copy(inputChannel = 0) } }
                    Chip("Input 2", s.inputChannel == 1) { set { it.copy(inputChannel = 1) } }
                    Chip("Mix", s.inputChannel == 2) { set { it.copy(inputChannel = 2) } }
                }
            }
            LabeledSlider("Input boost (+${s.inputGainDb.toInt()} dB)", s.inputGainDb / 24f) { v -> set { it.copy(inputGainDb = (v * 24f).let { d -> Math.round(d).toFloat() }) } }
            LabeledSlider("Monitor volume", s.monitorLevel) { v -> set { it.copy(monitorLevel = v) } }
            Toggle("Allow monitoring on phone speaker (feedback risk)", s.speakerMonitor) { v -> set { it.copy(speakerMonitor = v) } }
            Hint(
                "USB interface (e.g. Behringer UM2): plug it in before or after opening the app — mic and headphone monitoring switch to it automatically. " +
                    "Use the interface's gain knob so the meter peaks in amber, never red; use Input boost only if the knob is maxed. " +
                    "For condenser mics turn on 48V phantom power. If the interface disconnects or the mic is silent, the phone may not supply enough power for phantom — use a powered USB-C hub/OTG adapter. " +
                    "If your interface has a direct-monitor switch, turn it off so you hear only the harmonised mix. " +
                    when (r?.output) {
                        AudioRouting.OutputKind.BLUETOOTH -> "Bluetooth adds noticeable delay — wired or USB headphones are much better for singing."
                        else -> ""
                    }
            )

            Section("Karaoke backing track")
            Text(s.trackName ?: "No track loaded", color = Hc.Text, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { onPickTrack(); onDismiss() }) { Text(if (s.trackName == null) "Choose MP3 / audio file" else "Replace track") }
            LabeledSlider("Track volume", s.trackLevel) { v -> set { it.copy(trackLevel = v) } }
            LabeledSlider("Track sync nudge (${if (s.trackNudgeMs > 0) "+" else ""}${s.trackNudgeMs} ms)", (s.trackNudgeMs + 150) / 300f, left = "earlier", right = "later") { v ->
                set { it.copy(trackNudgeMs = (Math.round(v * 300f) - 150) / 5 * 5) }
            }
            Toggle("Set the harmony key from the track", s.trackSetsKey) { v -> set { it.copy(trackSetsKey = v) } }
            Hint("The track plays in your headphones while you sing and restarts with every take. Delay between headphones and mic is measured automatically; use the nudge only if the saved video sounds slightly early or late.")

            Section("Visuals")
            Toggle("Hand tracking overlay (green skeleton, red joints)", s.handOverlay) { v -> set { it.copy(handOverlay = v) } }
            Toggle("Glowing orb & sparkles at the thumb", s.orb) { v -> set { it.copy(orb = v) } }
            Hint("The overlay is drawn into the recorded video too. The camera image itself is never stretched or filtered.")
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(18.dp))
    Text(title.uppercase(), color = Hc.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Hint(text: String) {
    if (text.isBlank()) return
    Spacer(Modifier.height(6.dp))
    Text(text, color = Hc.TextDim, fontSize = 12.sp, lineHeight = 17.sp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, enabled = enabled, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Hc.Accent, selectedLabelColor = Color.Black)
    )
}

@Composable
private fun LabeledSlider(label: String, value: Float, left: String? = null, right: String? = null, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row {
            Text(label, color = Hc.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (left == null) Text("${(value * 100).toInt()}%", color = Hc.TextDim, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (left != null) Text(left, color = Hc.TextDim, fontSize = 11.sp)
            Slider(value = value.coerceIn(0f, 1f), onValueChange = onChange, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            if (right != null) Text(right, color = Hc.TextDim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Hc.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ─────────────────────────────── Lyrics ───────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSheet(vm: StudioViewModel, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editing by remember { mutableStateOf<Song?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Color(0xFF12121B)) {
        val e = editing
        if (e != null) {
            SongEditor(e, onCancel = { editing = null }) { saved ->
                vm.saveSong(saved)
                vm.selectSong(saved)
                editing = null
                onDismiss()
            }
        } else {
            SongList(vm, onNew = { editing = Song(LyricsStore.newId(), "", "") }, onEdit = { editing = it }, onPicked = onDismiss)
        }
    }
}

@Composable
private fun SongList(vm: StudioViewModel, onNew: () -> Unit, onEdit: (Song) -> Unit, onPicked: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lyrics", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = Hc.Text, modifier = Modifier.weight(1f))
            Button(onClick = onNew) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(4.dp))
                Text("New song")
            }
        }
        Text("Shown only on your screen while you sing — never in the recorded video.", color = Hc.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        if (vm.songs.isEmpty()) {
            Text("No songs yet. Tap “New song”, paste your lyrics from the clipboard and save.", color = Hc.TextDim, modifier = Modifier.padding(vertical = 24.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().height(420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.songs, key = { it.id }) { song ->
                val active = vm.activeSong?.id == song.id
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (active) Color(0x3322E6FF) else Color(0x14FFFFFF))
                        .clickable { vm.selectSong(if (active) null else song); onPicked() }
                        .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (active) { Icon(Icons.Rounded.CheckCircle, null, tint = Hc.Accent); Spacer(Modifier.width(10.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(song.title.ifBlank { "Untitled" }, color = Hc.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.lines.firstOrNull() ?: "", color = Hc.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { onEdit(song) }) { Icon(Icons.Rounded.Edit, "Edit", tint = Hc.TextDim) }
                    IconButton(onClick = { vm.deleteSong(song.id) }) { Icon(Icons.Rounded.Delete, "Delete", tint = Hc.TextDim) }
                }
            }
        }
        if (vm.activeSong != null) {
            TextButton(onClick = { vm.selectSong(null); onPicked() }) { Text("Hide lyrics prompter") }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SongEditor(song: Song, onCancel: () -> Unit, onSave: (Song) -> Unit) {
    var title by remember { mutableStateOf(song.title) }
    var text by remember { mutableStateOf(song.lyrics) }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
        Text(if (song.lyrics.isEmpty()) "New song" else "Edit song", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = Hc.Text)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = {
            val clip = clipboard.getText()?.text.orEmpty()
            if (clip.isNotBlank()) text = if (text.isBlank()) clip.trim() else text.trimEnd() + "\n" + clip.trim()
        }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.ContentPaste, null)
            Spacer(Modifier.width(8.dp))
            Text("Paste lyrics from clipboard")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it }, label = { Text("Lyrics — one line per sung phrase") },
            minLines = 8, maxLines = 14, modifier = Modifier.fillMaxWidth()
        )
        Text("Tip: put each sung phrase on its own line; blank lines are ignored.", color = Hc.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(
                onClick = { onSave(song.copy(title = title.trim().ifBlank { text.lines().firstOrNull { it.isNotBlank() }?.take(40) ?: "Untitled" }, lyrics = text)) },
                enabled = text.isNotBlank(), modifier = Modifier.weight(1f)
            ) { Text("Save & use") }
        }
        Spacer(Modifier.height(20.dp))
    }
}


// ─────────────────────────────── Sound FX ───────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FxSheet(vm: StudioViewModel, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Color(0xFF12121B)) {
        val s = vm.settings
        val set: ((StudioSettings) -> StudioSettings) -> Unit = { vm.updateSettings(it) }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Sound FX", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = Hc.Text)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (s.effectsOff) Color(0x33FFC94A) else Color(0x14FFFFFF)).padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("All effects off", color = Hc.Text, fontWeight = FontWeight.SemiBold)
                    Text("Plain recording: just your mic (and the karaoke track). No harmonies, tuning, reverb or compression.", color = Hc.TextDim, fontSize = 12.sp)
                }
                Switch(checked = s.effectsOff, onCheckedChange = { v -> set { it.copy(effectsOff = v) } })
            }
            val enabled = !s.effectsOff
            Column(Modifier.fillMaxWidth().then(if (enabled) Modifier else Modifier.background(Color.Transparent))) {
                Section("Harmonies")
                Toggle("Harmonies on", s.harmoniesOn && enabled) { v -> if (enabled) set { it.copy(harmoniesOn = v) } }
                LabeledSlider("Harmony level", s.harmonyLevel) { v -> set { it.copy(harmonyLevel = v) } }

                Section("Pitch correction")
                LabeledSlider("Amount", s.correction) { v -> set { it.copy(correction = v) } }
                LabeledSlider("Style", 1f - s.retune, left = "natural", right = "hard / robotic") { v -> set { it.copy(retune = 1f - v) } }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Off", s.correction == 0f) { set { it.copy(correction = 0f) } }
                    Chip("Subtle", s.correction in 0.01f..0.6f && s.retune >= 0.5f) { set { it.copy(correction = 0.5f, retune = 0.75f) } }
                    Chip("Studio", s.correction > 0.6f && s.retune in 0.25f..0.5f) { set { it.copy(correction = 0.9f, retune = 0.4f) } }
                    Chip("Robot", s.correction > 0.6f && s.retune < 0.1f) { set { it.copy(correction = 1f, retune = 0f) } }
                }
                Toggle("Hear tuning live in headphones (+20 ms)", s.monitorTuning) { v -> set { it.copy(monitorTuning = v) } }
                Hint("Pulls your lead vocal onto the notes of the key (${Notes.key(s.keyRoot, s.minor)}). Natural keeps your vibrato; hard gives the classic auto-tune sound. With live tuning off you hear your own voice with zero delay — the tuning is still applied to the saved video.")

                Section("Space & texture")
                LabeledSlider("Reverb", s.reverb) { v -> set { it.copy(reverb = v) } }
                LabeledSlider("Vocal doubler", s.doubler) { v -> set { it.copy(doubler = v) } }
                LabeledSlider("Echo", s.echo) { v -> set { it.copy(echo = v) } }
                Hint("Your left-hand pinch adds extra reverb on top of these while gestures are on.")
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

// ─────────────────────────────── Takes ───────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TakesSheet(vm: StudioViewModel, onPlay: (com.harmonycam.app.record.Take) -> Unit, onShare: (com.harmonycam.app.record.Take) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Color(0xFF12121B)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Takes", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = Hc.Text)
            Text("Every take is kept here. Save the ones you like to the Gallery; delete the rest.", color = Hc.TextDim, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            if (vm.takes.isEmpty()) Text("No takes yet — hit record.", color = Hc.TextDim, modifier = Modifier.padding(vertical = 24.dp))
            LazyColumn(Modifier.fillMaxWidth().height(460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.takes, key = { it.file.name }) { take ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x14FFFFFF))
                            .clickable { onPlay(take) }.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Take ${take.number}", color = Hc.Text, fontWeight = FontWeight.SemiBold)
                            val d = take.durationSec
                            Text(
                                "%d:%02d".format(d / 60, d % 60) + " · " +
                                    java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(take.createdMs)) +
                                    if (take.saved) " · in Gallery" else "",
                                color = Hc.TextDim, fontSize = 12.sp
                            )
                        }
                        IconButton(onClick = { onPlay(take) }) { Icon(androidx.compose.material.icons.Icons.Rounded.PlayArrow, "Play", tint = Hc.Text) }
                        IconButton(onClick = { vm.saveTake(take) }, enabled = !take.saved) {
                            Icon(androidx.compose.material.icons.Icons.Rounded.SaveAlt, "Save to Gallery", tint = if (take.saved) Hc.Ok else Hc.Text)
                        }
                        IconButton(onClick = { onShare(take) }) { Icon(androidx.compose.material.icons.Icons.Rounded.Share, "Share", tint = Hc.Text) }
                        IconButton(onClick = { vm.deleteTake(take) }) { Icon(Icons.Rounded.Delete, "Delete", tint = Hc.TextDim) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
