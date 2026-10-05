package io.github.dmitryweiner.synesthesia.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.dmitryweiner.synesthesia.R
import io.github.dmitryweiner.synesthesia.core.SessionView
import io.github.dmitryweiner.synesthesia.playback.PlaybackController

/**
 * The points (PLAN.md decision 9): *My points* — the ones the user kept, by
 * the names they typed — and the built-in ones, in a sheet. Tapping one loads
 * it: a fresh search, a hard switch and a new picture.
 *
 * There are no ids and no server (decision 8): a point is a name and a file
 * here, and a `#s=` token when it travels.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class) // ModalBottomSheet
@Composable
fun PointsSheet(controller: PlaybackController, view: SessionView, onDismiss: () -> Unit) {
    // The kept list lives in the core and is not Compose state; the session's
    // view changes whenever it does (a save, a load, a delete), so it is what
    // tells this sheet to read the list again.
    val kept = remember(view) { controller.points.names() }
    var forgetting by remember { mutableStateOf<Int?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("points")) {
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            item {
                Text(
                    stringResource(R.string.points_mine),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            if (kept.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.points_none),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            itemsIndexed(kept, key = { i, name -> "kept:$i:$name" }) { i, name ->
                ListItem(
                    headlineContent = { Text(name) },
                    leadingContent = { Text("💾") },
                    trailingContent = {
                        TextButton(
                            onClick = { forgetting = i },
                            modifier = Modifier.testTag("forget"),
                        ) { Text(stringResource(R.string.forget)) }
                    },
                    colors = itemColours(view.steps == 0u && view.pointName == name),
                    modifier = Modifier
                        .testTag("keptPoint")
                        .clickable {
                            controller.open(i)
                            onDismiss()
                        },
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Text(
                    stringResource(R.string.points_builtin),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                )
            }
            items(controller.presets, key = { "preset:${it.index}" }) { preset ->
                val index = preset.index.toInt()
                ListItem(
                    headlineContent = { Text(preset.name) },
                    leadingContent = { Text("$index") },
                    colors = itemColours(view.steps == 0u && view.pointName == preset.name),
                    modifier = Modifier.clickable {
                        controller.select(index)
                        onDismiss()
                    },
                )
            }
        }
    }

    forgetting?.let { index ->
        val name = kept.getOrNull(index) ?: return@let
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text(stringResource(R.string.forget_title, name)) },
            text = { Text(stringResource(R.string.forget_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.forget(index)
                        forgetting = null
                    },
                    modifier = Modifier.testTag("forgetConfirm"),
                ) { Text(stringResource(R.string.forget)) }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text(stringResource(R.string.keep_it)) } },
        )
    }
}

@Composable
private fun itemColours(selected: Boolean) = if (selected) {
    ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
} else {
    ListItemDefaults.colors()
}

/** 💾 Keeps the point under a name — the one the core suggests, or another. */
@Composable
fun KeepDialog(controller: PlaybackController, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(controller.suggestedName()) }
    val keep = {
        controller.keep(name)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.name_point)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("name"),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { keep() }),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                )
                Text(
                    stringResource(R.string.name_replaces),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { Button(onClick = keep, modifier = Modifier.testTag("keep")) { Text(stringResource(R.string.keep)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
