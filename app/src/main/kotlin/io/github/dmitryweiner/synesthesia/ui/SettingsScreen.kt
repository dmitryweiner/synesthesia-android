package io.github.dmitryweiner.synesthesia.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import io.github.dmitryweiner.synesthesia.R
import io.github.dmitryweiner.synesthesia.core.Control
import io.github.dmitryweiner.synesthesia.core.ControlKind
import io.github.dmitryweiner.synesthesia.core.PointEdit
import io.github.dmitryweiner.synesthesia.core.Section
import io.github.dmitryweiner.synesthesia.core.SettingsTab
import io.github.dmitryweiner.synesthesia.core.settingsPage
import io.github.dmitryweiner.synesthesia.playback.PlaybackController
import androidx.compose.material3.rememberTooltipState
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * ⚙ Settings (PLAN.md phase 5): every parameter of the point, on two tabs.
 *
 * Nothing here knows what the parameters are. The page is whatever the core
 * derives from the schema — sections, labels, ranges, steps, options, and
 * which switch gates what — so when the web app grows a parameter it appears
 * here on its own (the tanpura's Jawari and the delay's Shimmer did).
 *
 * An edit is heard as it is made: the point goes to the sound as the finger
 * moves, no more often than a morph pushes. The picture stops while the page
 * is open, as the web app's does, so it shows the result on closing. Closing
 * is one undoable step — a jump, not a morph, because the sound is already
 * there.
 */
@Composable
fun SettingsScreen(controller: PlaybackController, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val edit = remember { controller.openSettings() }
    val page = remember { settingsPage() }
    var tab by remember { mutableIntStateOf(0) }
    // A reset changes every control at once, and each one holds its own
    // remembered value; this is what tells them all to read the point again.
    var generation by remember { mutableIntStateOf(0) }
    // The tag is the tab's own name, not its label: the label is translated.
    val tabs = listOf(
        Triple(SettingsTab.SOUND, R.string.tab_sound, "tabSound"),
        Triple(SettingsTab.PICTURE, R.string.tab_picture, "tabPicture"),
    )

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose, modifier = Modifier.testTag("closeSettings")) {
                Text(stringResource(R.string.settings_done))
            }
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, (_, title, tag) ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(stringResource(title)) },
                    modifier = Modifier.testTag(tag),
                )
            }
        }
        val shown = page.filter { it.tab == tabs[tab].first }
        LazyColumn(Modifier.fillMaxSize().testTag("settingsList")) {
            // Begin again from nothing, this half of the point only: the
            // sound and the picture are reset apart, and ↩ takes it back
            // because the whole page is one step.
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = {
                            edit.reset(tabs[tab].first)
                            controller.settingsEdited()
                            generation++
                        },
                        modifier = Modifier.testTag("resetTab"),
                    ) { Text(stringResource(if (tab == 0) R.string.reset_sound else R.string.reset_picture)) }
                }
            }
            if (tab == 0) {
                item { Volume(edit, controller, generation) }
            }
            items(shown, key = { it.id }) { section ->
                SectionBlock(section, edit, controller, generation)
                HorizontalDivider()
            }
        }
    }
}

/** The master gain: not a gene, so it sits above the generated sections. */
@Composable
private fun Volume(edit: PointEdit, controller: PlaybackController, generation: Int) {
    var value by remember(generation) { mutableStateOf(edit.masterGain().toFloat()) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.volume, "%.2f".format(value)), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value,
            onValueChange = {
                value = it
                edit.setMasterGain(it.toDouble())
                controller.settingsEdited()
            },
            valueRange = 0f..1f,
            modifier = Modifier.testTag("volume"),
        )
    }
}

@Composable
private fun SectionBlock(section: Section, edit: PointEdit, controller: PlaybackController, generation: Int) {
    // Sections start closed: there are forty of them, and a point has 250
    // parameters.
    var open by remember(section.id) { mutableStateOf(false) }
    val toggle = section.toggle
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${if (open) "▾" else "▸"}  ${section.title}",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // What the thing is, in the schema's own words, and where to
            // read more. There is nothing to say about an FX module or an
            // LFO, and then there is no button.
            if (section.description.isNotEmpty()) {
                Explain(section)
            }
            if (toggle != null) {
                SwitchControl(toggle, edit, controller, generation)
            }
        }
        if (open) {
            for (control in section.controls) {
                ControlRow(control, edit, controller, generation)
            }
        }
    }
}

