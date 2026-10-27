package fm.heterodyne.app.module

import fm.heterodyne.app.Journal

enum class Band { AM, FM, DAB }
fun isSelectableFrequency(band: Band, frequencyKhz: Int): Boolean = when (band) {
    Band.FM -> frequencyKhz in 87500..108000 && (frequencyKhz - 87500) % 100 == 0
    Band.AM -> frequencyKhz in 711..1044 && (frequencyKhz - 522) % 9 == 0
    else -> false
}

enum class TuneResult { Sent, InvalidFrequency, NotConnected, BandNotFM, RateLimited, TransportError }

enum class TransitionState { Idle, Waiting, Unconfirmed, Unresponsive, Disconnected, CommandPause, Seeking, SeekIncomplete }
data class TuneTarget(val band: Band, val frequencyKhz: Int)
data class TransitionSnapshot(val state: TransitionState, val target: TuneTarget?, val confirmed: TuneTarget?) {
    val banner: String? get() = when (state) {
        TransitionState.Unconfirmed -> "Нет ответа от Модуля"
        TransitionState.Unresponsive -> "Модуль не отвечает, переподключите USB"
        TransitionState.Disconnected -> "Модуль отключён"
        TransitionState.Seeking -> "идёт поиск…"
        TransitionState.SeekIncomplete -> "Поиск не завершён"
        else -> null
    }
}
enum class Direction { Up, Down }

class TransportFailure(val errno: Int) : java.io.IOException()

enum class ConnectionState { Disconnected, DeviceFound, PermissionPending, Open, Observing }

