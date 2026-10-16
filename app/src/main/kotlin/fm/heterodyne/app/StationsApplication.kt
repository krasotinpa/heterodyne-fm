package fm.heterodyne.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fm.heterodyne.app.stations.AtomicStationsFile
import fm.heterodyne.app.stations.Catalog
import fm.heterodyne.app.stations.LoadStatus
import fm.heterodyne.app.stations.StationsRepository
import fm.heterodyne.app.stations.StationsState
import java.io.File
import java.util.concurrent.Executors

data class StationsScreenState(
    val state: StationsState? = null,
    val catalogs: List<Catalog> = emptyList(),
    val recovered: Boolean = false,
    val error: String? = null,
)

/** Owns one repository and one queue for the lifetime of this application process. */
class StationsApplication : Application() {
    private val storage = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    // One USB owner survives Activity recreation; process death releases the OS connection.
    private lateinit var usb: fm.heterodyne.app.usb.AndroidUsbAdapter
    var moduleState by mutableStateOf(fm.heterodyne.app.module.ConnectionState.Disconnected)
        private set

    fun retryModulePermission() { usb.retry() }

    private lateinit var repository: StationsRepository
    // Only the storage queue reads or changes these values.
    private var current = StationsScreenState()
    var screen by mutableStateOf(StationsScreenState())
        private set

    override fun onCreate() {
        super.onCreate()
        usb = fm.heterodyne.app.usb.AndroidUsbAdapter(this) { state ->
            main.post { moduleState = state }
        }
        usb.start()
        repository = StationsRepository(AtomicStationsFile(File(filesDir, "stations.json")))
        storage.execute {
            try {
                val catalogs = listOf("moskva", "yaroslavl").map { id ->
                    assets.open("catalogs/$id.json").bufferedReader().use { Catalog.parse(it.readText()) }
                }
                val loaded = repository.load()
                current = StationsScreenState(loaded.state, catalogs, loaded.status == LoadStatus.RECOVERED)
            } catch (_: Exception) {
                current = current.copy(error = "Не удалось прочитать Справочники")
            }
            publish()
        }
    }

    fun selectRegion(id: String) {
        storage.execute {
            val state = current.state ?: return@execute
            if (id !in state.regions) return@execute
            val next = state.copy(selectedRegion = id)
            current = if (repository.save(next)) current.copy(state = next, error = null)
            else current.copy(error = "Не удалось сохранить Регион")
            publish()
        }
    }

    private fun publish() {
        val snapshot = current
        main.post { screen = snapshot }
    }
}
