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
import fm.heterodyne.app.stations.Band as StationBand
import fm.heterodyne.app.stations.SessionStation
import fm.heterodyne.app.module.TuneTarget
import fm.heterodyne.app.module.isSelectableFrequency
import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.ConnectionState
import fm.heterodyne.app.module.Direction
import fm.heterodyne.app.module.TransitionSnapshot
import fm.heterodyne.app.module.TransitionState
import fm.heterodyne.app.stations.StationEntry
import fm.heterodyne.app.usb.AndroidUsbAdapter
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
    private lateinit var usb: AndroidUsbAdapter
    var moduleState by mutableStateOf(ConnectionState.Disconnected)
        private set
    var audioState by mutableStateOf(AudioState.Stopped)
        private set
    var currentStation by mutableStateOf<fm.heterodyne.app.stations.StationEntry?>(null)
        private set
    val activeEntries get() = screen.state?.let { it.regions.getValue(it.selectedRegion).active.entries }.orEmpty()
    var transition by mutableStateOf(TransitionSnapshot(
        TransitionState.Disconnected, null, null))
        private set
    private var cursor: StationEntry? = null
    private val bandStations = mutableMapOf<Band, Int>()

    fun pickStation(entry: StationEntry) {
        if (moduleState != ConnectionState.Observing) return
        cursor = entry
        selectStation(entry)
        startAudio()
    }

    fun nextStation(direction: Direction) {
        val state = screen.state ?: return
        val entries = state.regions.getValue(state.selectedRegion).active.entries
        if (entries.isEmpty()) return
        val confirmed = transition.confirmed
        val index = entries.indexOfFirst {
            if (cursor != null) it.band == cursor?.band && it.frequencyKhz == cursor?.frequencyKhz
            else it.band.name == confirmed?.band?.name && it.frequencyKhz == confirmed?.frequencyKhz
        }
        val next = if (index < 0) {
            if (direction == Direction.Up) 0 else entries.lastIndex
        } else Math.floorMod(index + if (direction == Direction.Up) 1 else -1, entries.size)
        pickStation(entries[next])
    }

    fun step(direction: Direction) { cursor = null; stepStation(direction) }
    fun seek(direction: Direction) { cursor = null; usb.seek(direction) }
    fun checkConnection() { usb.checkConnection() }
    fun changeBand() {
        val current = transition.confirmed ?: return
        val next = if (current.band == Band.FM)
            Band.AM else Band.FM
        // select composes BAND and playSelect; no separate UI command or timing policy.
        val frequency = bandStations[next]?.takeIf {
            if (next == Band.AM) it in 711..1044 else it in 87500..108000
        } ?: if (next == Band.AM) 711 else 87500
        cursor = null
        usb.select(next, frequency)
    }

    fun activateSet(name: String) {
        cursor = null
        storage.execute {
            val state = current.state ?: return@execute
            val region = state.regions.getValue(state.selectedRegion).activate(name).value ?: return@execute
            save(state.copy(regions = state.regions + (state.selectedRegion to region)))
        }
    }

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
                    val station = SessionStation(StationBand.valueOf(band.name), frequency)
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
        }, audioChanged = { counters -> main.post { audioState = counters.state } },
            transitionObserved = { snapshot -> main.post {
                if (snapshot.target == null && cursor?.let {
                    it.band.name == snapshot.confirmed?.band?.name && it.frequencyKhz == snapshot.confirmed?.frequencyKhz
                } == true) cursor = null
                if (snapshot.state == TransitionState.Disconnected) cursor = null
                transition = snapshot
                snapshot.confirmed?.let { bandStations[it.band] = it.frequencyKhz }
            } })
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
            usb.restoreStation(station?.let { TuneTarget(Band.valueOf(it.band.name), it.frequencyKhz) })
            main.post { usb.start() }
        }
    }

    fun selectRegion(id: String) {
        cursor = null
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
