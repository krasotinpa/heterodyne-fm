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
import java.util.concurrent.Executors

data class StationsScreenState(
    val state: StationsState? = null,
    val catalogs: List<Catalog> = emptyList(),
    val recovered: Boolean = false,
    val error: String? = null,
)

/** Owns the repository and its single storage queue for this process. */
class StationsSession(private val context: android.content.Context, private val main: Handler) {
    private val storage = Executors.newSingleThreadExecutor()
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
