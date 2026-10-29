package fm.heterodyne.app.stations

import android.app.Application
import android.os.Handler
import android.os.Looper
import fm.heterodyne.app.StationsSession
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CatalogSessionTest {
    @Test fun installedSetWithoutMoscowSupportsMissingAndSavedState() {
        val context = RuntimeEnvironment.getApplication()
        val raw = javaClass.getResourceAsStream("/catalog-fixtures/valid/test.json")!!.use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        val index = JSONObject().put("schema", 1).put("revision", "2026-10-30")
            .put("regions", JSONArray().put(JSONObject().put("id", "test").put("name", "Тест")
                .put("file", "test.json").put("sha256", hash).put("revision", "2026-01-01")))
        val reader = CatalogReader { if (it == "index.json") index.toString().toByteArray() else raw }
        val dir = File(context.filesDir, "catalog")
        val file = File(context.filesDir, "stations.json")
        try {
            val store = CatalogStore(dir, AssetsCatalogReader { context.assets.open("catalogs/$it") })
            assertTrue(store.install(reader).isSuccess)
            for (saved in listOf(false, true)) {
                val initial = StationsState.defaults().copy(selectedRegion = "unknown")
                if (saved) file.writeText(StationsJson.encode(initial)) else file.delete()
                val session = StationsSession(context, Handler(Looper.getMainLooper()), Executor { it.run() })
                session.load {}
                shadowOf(Looper.getMainLooper()).idle()
                assertNull(session.screen.error)
                assertEquals(listOf("test"), session.screen.catalogs.map { it.region.id })
                assertEquals("test", session.screen.state!!.selectedRegion)
                assertEquals(initial.regions, session.screen.state!!.regions)
            }
        } finally {
            dir.deleteRecursively()
            file.delete()
        }
    }
}
