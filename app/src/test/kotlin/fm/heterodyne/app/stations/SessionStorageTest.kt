package fm.heterodyne.app.stations

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun encodedStateIncludesLastPlayedFalse() {
        assertEquals(false, org.json.JSONObject(StationsJson.encode(StationsState.defaults())).get("lastPlayed"))
    }

    @Test fun lastPlayedRoundTripsBothValues() {
        for (value in listOf(true, false)) {
            val state = StationsState.defaults().copy(lastStation = SessionStation(90300), lastPlayed = value)
            val encoded = StationsJson.encode(state)
            assertEquals(value, org.json.JSONObject(encoded).get("lastPlayed"))
            assertEquals(state, StationsJson.decode(encoded))
        }
    }

    @Test fun missingOrNullLastPlayedLoadsFalseForBothSchemas() {
        for (schema in listOf(1, 2)) {
            for (explicitNull in listOf(false, true)) {
                val json = org.json.JSONObject(StationsJson.encode(StationsState.defaults()))
                    .put("schema", schema)
                if (schema == 1) { json.remove("band"); json.remove("lastStation") }
                if (explicitNull) json.put("lastPlayed", org.json.JSONObject.NULL)
                else json.remove("lastPlayed")
                val file = File(temporary.newFolder(), "stations.json").apply { writeText(json.toString()) }
                val loaded = StationsRepository(AtomicStationsFile(file)).load()
                assertEquals(LoadStatus.LOADED, loaded.status)
                assertEquals(StationsState.defaults(), loaded.state)
            }
        }
    }

    @Test fun invalidLastPlayedRejectsWholeDocument() {
        for (value in listOf<Any>("true", "false", 0, 1, org.json.JSONObject(), org.json.JSONArray())) {
            val json = org.json.JSONObject(StationsJson.encode(StationsState.defaults().copy(
                selectedRegion = "yaroslavl", lastStation = SessionStation(90300))))
                .put("lastPlayed", value)
            val file = File(temporary.newFolder(), "stations.json").apply { writeText(json.toString()) }
            val loaded = StationsRepository(AtomicStationsFile(file)).load()
            assertEquals(value.toString(), LoadStatus.RECOVERED, loaded.status)
            assertEquals(StationsState.defaults(), loaded.state)
            assertFalse(loaded.state.lastPlayed)
        }
    }

    @Test fun newRepositoryRestoresEverySessionChangeFromSameFile() {
        val file = File(temporary.newFolder(), "stations.json")
        var state = StationsState.defaults()
        val region = state.regions.getValue("yaroslavl").createSelection("Дорога").value!!
            .activate("Дорога").value!!
        val changes = listOf(
            state.copy(selectedRegion = "yaroslavl"),
            state.copy(selectedRegion = "yaroslavl", regions = state.regions + ("yaroslavl" to region)),
        )
        for (next in changes + changes.last().copy(lastStation = SessionStation(97700))) {
            assertTrue(StationsRepository(AtomicStationsFile(file)).save(next))
            state = StationsRepository(AtomicStationsFile(file)).load().state
            assertEquals(next, state)
        }
        assertEquals("Дорога", state.regions.getValue("yaroslavl").activeName)
        assertEquals(SessionStation(97700), state.lastStation)
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
        assertNull(loaded.state.lastStation)
        val region = loaded.state.regions.getValue("yaroslavl")
        assertEquals("Поездка", region.activeName)
        assertEquals(listOf("Основной", "Поездка"), region.selections.map { it.name })
        assertEquals("Основной", loaded.state.regions.getValue("moskva").activeName)
        assertEquals(listOf(
            StationEntry(90300, "Моя станция", "custom"),
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
            .put("schema", 1).apply { remove("band"); remove("lastStation"); remove("lastPlayed") }
        file.writeText(legacy.toString())
        val repository = StationsRepository(AtomicStationsFile(file))
        assertEquals(LoadStatus.LOADED, repository.load().status)
        assertEquals(StationsState.defaults(), repository.load().state)
        assertTrue(repository.save(repository.load().state))
        assertEquals(2, org.json.JSONObject(file.readText()).getInt("schema"))
    }

    @Test fun corruptOrIncompleteSessionUsesDefaultsAndPreservesOriginal() {
        val valid = StationsJson.encode(StationsState.defaults().copy(
            lastStation = SessionStation(90300)))
        val invalid = listOf("garbage", "", valid.replace("\"schema\":2", "\"schema\":99"),
            valid.replace("\"schema\":2", "\"schema\":2.5"),
            valid.replace("90300", "90301"), valid.replace("90300", "108100"),
            valid.replace("90300", "null"),
            org.json.JSONObject(valid).apply { remove("lastStation") }.toString())
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
