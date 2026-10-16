package fm.heterodyne.app

import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import fm.heterodyne.app.stations.AtomicStationsFile
import fm.heterodyne.app.stations.Catalog
import fm.heterodyne.app.stations.LoadStatus
import fm.heterodyne.app.stations.StationsRepository
import fm.heterodyne.app.stations.StationsState
import fm.heterodyne.app.stations.Band as StationBand
import fm.heterodyne.app.stations.SessionStation
import fm.heterodyne.app.module.TuneTarget
import fm.heterodyne.app.module.Band
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class StationsScreenState(
    val state: StationsState? = null,
    val catalogs: List<Catalog> = emptyList(),
    val recovered: Boolean = false,
    val error: String? = null,
)

/** Owns the repository and its single storage queue for this process. */
class StationsSession(
    private val context: android.content.Context,
    private val main: Handler,
    private val storage: Executor = Executors.newSingleThreadExecutor(),
) {
    private val repository = StationsRepository(AtomicStationsFile(File(context.filesDir, "stations.json")))
    // Only the storage queue reads or changes these values.
    private var current = StationsScreenState()
    var screen by mutableStateOf(StationsScreenState())
        private set
    val activeEntries get() = screen.state?.let { it.regions.getValue(it.selectedRegion).active.entries }.orEmpty()

    fun activateSet(name: String) {
        storage.execute {
            val state = current.state ?: return@execute
            val region = state.regions.getValue(state.selectedRegion).activate(name).value ?: return@execute
            save(state.copy(regions = state.regions + (state.selectedRegion to region)))
        }
    }

    fun createSet(regionId: String, name: String) = changeRegion(regionId) { it.createSet(name) }
    fun renameSet(regionId: String, name: String, newName: String) = changeRegion(regionId) { it.rename(name, newName) }
    fun deleteSet(regionId: String, name: String) = changeRegion(regionId) { it.delete(name) }
    fun addStation(regionId: String, name: String, entry: fm.heterodyne.app.stations.StationEntry) = changeSet(regionId, name) { it.add(entry) }
    fun removeStation(regionId: String, name: String, entry: fm.heterodyne.app.stations.StationEntry) = changeSet(regionId, name) { it.remove(entry) }
    fun moveStation(regionId: String, name: String, entry: fm.heterodyne.app.stations.StationEntry, to: Int) = changeSet(regionId, name) { set ->
        val from = set.entries.indexOfFirst { it.band == entry.band && it.frequencyKhz == entry.frequencyKhz }
        set.move(from, to)
    }

    private fun changeSet(regionId: String, name: String, change: (fm.heterodyne.app.stations.StationSet) -> fm.heterodyne.app.stations.Change<fm.heterodyne.app.stations.StationSet>) =
        changeRegion(regionId) { region ->
            val set = region.sets.firstOrNull { it.name == name }
            val result = set?.let(change)
            fm.heterodyne.app.stations.Change(result?.value?.let { updated ->
                fm.heterodyne.app.stations.RegionSets.restore(region.sets.map {
                    if (it.name == name) updated else it
                }, region.activeName)
            }, result?.error ?: if (set == null) fm.heterodyne.app.stations.ChangeError.NOT_FOUND else null)
        }

    private fun changeRegion(regionId: String, change: (fm.heterodyne.app.stations.RegionSets) -> fm.heterodyne.app.stations.Change<fm.heterodyne.app.stations.RegionSets>) {
        storage.execute {
            val state = current.state ?: return@execute
            if (regionId != state.selectedRegion) return@execute
            val result = change(state.regions.getValue(regionId))
            val region = result.value
            if (region != null) save(state.copy(regions = state.regions + (regionId to region)))
            else {
                val message = when (result.error) {
                    fm.heterodyne.app.stations.ChangeError.INVALID_NAME -> "Введите имя Набора"
                    fm.heterodyne.app.stations.ChangeError.DUPLICATE_NAME -> "Набор с таким именем уже есть"
                    fm.heterodyne.app.stations.ChangeError.DUPLICATE_FREQUENCY -> "Частота уже есть в Наборе"
                    fm.heterodyne.app.stations.ChangeError.LAST_SET -> "Нельзя удалить последний Набор"
                    else -> "Набор или Станция не найдены"
                }
                current = current.copy(error = message)
                publish()
            }
        }
    }

    fun load(loaded: (TuneTarget?) -> Unit) {
        storage.execute {
            try {
                val catalogs = listOf("moskva", "yaroslavl").map { id ->
                    context.assets.open("catalogs/$id.json").bufferedReader().use { Catalog.parse(it.readText()) }
                }
                val loaded = repository.load()
                current = StationsScreenState(loaded.state, catalogs, loaded.status == LoadStatus.RECOVERED)
            } catch (_: Exception) {
                current = current.copy(error = "Не удалось прочитать Справочники")
            }
            publish()
            val station = current.state?.lastStation
            loaded(station?.let { TuneTarget(Band.valueOf(it.band.name), it.frequencyKhz) })
        }
    }

    fun rememberStation(band: Band, frequency: Int, saved: () -> Unit) {
        storage.execute {
            val state = current.state ?: return@execute
            val station = SessionStation(StationBand.valueOf(band.name), frequency)
            if (save(state.copy(lastStation = station, band = station.band))) saved()
        }
    }

    fun selectRegion(id: String) {
        storage.execute {
            val state = current.state ?: return@execute
            if (id !in state.regions) return@execute
            val next = state.copy(selectedRegion = id)
            save(next)
        }
    }

    private fun save(state: StationsState): Boolean {
        if (state == current.state && !current.recovered && current.error == null) return true
        val saved = repository.save(state)
        current = if (saved) current.copy(state = state, error = null)
        else current.copy(error = "Не удалось сохранить состояние")
        publish()
        return saved
    }

    private fun publish() {
        val snapshot = current
        main.post { screen = snapshot }
    }
}
