package fm.heterodyne.app.stations

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class InitialSelectionTest {
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

    private val catalogs = expected.keys.map { id ->
        Catalog.parse(File("src/main/assets/catalogs/$id.json").readText())
    }

    @Test fun missingSeedsExactlyTheChosenStationsInFrequencyOrder() {
        val state = initialSelections(StationsLoad(StationsState.defaults(), LoadStatus.MISSING), catalogs)
        assertEquals(expected.keys, state.regions.keys)
        for ((id, ids) in expected) {
            val entries = state.regions.getValue(id).active.entries
            assertEquals(ids, entries.map { it.catalogId })
            assertEquals(entries.map { it.frequencyKhz }.sorted(), entries.map { it.frequencyKhz })
            val catalog = catalogs.single { it.region.id == id }
            assertEquals(ids.map { chosen -> catalog.stations.single { it.id == chosen }.copyEntry() }, entries)
        }
    }

    @Test fun loadedAndRecoveredKeepEmptyDefaults() {
        for (status in listOf(LoadStatus.LOADED, LoadStatus.RECOVERED)) {
            assertEquals(StationsState.defaults(), initialSelections(StationsLoad(StationsState.defaults(), status), catalogs))
        }
    }
}