/**
 * What this section is, in a tooltip: the schema's own line about the thing,
 * and the Wikipedia article about it where the core has one. A tooltip rather
 * than a line in the page — the answer to "what is a Lorenz attractor" is not
 * part of the settings, it is an aside, and it closes when it is read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Explain(section: Section) {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        state = state,
        tooltip = {
            RichTooltip(
                title = { Text(section.title) },
                action = section.article?.let { url ->
                    {
                        TextButton(
                            onClick = {
                                uriHandler.openUri(url)
                                state.dismiss()
                            },
                            modifier = Modifier.testTag("read:${section.id}"),
                        ) { Text(stringResource(R.string.read_more)) }
                    }
                },
                modifier = Modifier.testTag("about:${section.id}"),
            ) { Text(section.description) }
        },
    ) {
        TextButton(
            onClick = { scope.launch { state.show() } },
            modifier = Modifier.testTag("explain:${section.id}"),
        ) { Text("?") }
    }
}

@Composable
private fun ControlRow(control: Control, edit: PointEdit, controller: PlaybackController, generation: Int) {
    // A control whose switch is off keeps its value and does nothing, so it
    // is shown dimmed rather than hidden (the core says which).
    val active = control.activeIf == null || edit.isActive(control.id)
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 4.dp)) {
        when (control.kind) {
            ControlKind.SWITCH -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(control.shortLabel, Modifier.weight(1f), style = label(active))
                SwitchControl(control, edit, controller, generation)
            }
            ControlKind.CHOICE -> ChoiceControl(control, edit, controller, active, generation)
            ControlKind.SLIDER -> SliderControl(control, edit, controller, active, generation)
        }
    }
}

@Composable
private fun label(active: Boolean) = MaterialTheme.typography.bodySmall.copy(
    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
)

@Composable
private fun SwitchControl(control: Control, edit: PointEdit, controller: PlaybackController, generation: Int) {
    var on by remember(control.id, generation) { mutableStateOf(edit.value(control.id) >= 0.5) }
    Switch(
        checked = on,
        onCheckedChange = {
            on = it
            edit.setValue(control.id, if (it) 1.0 else 0.0)
            controller.settingsEdited()
        },
        modifier = Modifier.testTag(control.id),
    )
}

@Composable
private fun ChoiceControl(
    control: Control,
    edit: PointEdit,
    controller: PlaybackController,
    active: Boolean,
    generation: Int,
) {
    var index by remember(control.id, generation) { mutableIntStateOf(edit.value(control.id).roundToInt()) }
    var open by remember(control.id) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(control.shortLabel, Modifier.weight(1f), style = label(active))
        TextButton(onClick = { open = true }, modifier = Modifier.testTag(control.id)) {
            Text(control.options.getOrElse(index) { "$index" }, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            control.options.forEachIndexed { i, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        index = i
                        edit.setValue(control.id, i.toDouble())
                        controller.settingsEdited()
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SliderControl(
    control: Control,
    edit: PointEdit,
    controller: PlaybackController,
    active: Boolean,
    generation: Int,
) {
    // A frequency-like control moves in octaves, as the schema says and as a
    // route's depth does: the slider is log, the value is not.
    val scale = remember(control.id) { Scale(control) }
    var position by remember(control.id, generation) { mutableStateOf(scale.toSlider(edit.value(control.id))) }
    val value = scale.toValue(position)
    Column {
        Text("${control.shortLabel} · ${format(value)}", style = label(active))
        Slider(
            value = position,
            onValueChange = {
                position = it
                edit.setValue(control.id, scale.toValue(it))
                controller.settingsEdited()
            },
            steps = scale.steps,
            modifier = Modifier.testTag(control.id),
        )
    }
}

/** 0..1 on the slider ↔ the control's own units, logarithmic where the schema says. */
private class Scale(private val control: Control) {
    private val log = control.exp && control.min > 0.0 && control.max > control.min

    /** Notches, for a step the schema gives on a short range. */
    val steps: Int = if (!log && control.step > 0.0) {
        (((control.max - control.min) / control.step).roundToInt() - 1).coerceIn(0, 100)
    } else {
        0
    }

    fun toSlider(value: Double): Float {
        val v = value.coerceIn(control.min, control.max)
        val t = if (log) {
            ln(v / control.min) / ln(control.max / control.min)
        } else {
            (v - control.min) / (control.max - control.min)
        }
        return t.coerceIn(0.0, 1.0).toFloat()
    }

    fun toValue(position: Float): Double {
        val t = position.coerceIn(0f, 1f).toDouble()
        return if (log) {
            control.min * (control.max / control.min).pow(t)
        } else {
            control.min + (control.max - control.min) * t
        }
    }
}

/** Enough digits to see a change, not more. */
internal fun format(v: Double): String = when {
    v == 0.0 -> "0"
    kotlin.math.abs(v) >= 100 -> "%.0f".format(v)
    kotlin.math.abs(v) >= 1 -> "%.2f".format(v)
    else -> "%.4f".format(v)
}
