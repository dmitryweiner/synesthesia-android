package io.github.dmitryweiner.synesthesia.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dmitryweiner.synesthesia.BuildConfig
import io.github.dmitryweiner.synesthesia.R
import io.github.dmitryweiner.synesthesia.audio.OutputStats
import io.github.dmitryweiner.synesthesia.core.AudioFrame
import io.github.dmitryweiner.synesthesia.core.SessionView
import io.github.dmitryweiner.synesthesia.core.coreVersion
import io.github.dmitryweiner.synesthesia.playback.PlaybackController

/**
 * What fills the screen — the console's three (`VizMode` in
 * synesthesia-rust), so the two apps feel the same: the picture, the spectrum
 * in its place, or the picture over everything. The spectrum and the picture
 * share the space because together they fight: the bars read as part of the
 * image.
 *
 * The console cycles all three with one key, which is a terminal's way. Here
 * the switch sits under the block it changes and swaps the two that share it,
 * and full screen is its own button and its own way out — one control, one
 * thing, and all three reachable from any of them.
 */
enum class ViewMode {
    Picture,
    Spectrum,
    Full,
    ;

    /** The other of the two that share the block. */
    fun other(): ViewMode = if (this == Spectrum) Picture else Spectrum

    val label: String
        get() = when (this) {
            Picture -> "Picture"
            Spectrum -> "Spectrum"
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
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    var frame by remember { mutableStateOf<AudioFrame?>(null) }
    var stats by remember { mutableStateOf<OutputStats?>(null) }
    var view by rememberSaveable { mutableStateOf(ViewMode.Picture) }
    // What full screen goes back to, so Close returns where it came from.
    var beforeFull by rememberSaveable { mutableStateOf(ViewMode.Picture) }
    var pointsOpen by rememberSaveable { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
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

    val swap = {
        view = view.other()
        said = "Showing the ${view.label.lowercase()}"
    }
    val enterFull = {
        beforeFull = view
        view = ViewMode.Full
        said = null
    }
    val leaveFull = {
        view = beforeFull
        said = null
    }

    if (view == ViewMode.Full) {
        FullPicture(controller, state, onClose = leaveFull)
    } else {
        Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Header(
                state = state,
                onPoints = { pointsOpen = true },
                onSettings = onSettings,
                onKeep = { naming = true },
                onDetails = { details = true },
                onHelp = { help = true },
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
            // The switch belongs to the block above it, not to the header.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { StatsLine(stats) }
                TextButton(onClick = swap, modifier = Modifier.testTag("viewMode")) {
                    Text(view.other().label)
                }
                TextButton(onClick = enterFull, modifier = Modifier.testTag("fullScreen")) { Text("⤢") }
            }
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
    if (details) {
        DetailsSheet(controller, state.session, onDismiss = { details = false })
    }
    if (help) {
        HelpDialog(onDismiss = { help = false })
    }
}

/**
 * The controls on one line and the point's name on its own, under them.
 *
 * The name shared the line at first and lost: it is the longest thing on the
 * screen and the buttons are the widest, so "Candle glaze · 3 steps" came out
 * as "Cand…" even on a large phone. It has the full width now, and the
 * app's own mark stands where the version number used to — a build number is
 * in the APK and in the release it came from, which is where it is read.
 */
@Composable
private fun Header(
    state: PlaybackController.State,
    onPoints: () -> Unit,
    onSettings: () -> Unit,
    onKeep: () -> Unit,
    onDetails: () -> Unit,
    onHelp: () -> Unit,
    onSaid: (String) -> Unit,
    controller: PlaybackController,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(40.dp).testTag("logo"),
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onPoints, modifier = Modifier.testTag("points")) { Text("Points") }
            TextButton(onClick = onSettings, modifier = Modifier.testTag("openSettings")) { Text("⚙") }
            TextButton(onClick = onKeep, modifier = Modifier.testTag("keepPoint")) { Text("💾") }
            MoreMenu(controller, onDetails = onDetails, onHelp = onHelp, onSaid = onSaid)
        }
        Text(
            state.session.name,
            Modifier.fillMaxWidth().clickable(onClick = onPoints).testTag("openPoints"),
            // A fifth smaller than a title: it is the longest line on the
            // screen and it is read, not announced.
            style = MaterialTheme.typography.titleLarge.let { it.copy(fontSize = it.fontSize * 0.8f) },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * What is wanted rarely: what the point is made of, what the app is, and the
 * tokens a point travels as (PLAN.md decision 8).
 */
@Composable
private fun MoreMenu(
    controller: PlaybackController,
    onDetails: () -> Unit,
    onHelp: () -> Unit,
    onSaid: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("more")) { Text("⋮") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("What is this point?") },
                onClick = {
                    onDetails()
                    open = false
                },
                modifier = Modifier.testTag("openDetails"),
            )
            DropdownMenuItem(
                text = { Text("How this works") },
                onClick = {
                    onHelp()
                    open = false
                },
                modifier = Modifier.testTag("openHelp"),
            )
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
            // Which build this is, and which model it runs — the two numbers
            // a report about anything needs. Tapping copies them, so they can
            // be pasted rather than transcribed.
            val version = "${BuildConfig.VERSION_NAME} · core ${remember { coreVersion() }}"
            DropdownMenuItem(
                text = { Text("Version $version") },
                onClick = {
                    clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, version))
                    onSaid("Copied: $version")
                    open = false
                },
                modifier = Modifier.testTag("version"),
            )
        }
    }
}

private const val CLIP_LABEL = "Synesthesia point"

/**
 * The picture over everything, with the presses still at hand.
 *
 * The picture itself runs edge to edge — under the status bar and the
 * navigation bar, which is the point of full screen — but nothing one has to
 * read or press does: the controls keep out of the system bars' way, where
 * they were being cut in half.
 */
@Composable
private fun FullPicture(controller: PlaybackController, state: PlaybackController.State, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Picture(controller, Modifier.fillMaxSize(), running = !state.settingsOpen)
        TextButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp).testTag("closeFull"),
        ) { Text("✕ Close") }
        Column(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                .safeDrawingPadding()
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
 * What the last press did, how many steps ↩ can still take back, and what the
 * scout is up to — a phone that is rendering candidates is a phone that is
 * warm, so it says so.
 *
 * Two lines, never one and never three: the core's status is two lines (what
 * happened, and which genes moved), and letting it grow pushed the picture up
 * and down on every press. Only the first line is shown, clipped rather than
 * wrapped; what changed belongs on a page of its own.
 */
@Composable
private fun StatusLine(view: SessionView, scoutThreads: Int) {
    Column(Modifier.fillMaxWidth().height(44.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            view.status.substringBefore('\n').ifEmpty { "👍 when you like where it is going, 👎 when you don't" },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
