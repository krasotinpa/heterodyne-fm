package fm.heterodyne.app.stations

import java.util.Collections
import org.json.JSONObject

enum class Band { FM, AM }
data class Region(val id: String, val name: String)
data class CatalogSource(val text: String, val url: String)
data class CatalogStation(val id: String, val name: String, val band: Band, val frequencyKhz: Int) {
    fun copyEntry() = StationEntry(band, frequencyKhz, name, id)
}
data class StationEntry(val band: Band, val frequencyKhz: Int, val name: String? = null, val catalogId: String? = null)

data class Catalog(
    val schema: Int,
    val revision: String,
    val region: Region,
    val source: CatalogSource,
    val stations: List<CatalogStation>,
) {
    companion object {
        fun parse(text: String): Catalog {
            val root = JSONObject(text)
            require(root.getInt("schema") == 1)
            val region = root.getJSONObject("region")
            val source = root.getJSONObject("source")
            val rows = root.getJSONArray("stations")
            val stations = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                CatalogStation(row.getString("id"), row.getString("name"), Band.valueOf(row.getString("band")), row.getInt("frequencyKhz"))
            }
            require(stations.map { it.id }.distinct().size == stations.size)
            return Catalog(1, root.getString("revision"), Region(region.getString("id"), region.getString("name")),
                CatalogSource(source.getString("text"), source.getString("url")), Collections.unmodifiableList(stations))
        }
    }
}

enum class ChangeError { DUPLICATE_NAME, INVALID_NAME, DUPLICATE_FREQUENCY, NOT_FOUND, LAST_SELECTION }
data class Change<out T>(val value: T? = null, val error: ChangeError? = null)

// Private construction keeps duplicate entries out of every aggregate instance.
@ConsistentCopyVisibility
data class StationSelection private constructor(val name: String, val entries: List<StationEntry>) {
    init {
        require(name.isNotBlank())
        require(entries.distinctBy { it.band to it.frequencyKhz }.size == entries.size)
    }
    fun add(entry: StationEntry): Change<StationSelection> {
        if (entries.any { it.band == entry.band && it.frequencyKhz == entry.frequencyKhz }) {
            return Change(error = ChangeError.DUPLICATE_FREQUENCY)
        }
        return Change(StationSelection(name, Collections.unmodifiableList(entries + entry.copy())))
    }
    fun rename(name: String): Change<StationSelection> = if (name.isBlank()) Change(error = ChangeError.INVALID_NAME)
        else Change(StationSelection(name, entries))

    fun remove(entry: StationEntry): Change<StationSelection> {
        val index = entries.indexOfFirst { it.band == entry.band && it.frequencyKhz == entry.frequencyKhz }
        if (index < 0) return Change(error = ChangeError.NOT_FOUND)
        return Change(StationSelection(name, Collections.unmodifiableList(entries.filterIndexed { i, _ -> i != index })))
    }
    fun move(from: Int, to: Int): Change<StationSelection> {
        if (from !in entries.indices || to !in entries.indices) return Change(error = ChangeError.NOT_FOUND)
        val reordered = entries.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        return Change(StationSelection(name, Collections.unmodifiableList(reordered)))
    }
    companion object {
        fun empty(name: String): StationSelection = StationSelection(name, emptyList())
    }
}

@ConsistentCopyVisibility
data class RegionSelections private constructor(val selections: List<StationSelection>, val activeName: String) {
    init {
        require(selections.isNotEmpty())
        require(selections.map { it.name }.distinct().size == selections.size)
        require(selections.count { it.name == activeName } == 1)
    }
    val active: StationSelection get() = selections.first { it.name == activeName }
    fun createSelection(name: String): Change<RegionSelections> = when {
        name.isBlank() -> Change(error = ChangeError.INVALID_NAME)
        selections.any { it.name == name } -> Change(error = ChangeError.DUPLICATE_NAME)
        else -> Change(RegionSelections(Collections.unmodifiableList(selections + StationSelection.empty(name)), activeName))
    }
    fun activate(name: String): Change<RegionSelections> = if (selections.none { it.name == name }) {
        Change(error = ChangeError.NOT_FOUND)
    } else Change(RegionSelections(selections, name))
    fun replaceActive(selection: StationSelection): RegionSelections {
        require(selection.name == activeName)
        return RegionSelections(Collections.unmodifiableList(selections.map { if (it.name == activeName) selection else it }), activeName)
    }
    fun rename(name: String, newName: String): Change<RegionSelections> = when {
        selections.none { it.name == name } -> Change(error = ChangeError.NOT_FOUND)
        newName.isBlank() -> Change(error = ChangeError.INVALID_NAME)
        newName != name && selections.any { it.name == newName } -> Change(error = ChangeError.DUPLICATE_NAME)
        else -> Change(RegionSelections(Collections.unmodifiableList(selections.map {
            if (it.name == name) it.rename(newName).value!! else it
        }), if (activeName == name) newName else activeName))
    }
    fun delete(name: String): Change<RegionSelections> = when {
        selections.none { it.name == name } -> Change(error = ChangeError.NOT_FOUND)
        selections.size == 1 -> Change(error = ChangeError.LAST_SELECTION)
        else -> {
            val remaining = selections.filter { it.name != name }
            Change(RegionSelections(Collections.unmodifiableList(remaining),
                if (activeName == name) remaining.first().name else activeName))
        }
    }
    companion object {
        internal fun restore(selections: List<StationSelection>, activeName: String) = RegionSelections(Collections.unmodifiableList(selections.toList()), activeName)
        fun defaults() = RegionSelections(listOf(StationSelection.empty("Основной")), "Основной")
    }
}

data class SessionStation(val band: Band, val frequencyKhz: Int) {
    init {
        require(fm.heterodyne.app.module.isSelectableFrequency(
            fm.heterodyne.app.module.Band.valueOf(band.name), frequencyKhz))
    }
}

data class StationsState(
    val selectedRegion: String,
    val regions: Map<String, RegionSelections>,
    val lastStation: SessionStation? = null,
    val band: Band = Band.FM,
) {
    init {
        require(regions.keys == setOf("moskva", "yaroslavl"))
        require(selectedRegion in regions)
    }
    companion object {
        fun defaults() = StationsState("moskva", mapOf("moskva" to RegionSelections.defaults(), "yaroslavl" to RegionSelections.defaults()))
    }
}
