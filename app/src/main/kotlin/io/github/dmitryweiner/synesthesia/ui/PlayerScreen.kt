package io.github.dmitryweiner.synesthesia.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dmitryweiner.synesthesia.BuildConfig
import io.github.dmitryweiner.synesthesia.audio.OutputStats
import io.github.dmitryweiner.synesthesia.core.AudioFrame
import io.github.dmitryweiner.synesthesia.core.SessionView
import io.github.dmitryweiner.synesthesia.core.coreVersion
import io.github.dmitryweiner.synesthesia.playback.PlaybackController

/**
 * What fills the screen, cycled by one button — the console's three modes in
 * the same order (`VizMode` in synesthesia-rust), so the two apps feel the
 * same: the spectrum, the picture in its place, or the picture over
 * everything. The spectrum and the picture share the space because together
 * they fight: the bars read as part of the image.
 */
enum class ViewMode {
    Spectrum,
    Picture,
    Full,
    ;

    fun next(): ViewMode = entries[(ordinal + 1) % entries.size]

    /** What the button says it is showing now. */
    val label: String
        get() = when (this) {
            Spectrum -> "Spectrum"
            Picture -> "Picture"
            Full -> "Full"
        }
}

/**
 * The player (PLAN.md phases 1–5): the picture or the spectrum, the point's
 * name with its step count, ▶, 👍 👎 🎲 ↩ and the status line that says what
 * the last press did. A finger on the picture paints into it.
 *
 * The name is the way into the points; the header's ⋮ holds what is needed
 * rarely — the tokens a point travels as, and the bench.
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
    var view by rememberSaveable { mutableStateOf(ViewMode.Picture) }
    var pointsOpen by rememberSaveable { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }

    // The meters follow the *played* sound, once per screen frame — and only
    // while they are on screen.
    val metersShown = state.playing && view != ViewMode.Full
    LaunchedEffect(metersShown) {
        if (!metersShown) {
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

    val cycle = {
        view = view.next()
        said = "Showing the ${view.label.lowercase()}"
    }

    if (view == ViewMode.Full) {
        FullPicture(controller, state, onLeave = cycle)
    } else {
        Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Header(
                state = state,
                view = view,
                onPoints = { pointsOpen = true },
                onCycleView = cycle,
                onSettings = onSettings,
                onKeep = { naming = true },
                onBench = onBench,
                onSaid = { said = it },
                controller = controller,
            )
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
            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            said?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            // The two share the space, one at a time.
            if (view == ViewMode.Picture) {
                Picture(controller, Modifier.weight(1f).fillMaxWidth(), running = !state.settingsOpen)
            } else {
                Spectrogram(frame, Modifier.weight(1f).fillMaxWidth())
            }
            StatsLine(stats)
            SearchBar(controller, state.session)
            StatusLine(state.session, remember { controller.scoutThreads() })
        }
    }

    if (pointsOpen) {
        PointsSheet(controller, state.session, onDismiss = { pointsOpen = false })
    }
    if (naming) {
        KeepDialog(controller, onDismiss = { naming = false })
    }
}

/**
 * The point's name is the way into the points, as it is in the web app; what
 * is used often sits next to it, and ⋮ holds the rest.
 */
@Composable
private fun Header(
    state: PlaybackController.State,
    view: ViewMode,
    onPoints: () -> Unit,
    onCycleView: () -> Unit,
    onSettings: () -> Unit,
    onKeep: () -> Unit,
    onBench: () -> Unit,
    onSaid: (String) -> Unit,
    controller: PlaybackController,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).clickable(onClick = onPoints).testTag("openPoints").padding(vertical = 4.dp),
        ) {
            Text(
                state.session.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${BuildConfig.VERSION_NAME} · core ${remember { coreVersion() }} · tap for points",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        TextButton(onClick = onCycleView, modifier = Modifier.testTag("viewMode")) { Text(view.label) }
        TextButton(onClick = onSettings, modifier = Modifier.testTag("openSettings")) { Text("⚙") }
        TextButton(onClick = onKeep, modifier = Modifier.testTag("keepPoint")) { Text("💾") }
        MoreMenu(controller, playing = state.playing, onBench = onBench, onSaid = onSaid)
    }
}

/**
 * What is wanted rarely: the tokens a point travels as (PLAN.md decision 8)
 * and the bench. The bench renders every point offline as fast as it can, so
 * it waits for the sound to stop rather than fighting it for the cores — the
 * menu says so instead of leaving a grey button on the screen.
 */
@Composable
private fun MoreMenu(
    controller: PlaybackController,
    playing: Boolean,
    onBench: () -> Unit,
    onSaid: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("more")) { Text("⋮") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Copy this point as a token") },
                onClick = {
                    clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, "#s=${controller.token()}"))
                    onSaid("The point is on the clipboard, as a #s= token")
                    open = false
                },
                modifier = Modifier.testTag("copyToken"),
            )
            DropdownMenuItem(
                text = { Text("Open a token from the clipboard") },
                onClick = {
                    val pasted = clipboard.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.coerceToText(context)
                        ?.toString()
                    onSaid(
                        if (pasted.isNullOrBlank()) {
                            "Nothing on the clipboard"
                        } else {
                            controller.importToken(pasted)
                        },
                    )
                    open = false
                },
                modifier = Modifier.testTag("pasteToken"),
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Bench: how fast each point renders")
                        if (playing) {
                            Text(
                                "stop the sound first — it renders flat out",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
                enabled = !playing,
                onClick = {
                    onBench()
                    open = false
                },
                modifier = Modifier.testTag("openBench"),
            )
        }
    }
}

private const val CLIP_LABEL = "Synesthesia point"

/** The picture over everything, with the presses still at hand. */
@Composable
private fun FullPicture(controller: PlaybackController, state: PlaybackController.State, onLeave: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Picture(controller, Modifier.fillMaxSize(), running = !state.settingsOpen)
        TextButton(
            onClick = onLeave,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("viewMode"),
        ) { Text(ViewMode.Full.label) }
        Column(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SearchBar(controller, state.session)
            StatusLine(state.session, remember { controller.scoutThreads() })
        }
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
        // How many steps are left to take back is in the status line below;
        // on the button it only has room to break the label in half.
        Press("↩", "undo", Modifier.weight(1f), enabled = view.canUndo) { controller.undo() }
    }
}

@Composable
private fun Press(label: String, tag: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.testTag(tag),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
    }
}

/**
 * What the last press did and what it changed (the core's two lines), how
 * many steps ↩ can still take back, and what the scout is up to — a phone
 * that is rendering candidates is a phone that is warm, so it says so.
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
            buildString {
                when {
                    view.scoutBusy -> append("scouting on $scoutThreads threads")
                    view.scoutedLike + view.scoutedDislike > 0u ->
                        append("ready: ${view.scoutedLike} 👍 · ${view.scoutedDislike} 👎")
                    else -> append("spread %.2f".format(view.sigma))
                }
                if (view.canUndo) append(" · ↩ ${view.undoDepth}")
            },
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
