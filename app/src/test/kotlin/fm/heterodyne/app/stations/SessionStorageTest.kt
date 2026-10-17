package fm.heterodyne.app.stations

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun newRepositoryRestoresEverySessionChangeFromSameFile() {
        val file = File(temporary.newFolder(), "stations.json")
        var state = StationsState.defaults()
        val region = state.regions.getValue("yaroslavl").createSelection("Дорога").value!!
            .activate("Дорога").value!!
        val changes = listOf(
            state.copy(selectedRegion = "yaroslavl"),
            state.copy(selectedRegion = "yaroslavl", regions = state.regions + ("yaroslavl" to region)),
        )
        for (next in changes + changes.last().copy(lastStation = SessionStation(Band.AM, 711), band = Band.AM)) {
            assertTrue(StationsRepository(AtomicStationsFile(file)).save(next))
            state = StationsRepository(AtomicStationsFile(file)).load().state
            assertEquals(next, state)
        }
        assertEquals("Дорога", state.regions.getValue("yaroslavl").activeName)
        assertEquals(SessionStation(Band.AM, 711), state.lastStation)
        assertEquals(Band.AM, state.band)
    }

    @Test fun legacySetsKeyPreservesNamesEntriesAndSession() {
        // R25: ключ sets сохранён намеренно для чтения файлов старой версии.
        val legacy = """
            {"schema":2,"selectedRegion":"yaroslavl","band":"AM",
             "lastStation":{"band":"AM","frequencyKhz":855},"regions":{
              "moskva":{"activeName":"Основной","sets":[{"name":"Основной","entries":[]}]},
              "yaroslavl":{"activeName":"Поездка","sets":[
                {"name":"Основной","entries":[]},
                {"name":"Поездка","entries":[
                  {"band":"FM","frequencyKhz":90300,"name":"Моя станция","catalogId":"custom"},
                  {"band":"AM","frequencyKhz":855,"name":null,"catalogId":null}]}]}}}
        """.trimIndent()
        val file = File(temporary.newFolder(), "stations.json").apply { writeText(legacy) }
        val repository = StationsRepository(AtomicStationsFile(file))
        val loaded = repository.load()
        assertEquals(LoadStatus.LOADED, loaded.status)
        assertEquals("yaroslavl", loaded.state.selectedRegion)
        assertEquals(Band.AM, loaded.state.band)
        assertEquals(SessionStation(Band.AM, 855), loaded.state.lastStation)
        val region = loaded.state.regions.getValue("yaroslavl")
        assertEquals("Поездка", region.activeName)
        assertEquals(listOf("Основной", "Поездка"), region.selections.map { it.name })
        assertEquals("Основной", loaded.state.regions.getValue("moskva").activeName)
        assertEquals(listOf(
            StationEntry(Band.FM, 90300, "Моя станция", "custom"),
            StationEntry(Band.AM, 855),
        ), region.active.entries)
        assertTrue(repository.save(loaded.state))
        val savedRegion = org.json.JSONObject(file.readText()).getJSONObject("regions").getJSONObject("yaroslavl")
        assertTrue(savedRegion.has("sets"))
        assertFalse(savedRegion.has("selections"))
        assertEquals(loaded.state, StationsRepository(AtomicStationsFile(file)).load().state)
    }

    @Test fun legacySchemaLoadsWithoutStationAndMigratesOnSave() {
        val file = File(temporary.newFolder(), "stations.json")
        val legacy = org.json.JSONObject(StationsJson.encode(StationsState.defaults()))
            .put("schema", 1).apply { remove("band"); remove("lastStation") }
        file.writeText(legacy.toString())
        val repository = StationsRepository(AtomicStationsFile(file))
        assertEquals(LoadStatus.LOADED, repository.load().status)
        assertEquals(StationsState.defaults(), repository.load().state)
        assertTrue(repository.save(repository.load().state))
        assertEquals(2, org.json.JSONObject(file.readText()).getInt("schema"))
    }

    @Test fun corruptOrIncompleteSessionUsesDefaultsAndPreservesOriginal() {
        val valid = StationsJson.encode(StationsState.defaults().copy(
            lastStation = SessionStation(Band.FM, 90300)))
        val invalid = listOf("garbage", "", valid.replace("\"schema\":2", "\"schema\":99"),
            valid.replace("\"schema\":2", "\"schema\":2.5"),
            valid.replace("90300", "90301"), valid.replace("90300", "108100"),
            valid.replace("FM", "DAB"), valid.replace("90300", "null"),
            org.json.JSONObject(valid).apply { remove("band") }.toString(),
            org.json.JSONObject(valid).apply { remove("lastStation") }.toString(),
            org.json.JSONObject(valid).put("lastStation",
                org.json.JSONObject().put("band", "AM").put("frequencyKhz", 1053)).toString())
        for (text in invalid) {
            val file = File(temporary.newFolder(), "stations.json").apply { writeText(text) }
            val repository = StationsRepository(AtomicStationsFile(file))
            assertEquals(text, LoadStatus.RECOVERED, repository.load().status)
            assertEquals(StationsState.defaults(), repository.load().state)
            assertTrue(repository.save(repository.load().state))
            assertEquals(text, File(file.parentFile, "stations.json.corrupt").readText())
        }
    }
}
