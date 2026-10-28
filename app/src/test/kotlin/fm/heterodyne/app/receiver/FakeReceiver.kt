package fm.heterodyne.app.receiver

import fm.heterodyne.app.audio.AudioCounters
import fm.heterodyne.app.module.*

/** A second kind confirms transitions without Module frames or commands. */
class FakeReceiver(override val kind: ReceiverKind = ReceiverKind("test")) : Receiver {
    override val capabilities = ReceiverCapabilities(false, false, false, false, false)
    override var state = ConnectionState.Disconnected
    override var snapshot = TransitionSnapshot(TransitionState.Idle, null, TuneTarget(Band.FM, 90000))
    override val transitionState get() = snapshot.state
    override var stationRevision = 0L
    override val returnProbe: ConnectionProbe? = null
    override val audioCounters = AudioCounters()
    val selected = mutableListOf<TuneTarget>()
    override fun found(device: DeviceProfile) { state = ConnectionState.DeviceFound }
    override fun requestPermission(): Long = 1
    override fun permissionResult(request: Long, granted: Boolean) {
        state = if (granted) ConnectionState.Observing else ConnectionState.Disconnected
    }
    override fun select(nextBand: Band, frequencyKhz: Int, bandSettleMillis: Long, signal: Boolean): TuneResult {
        val station = TuneTarget(nextBand, frequencyKhz)
        selected.add(station)
        snapshot = TransitionSnapshot(TransitionState.Idle, null, station)
        stationRevision++
        return TuneResult.Sent
    }
    override fun checkConnection(): TuneResult { stationRevision++; return TuneResult.Sent }
    override fun cancelSelection() {}
    override fun step(direction: Direction) = TuneResult.Sent
    override fun seek(direction: Direction) = TuneResult.NotConnected
    override fun switchBand() = TuneResult.NotConnected
    override fun playAudio() = true
    override fun stopAudio() = true
    override fun close() { state = ConnectionState.Disconnected }
}
