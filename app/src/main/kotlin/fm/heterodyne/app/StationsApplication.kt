package fm.heterodyne.app

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import fm.heterodyne.app.audio.PlaybackService

/** Wires process-wide storage, module control and diagnostics. */
class StationsApplication : Application() {
    lateinit var session: StationsSession
        private set
    lateinit var module: StationsModule
        private set
    lateinit var diagnostics: StationsDiagnostics
        private set

    override fun onCreate() {
        super.onCreate()
        val main = Handler(Looper.getMainLooper())
        session = StationsSession(this, main)
        module = StationsModule(this, main, session) {
            startForegroundService(Intent(this, PlaybackService::class.java))
        }
        diagnostics = StationsDiagnostics(this, main, module.usb)
        session.load {
            main.post { module.usb.start() }
        }
    }
}
