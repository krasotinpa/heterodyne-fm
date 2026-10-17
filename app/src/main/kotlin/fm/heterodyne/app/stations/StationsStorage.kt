package fm.heterodyne.app.stations

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

object StationsJson {
    fun encode(state: StationsState): String = JSONObject().put("schema", 2)
        .put("selectedRegion", state.selectedRegion)
        .put("band", state.band.name)
        .put("lastStation", state.lastStation?.let {
            JSONObject().put("band", it.band.name).put("frequencyKhz", it.frequencyKhz)
        } ?: JSONObject.NULL)
        .put("regions", JSONObject().apply {
            state.regions.forEach { (id, region) ->
                put(id, JSONObject().put("activeName", region.activeName).put("sets", JSONArray().apply {
                    region.selections.forEach { selection ->
                        put(JSONObject().put("name", selection.name).put("entries", JSONArray().apply {
                            selection.entries.forEach { entry ->
                                put(JSONObject().put("band", entry.band.name).put("frequencyKhz", entry.frequencyKhz)
                                    .put("name", entry.name).put("catalogId", entry.catalogId))
                            }
                        }))
                    }
                }))
            }
        }).toString()

    // Invalid or incomplete documents are rejected as a whole by the repository.
    fun decode(text: String): StationsState {
        val root = JSONObject(text)
        val schemaValue = root.get("schema")
        require(schemaValue is Number && schemaValue.toDouble() == schemaValue.toInt().toDouble())
        val schema = schemaValue.toInt()
        require(schema == 1 || schema == 2)
        val regionsJson = root.getJSONObject("regions")
        require(regionsJson.keys().asSequence().toSet() == setOf("moskva", "yaroslavl"))
        val regions = listOf("moskva", "yaroslavl").associateWith { id ->
            val region = regionsJson.getJSONObject(id)
            val selections = region.getJSONArray("sets")
            require(selections.length() > 0)
            val names = mutableSetOf<String>()
            val restoredSelections = (0 until selections.length()).map { index ->
                val selection = selections.getJSONObject(index)
                val name = selection.getString("name")
                require(name.isNotBlank() && names.add(name))
                var populated = StationSelection.empty(name)
                val entries = selection.getJSONArray("entries")
                for (entryIndex in 0 until entries.length()) {
                    val entry = entries.getJSONObject(entryIndex)
                    val frequency = entry.get("frequencyKhz")
                    require(frequency is Number && frequency.toDouble() == frequency.toInt().toDouble())
                    populated = requireNotNull(populated.add(StationEntry(
                        Band.valueOf(entry.getString("band")), frequency.toInt(),
                        if (entry.has("name") && !entry.isNull("name")) entry.getString("name") else null,
                        if (entry.has("catalogId") && !entry.isNull("catalogId")) entry.getString("catalogId") else null,
                    )).value)
                }
                populated
            }
            RegionSelections.restore(restoredSelections, region.getString("activeName"))
        }
        val lastStation = if (schema == 2 && !root.isNull("lastStation")) {
            val station = root.getJSONObject("lastStation")
            val frequency = station.get("frequencyKhz")
            require(frequency is Number && frequency.toDouble() == frequency.toInt().toDouble())
            SessionStation(Band.valueOf(station.getString("band")), frequency.toInt())
        } else null
        if (schema == 2) require(root.has("lastStation"))
        return StationsState(root.getString("selectedRegion"), regions, lastStation,
            if (schema == 2) Band.valueOf(root.getString("band")) else Band.FM)
    }
}

interface StationsFile {
    /** Null means absent; all other read failures must throw. */
    fun read(): String?
    fun writeAtomic(text: String)
    fun preserveCorrupt() { throw java.io.IOException("Backup is not supported") }
}

/** All instances share a process lock, including instances targeting the same path. */
class AtomicStationsFile(private val file: File) : StationsFile {
    override fun read(): String? = synchronized(ioLock) {
        try {
            String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
        } catch (_: java.nio.file.NoSuchFileException) {
            null
        }
    }

    override fun preserveCorrupt(): Unit = synchronized(ioLock) {
        val backup = File(file.parentFile, file.name + ".corrupt")
        if (!backup.exists()) {
            // Copy bytes, rather than decoded JSON, to preserve the original evidence.
            val temporary = File.createTempFile("stations-corrupt-", ".tmp", file.parentFile)
            try {
                Files.copy(file.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Files.move(temporary.toPath(), backup.toPath())
            } finally {
                temporary.delete()
            }
        }
        check(backup.isFile) { "Backup is not a file" }
    }

    override fun writeAtomic(text: String): Unit = synchronized(ioLock) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        val ioLock = Any()
    }
}

enum class LoadStatus { MISSING, LOADED, RECOVERED }
data class StationsLoad(val state: StationsState, val status: LoadStatus)

/** The process owner serializes load, change and save on its single queue. */
class StationsRepository(private val file: StationsFile) {
    private var loaded: StationsLoad? = null

    fun load(): StationsLoad {
        loaded?.let { return it }
        val result = try {
            val text = file.read()
            if (text == null) StationsLoad(StationsState.defaults(), LoadStatus.MISSING)
            else StationsLoad(StationsJson.decode(text), LoadStatus.LOADED)
        } catch (_: Exception) {
            StationsLoad(StationsState.defaults(), LoadStatus.RECOVERED)
        }
        loaded = result
        return result
    }

    fun save(state: StationsState): Boolean = try {
        if (load().status == LoadStatus.RECOVERED) file.preserveCorrupt()
        file.writeAtomic(StationsJson.encode(state))
        loaded = StationsLoad(state, LoadStatus.LOADED)
        true
    } catch (_: Exception) {
        false
    }
}
