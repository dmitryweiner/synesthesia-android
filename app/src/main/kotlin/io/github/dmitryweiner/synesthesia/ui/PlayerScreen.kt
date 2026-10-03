package io.github.dmitryweiner.synesthesia.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dmitryweiner.synesthesia.audio.OutputStats
import io.github.dmitryweiner.synesthesia.core.AudioFrame
import io.github.dmitryweiner.synesthesia.core.coreVersion
import io.github.dmitryweiner.synesthesia.playback.PlaybackController

/**
 * Phase 1 (PLAN.md): play a built-in point, keep it playing with the screen
 * off, and see what the sound is doing — the features the picture will read
 * and what the output costs.
 */
@Composable
fun PlayerScreen(controller: PlaybackController, onPlay: () -> Unit, onBench: () -> Unit, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsStateWithLifecycle()
    var frame by remember { mutableStateOf<AudioFrame?>(null) }
    var stats by remember { mutableStateOf<OutputStats?>(null) }

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
                Text("Synesthesia", style = MaterialTheme.typography.headlineSmall)
                Text("core ${remember { coreVersion() }}", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onBench, enabled = !state.playing) { Text("Bench") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { if (state.holdsForeground) controller.stop() else onPlay() },
                modifier = Modifier.testTag("play"),
            ) {
                Text(if (state.holdsForeground) "⏹ Stop" else "▶ Play")
            }
            Column {
                Text(state.pointName, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        state.pausedForFocus -> "paused while another app plays"
                        state.playing -> "playing"
                        else -> "stopped"
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Meters(frame, Modifier.fillMaxWidth())
        StatsLine(stats)
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(controller.presets, key = { _, p -> p.index.toInt() }) { i, p ->
                val selected = i == state.presetIndex
                ListItem(
                    headlineContent = { Text(p.name) },
                    leadingContent = { Text("$i") },
                    colors = if (selected) {
                        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    } else {
                        ListItemDefaults.colors()
                    },
                    modifier = Modifier.clickable { controller.select(i) },
                )
            }
        }
    }
}
