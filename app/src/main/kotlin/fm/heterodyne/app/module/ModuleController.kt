package fm.heterodyne.app.module

import fm.heterodyne.app.DiagnosticLog

enum class Band { AM, FM, DAB }
enum class TuneResult { Sent, InvalidFrequency, NotConnected, BandNotFM, RateLimited, TransportError }

enum class ConnectionState { Disconnected, DeviceFound, PermissionPending, Open, Observing }

/** Single owner: the Android adapter serializes all calls on its worker. No radio TX queue exists. */
class ModuleController(
    private val transport: UsbTransport,
    private val log: DiagnosticLog,
    private val stationChanged: (Band, Int?) -> Unit = { _, _ -> },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val changed: (ConnectionState) -> Unit = {},
) {
    private var band: Band? = null
    private var lastSend: Long? = null
    private var sequence = 0
    private val decoder = FrameDecoder { frame ->
        if (frame[5].toInt() == 0x22) {
            val b6 = frame[6].toInt() and 0xff
            val cid = ((b6 and 0x0f) shl 8) or (frame[7].toInt() and 0xff)
            band = when ((b6 shr 5) and 3) { 0 -> Band.AM; 1 -> Band.FM; else -> Band.DAB }
            when (band) {
                Band.FM -> if (cid <= 205) stationChanged(Band.FM, 87500 + 100 * cid)
                Band.AM -> stationChanged(Band.AM, 522 + 9 * cid)
                Band.DAB -> stationChanged(Band.DAB, null)
                null -> Unit
            }
        }
    }

    /** Called once per explicit user action; rejected actions are never queued. */
    fun tune(frequencyKhz: Int): TuneResult {
        if (frequencyKhz !in 87500..108000 || (frequencyKhz - 87500) % 100 != 0) return TuneResult.InvalidFrequency
        if (state != ConnectionState.Observing) return TuneResult.NotConnected
        if (band != Band.FM) return TuneResult.BandNotFM
        val now = clock()
        if (lastSend?.let { now - it < 1000 } == true) return TuneResult.RateLimited
        val cid = (frequencyKhz - 87500) / 100
        // Host SEQ starts at 1 and wraps FF -> 00; RX SEQ is independent.
        sequence = (sequence + 1) and 0xff
        val body = byteArrayOf(0x80.toByte(), 0x82.toByte(), 0x20, cid.toByte(), 0, cid.toByte(), 0)
        body[6] = (-body.sumOf { it.toInt() and 0xff }).toByte()
        val frame = byteArrayOf(0xfa.toByte(), 0x55, sequence.toByte(), 0, 7) + body
        lastSend = now
        return try {
            safeLog { log.tx(frame) }
            transport.send(frame)
            TuneResult.Sent
        } catch (_: Exception) { error(); TuneResult.TransportError }
    }

    fun receive() {
        if (state != ConnectionState.Observing) return
        try {
            val chunk = transport.receive()
            if (chunk.isNotEmpty()) {
                safeLog { log.rx(chunk) }
                decoder.accept(chunk)
            }
        } catch (_: Exception) { error() }
    }

    var state = ConnectionState.Disconnected; private set
    private var profile: DeviceProfile? = null
    private var generation = 0L
    private var pending: Long? = null
    val pendingCount get() = if (pending == null) 0 else 1
    val deviceId get() = profile?.deviceId

    private fun safeLog(event: String) = safeLog { log.event(event) }
    private fun safeLog(write: () -> Unit) {
        try { write() } catch (_: Exception) {
            // Journal failures must never prevent USB cleanup or state changes.
        }
    }

    private fun transition(next: ConnectionState) { state = next; changed(next) }
    fun found(device: DeviceProfile) {
        if (profile?.deviceId == device.deviceId) return
        if (profile != null) disconnect()
        profile = device
        safeLog("USB found")
        transition(ConnectionState.DeviceFound)
    }
    fun requestPermission(): Long? {
        if (profile == null || pending != null || state == ConnectionState.Observing) return null
        transition(ConnectionState.DeviceFound)
        pending = ++generation
        safeLog("USB permission requested")
        transition(ConnectionState.PermissionPending)
        return pending
    }
    fun permissionResult(request: Long, granted: Boolean) {
        if (pending != request) return
        pending = null
        if (!granted) {
            safeLog("USB permission denied")
            transition(ConnectionState.Disconnected)
            return
        }
        safeLog("USB permission granted")
        try {
            transport.open(checkNotNull(profile))
            safeLog("USB open")
            transition(ConnectionState.Open)
            transition(ConnectionState.Observing)
        } catch (_: Exception) { error() }
    }
    fun detach(id: String) {
        if (profile?.deviceId != id) return
        try {
            safeLog("USB detach")
            disconnect()
        } finally { profile = null }
    }
    fun error() {
        // Exception messages may contain device paths or identifiers.
        safeLog("USB error")
        disconnect()
    }
    fun close() { try { disconnect() } finally { profile = null } }
    private fun disconnect() {
        pending = null
        band = null
        decoder.clear()
        generation++
        try { transport.close() } catch (_: Exception) { safeLog("USB error") }
        finally { safeLog("USB close"); transition(ConnectionState.Disconnected) }
    }
}
