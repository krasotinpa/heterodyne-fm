package fm.heterodyne.app.stations

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Schema3StorageTest {
    @Test fun legacySchemasPreserveSelectionsAndSessionWhenSavedAsSchema3() {
        for (schema in listOf(1, 2)) {
            val file = MemoryFile(document(schema, "yaroslavl"))
            val repository = StationsRepository(file)
            val loaded = repository.load()
            assertEquals(LoadStatus.LOADED, loaded.status)
            assertEquals("yaroslavl", loaded.state.selectedRegion)
            val region = loaded.state.regionSelections("yaroslavl")
            assertEquals(listOf("Основной", "Дорога"), region.selections.map { it.name })
            assertEquals("Дорога", region.activeName)
            assertEquals(listOf(StationEntry(90300, "Моя", "custom")), region.active.entries)
            assertEquals(if (schema == 2) SessionStation(97700) else null, loaded.state.lastStation)
            assertEquals(schema == 2, loaded.state.lastPlayed)
            assertTrue(repository.save(loaded.state))
            assertEquals(3, JSONObject(file.text).getInt("schema"))
            assertEquals(loaded.state, StationsRepository(file).load().state)
        }
    }

    @Test fun unknownRegionIsLoadedWithoutDiscardingUserData() {
        val root = JSONObject(document(3, "unknown-region"))
        root.getJSONObject("regions").remove("moskva")
        val file = MemoryFile(root.toString())
        val loaded = StationsRepository(file).load()
        assertEquals(LoadStatus.LOADED, loaded.status)
        assertEquals("unknown-region", loaded.state.selectedRegion)
        assertEquals(listOf(StationEntry(90300, "Моя", "custom")),
            loaded.state.regionSelections("unknown-region").active.entries)
        assertEquals(loaded.state, StationsJson.decode(StationsJson.encode(loaded.state)))
    }

    @Test fun defaultsStoreNoRegions() {
        val state = StationsState.defaults()
        assertEquals("moskva", state.selectedRegion)
        assertTrue(state.regions.isEmpty())
        val region = state.regionSelections("new-region")
        assertEquals("Основной", region.activeName)
        assertTrue(region.active.entries.isEmpty())
        assertTrue(state.regions.isEmpty())
        val populated = region.replaceActive(region.active.add(StationEntry(90300)).value!!)
        val changed = state.copy(regions = mapOf("new-region" to populated))
        assertEquals(setOf("new-region"), StationsJson.decode(StationsJson.encode(changed)).regions.keys)
        assertEquals(0, JSONObject(StationsJson.encode(state)).getJSONObject("regions").length())
    }

    private fun document(schema: Int, id: String) = """{
        "schema":$schema,"selectedRegion":"$id","lastStation":{"band":"FM","frequencyKhz":97700},
        "lastPlayed":${schema >= 2},"regions":{
          "moskva":{"activeName":"Основной","sets":[{"name":"Основной","entries":[]}]},
          "$id":{"activeName":"Дорога","sets":[{"name":"Основной","entries":[]},
            {"name":"Дорога","entries":[{"band":"FM","frequencyKhz":90300,"name":"Моя","catalogId":"custom"}]}]}}}
    """

    private class MemoryFile(var text: String) : StationsFile {
        override fun read() = text
        override fun writeAtomic(text: String) { this.text = text }
    }
}
