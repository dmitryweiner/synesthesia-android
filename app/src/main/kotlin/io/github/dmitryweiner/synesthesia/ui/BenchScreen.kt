package io.github.dmitryweiner.synesthesia.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.dmitryweiner.synesthesia.bench.Bench
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs [Bench] over every built-in point and shows the numbers, ready to copy. */
@Composable
fun BenchScreen(sampleRate: Int, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val rows = remember { mutableStateListOf<Bench.Row>() }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            TextButton(onClick = onBack) { Text("← Back") }
        }
        Text("Bench", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Renders ${Bench.SECONDS.toInt()} s of every point at $sampleRate Hz on one thread. " +
                "× realtime must stay above 1 for the live sound; the share of a core is its inverse.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(Bench.device(), style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !running,
                onClick = {
                    rows.clear()
                    running = true
                    scope.launch {
                        for (i in 0 until Bench.count) {
                            rows += withContext(Dispatchers.Default) { Bench.measure(i, sampleRate) }
                        }
                        Log.i("SynBench", Bench.report(sampleRate, rows))
                        running = false
                    }
                },
            ) { Text(if (running) "Running ${rows.size + 1}/${Bench.count}…" else "Run") }
            TextButton(
                enabled = rows.isNotEmpty() && !running,
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Synesthesia bench", Bench.report(sampleRate, rows)))
                },
            ) { Text("Copy") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.index }) { r ->
                ListItem(
                    headlineContent = { Text("${r.index}  ${r.name}") },
                    supportingContent = {
                        Text("%.1f× realtime · %.1f%% of a core · %.1f dB".format(r.realtime, 100 * r.coreShare, r.rmsDb))
                    },
                )
            }
        }
    }
}
