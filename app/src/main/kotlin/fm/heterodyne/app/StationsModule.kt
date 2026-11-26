package fm.heterodyne.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import fm.heterodyne.app.audio.AudioState
import fm.heterodyne.app.audio.PlaybackService
import fm.heterodyne.app.host.ReceiverPreferences
import fm.heterodyne.app.module.*
import fm.heterodyne.app.receiver.*
import fm.heterodyne.app.stations.StationEntry
import fm.heterodyne.app.usb.AndroidUsbAdapter
import fm.heterodyne.app.usb.ReceiverObservation
import fm.heterodyne.app.usb.moduleWorker
import fm.heterodyne.ui.contract.FocusEffect
import java.util.concurrent.ScheduledExecutorService

/** Main-thread view of the worker-owned receiver. The service has its own wheel cursor. */
class StationsModule(
    context: Context,
    private val main: Handler,
    private val session: StationsSession,
    private val profiles: ReceiverProfiles = ReceiverProfiles.Module,
    additionalReceivers: List<Receiver> = emptyList(),
    fixture: ReceiverFixture? = null,
    worker: ScheduledExecutorService = moduleWorker(),
    private val startPlayback: () -> Unit,
) {
    internal var changed: () -> Unit = {}
    fun receiverName(kind: ReceiverKind) = profiles.displayName(kind)
    val receiverKinds get() = profiles.kinds
    private val initialKind = ReceiverPreferences(context.getSharedPreferences("receiver", Context.MODE_PRIVATE))
        .kind?.takeIf { it in profiles.kinds } ?: profiles.kinds.singleOrNull()
    private val noFeatures = ReceiverCapabilities(false, false, false, false, false)
    private var observation = ReceiverObservation(0, initialKind, moduleCapabilities.takeIf { initialKind == ReceiverKind.Module },
        ConnectionState.Disconnected, TransitionSnapshot(TransitionState.Disconnected, null, null), AudioState.Stopped)
    val selectedReceiver get() = observation.kind
    val capabilities get() = observation.capabilities ?: noFeatures
    val moduleState get() = observation.connection
    val transition get() = observation.tuning
    val currentStation get() = transition.confirmed?.takeIf { it.band == Band.FM }?.let { StationEntry(it.frequencyKhz) }
    val activeEntries get() = session.activeEntries
    val generation get() = observation.generation
    var audioState = AudioState.Stopped
        private set
    var focusEffect = FocusEffect.None
        private set
    var noFocus = false
        private set
    var driving = false
        private set
    var seekHintSeconds: Int? = null
        private set
    val seekHint get() = seekHintSeconds?.let { "Поиск доступен через $it с" }
    private val clearSeekHint = Runnable { seekHintSeconds = null; changed() }

    fun updateDriving(value: Boolean) {
        driving = value
        if (!value) {
            main.removeCallbacks(clearSeekHint)
            seekHintSeconds = null
        }
        changed()
    }

    internal fun onSeekPaused(remainingMillis: Long) {
        main.removeCallbacks(clearSeekHint)
        seekHintSeconds = seekPauseSeconds(driving, remainingMillis)
        if (seekHintSeconds != null) main.postDelayed(clearSeekHint, 2000)
        changed()
    }

    private var opening = 0L
    private var applicationOpened = false
    fun openApplication(restoredActivity: Boolean = false) {
        if (restoredActivity && applicationOpened) return
        applicationOpened = true
        val token = ++opening
        val cancellation = usb.openingGeneration()
        session.readLaunchStation { station -> main.post {
            if (opening == token)
                usb.openApplication(station, cancellation)
        } }
    }
    internal fun cancelAutostart() { opening++; noFocus = false; usb.cancelAutostart() }
    val audioCounters get() = usb.audioCounters

    internal val usb: AndroidUsbAdapter = AndroidUsbAdapter(context, profiles = profiles,
        additionalReceivers = additionalReceivers, fixture = fixture, worker = worker,
        readLaunchStation = session::readLaunchStation, seekPaused = ::onSeekPaused,
        autostartPlayback = { completed ->
            completed(runCatching {
                context.startForegroundService(Intent(context, PlaybackService::class.java)
                    .putExtra(PlaybackService.EXTRA_AUTOSTART, true))
            }.isSuccess)
        }, noFocusChanged = { noFocus = it; changed() },
        focusChanged = { focusEffect = it; changed() },
        stationChanged = ::remember,
        observed = { next ->
            if (generation != next.generation) {
                noFocus = false
                seekHintSeconds = null
                manualStartPending = false
            }
            observation = next
            audioState = next.audio
            changed()
        }, audioChanged = {
            audioState = it
            manualStartPending = false
            changed()
        })

    private fun remember(band: Band, frequency: Int, generation: Long) {
        if (isSelectableFrequency(band, frequency))
            session.rememberStation(band, frequency, valid = { generation == usb.connectionGeneration }) {}
    }

    @Volatile private var playbackIntent = 0L
    private var manualReceiver: () -> Boolean = { true }
    private var manualStartPending = false
    private var stoppedByUser = false
    internal fun startAudio(reclaim: Boolean = false) {
        cancelAutostart()
        if (manualStartPending) return
        if (!reclaim && !stoppedByUser && audioState in listOf(AudioState.Playing, AudioState.Starting)) return
        manualStartPending = stoppedByUser || audioState !in listOf(AudioState.Playing, AudioState.Starting)
        val intent = ++playbackIntent
        val receiver = currentReceiver()
        manualReceiver = { playbackIntent == intent && receiver() }
        stoppedByUser = false
        session.setLastPlayed(true)
        startPlayback()
    }
    fun userStop() {
        cancelAutostart()
        if (stoppedByUser) return
        stoppedByUser = true
        playbackIntent++
        manualStartPending = false
        session.setLastPlayed(false)
        usb.stopAudio()
    }
    fun updatePlaybackService(active: Boolean) {
        if (!active) manualStartPending = false
        usb.updatePlaybackService(active)
    }
    fun playAudio(autostart: Boolean = false, started: () -> Unit = {}, stopped: () -> Unit) {
        if (!autostart && (stoppedByUser || !manualReceiver())) { stopped(); return }
        usb.playAudio(autostart, started, stopped, if (autostart) currentReceiver() else manualReceiver)
    }
    internal fun currentReceiver(): () -> Boolean {
        val token = generation
        return { usb.connectionGeneration == token }
    }
    fun selectStation(entry: StationEntry) { cancelAutostart(); usb.wheelSelect(entry.frequencyKhz, currentReceiver()) }
    fun seekStation(direction: Direction) {
        if (capabilities.seek) { cancelAutostart(); usb.wheelSeek(direction, currentReceiver()) }
    }
    fun reclaimAudioFocus(completed: () -> Unit) = usb.reclaimAudioFocus(completed)
    fun stopAudio() = usb.stopAudio()
}

internal fun seekPauseSeconds(driving: Boolean, remainingMillis: Long): Int? =
    if (driving) ((remainingMillis + 999) / 1000).coerceAtLeast(1).toInt() else null
