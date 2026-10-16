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
import fm.heterodyne.app.stations.Band
import fm.heterodyne.app.stations.SessionStation
import fm.heterodyne.app.module.TuneTarget
import fm.heterodyne.app.module.TuneResult
import fm.heterodyne.app.module.isSelectableFrequency
import fm.heterodyne.app.module.Band as ModuleBand
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

    lateinit var developerMode: DeveloperMode
        private set
    var exportBusy by mutableStateOf(false)
        private set
    var diagnosticMessage by mutableStateOf<String?>(null)
        private set
    val diagnosticFrames get() = usb.frames
    fun readJournal(done: (String) -> Unit) { usb.readJournal { text -> main.post { done(text) } } }
    fun beginExport(ready: () -> Unit) {
        if (exportBusy) return
        exportBusy = true
        usb.beginExport { success -> main.post {
            if (success) ready() else {
                exportBusy = false
                diagnosticMessage = "Не удалось отключить Модуль"
            }
        } }
    }
    fun finishExport(uri: android.net.Uri?) {
        usb.finishExport(uri?.let { target -> {
            contentResolver.openOutputStream(target, "wt") ?: throw java.io.IOException()
        } }) { error -> main.post {
            exportBusy = false
            diagnosticMessage = error ?: if (uri == null) "Экспорт отменён" else "Журнал сохранён"
        } }
    }
    fun diagnosticCommand(command: fm.heterodyne.app.usb.DiagnosticCommand) {
        if (!developerMode.enabled || exportBusy) return
        usb.diagnosticCommand(command) { result -> main.post { diagnosticMessage = "${command.label}: $result" } }
    }

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
        val preferences = getSharedPreferences("diagnostics", MODE_PRIVATE)
        developerMode = DeveloperMode(preferences.getBoolean("developer", false)) {
            preferences.edit().putBoolean("developer", it).apply()
        }
        usb = fm.heterodyne.app.usb.AndroidUsbAdapter(this, stationChanged = { band, frequency ->
            main.post {
                currentStation = if (frequency == null || band == fm.heterodyne.app.module.Band.DAB) null
                else fm.heterodyne.app.stations.StationEntry(fm.heterodyne.app.stations.Band.valueOf(band.name), frequency)
            }
            if (frequency != null && isSelectableFrequency(band, frequency)) {
                storage.execute {
                    val state = current.state ?: return@execute
                    val station = SessionStation(Band.valueOf(band.name), frequency)
                    if (save(state.copy(lastStation = station, band = station.band))) {
                        usb.restoreStation(TuneTarget(band, frequency))
                    }
                }
            }
        }, changed = { state ->
            main.post {
                moduleState = state
                if (state == fm.heterodyne.app.module.ConnectionState.Disconnected) currentStation = null
            }
        }, audioChanged = { counters -> main.post { audioState = counters.state } })
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
            val station = current.state?.lastStation
            usb.restoreStation(station?.let { TuneTarget(ModuleBand.valueOf(it.band.name), it.frequencyKhz) })
            main.post { usb.start() }
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

    fun selectSet(name: String) {
        storage.execute {
            val state = current.state ?: return@execute
            val region = state.regions.getValue(state.selectedRegion)
            val next = region.activate(name).value ?: return@execute
            save(state.copy(regions = state.regions + (state.selectedRegion to next)))
        }
    }

    fun selectStation(band: Band, frequencyKhz: Int) {
        val moduleBand = ModuleBand.valueOf(band.name)
        if (!isSelectableFrequency(moduleBand, frequencyKhz)) return
        // Cancel on the USB queue before storage I/O can delay the user's intent.
        usb.userSelectionPending()
        storage.execute {
            val state = current.state ?: return@execute
            val station = SessionStation(band, frequencyKhz)
            if (!save(state.copy(lastStation = station, band = band))) return@execute
            usb.restoreStation(TuneTarget(moduleBand, frequencyKhz))
            usb.select(moduleBand, frequencyKhz) { result ->
                if (result == TuneResult.Sent || result == TuneResult.RateLimited) main.post { startAudio() }
            }
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
