package fm.heterodyne.app.preflight

import android.hardware.usb.UsbManager
import android.os.Build
import fm.heterodyne.app.StationsApplication
import fm.heterodyne.app.module.DeviceProfile
import fm.heterodyne.app.receiver.ReceiverFixture

/** The manifest selects this class on every ordinary process start. */
class PreflightApplication : StationsApplication() {
    override fun onCreate() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Preflight requires an Emulator" }
        check(getSystemService(UsbManager::class.java)?.deviceList?.isEmpty() == true) {
            "Preflight requires an available USB service without devices"
        }
        super.onCreate()
        module.usb.probeEvent("PREFLIGHT pid=${android.os.Process.myPid()} receiver=FakeReceiver " +
            "transport=FakeTransport pcm=synthetic-48000-s16le-stereo")
    }

    override fun onlineCatalog(): fm.heterodyne.app.stations.CatalogReader =
        PreflightCatalog(this, getSharedPreferences("preflight", MODE_PRIVATE).getString("catalog", "https")!!)

    override fun receiverFixture(): ReceiverFixture {
        val transport = FakeModuleTransport()
        return ReceiverFixture(DeviceProfile("preflight-fake-module"), transport, SyntheticPcmSource(), transport)
    }
}
