package io.github.dmitryweiner.synesthesia

import android.app.Application
import io.github.dmitryweiner.synesthesia.playback.PlaybackController

/**
 * Holds the one [PlaybackController] of the process. The screen and the
 * background service both talk to it; the service is what keeps the process
 * alive and in front while the sound plays (PLAN.md decision 10).
 */
class SynesthesiaApp : Application() {
    val playback: PlaybackController by lazy { PlaybackController(this) }
}
