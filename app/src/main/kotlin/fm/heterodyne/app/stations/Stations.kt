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

enum class ChangeError { DUPLICATE_NAME, INVALID_NAME, DUPLICATE_FREQUENCY, NOT_FOUND }
data class Change<out T>(val value: T? = null, val error: ChangeError? = null)

// Private construction keeps duplicate entries out of every aggregate instance.
@ConsistentCopyVisibility
data class StationSet private constructor(val name: String, val entries: List<StationEntry>) {
    init {
        require(name.isNotBlank())
        require(entries.distinctBy { it.band to it.frequencyKhz }.size == entries.size)
    }
    fun add(entry: StationEntry): Change<StationSet> {
        if (entries.any { it.band == entry.band && it.frequencyKhz == entry.frequencyKhz }) {
            return Change(error = ChangeError.DUPLICATE_FREQUENCY)
        }
        return Change(StationSet(name, Collections.unmodifiableList(entries + entry.copy())))
    }
    companion object {
        fun empty(name: String): StationSet = StationSet(name, emptyList())
    }
}

@ConsistentCopyVisibility
data class RegionSets private constructor(val sets: List<StationSet>, val activeName: String) {
    init {
        require(sets.isNotEmpty())
        require(sets.map { it.name }.distinct().size == sets.size)
        require(sets.count { it.name == activeName } == 1)
    }
    val active: StationSet get() = sets.first { it.name == activeName }
    fun createSet(name: String): Change<RegionSets> = when {
        name.isBlank() -> Change(error = ChangeError.INVALID_NAME)
        sets.any { it.name == name } -> Change(error = ChangeError.DUPLICATE_NAME)
        else -> Change(RegionSets(Collections.unmodifiableList(sets + StationSet.empty(name)), activeName))
    }
    fun activate(name: String): Change<RegionSets> = if (sets.none { it.name == name }) {
        Change(error = ChangeError.NOT_FOUND)
    } else Change(RegionSets(sets, name))
    fun replaceActive(set: StationSet): RegionSets {
        require(set.name == activeName)
        return RegionSets(Collections.unmodifiableList(sets.map { if (it.name == activeName) set else it }), activeName)
    }
    companion object {
        internal fun restore(sets: List<StationSet>, activeName: String) = RegionSets(Collections.unmodifiableList(sets.toList()), activeName)
        fun defaults() = RegionSets(listOf(StationSet.empty("Основной")), "Основной")
    }
}

data class StationsState(val selectedRegion: String, val regions: Map<String, RegionSets>) {
    init {
        require(regions.keys == setOf("moskva", "yaroslavl"))
        require(selectedRegion in regions)
    }
    companion object {
        fun defaults() = StationsState("moskva", mapOf("moskva" to RegionSets.defaults(), "yaroslavl" to RegionSets.defaults()))
    }
}
