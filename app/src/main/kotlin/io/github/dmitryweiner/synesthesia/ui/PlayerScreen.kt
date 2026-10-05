package io.github.dmitryweiner.synesthesia.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import androidx.annotation.StringRes
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

    @get:StringRes
    val label: Int
        get() = when (this) {
            Picture -> R.string.view_picture
            Spectrum -> R.string.view_spectrum
            Full -> R.string.view_full
        }
}

/** What was written to the store last time, or the picture. */
private fun String?.toViewMode(): ViewMode =
    ViewMode.entries.firstOrNull { it.name == this } ?: ViewMode.Picture

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
    // Which view was on last time: the app opens where it was left, as it
    // opens on the point it was left on.
    var view by rememberSaveable { mutableStateOf(controller.files.view().toViewMode()) }
    // What full screen goes back to, so Close returns where it came from.
    var beforeFull by rememberSaveable { mutableStateOf(view.takeUnless { it == ViewMode.Full } ?: ViewMode.Picture) }
    // Shown once, on the first run (PLAN.md phase 6).
    var welcome by rememberSaveable { mutableStateOf(!controller.files.welcomeShown()) }
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

    // Read where a composable may read them, said where the press is handled.
    val showingPicture = stringResource(R.string.showing_picture)
    val showingSpectrum = stringResource(R.string.showing_spectrum)

    val show = { next: ViewMode ->
        view = next
        controller.files.saveView(next.name)
    }
    val swap = {
        show(view.other())
        said = if (view == ViewMode.Spectrum) showingSpectrum else showingPicture
    }
    val enterFull = {
        beforeFull = view
        show(ViewMode.Full)
        said = null
    }
    val leaveFull = {
        show(beforeFull)
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
                    Text(stringResource(if (state.holdsForeground) R.string.stop else R.string.play))
                }
                Text(
                    stringResource(
                        when {
                            state.pausedForFocus -> R.string.state_paused
                            state.playing -> R.string.state_playing
                            else -> R.string.state_stopped
                        },
                    ),
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
                    Text(stringResource(view.other().label))
                }
                val full = stringResource(R.string.full_screen)
                TextButton(
                    onClick = enterFull,
                    modifier = Modifier
                        .semantics { contentDescription = full }
                        .testTag("fullScreen"),
                ) {
                    // A glyph at the body size is a smudge; this one is a button.
                    Text("⤢", style = MaterialTheme.typography.headlineSmall)
                }
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
    if (welcome) {
        // The first run: what the app is and, first of all, that ▶ Play is
        // what starts the sound — the one thing people were not finding.
        HelpDialog(
            onDismiss = {
                welcome = false
                controller.files.rememberWelcomeShown()
            },
            welcome = true,
        )
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
            // A tablet has the room for it; a phone has not.
            val wide = LocalConfiguration.current.screenWidthDp >= 600
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(if (wide) 72.dp else 40.dp).testTag("logo"),
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onPoints, modifier = Modifier.testTag("points")) { Text(stringResource(R.string.points)) }
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
    val uriHandler = LocalUriHandler.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val copied = stringResource(R.string.copied_token)
    val empty = stringResource(R.string.clipboard_empty)
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("more")) { Text("⋮") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_details)) },
                onClick = {
                    onDetails()
                    open = false
                },
                modifier = Modifier.testTag("openDetails"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_help)) },
                onClick = {
                    onHelp()
                    open = false
                },
                modifier = Modifier.testTag("openHelp"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_copy)) },
                onClick = {
                    clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, "#s=${controller.token()}"))
                    onSaid(copied)
                    open = false
                },
                modifier = Modifier.testTag("copyToken"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_paste)) },
                onClick = {
                    val pasted = clipboard.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.coerceToText(context)
                        ?.toString()
                    onSaid(
                        if (pasted.isNullOrBlank()) {
                            empty
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
                text = { Text(stringResource(R.string.menu_version, version)) },
                onClick = {
                    // Where the build came from, and where to report what it
                    // does: the repository it was made in.
                    uriHandler.openUri(REPOSITORY)
                    open = false
                },
                modifier = Modifier.testTag("version"),
            )
        }
    }
}

private const val CLIP_LABEL = "Synesthesia point"

/** Where the build came from, and where anything about it is reported. */
internal const val REPOSITORY = "https://github.com/dmitryweiner/synesthesia-android/"

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
    // Full screen means the system's bars as well: they come back on a swipe
    // from the edge, and go again when the page does.
    HideSystemBars()
    Box(Modifier.fillMaxSize()) {
        Picture(controller, Modifier.fillMaxSize(), running = !state.settingsOpen)
        TextButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp).testTag("closeFull"),
        ) { Text(stringResource(R.string.close)) }
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

/** Hides the status and navigation bars while this is in the composition. */
@Composable
private fun HideSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
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
            view.status.substringBefore('\n').ifEmpty { stringResource(R.string.status_hint) },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("status"),
        )
        val doing = when {
            view.scoutBusy -> stringResource(R.string.scouting, scoutThreads)
            view.scoutedLike + view.scoutedDislike > 0u ->
                stringResource(R.string.scouted, view.scoutedLike.toInt(), view.scoutedDislike.toInt())
            else -> stringResource(R.string.spread, "%.2f".format(view.sigma))
        }
        val left = if (view.canUndo) stringResource(R.string.undo_left, view.undoDepth.toInt()) else ""
        Text(
            doing + left,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
