package io.github.dmitryweiner.synesthesia.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import io.github.dmitryweiner.synesthesia.MainActivity
import io.github.dmitryweiner.synesthesia.R
import io.github.dmitryweiner.synesthesia.SynesthesiaApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the sound going with the screen off (PLAN.md decision 10): a
 * foreground service of type `mediaPlayback`, a media session (lock screen,
 * headphone buttons, the system's media controls) and the notification.
 *
 * It holds no sound of its own — [PlaybackController] does — and follows the
 * controller's state: in front while the sound plays (or waits for a call to
 * end), and when it stops, the notification stays with ▶ to start it again,
 * and the service ends.
 *
 * From phase 2 the search is on the notification too: 👎 and 👍 steer the
 * point with the screen off, which is the way this app is mostly listened to.
 */
class PlaybackService : Service() {
    companion object {
        const val ACTION_PLAY = "io.github.dmitryweiner.synesthesia.PLAY"
        const val ACTION_STOP = "io.github.dmitryweiner.synesthesia.STOP"
        const val ACTION_LIKE = "io.github.dmitryweiner.synesthesia.LIKE"
        const val ACTION_DISLIKE = "io.github.dmitryweiner.synesthesia.DISLIKE"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1

        /**
         * True while the service is in the foreground. A service that has
         * just stopped itself may still be alive for a moment; this is false
         * then, so [PlaybackController.play] starts it again.
         */
        @Volatile
        var isInForeground = false
            private set
    }

    private lateinit var playback: PlaybackController
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inForeground = false
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        playback = (application as SynesthesiaApp).playback
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        session = MediaSession(this, "Synesthesia").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = playback.play()
                    override fun onPause() = playback.stop()
                    override fun onStop() = playback.stop()

                    override fun onCustomAction(action: String, extras: Bundle?) {
                        when (action) {
                            ACTION_LIKE -> playback.like()
                            ACTION_DISLIKE -> playback.dislike()
                        }
                    }
                },
            )
            setSessionActivity(openAppIntent())
            isActive = true
        }
        scope.launch { playback.state.collect(::follow) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_PLAY -> playback.play()
            ACTION_STOP -> playback.stop()
            ACTION_LIKE -> playback.like()
            ACTION_DISLIKE -> playback.dislike()
        }
        // Every start of this service is a startForegroundService(), which
        // must be answered with startForeground() — even when the answer is
        // "nothing plays", which follow() then takes back.
        val state = playback.state.value
        goForeground(state)
        if (!state.holdsForeground) follow(state)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        session.release()
        isInForeground = false
        super.onDestroy()
    }

    private fun follow(state: PlaybackController.State) {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.pointName)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.app_name))
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(
                    if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_STOPPED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP,
                )
                .addCustomAction(ACTION_DISLIKE, getString(R.string.action_dislike), R.drawable.ic_action_dislike)
                .addCustomAction(ACTION_LIKE, getString(R.string.action_like), R.drawable.ic_action_like)
                .build(),
        )
        if (state.holdsForeground) {
            goForeground(state)
        } else if (inForeground) {
            stopForeground(STOP_FOREGROUND_DETACH)
            inForeground = false
            isInForeground = false
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
            // Not stopSelf(): a play() that raced this stop has a newer start id.
            stopSelf(lastStartId)
        }
    }

    private fun goForeground(state: PlaybackController.State) {
        val n = notification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        inForeground = true
        isInForeground = true
    }

    private fun notification(state: PlaybackController.State): Notification {
        val transport = if (state.holdsForeground) {
            action(R.drawable.ic_action_stop, R.string.action_stop, ACTION_STOP)
        } else {
            action(R.drawable.ic_action_play, R.string.action_play, ACTION_PLAY)
        }
        // What the last press did, if anything; otherwise what the sound is
        // doing. The status's first line is the one worth a glance (the
        // second names the genes that moved).
        val said = state.session.status.substringBefore('\n')
        val text = said.ifEmpty {
            when {
                state.pausedForFocus -> getString(R.string.paused_for_focus)
                state.playing -> getString(R.string.playing)
                else -> getString(R.string.stopped)
            }
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_synesthesia)
            .setContentTitle(state.pointName)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(state.holdsForeground)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(action(R.drawable.ic_action_dislike, R.string.action_dislike, ACTION_DISLIKE))
            .addAction(transport)
            .addAction(action(R.drawable.ic_action_like, R.string.action_like, ACTION_LIKE))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    // 👎 ⏹ 👍 — steering the search without unlocking the phone.
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun action(icon: Int, title: Int, intentAction: String): Notification.Action =
        Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon),
            getString(title),
            serviceIntent(intentAction),
        ).build()

    private fun serviceIntent(action: String): PendingIntent = PendingIntent.getForegroundService(
        this,
        action.hashCode(),
        Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
