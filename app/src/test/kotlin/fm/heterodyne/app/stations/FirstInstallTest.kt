package fm.heterodyne.app.stations

import android.app.Application
import android.os.Handler
import android.os.Looper
import fm.heterodyne.app.StationsSession
import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FirstInstallTest {
    private val expected = mapOf(
        "moskva" to listOf(
            "moskva-biznes-fm", "moskva-radio-rekord", "moskva-moskva-fm",
            "moskva-pervoe-sportivnoe-radio", "moskva-detskoe-radio", "moskva-radio-romantika",
            "moskva-dfm", "moskva-mayak", "moskva-capital-fm", "moskva-militseyskaya-volna"),
        "yaroslavl" to listOf(
            "yaroslavl-radio-rodnyh-dorog", "yaroslavl-radio-iskatel", "yaroslavl-radio-vera",
            "yaroslavl-radio-rossii", "yaroslavl-love-radio", "yaroslavl-radio-7",
            "yaroslavl-radio-dacha", "yaroslavl-avtoradio", "yaroslavl-marusya-fm", "yaroslavl-mayak"),
    )

    @Test fun missingFileSeedsBothRegionsAndPersistsBeforeAnyEdit() {
        file().delete()
        val session = load()
        for ((regionId, ids) in expected) {
            val region = session.screen.state!!.regions.getValue(regionId)
            assertEquals("Основной", region.activeName)
            assertEquals(ids, region.active.entries.map { it.catalogId })
            val frequencies = region.active.entries.map { it.frequencyKhz }
            assertEquals(frequencies.sorted(), frequencies)
            val catalog = session.screen.catalogs.first { it.region.id == regionId }
            assertEquals(ids.map { id -> catalog.stations.single { it.id == id }.copyEntry() }, region.active.entries)
        }
        assertEquals(LoadStatus.LOADED, StationsRepository(AtomicStationsFile(file())).load().status)
        assertEquals(session.screen.state, load().screen.state)
    }

    @Test fun everySelectedIdExistsInItsAssetCatalog() {
        val context = RuntimeEnvironment.getApplication()
        for ((regionId, ids) in expected) {
            val catalog = context.assets.open("catalogs/$regionId.json").bufferedReader().use { Catalog.parse(it.readText()) }
            assertEquals(10, ids.size)
            ids.forEach { id -> assertTrue(id, catalog.stations.any { it.id == id }) }
        }
    }

    @Test fun loadedAndRecoveredDefaultsRemainEmpty() {
        for (corrupt in listOf(false, true)) {
            file().writeText(if (corrupt) "broken" else StationsJson.encode(StationsState.defaults()))
            val session = load()
            assertEquals(corrupt, session.screen.recovered)
            assertEquals(StationsState.defaults(), session.screen.state)
        }
    }

    @Test fun existingUserEntriesRemainUnchanged() {
        val region = RegionSelections.defaults().let {
            it.replaceActive(it.active.add(StationEntry(90300, "Моя станция")).value!!)
        }
        val state = StationsState.defaults().copy(regions = StationsState.defaults().regions + ("moskva" to region))
        file().writeText(StationsJson.encode(state))
        assertEquals(state, load().screen.state)
    }

    private fun file() = File(RuntimeEnvironment.getApplication().filesDir, "stations.json")

    private fun load(): StationsSession {
        val session = StationsSession(RuntimeEnvironment.getApplication(), Handler(Looper.getMainLooper()), Executor { it.run() })
        session.load {}
        shadowOf(Looper.getMainLooper()).idle()
        return session
    }
}
