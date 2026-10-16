package fm.heterodyne.app.stations

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class StationsRecoveryTest {
    @Test fun originalIsCopiedBeforeFirstRecoveryWriteAndNeverReplaced() {
        val directory = Files.createTempDirectory("stations-recovery").toFile()
        try {
            val file = File(directory, "stations.json").apply { writeText("broken original") }
            val repository = StationsRepository(AtomicStationsFile(file))
            assertEquals(LoadStatus.RECOVERED, repository.load().status)
            assertEquals(StationsState.defaults(), repository.load().state)
            assertEquals("broken original", file.readText())
            assertFalse(File(directory, "stations.json.corrupt").exists())
            assertTrue(repository.save(StationsState.defaults()))
            assertEquals("broken original", File(directory, "stations.json.corrupt").readText())
            file.writeText("another damaged file")
            val second = StationsRepository(AtomicStationsFile(file))
            assertEquals(LoadStatus.RECOVERED, second.load().status)
            assertTrue(second.save(StationsState.defaults()))
            assertEquals("broken original", File(directory, "stations.json.corrupt").readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun transientReadFailureDoesNotWriteAndLoadRunsOnce() {
        var reads = 0
        var writes = 0
        val repository = StationsRepository(object : StationsFile {
            override fun read(): String { reads++; throw IOException("temporary") }
            override fun writeAtomic(text: String) { writes++ }
            override fun preserveCorrupt() { throw IOException("still unreadable") }
        })
        assertEquals(LoadStatus.RECOVERED, repository.load().status)
        assertEquals(StationsState.defaults(), repository.load().state)
        assertEquals(1, reads)
        assertEquals(0, writes)
        assertFalse(repository.save(StationsState.defaults()))
        assertEquals(0, writes)
    }

    @Test fun absentAndValidFilesHaveDistinctLoadResults() {
        val directory = Files.createTempDirectory("stations-missing").toFile()
        try {
            val file = File(directory, "stations.json")
            val repository = StationsRepository(AtomicStationsFile(file))
            assertEquals(LoadStatus.MISSING, repository.load().status)
            assertTrue(repository.save(StationsState.defaults()))
            assertEquals(LoadStatus.LOADED, StationsRepository(AtomicStationsFile(file)).load().status)
            assertFalse(File(directory, "stations.json.corrupt").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun twoFileInstancesSerializeReplacementOnSamePath() {
        val directory = Files.createTempDirectory("stations-concurrent").toFile()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val file = File(directory, "stations.json")
            val first = AtomicStationsFile(file)
            val second = AtomicStationsFile(file)
            val states = listOf(StationsState.defaults(), StationsState.defaults().copy(selectedRegion = "yaroslavl"))
            val tasks = listOf(first, second).mapIndexed { index, port ->
                workers.submit { repeat(50) { port.writeAtomic(StationsJson.encode(states[index])) } }
            }
            tasks.forEach { it.get() }
            assertTrue(StationsJson.decode(file.readText()) in states)
            assertFalse(File(directory, "stations.json.tmp").exists())
        } finally { workers.shutdownNow(); directory.deleteRecursively() }
    }
}
