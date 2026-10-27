package fm.heterodyne.app

import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import fm.heterodyne.app.audio.AudioState
import fm.heterodyne.app.module.isSelectableFrequency
import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.ConnectionState
import fm.heterodyne.app.module.Direction
import fm.heterodyne.app.module.TransitionSnapshot
import fm.heterodyne.app.module.TransitionState
import fm.heterodyne.app.stations.StationEntry
import fm.heterodyne.app.usb.AndroidUsbAdapter

/** One USB owner survives Activity recreation. */
class StationsModule(
    context: android.content.Context,
    private val main: Handler,
    private val session: StationsSession,
    private val startPlayback: () -> Unit,
) {
    var driving by mutableStateOf(false)
        private set

    // Источник не измерен, см. #89. Единственная точка установки сигнала.
    fun updateDriving(value: Boolean) {
        driving = value
        if (!value) {
            main.removeCallbacks(clearSeekHint)
            seekHint = null
        }
    }

    var seekHint by mutableStateOf<String?>(null)
        private set
    private val clearSeekHint = Runnable { seekHint = null }

    var moduleState by mutableStateOf(ConnectionState.Disconnected)
        private set
    var audioState by mutableStateOf(AudioState.Stopped)
        private set
    var currentStation by mutableStateOf<fm.heterodyne.app.stations.StationEntry?>(null)
        private set
    val activeEntries get() = session.activeEntries
    var transition by mutableStateOf(TransitionSnapshot(
        TransitionState.Disconnected, null, null))
        private set
    private var cursor: StationEntry? = null
    private val bandStations = mutableMapOf<Band, Int>()

    fun pickStation(entry: StationEntry) {
        cancelAutostart()
        if (moduleState != ConnectionState.Observing) return
        cursor = entry
        selectStation(entry)
        startAudio()
    }

    fun nextStation(direction: Direction) {
        cancelAutostart()
        val state = session.screen.state ?: return
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
    fun seek(direction: Direction) { cancelAutostart(); cursor = null; usb.seek(direction) }
    fun checkConnection() { usb.checkConnection() }
    fun changeBand() {
        cancelAutostart()
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

    var noFocus by mutableStateOf(false)
        private set
    private var opening = 0L
    private var applicationOpened = false
    fun openApplication(restoredActivity: Boolean = false) {
        // A restored Activity in a new process still represents an application opening.
        if (restoredActivity && applicationOpened) return
        applicationOpened = true
        val token = ++opening
        val generation = usb.openingGeneration()
        session.readLaunchStation { station -> main.post {
            if (opening == token) usb.openApplication(station, generation)
        } }
    }
    private fun cancelAutostart() { opening++; noFocus = false; usb.cancelAutostart() }

    val audioCounters get() = usb.audioCounters

    internal lateinit var usb: AndroidUsbAdapter
        private set

    init {
        usb = AndroidUsbAdapter(context,
            readLaunchStation = session::readLaunchStation,
            autostartPlayback = { completed ->
                try {
                    context.startForegroundService(android.content.Intent(context, fm.heterodyne.app.audio.PlaybackService::class.java)
                        .putExtra(fm.heterodyne.app.audio.PlaybackService.EXTRA_AUTOSTART, true))
                    completed(true)
                } catch (_: Exception) {
                    // Includes ForegroundServiceStartNotAllowedException; the worker logs and releases its lease.
                    completed(false)
                }
            }, noFocusChanged = { noFocus = it }, stationChanged = { band, frequency ->
            main.post {
                currentStation = if (frequency == null || band == fm.heterodyne.app.module.Band.DAB) null
                else fm.heterodyne.app.stations.StationEntry(fm.heterodyne.app.stations.Band.valueOf(band.name), frequency)
            }
            if (frequency != null && isSelectableFrequency(band, frequency)) {
                session.rememberStation(band, frequency) {}
            }
        }, changed = { state ->
            main.post {
                moduleState = state
                if (state == fm.heterodyne.app.module.ConnectionState.Disconnected) {
                    currentStation = null
                    noFocus = false
                }
            }
        }, audioChanged = { counters -> main.post { audioState = counters.state } },
            seekPaused = { remaining ->
                main.removeCallbacks(clearSeekHint)
                seekHint = seekPauseHint(driving, remaining)
                if (seekHint != null) main.postDelayed(clearSeekHint, 2000L)
            }, transitionObserved = { snapshot -> main.post {
                if (snapshot.target == null && cursor?.let {
                    it.band.name == snapshot.confirmed?.band?.name && it.frequencyKhz == snapshot.confirmed?.frequencyKhz
                } == true) cursor = null
                if (snapshot.state == TransitionState.Disconnected) cursor = null
                transition = snapshot
                snapshot.confirmed?.let { bandStations[it.band] = it.frequencyKhz }
            } })
    }

    fun retryModulePermission() { usb.retry() }
    fun startAudio() {
        cancelAutostart()
        session.setLastPlayed(true)
        startPlayback()
    }
    fun userStop() {
        cancelAutostart()
        session.setLastPlayed(false)
        usb.stopAudio()
    }
    fun updatePlaybackService(active: Boolean) { usb.updatePlaybackService(active) }
    fun playAudio(autostart: Boolean = false, started: () -> Unit = {}, stopped: () -> Unit) { usb.playAudio(autostart, started, stopped) }
    fun selectStation(entry: fm.heterodyne.app.stations.StationEntry) { cancelAutostart(); usb.selectStation(entry) }
    fun stepStation(direction: fm.heterodyne.app.module.Direction) { cancelAutostart(); usb.stepStation(direction) }
    fun seekStation(direction: Direction) { cancelAutostart(); usb.seekStation(direction) }
    fun reclaimAudioFocus(completed: () -> Unit) { usb.reclaimAudioFocus(completed) }
    fun stopAudio() { usb.stopAudio() }

    fun activateSelection(name: String) { cursor = null; session.activateSelection(name) }
    fun selectRegion(id: String) { cursor = null; session.selectRegion(id) }
}

internal fun seekPauseHint(driving: Boolean, remainingMillis: Long): String? =
    if (driving) "Поиск доступен через ${maxOf(1L, remainingMillis / 1000 + if (remainingMillis % 1000 > 0) 1 else 0)} с"
    else null
