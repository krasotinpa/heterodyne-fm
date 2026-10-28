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
import fm.heterodyne.app.receiver.ReceiverProfiles
import fm.heterodyne.app.stations.StationEntry
import fm.heterodyne.app.usb.AndroidUsbAdapter

/** One USB owner survives Activity recreation. */
class StationsModule(
    context: android.content.Context,
    private val main: Handler,
    private val session: StationsSession,
    private val profiles: ReceiverProfiles = ReceiverProfiles.Module,
    private val additionalReceivers: List<fm.heterodyne.app.receiver.Receiver> = emptyList(),
    private val startPlayback: () -> Unit,
) {
    fun receiverName(kind: fm.heterodyne.app.receiver.ReceiverKind): String = profiles.displayName(kind)
    val receiverKinds get() = profiles.kinds
    var selectedReceiver by mutableStateOf<fm.heterodyne.app.receiver.ReceiverKind?>(
        fm.heterodyne.app.ui.ReceiverPreferences(context.getSharedPreferences("receiver", android.content.Context.MODE_PRIVATE)).kind
            ?.takeIf { it in profiles.kinds } ?: profiles.kinds.singleOrNull())
        private set
    var capabilities by mutableStateOf(if (selectedReceiver == fm.heterodyne.app.receiver.ReceiverKind.Module)
        fm.heterodyne.app.receiver.moduleCapabilities else fm.heterodyne.app.receiver.ReceiverCapabilities(false, false, false, false, false))
        private set
    @Volatile private var parked = false
    @Volatile private var moving = false
    fun updateParked(value: Boolean) { parked = value && !driving }
    fun selectReceiver(kind: fm.heterodyne.app.receiver.ReceiverKind) {
        if (!parked || driving) return
        cancelAutostart()
        usb.selectReceiver(kind) { parked && !moving }
    }

    var driving by mutableStateOf(false)
        private set

    // GNSS с гистерезисом. Главный поток устанавливает сигнал в одной точке.
    fun updateDriving(value: Boolean) {
        moving = value
        driving = value
        if (value) parked = false
        if (!value) {
            main.removeCallbacks(clearSeekHint)
            seekHint = null
        }
    }

    var seekHint by mutableStateOf<String?>(null)
        private set
    private val clearSeekHint = Runnable { seekHint = null }

    internal fun onSeekPaused(remainingMillis: Long) {
        main.removeCallbacks(clearSeekHint)
        seekHint = seekPauseHint(driving, remainingMillis)
        if (seekHint != null) main.postDelayed(clearSeekHint, 2000L)
    }

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
        val entries = state.regionSelections(state.selectedRegion).active.entries
        if (entries.isEmpty()) return
        val confirmed = transition.confirmed
        val index = entries.indexOfFirst {
            if (cursor != null) it.frequencyKhz == cursor?.frequencyKhz
            else confirmed?.band == Band.FM && it.frequencyKhz == confirmed?.frequencyKhz
        }
        val next = if (index < 0) {
            if (direction == Direction.Up) 0 else entries.lastIndex
        } else Math.floorMod(index + if (direction == Direction.Up) 1 else -1, entries.size)
        pickStation(entries[next])
    }

    fun step(direction: Direction) { cursor = null; stepStation(direction) }
    fun seek(direction: Direction) { if (!capabilities.seek) return; cancelAutostart(); cursor = null; usb.seek(direction) }
    fun checkConnection() { usb.checkConnection() }
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
        usb = AndroidUsbAdapter(context, profiles = profiles, additionalReceivers = additionalReceivers,
            selectionChanged = { kind, features ->
                selectedReceiver = kind
                capabilities = features ?: fm.heterodyne.app.receiver.ReceiverCapabilities(false, false, false, false, false)
                cursor = null
                currentStation = null
            },
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
                currentStation = if (frequency == null || band != Band.FM) null
                else fm.heterodyne.app.stations.StationEntry(frequency)
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
            seekPaused = ::onSeekPaused, transitionObserved = { snapshot -> main.post {
                if (snapshot.target == null && cursor?.let {
                    snapshot.confirmed?.band == Band.FM && it.frequencyKhz == snapshot.confirmed?.frequencyKhz
                } == true) cursor = null
                if (snapshot.state == TransitionState.Disconnected) cursor = null
                transition = snapshot
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
    fun selectStation(entry: fm.heterodyne.app.stations.StationEntry) {
        cancelAutostart(); usb.selectStation(entry)
    }
    fun stepStation(direction: fm.heterodyne.app.module.Direction) { cancelAutostart(); usb.stepStation(direction) }
    fun seekStation(direction: Direction) = seek(direction)
    fun reclaimAudioFocus(completed: () -> Unit) { usb.reclaimAudioFocus(completed) }
    fun stopAudio() { usb.stopAudio() }

    fun activateSelection(name: String) { cursor = null; session.activateSelection(name) }
    fun selectRegion(id: String) { cursor = null; session.selectRegion(id) }
}

internal fun seekPauseHint(driving: Boolean, remainingMillis: Long): String? =
    if (driving) "Поиск доступен через ${maxOf(1L, remainingMillis / 1000 + if (remainingMillis % 1000 > 0) 1 else 0)} с"
    else null
