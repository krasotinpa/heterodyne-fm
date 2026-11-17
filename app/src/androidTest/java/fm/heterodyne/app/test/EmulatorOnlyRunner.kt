package fm.heterodyne.app.test

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.test.runner.AndroidJUnitRunner

/** Check the virtual fixture before StationsApplication can start USB discovery. */
class EmulatorOnlyRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator-only test runner" }
        check(context.getSystemService(UsbManager::class.java)?.deviceList?.isEmpty() == true) {
            "Instrumented smoke test requires an available USB service without devices"
        }
        return super.newApplication(cl, className, context)
    }
}
