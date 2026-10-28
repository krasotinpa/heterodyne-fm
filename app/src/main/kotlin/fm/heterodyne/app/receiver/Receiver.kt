package fm.heterodyne.app.receiver

import fm.heterodyne.app.audio.AudioCounters
import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.ConnectionState
import fm.heterodyne.app.module.DeviceProfile
import fm.heterodyne.app.module.Direction
import fm.heterodyne.app.module.TransitionSnapshot
import fm.heterodyne.app.module.TransitionState
import fm.heterodyne.app.module.TuneResult

@JvmInline
value class ReceiverKind(val value: String) {
    init { require(value.matches(Regex("[a-z][a-z0-9-]*"))) }
    companion object { val Module = ReceiverKind("module") }
}

/** Accepted features stay fixed for the lifetime of a Receiver. */
class ReceiverCapabilities(
    val seek: Boolean,
    val cancelSeek: Boolean,
    val rds: Boolean,
    val signalLevel: Boolean,
    val stereo: Boolean,
)

enum class PauseKind { Step, Seek }

/** Publication uses the receiver monotonic clock (Android elapsedRealtime in production). */
data class CommandPause(val kind: PauseKind, val direction: Direction, val durationMillis: Long, val remainingMillis: Long,
    val publishedAtMillis: Long = 0)

data class ConnectionProbe(val sentAt: Long, val revision: Long)

/** Worker-owned receiver boundary. No frames or device commands cross it. */
interface Receiver {
    val kind: ReceiverKind
    val capabilities: ReceiverCapabilities
    val state: ConnectionState
    val snapshot: TransitionSnapshot
    val transitionState: TransitionState
    val stationRevision: Long
    val returnProbe: ConnectionProbe?
    val audioCounters: AudioCounters
    fun found(device: DeviceProfile)
    fun requestPermission(): Long?
    fun permissionResult(request: Long, granted: Boolean)
    fun select(nextBand: Band, frequencyKhz: Int, bandSettleMillis: Long = 0, signal: Boolean = true): TuneResult
    fun cancelSelection()
    fun step(direction: Direction): TuneResult
    fun seek(direction: Direction): TuneResult
    fun switchBand(): TuneResult
    fun checkConnection(): TuneResult
    fun playAudio(): Boolean
    fun stopAudio(): Boolean
    fun close()
}

/** Возможности Модуля, принятые для v1. */
val moduleCapabilities = ReceiverCapabilities(true, false, false, false, false)
