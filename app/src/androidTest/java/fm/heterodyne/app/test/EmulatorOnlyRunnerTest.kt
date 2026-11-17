package fm.heterodyne.app.test

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmulatorOnlyRunnerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun missingUsbServiceRejectsBeforeApplicationConstruction() {
        val noUsbService = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.USB_SERVICE) null else super.getSystemService(name)
        }
        RunnerProbeApplication.instances = 0

        assertThrows(IllegalStateException::class.java) { createApplication(noUsbService) }

        assertEquals(0, RunnerProbeApplication.instances)
    }

    @Test fun emptyEmulatorFixtureAllowsApplicationConstruction() {
        RunnerProbeApplication.instances = 0

        createApplication(context)

        assertEquals(1, RunnerProbeApplication.instances)
    }

    private fun createApplication(context: Context): Application = EmulatorOnlyRunner().newApplication(
        RunnerProbeApplication::class.java.classLoader!!,
        RunnerProbeApplication::class.java.name,
        context,
    )
}

class RunnerProbeApplication : Application() {
    companion object { var instances = 0 }
    init { instances++ }
}
