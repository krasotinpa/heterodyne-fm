package fm.heterodyne.app.preflight

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import fm.heterodyne.app.stations.loadCatalogSet
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PreflightCatalogTest {
    @Test fun fixtureInstallsAValidNewRevisionAndFailureCannotReadNetwork() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fixture = PreflightCatalog(context, "fixture")
        assertEquals("9999-01-01", loadCatalogSet(fixture).revision)
        assertThrows(java.io.IOException::class.java) { PreflightCatalog(context, "failure").read("index.json") }
        assertThrows(IllegalArgumentException::class.java) { PreflightCatalog(context, "unknown") }
    }
}
