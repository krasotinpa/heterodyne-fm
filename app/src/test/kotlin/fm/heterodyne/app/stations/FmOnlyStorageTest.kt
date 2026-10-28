package fm.heterodyne.app.stations

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FmOnlyStorageTest {
    @Test fun catalogDiscardsAllNonFmRowsBeforeReadingTheirFields() {
        val catalog = Catalog.parse("""{
            "schema":1,"revision":"test","region":{"id":"moskva","name":"Москва"},
            "source":{"text":"test","url":"https://example.com"},"stations":[
                {"id":"fm","name":"FM","band":"FM","frequencyKhz":90300},
                {"band":"AM"},{"band":"DAB"}]
        }""")
        assertEquals(listOf(CatalogStation("fm", "FM", 90300)), catalog.stations)
    }

    @Test fun legacyAmEntriesAndLastPlayedAreDiscardedWithoutRecovery() {
        val root = JSONObject(StationsJson.encode(StationsState.defaults()))
        val entries = root.getJSONObject("regions").getJSONObject("moskva")
            .getJSONArray("sets").getJSONObject(0).getJSONArray("entries")
        entries.put(JSONObject("""{"band":"FM","frequencyKhz":90300,"name":"FM"}"""))
        entries.put(JSONObject("""{"band":"AM","frequencyKhz":855}"""))
        root.put("lastStation", JSONObject("""{"band":"AM","frequencyKhz":855}"""))
        root.put("lastPlayed", true)
        val original = root.toString()
        val file = object : StationsFile {
            override fun read() = original
            override fun writeAtomic(text: String) { fail("Loading must not migrate the file") }
        }
        val loaded = StationsRepository(file).load()
        assertEquals(LoadStatus.LOADED, loaded.status)
        assertEquals(listOf(90300), loaded.state.regions.getValue("moskva").active.entries.map { it.frequencyKhz })
        assertNull(loaded.state.lastStation)
        assertFalse(loaded.state.lastPlayed)
    }
}
