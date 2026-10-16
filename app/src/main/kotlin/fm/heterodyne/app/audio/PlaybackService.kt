package fm.heterodyne.app.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import fm.heterodyne.app.MainActivity
import fm.heterodyne.app.StationsApplication

/** The application owns playback; this service only keeps it in the foreground. */
class PlaybackService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val stations get() = application as StationsApplication

    override fun onCreate() {
        super.onCreate()
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
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stations.stopAudio()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
