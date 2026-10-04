package io.github.dmitryweiner.synesthesia.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
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
                    "My points",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            if (kept.isEmpty()) {
                item {
                    Text(
                        "None yet — 💾 keeps the point you are on, under a name.",
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
                        TextButton(onClick = { forgetting = i }) { Text("Forget") }
                    },
                    colors = itemColours(view.steps == 0u && view.pointName == name),
                    modifier = Modifier.clickable {
                        controller.open(i)
                        onDismiss()
                    },
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Text(
                    "Built in",
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
            title = { Text("Forget “$name”?") },
            text = { Text("It goes from your points. Whatever is playing keeps playing.") },
            confirmButton = {
                TextButton(onClick = {
                    controller.forget(index)
                    forgetting = null
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Keep it") } },
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
        title = { Text("Name this point") },
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
                    "Keeping a name you already used replaces that point.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { Button(onClick = keep, modifier = Modifier.testTag("keep")) { Text("💾 Keep") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The token: a point as text, to paste into a browser or from one — the whole
 * of how a point travels between the apps (decision 8: no network).
 *
 * The system clipboard, not Compose's: a token is plain text that another app
 * wrote or will read, and the framework's clipboard is what those apps use.
 */
@Composable
fun TokenRow(controller: PlaybackController, onSaid: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = {
                clipboard.setPrimaryClip(ClipData.newPlainText(LABEL, "#s=${controller.token()}"))
                onSaid("The point is on the clipboard, as a #s= token")
            },
            modifier = Modifier.testTag("copyToken"),
        ) { Text("Copy token") }
        TextButton(
            onClick = {
                val pasted = clipboard.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
                onSaid(if (pasted.isNullOrBlank()) "Nothing on the clipboard" else controller.importToken(pasted))
            },
            modifier = Modifier.testTag("pasteToken"),
        ) { Text("Paste token") }
    }
}

private const val LABEL = "Synesthesia point"
