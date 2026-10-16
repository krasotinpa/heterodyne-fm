package fm.heterodyne.app

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fm.heterodyne.app.audio.AudioState
import fm.heterodyne.app.audio.PlaybackService
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
    var audioState by mutableStateOf(AudioState.Stopped)
        private set
    var currentStation by mutableStateOf<fm.heterodyne.app.stations.StationEntry?>(null)
        private set
    val activeEntries get() = screen.state?.let { it.regions.getValue(it.selectedRegion).active.entries }.orEmpty()
    val audioCounters get() = usb.audioCounters

    fun retryModulePermission() { usb.retry() }
    fun startAudio() { startForegroundService(Intent(this, PlaybackService::class.java)) }
    fun playAudio(stopped: () -> Unit) { usb.playAudio(stopped) }
    fun selectStation(entry: fm.heterodyne.app.stations.StationEntry) { usb.selectStation(entry) }
    fun stepStation(direction: fm.heterodyne.app.module.Direction) { usb.stepStation(direction) }
    fun reclaimAudioFocus(completed: () -> Unit) { usb.reclaimAudioFocus(completed) }
    fun stopAudio() { usb.stopAudio() }

    private lateinit var repository: StationsRepository
    // Only the storage queue reads or changes these values.
    private var current = StationsScreenState()
    var screen by mutableStateOf(StationsScreenState())
        private set

    override fun onCreate() {
        super.onCreate()
        usb = fm.heterodyne.app.usb.AndroidUsbAdapter(this, stationChanged = { band, khz ->
            main.post {
                currentStation = if (khz == null || band == fm.heterodyne.app.module.Band.DAB) null
                else fm.heterodyne.app.stations.StationEntry(fm.heterodyne.app.stations.Band.valueOf(band.name), khz)
            }
        }, changed = { state ->
            main.post {
                moduleState = state
                if (state == fm.heterodyne.app.module.ConnectionState.Disconnected) currentStation = null
            }
        }, audioChanged = { counters -> main.post { audioState = counters.state } })
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
