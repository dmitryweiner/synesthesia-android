package io.github.dmitryweiner.synesthesia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.dmitryweiner.synesthesia.core.PresetInfo
import io.github.dmitryweiner.synesthesia.core.coreVersion
import io.github.dmitryweiner.synesthesia.core.presets

/**
 * Phase 0 (PLAN.md): proves the chain — the Rust core, built for this
 * device, called through the generated bindings — by listing the built-in
 * presets it holds.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PresetList(remember { presets() }, remember { coreVersion() })
            }
        }
    }
}

@Composable
private fun PresetList(presets: List<PresetInfo>, version: String) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item {
                ListItem(
                    headlineContent = { Text("Synesthesia", style = MaterialTheme.typography.headlineSmall) },
                    supportingContent = { Text("core $version · ${presets.size} built-in points") },
                )
            }
            items(presets, key = { it.index.toInt() }) { p ->
                ListItem(
                    headlineContent = { Text(p.name) },
                    leadingContent = { Text("${p.index}") },
                )
            }
        }
    }
}
