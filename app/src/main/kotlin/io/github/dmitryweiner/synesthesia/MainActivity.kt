package io.github.dmitryweiner.synesthesia

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import io.github.dmitryweiner.synesthesia.playback.PlaybackController
import io.github.dmitryweiner.synesthesia.ui.BenchScreen
import io.github.dmitryweiner.synesthesia.ui.PlayerScreen
import io.github.dmitryweiner.synesthesia.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = (application as SynesthesiaApp).playback
        // A link to the web app, if that is what started this (decision 8).
        var fromLink by mutableStateOf(openLink(controller, intent))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var bench by rememberSaveable { mutableStateOf(false) }
                var settings by rememberSaveable { mutableStateOf(false) }
                // Android 13+ asks before showing the playback notification.
                // The sound plays whatever the answer: it is asked once, on
                // the first ▶.
                val askNotifications = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { controller.play() }
                val play = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                        !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) &&
                        !askedForNotifications
                    ) {
                        askedForNotifications = true
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        controller.play()
                    }
                }
                Scaffold { padding ->
                    fromLink?.let { said ->
                        LaunchedEffect(said) {
                            // One line, once: the point itself is already open.
                            controller.say(said)
                            fromLink = null
                        }
                    }
                    val closeSettings = {
                        controller.closeSettings()
                        settings = false
                    }
                    when {
                        bench -> {
                            BackHandler { bench = false }
                            BenchScreen(controller.sampleRate, onBack = { bench = false }, Modifier.padding(padding))
                        }
                        settings -> {
                            // Back closes the page the same way Done does:
                            // one undoable step, or nothing at all.
                            BackHandler(onBack = closeSettings)
                            SettingsScreen(controller, onClose = closeSettings, Modifier.padding(padding))
                        }
                        else -> PlayerScreen(
                            controller,
                            onPlay = play,
                            onBench = { bench = true },
                            onSettings = { settings = true },
                            Modifier.padding(padding),
                        )
                    }
                }
            }
        }
    }

    /** A link opened while the app was already running (`singleTop`). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val controller = (application as SynesthesiaApp).playback
        openLink(controller, intent)?.let(controller::say)
    }

    private fun openLink(controller: PlaybackController, intent: Intent?): String? {
        val url = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.toString() ?: return null
        return controller.openLink(url)
    }

    private var askedForNotifications = false
}