/** Single owner: the Android adapter serializes all calls on its worker. No radio TX queue exists. */
class ModuleController(
    private val transport: UsbTransport,
    private val log: Journal,
    private val stationChanged: (Band, Int?) -> Unit = { _, _ -> },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val transitionObserved: (TransitionSnapshot) -> Unit = {},
    private val transitionChanged: (TransitionState) -> Unit = {},
    private val frameObserved: (String, ByteArray) -> Unit = { _, _ -> },
    private val seekLimitMillis: () -> Long = { 20000L },
    private val seekPaused: (Long) -> Unit = {},
    private val beforeTick: () -> Unit = {},
    private val moduleReturning: () -> Unit = {},
    private val changed: (ConnectionState) -> Unit = {},
) {
    private var band: Band? = null
    private var lastSend: Long? = null
    private var sequence = 0
    var transitionState = TransitionState.Disconnected; private set
    var target: TuneTarget? = null; private set
    private var confirmed: TuneTarget? = null
    var stationRevision = 0L; private set
    val snapshot get() = TransitionSnapshot(transitionState, target, confirmed)
    private fun publish() { transitionObserved(snapshot) }
    private var started: Long? = null
    private var checking = false
    private var bandReply: Band? = null
    private var selected = false
    private var lastBand: Long? = null
    private var bandSettleMillis = 0L
    private var selectNotBefore = 0L
    private var stepStarted: Long? = null
    private var lastStep: Long? = null
    private var lastSeek: Long? = null
    private var pauseUntil = 0L
    private var seekPause = 0L
    private var seeking: Long? = null
    private var seekReply: Long? = null
    private var activeSeekLimit = 20000L
    private var lastCheck: Long? = null
    data class ReturnProbe(val sentAt: Long, val revision: Long)
    var returnProbe: ReturnProbe? = null; private set
    private var returning = false
    private var probeNeeded = false
    private fun radio(next: TransitionState) {
        if (transitionState == next) return
        transitionState = next
        safeLog("R26 $next")
        transitionChanged(next)
        publish()
    }
    private fun clearAction() {
        target = null; started = null; checking = false; bandReply = null; selected = false
        seeking = null; seekReply = null; stepStarted = null
        selectNotBefore = 0L; bandSettleMillis = 0L
    }
    /** The adapter calls this on its serialized worker, including during idle reads. */
    fun tick() {
        beforeTick()
        advance(true)
        if (probeNeeded) tryCheckConnection()
    }

    private fun advance(dispatchPending: Boolean) {
        val now = clock()
        val seekStart = seeking
        if (seekStart != null) {
            if (now - seekStart >= activeSeekLimit) {
                clearAction(); radio(TransitionState.SeekIncomplete)
            } else if (seekReply?.let { now - it >= 300 } == true) {
                clearAction(); radio(TransitionState.Idle)
            }
            return
        }
        if (stepStarted?.let { now - it >= 3000 } == true) {
            clearAction(); radio(TransitionState.Unconfirmed); return
        }
        if (stepStarted?.let { now - it > 300 } == true) radio(TransitionState.Waiting)
        val start = started
        if (start != null && now - start >= 3000) {
            val failedCheck = checking
            clearAction()
            radio(if (failedCheck) TransitionState.Unresponsive else TransitionState.Unconfirmed)
            return
        }
        if (start != null && now - start > 300) radio(TransitionState.Waiting)
        if (dispatchPending && target != null) dispatch()
        if (transitionState == TransitionState.CommandPause && now >= pauseUntil)
            radio(TransitionState.Idle)
    }
    private val decoder = FrameDecoder { frame ->
        if (transitionState == TransitionState.Unconfirmed || transitionState == TransitionState.Unresponsive)
            radio(TransitionState.Idle)
        if (checking) { checking = false; started = null; radio(TransitionState.Idle) }
        if (frame[5].toInt() == 0x22) {
            stepStarted = null
            val b6 = frame[6].toInt() and 0xff
            val cid = ((b6 and 0x0f) shl 8) or (frame[7].toInt() and 0xff)
            band = when ((b6 shr 5) and 3) { 0 -> Band.AM; 1 -> Band.FM; else -> Band.DAB }
            if (bandReply == band) {
                bandReply = null
                if (target == null) { started = null; radio(TransitionState.Idle) }
            }
            if (seeking != null) seekReply = clock()
            val station = actualStation(band, cid)
            if (station != null) { confirmed = station; stationRevision++ }
            val goal = target
            val actual = station?.frequencyKhz
            if (selected && goal != null && goal.band == band && goal.frequencyKhz == actual) {
                clearAction(); radio(TransitionState.Idle)
            } else if (goal == null && seeking == null) {
                started = null
                if (transitionState != TransitionState.SeekIncomplete) radio(TransitionState.Idle)
            }
            publish()
            when (band) {
                Band.FM -> if (cid <= 205) stationChanged(Band.FM, 87500 + 100 * cid)
                Band.AM -> stationChanged(Band.AM, 522 + 9 * cid)
                Band.DAB -> stationChanged(Band.DAB, null)
                null -> Unit
            }
        }
    }

    private fun actualStation(band: Band?, cid: Int): TuneTarget? = when (band) {
        Band.FM -> if (cid <= 205) TuneTarget(Band.FM, 87500 + 100 * cid) else null
        Band.AM -> TuneTarget(Band.AM, 522 + 9 * cid)
        else -> null
    }

    /** Legacy FM-only entry point; select also supports composed AM/FM transitions. */
    fun tune(frequencyKhz: Int): TuneResult {
        if (frequencyKhz !in 87500..108000 || (frequencyKhz - 87500) % 100 != 0) return TuneResult.InvalidFrequency
        if (state != ConnectionState.Observing) return TuneResult.NotConnected
        if (band != Band.FM) return TuneResult.BandNotFM
        return select(Band.FM, frequencyKhz)
    }

    /** Extra BAND settling is used only by launch autostart; the R26 deadline remains shared. */
    fun select(nextBand: Band, frequencyKhz: Int, bandSettleMillis: Long = 0): TuneResult {
        if (!isSelectableFrequency(nextBand, frequencyKhz)) return TuneResult.InvalidFrequency
        advance(false)
        if (state != ConnectionState.Observing) return TuneResult.NotConnected
        if (band == null || band == Band.DAB) return TuneResult.BandNotFM
        if (seeking != null || checking || stepStarted != null) return TuneResult.RateLimited
        if (bandReply == null) {
            started = null
            radio(TransitionState.Idle)
        }
        this.bandSettleMillis = bandSettleMillis
        target = TuneTarget(nextBand, frequencyKhz)
        publish()
        selected = false
        return dispatch()
    }
    /** Discard unsent work when the user cancels launch autostart. Sent BAND cannot be undone. */
    fun cancelSelection() {
        val outstandingBand = bandReply
        val bandStarted = started
        clearAction()
        if (outstandingBand != null) { bandReply = outstandingBand; started = bandStarted }
        if (state == ConnectionState.Observing) radio(TransitionState.Idle)
    }

    private fun dispatch(): TuneResult {
        val goal = target ?: return TuneResult.RateLimited
        if (selected || bandReply != null) return TuneResult.RateLimited
        val now = clock()
        if (band != goal.band) {
            if (lastBand?.let { now - it < 2000 } == true) return TuneResult.RateLimited
            if (started == null) started = now
            lastBand = now
            selectNotBefore = now + bandSettleMillis
            bandReply = if (band == Band.FM) Band.AM else Band.FM
            return send(0x84)
        }
        if (now < selectNotBefore) return TuneResult.RateLimited
        if (lastSend?.let { now - it < 1000 } == true) return TuneResult.RateLimited
        val cid = if (goal.band == Band.FM) (goal.frequencyKhz - 87500) / 100 else (goal.frequencyKhz - 522) / 9
        if (started == null) started = now
        lastSend = now
        selected = true
        return send(0x82, 0x20, cid, 0, cid)
    }
    fun switchBand(): TuneResult {
        tick()
        if (state != ConnectionState.Observing) return TuneResult.NotConnected
        if (band != Band.FM && band != Band.AM) return TuneResult.BandNotFM
        if (started != null || stepStarted != null || seeking != null || target != null ||
            lastBand?.let { clock() - it < 2000 } == true) return TuneResult.RateLimited
        lastBand = clock()
        started = clock()
        bandReply = if (band == Band.FM) Band.AM else Band.FM
        return send(0x84)
    }

    fun step(direction: Direction): TuneResult {
        tick()
        if (state != ConnectionState.Observing) return TuneResult.NotConnected
        if (band != Band.FM && band != Band.AM) return TuneResult.BandNotFM
        if (stepStarted != null || started != null || seeking != null || target != null || lastStep?.let { clock() - it < 500 } == true) {
            if (started == null && seeking == null) radio(TransitionState.CommandPause)
            return TuneResult.RateLimited
        }
        stepStarted = clock()
        lastStep = clock()
        pauseUntil = clock() + 500
        radio(TransitionState.CommandPause)
        return send(if (direction == Direction.Up) 0x89 else 0x88)
    }
    fun seek(direction: Direction): TuneResult {
        tick()
        if (state != ConnectionState.Observing) return TuneResult.NotConnected
        if (band != Band.FM && band != Band.AM) return TuneResult.BandNotFM
        if (stepStarted != null || started != null || seeking != null || target != null) return TuneResult.RateLimited
        if (lastSeek?.let { clock() - it < seekPause } == true) {
            pauseUntil = checkNotNull(lastSeek) + seekPause
            seekPaused(pauseUntil - clock())
            radio(TransitionState.CommandPause)
            return TuneResult.RateLimited
        }
        stepStarted = null
        lastSeek = clock(); seekPause = if (band == Band.FM) 5000 else 15000
        activeSeekLimit = seekLimitMillis()
        seeking = clock(); seekReply = null
        radio(TransitionState.Seeking)
        return send(if (direction == Direction.Up) 0x87 else 0x86)
    }
    fun checkConnection(): TuneResult {
        tick()
        return tryCheckConnection()
    }
    private fun tryCheckConnection(): TuneResult {
        if (state != ConnectionState.Observing) return TuneResult.NotConnected
        if (stepStarted != null || started != null || seeking != null || target != null || lastCheck?.let { clock() - it < 10000 } == true)
            return TuneResult.RateLimited
        stepStarted = null
        // Consume the return obligation at send time; a timeout must not retry it.
        val returnProbe = probeNeeded
        probeNeeded = false
        val sentAt = clock()
        lastCheck = sentAt; checking = true; started = sentAt
        val result = send(0x85, 7)
        if (returnProbe && result == TuneResult.Sent) this.returnProbe = ReturnProbe(sentAt, stationRevision)
        return result
    }
    private fun send(vararg payload: Int): TuneResult {
        // Host SEQ starts at 1 and wraps FF -> 00; RX SEQ is independent.
        sequence = (sequence + 1) and 0xff
        val body = byteArrayOf(0x80.toByte()) + payload.map { it.toByte() }.toByteArray()
        val frame = byteArrayOf(0xfa.toByte(), 0x55, sequence.toByte(), 0, (body.size + 1).toByte()) +
            body + (-body.sumOf { it.toInt() and 255 }).toByte()
        return try {
            observeFrame("TX", frame)
            safeLog { log.tx(frame) }; transport.send(frame); TuneResult.Sent
        } catch (failure: Exception) { error((failure as? TransportFailure)?.errno); TuneResult.TransportError }
    }

    fun receive() {
        if (state != ConnectionState.Observing) return
        tick()
        try {
            val chunk = transport.receive()
            if (chunk.isNotEmpty()) {
                observeFrame("RX", chunk)
                safeLog { log.rx(chunk) }
                decoder.accept(chunk)
                tick()
            }
        } catch (failure: Exception) { error((failure as? TransportFailure)?.errno) }
    }

    var state = ConnectionState.Disconnected; private set
    private var profile: DeviceProfile? = null
    private var generation = 0L
    private var pending: Long? = null
    val pendingCount get() = if (pending == null) 0 else 1
    val deviceId get() = profile?.deviceId

    private fun observeFrame(direction: String, bytes: ByteArray) {
        try { frameObserved(direction, bytes.copyOf()) } catch (_: Exception) { /* Passive observer. */ }
    }

    private fun safeLog(event: String) = safeLog { log.event(event) }
    private fun safeLog(write: () -> Unit) {
        try { write() } catch (_: Exception) {
            // Journal failures must never prevent USB cleanup or state changes.
        }
    }

    private fun transition(next: ConnectionState) {
        state = next
        changed(next)
    }
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
            if (returning) { returning = false; probeNeeded = true; moduleReturning(); tick() }
        } catch (failure: Exception) { error((failure as? TransportFailure)?.errno) }
    }
    fun detach(id: String) {
        if (profile?.deviceId != id) return
        try {
            safeLog("USB detach")
            returning = true
            disconnect()
            radio(TransitionState.Disconnected)
        } finally { profile = null }
    }
    fun error(errno: Int? = null) {
        // Exception messages may contain device paths or identifiers.
        safeLog("USB error")
        if (errno == 19) returning = true
        disconnect()
        radio(TransitionState.Disconnected)
    }
    fun close() { try { disconnect() } finally { profile = null } }
    private fun disconnect() {
        probeNeeded = false
        returnProbe = null
        clearAction()
        radio(TransitionState.Disconnected)
        pending = null
        band = null
        decoder.clear()
        generation++
        try { transport.close() } catch (_: Exception) { safeLog("USB error") }
        finally { safeLog("USB close"); transition(ConnectionState.Disconnected) }
    }
}
