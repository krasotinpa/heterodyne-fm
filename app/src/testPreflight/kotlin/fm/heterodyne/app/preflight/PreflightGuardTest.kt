package fm.heterodyne.app.preflight

import android.app.Application
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PreflightGuardTest {
    @Test fun physicalHardwareCannotStartPreflightApplication() {
        ShadowBuild.setHardware("physical-test-device")
        val app = PreflightApplication()
        assertThrows(IllegalStateException::class.java) { app.onCreate() }
        assertThrows(UninitializedPropertyAccessException::class.java) { app.module }
    }
}
