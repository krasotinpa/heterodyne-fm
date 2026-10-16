package fm.heterodyne.app.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import fm.heterodyne.app.module.Direction
import android.os.Handler
import android.os.Looper
import fm.heterodyne.app.MainActivity
import fm.heterodyne.app.StationsApplication

/** Owns foreground playback and steering wheel events on the main event loop. */
class PlaybackService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val stations get() = application as StationsApplication

    private var destroyed = false
    private lateinit var session: MediaSession
    private lateinit var wheel: WheelController

    override fun onCreate() {
        super.onCreate()
        wheel = WheelController(WheelScheduler { delay, action ->
            val task = Runnable {
                wheel.update(stations.activeEntries, stations.currentStation)
                action()
            }
            main.postDelayed(task, delay)
            val cancel: () -> Unit = { main.removeCallbacks(task) }
            cancel
        }, stations::selectStation, stations::stepStation)
        session = MediaSession(this, "Heterodyne FM").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return true
                    val direction = when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_NEXT -> Direction.Up
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Direction.Down
                        else -> return true // Live radio has no wheel pause or stop.
                    }
                    wheel.update(stations.activeEntries, stations.currentStation)
                    when (event.action) {
                        KeyEvent.ACTION_UP -> wheel.keyUp()
                        KeyEvent.ACTION_DOWN -> wheel.keyDown(direction,
                            event.flags and KeyEvent.FLAG_LONG_PRESS != 0, event.repeatCount)
                    }
                    return true
                }
            }, main)
            setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build())
            isActive = true
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("module-audio", "Звук Модуля", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val content = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "module-audio")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Звук Модуля")
            .setContentText("Heterodyne FM")
            .setContentIntent(content)
            .setOngoing(true)
            .build())
        // startId prevents an old session's queued completion from stopping a newer start.
        stations.playAudio { main.post { stopSelf(startId) } }
        reclaimWheel()
        return START_NOT_STICKY
    }

    fun reclaimWheel() {
        stations.reclaimAudioFocus {
            main.post { if (!destroyed) session.isActive = true }
        }
    }

    override fun onDestroy() {
        destroyed = true
        wheel.close()
        session.isActive = false
        session.release()
        stations.stopAudio()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
