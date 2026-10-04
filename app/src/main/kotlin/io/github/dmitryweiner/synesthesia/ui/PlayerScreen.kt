package io.github.dmitryweiner.synesthesia.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dmitryweiner.synesthesia.audio.OutputStats
import io.github.dmitryweiner.synesthesia.core.AudioFrame
import io.github.dmitryweiner.synesthesia.core.SessionView
import io.github.dmitryweiner.synesthesia.core.coreVersion
import io.github.dmitryweiner.synesthesia.playback.PlaybackController

/**
 * The player (PLAN.md phases 1–4): the picture, the point's name with its
 * step count, ▶, 💾, the points sheet, and 👍 👎 🎲 ↩ with the status line
 * that says what the last press did. A finger on the picture paints into it.
 */
@Composable
fun PlayerScreen(
    controller: PlaybackController,
    onPlay: () -> Unit,
    onBench: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    var frame by remember { mutableStateOf<AudioFrame?>(null) }
    var stats by remember { mutableStateOf<OutputStats?>(null) }
    var pointsOpen by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }

    // The meters follow the *played* sound, once per screen frame.
    LaunchedEffect(state.playing) {
        if (!state.playing) {
            frame = null
            stats = null
            return@LaunchedEffect
        }
        while (true) {
            withFrameMillis { }
            frame = controller.frameNow()
            stats = controller.stats()
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    state.session.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("core ${remember { coreVersion() }}", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onSettings, modifier = Modifier.testTag("openSettings")) { Text("⚙") }
            TextButton(onClick = { naming = true }, modifier = Modifier.testTag("keepPoint")) { Text("💾") }
            TextButton(onClick = { pointsOpen = true }, modifier = Modifier.testTag("openPoints")) { Text("Points") }
            TextButton(onClick = onBench, enabled = !state.playing) { Text("Bench") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { if (state.holdsForeground) controller.stop() else onPlay() },
                modifier = Modifier.testTag("play"),
            ) {
                Text(if (state.holdsForeground) "⏹ Stop" else "▶ Play")
            }
            Text(
                when {
                    state.pausedForFocus -> "paused while another app plays"
                    state.playing -> "playing"
                    else -> "stopped"
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        said?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Picture(controller, Modifier.weight(1f).fillMaxWidth(), running = !state.settingsOpen)
        Meters(frame, Modifier.fillMaxWidth())
        StatsLine(stats)
        SearchBar(controller, state.session)
        StatusLine(state.session, remember { controller.scoutThreads() })
        TokenRow(controller, onSaid = { said = it }, Modifier.padding(bottom = 8.dp))
    }

    if (pointsOpen) {
        PointsSheet(controller, state.session, onDismiss = { pointsOpen = false })
    }
    if (naming) {
        KeepDialog(controller, onDismiss = { naming = false })
    }
}

/**
 * The four presses the whole app is about. They work whether or not the sound
 * is playing: the session morphs either way, and ▶ starts on the point the
 * presses have reached.
 */
@Composable
private fun SearchBar(controller: PlaybackController, view: SessionView) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Press("👎", "dislike", Modifier.weight(1f)) { controller.dislike() }
        Press("👍", "like", Modifier.weight(1f)) { controller.like() }
        Press("🎲", "surprise", Modifier.weight(1f)) { controller.surprise() }
        Press(
            if (view.canUndo) "↩ ${view.undoDepth}" else "↩",
            "undo",
            Modifier.weight(1f),
            enabled = view.canUndo,
        ) { controller.undo() }
    }
}

@Composable
private fun Press(label: String, tag: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.testTag(tag)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * What the last press did and what it changed (the core's two lines), and
 * what the scout is up to — a phone that is rendering candidates is a phone
 * that is warm, so it says so.
 */
@Composable
private fun StatusLine(view: SessionView, scoutThreads: Int) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            view.status.ifEmpty { "👍 when you like where it is going, 👎 when you don't" },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("status"),
        )
        Text(
            when {
                view.scoutBusy -> "scouting on $scoutThreads threads…"
                view.scoutedLike + view.scoutedDislike > 0u ->
                    "ready: ${view.scoutedLike} 👍 · ${view.scoutedDislike} 👎 · spread %.2f".format(view.sigma)
                else -> "spread %.2f".format(view.sigma)
            },
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
