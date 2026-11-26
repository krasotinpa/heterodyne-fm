package fm.heterodyne.app

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import fm.heterodyne.app.audio.PlaybackService

/** Wires process-wide storage, module control and diagnostics. */
open class StationsApplication : Application() {
    protected open fun receiverFixture(): fm.heterodyne.app.receiver.ReceiverFixture? = null
    protected open fun onlineCatalog(): fm.heterodyne.app.stations.CatalogReader = fm.heterodyne.app.stations.HttpsCatalogReader()
    lateinit var session: StationsSession
        private set
    lateinit var module: StationsModule
        private set
    lateinit var diagnostics: StationsDiagnostics
        private set

    lateinit var radio: ApplicationAdapter
        private set

    override fun onCreate() {
        super.onCreate()
        val main = Handler(Looper.getMainLooper())
        session = StationsSession(this, main, onlineCatalog = ::onlineCatalog)
        module = StationsModule(this, main, session, fixture = receiverFixture()) {
            startForegroundService(Intent(this, PlaybackService::class.java))
        }
        diagnostics = StationsDiagnostics(this, main, module.usb)
        radio = ApplicationAdapter(this, module, session, diagnostics)
        session.load {
            main.post { module.usb.start() }
        }
    }
}
