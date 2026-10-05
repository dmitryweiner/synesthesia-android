package io.github.dmitryweiner.synesthesia.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dmitryweiner.synesthesia.R
import io.github.dmitryweiner.synesthesia.core.ControlKind
import io.github.dmitryweiner.synesthesia.core.PointEdit
import io.github.dmitryweiner.synesthesia.core.SessionView
import io.github.dmitryweiner.synesthesia.core.settingsPage
import io.github.dmitryweiner.synesthesia.playback.PlaybackController
import kotlin.math.roundToInt

/**
 * What this point is, and what the last press did to it (PLAN.md phase 6).
 *
 * The main screen says what happened in one line, because a line that grows
 * pushes the picture about; the rest is here, where it can be read: the
 * second half of the status — which genes moved — and the point itself, part
 * by part.
 *
 * Nothing is written out: the parts are the sections the core derives from
 * the schema (the same ones ⚙ Settings shows), and only what is switched on
 * is listed.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class) // ModalBottomSheet
@Composable
fun DetailsSheet(controller: PlaybackController, view: SessionView, onDismiss: () -> Unit) {
    val on = stringResource(R.string.on)
    val off = stringResource(R.string.off)
    val lines = remember(view) { describe(controller, on, off) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("details")) {
        LazyColumn(Modifier.heightIn(max = 560.dp).padding(horizontal = 16.dp).testTag("detailsList")) {
            item {
                Text(view.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.details_step, view.steps.toInt(), view.sigma, view.undoDepth.toInt()),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (view.status.isNotEmpty()) {
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(stringResource(R.string.details_last_press), style = MaterialTheme.typography.titleSmall)
                    // Both lines of the core's status: what happened, and
                    // which genes moved and which way.
                    Text(view.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("changed"))
                }
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.details_switched_on), style = MaterialTheme.typography.titleSmall)
            }
            items(lines, key = { it.first }) { (title, body) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(title, style = MaterialTheme.typography.labelLarge)
                    Text(body, style = MaterialTheme.typography.bodySmall)
                }
            }
            item { Text("", Modifier.padding(bottom = 24.dp)) }
        }
    }
}

/**
 * The point, section by section, from the page the core generates: a section
 * with a switch appears only while it is on, and each of its controls reads
 * as the schema labels it.
 */
private fun describe(controller: PlaybackController, on: String, off: String): List<Pair<String, String>> {
    val point = PointEdit(controller.pointJson())
    return point.use { edit ->
        settingsPage().mapNotNull { section ->
            val toggle = section.toggle
            if (toggle != null && edit.value(toggle.id) < 0.5) return@mapNotNull null
            val body = section.controls.joinToString(" · ") { control ->
                val v = edit.value(control.id)
                val shown = when (control.kind) {
                    ControlKind.CHOICE -> control.options.getOrElse(v.roundToInt()) { "${v.roundToInt()}" }
                    ControlKind.SWITCH -> if (v >= 0.5) on else off
                    ControlKind.SLIDER -> format(v)
                }
                "${control.shortLabel} $shown"
            }
            section.title to body.ifEmpty { on }
        }
    }
}

/**
 * What the app is and what the buttons do — shown from the menu, and once by
 * itself on the first run (`welcome`).
 *
 * It opens with ▶ Play, which is the thing people were not finding: the app
 * is silent until it is pressed, and everything else is about what you hear.
 */
@Composable
fun HelpDialog(onDismiss: () -> Unit, welcome: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(if (welcome) "welcome" else "help"),
        title = { Text(stringResource(if (welcome) R.string.welcome_title else R.string.app_name)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.help_play),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.help_body),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (welcome) R.string.welcome_done else R.string.help_done))
            }
        },
    )
}
