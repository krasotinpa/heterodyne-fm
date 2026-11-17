package fm.heterodyne.app.preflight

import android.hardware.usb.UsbManager
import android.os.Build
import fm.heterodyne.app.StationsApplication
import fm.heterodyne.app.module.DeviceProfile
import fm.heterodyne.app.receiver.ReceiverFixture

/** The manifest selects this class on every ordinary process start. */
class PreflightApplication : StationsApplication() {
    val fakeTransport = FakeModuleTransport()
    val syntheticPcm = SyntheticPcmSource()
    fun setReceiverConnected(connected: Boolean) = module.usb.setFixtureConnected(connected)

    override fun onCreate() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Preflight requires an Emulator" }
        check(getSystemService(UsbManager::class.java)?.deviceList?.isEmpty() == true) {
            "Preflight requires an available USB service without devices"
        }
        super.onCreate()
        module.usb.probeEvent("PREFLIGHT pid=${android.os.Process.myPid()} receiver=FakeReceiver " +
            "transport=FakeTransport pcm=synthetic-48000-s16le-stereo")
        // Reports actual AudioTrack/receiver state during ordinary launches, without instrumentation.
        val handler = android.os.Handler(mainLooper)
        handler.post(object : Runnable {
            private var previous = ""
            override fun run() {
                val audio = module.audioCounters
                val state = "${audio.state} freq=${module.transition.confirmed?.frequencyKhz} " +
                    "streaming=${audio.bytes >= 96000 && audio.state == fm.heterodyne.app.audio.AudioState.Playing}"
                if (state != previous) {
                    module.usb.probeEvent("F_AUDIO pid=${android.os.Process.myPid()} state=$state")
                    previous = state
                }
                handler.postDelayed(this, 100)
            }
        })
    }

    override fun onlineCatalog(): fm.heterodyne.app.stations.CatalogReader =
        PreflightCatalog(this, getSharedPreferences("preflight", MODE_PRIVATE).getString("catalog", "https")!!)

    override fun receiverFixture(): ReceiverFixture {
        return ReceiverFixture(DeviceProfile("preflight-fake-module"), fakeTransport, syntheticPcm, fakeTransport)
    }
}
