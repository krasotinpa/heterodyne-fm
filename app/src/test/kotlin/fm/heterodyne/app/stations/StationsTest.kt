package fm.heterodyne.app.stations

import org.json.JSONObject
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class StationsTest {
    @Test fun catalogsRespectSchemaAndFrequencyGrid() {
        for (id in listOf("moskva", "yaroslavl")) {
            val file = File("src/main/assets/catalogs/$id.json")
            val raw = file.readText()
            assertFalse(raw.contains("\"cid\""))
            val catalog = Catalog.parse(raw)
            assertEquals(1, catalog.schema)
            assertEquals(id, catalog.region.id)
            assertEquals(catalog.stations.size, catalog.stations.map { it.id }.toSet().size)
            assertTrue(catalog.stations.isNotEmpty())
            catalog.stations.forEach {
                assertTrue(it.frequencyKhz in 87500..108000)
                assertEquals(0, it.frequencyKhz % 100)
                assertTrue((it.frequencyKhz - 87500) / 100 in 0..205)
            }
        }
    }
}

class StationSelectionsTest {
    @Test fun defaultsAndRegionInvariants() {
        val state = StationsState.defaults()
        assertEquals("moskva", state.selectedRegion)
        val region = state.regionSelections("moskva")
        assertEquals("Основной", region.active.name)
        val added = region.createSelection("Дорога").value!!
        assertEquals(listOf("Основной", "Дорога"), added.selections.map { it.name })
        assertEquals(ChangeError.DUPLICATE_NAME, added.createSelection("Дорога").error)
        assertEquals(ChangeError.INVALID_NAME, added.createSelection("  ").error)
        assertEquals("Дорога", added.activate("Дорога").value!!.active.name)
        assertEquals(ChangeError.NOT_FOUND, added.activate("Нет").error)
        assertEquals("Основной", state.regionSelections("yaroslavl").active.name)
    }

    @Test fun entriesAreIndependentOrderedCopiesAndRejectDuplicatePairs() {
        val station = Catalog.parse(File("src/main/assets/catalogs/moskva.json").readText()).stations.first()
        val first = StationSelection.empty("Основной").add(station.copyEntry()).value!!
        assertNotSame(station, first.entries.first())
        assertEquals(station.id, first.entries.first().catalogId)
        val renamed = station.copy(name = "Новое имя")
        assertEquals(station.name, first.entries.first().name)
        assertNotEquals(renamed.name, first.entries.first().name)
        assertEquals(ChangeError.DUPLICATE_FREQUENCY, first.add(station.copyEntry().copy(name = "Другое")).error)
        val am = StationEntry(97700, "Другая")
        val next = first.add(am).value!!
        assertEquals(listOf(station.copyEntry(), am), next.entries)
        assertEquals(1, first.entries.size)
    }
}

class StationsStorageTest {
    @Test fun roundTripPreservesRegionsActiveSelectionAndEntries() {
        val initial = StationsState.defaults()
        val region = initial.regionSelections("yaroslavl").createSelection("Дорога").value!!.activate("Дорога").value!!
        val populated = region.replaceActive(region.active.add(StationEntry(97700)).value!!)
        val state = initial.copy(selectedRegion = "yaroslavl", regions = initial.regions + ("yaroslavl" to populated))
        assertEquals(state, StationsJson.decode(StationsJson.encode(state)))
        assertFalse(StationsJson.encode(state).contains("\"cid\""))
    }

    @Test fun corruptOrIncompleteStorageFallsBack() {
        val valid = StationsJson.encode(StationsState.defaults().copy(regions = mapOf("moskva" to RegionSelections.defaults())))
        val duplicate = valid.replace("\"sets\":[", "\"sets\":[{\"name\":\"Основной\",\"entries\":[]},")
        for (raw in listOf("garbage", "", "{}", "{\"schema\":1}", duplicate)) {
            val repository = StationsRepository(object : StationsFile {
                override fun read() = raw
                override fun writeAtomic(text: String) = Unit
            })
            assertEquals(StationsState.defaults(), repository.load().state)
        }
        val unreadable = StationsRepository(object : StationsFile {
            override fun read(): String = throw java.io.IOException("unreadable")
            override fun writeAtomic(text: String) = Unit
        })
        assertEquals(StationsState.defaults(), unreadable.load().state)
    }

    @Test fun duplicateEntriesAndMissingActiveSelectionRejectEntireDocument() {
        val state = StationsState.defaults().copy(regions = mapOf("moskva" to RegionSelections.defaults()))
        for (case in listOf("duplicate", "active", "entries")) {
            val root = JSONObject(StationsJson.encode(state))
            val regions = root.getJSONObject("regions")
            val region = regions.getJSONObject("moskva")
            val selection = region.getJSONArray("sets").getJSONObject(0)
            when (case) {
                "duplicate" -> {
                    val entry = JSONObject().put("band", "FM").put("frequencyKhz", 87500)
                    selection.getJSONArray("entries").put(entry).put(entry)
                }
                "active" -> region.put("activeName", "Нет")
                "region" -> regions.remove("yaroslavl")
                "entries" -> selection.remove("entries")
            }
            val repository = StationsRepository(object : StationsFile {
                override fun read() = root.toString()
                override fun writeAtomic(text: String) = Unit
            })
            assertEquals(LoadStatus.RECOVERED, repository.load().status)
            assertEquals(StationsState.defaults(), repository.load().state)
        }
    }

    @Test fun fileReplacementPersistsAndFailureLeavesPreviousFile() {
        val directory = java.nio.file.Files.createTempDirectory("stations").toFile()
        try {
            val target = File(directory, "stations.json")
            val repository = StationsRepository(AtomicStationsFile(target))
            assertTrue(repository.save(StationsState.defaults()))
            val changed = StationsState.defaults().copy(selectedRegion = "yaroslavl")
            assertTrue(repository.save(changed))
            assertEquals(changed, repository.load().state)
            assertEquals(listOf("stations.json"), directory.list()!!.toList())
            // A directory at the temporary path prevents writing before rename.
            File(directory, "stations.json.tmp").mkdir()
            File(directory, "stations.json.tmp/block").writeText("block")
            assertFalse(repository.save(StationsState.defaults()))
            assertEquals(changed, repository.load().state)
        } finally { directory.deleteRecursively() }
    }
}
