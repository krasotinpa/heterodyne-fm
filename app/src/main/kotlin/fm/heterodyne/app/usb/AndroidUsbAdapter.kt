package fm.heterodyne.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import fm.heterodyne.app.DiagnosticLog
import fm.heterodyne.app.AsyncDiagnosticLog
import fm.heterodyne.app.audio.*
import fm.heterodyne.app.module.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Application owns one adapter. Discovery, permission and open run on its worker, outside UI. */
class AndroidUsbAdapter(
    private val context: Context,
    stationChanged: (Band, Int?) -> Unit = { _, _ -> },
    changed: (ConnectionState) -> Unit,
    private val audioChanged: (AudioCounters) -> Unit = {},
    transitionObserved: (TransitionSnapshot) -> Unit = {},
    private val autostartPlayback: ((Boolean) -> Unit) -> Unit = { it(false) },
    private val noFocusChanged: (Boolean) -> Unit = {},
    private val readLaunchStation: ((TuneTarget?) -> Unit) -> Unit = { it(null) },
) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    val frames = fm.heterodyne.app.DiagnosticFrames()
    // Journal reads wait for the Journal thread; keep them off the Module thread so commands and detach never queue behind disk IO.
    private val journalReader = Executors.newSingleThreadExecutor { task -> Thread(task, "JournalReader").apply { isDaemon = true } }
    // Probe worker only enqueues; persistent writes belong to AsyncDiagnosticLog. No USB operation.
    fun probeEvent(text: String) { log.event(text) }
    fun readJournal(done: (String) -> Unit) { journalReader.execute {
        done(try { log.readTail(32768) } catch (_: Exception) { "Не удалось прочитать Журнал" })
    } }
    fun exportJournal(store: fm.heterodyne.app.DownloadStore,
                      done: (Result<fm.heterodyne.app.JournalExportResult>) -> Unit) {
        // Snapshot inside the read barrier (ring is capped at 10 MB); slow storage then never stalls the Journal thread.
        journalReader.execute { done(runCatching {
            val snapshot = java.io.ByteArrayOutputStream().also(log::copyTo).toByteArray()
            fm.heterodyne.app.JournalExport(store).save { it.write(snapshot) }
        }) }
    }
    fun diagnosticCommand(command: DiagnosticCommand, done: (TuneResult) -> Unit) {
        cancelAutostart()
        worker.execute {
            val result = when (command) {
                DiagnosticCommand.RequestAllInfo -> controller.checkConnection()
                DiagnosticCommand.TuneUp -> controller.step(Direction.Down)
                DiagnosticCommand.TuneDown -> controller.step(Direction.Up)
                DiagnosticCommand.Band -> controller.switchBand()
                DiagnosticCommand.PlaySelect -> controller.snapshot.confirmed?.let { controller.select(it.band, it.frequencyKhz) }
                    ?: TuneResult.BandNotFM
                DiagnosticCommand.SeekUp -> controller.seek(Direction.Down)
                DiagnosticCommand.SeekDown -> controller.seek(Direction.Up)
            }
            done(result)
        }
    }
    private val manager = context.getSystemService(UsbManager::class.java)
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "Module") }
    private val log by lazy {
        AsyncDiagnosticLog(create = {
            DiagnosticLog(java.io.File(context.filesDir, "module-diagnostics")).also {
                it.buildVersion(context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown")
            }
        }, priority = { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) })
    }
    private val transport: AndroidUsbTransport = AndroidUsbTransport(manager) { audio.stop() }
    private var connectionGeneration = 0L
    private var returnGeneration = 0L
    private fun moduleReturning() {
        launch.cancel()
        val connection = connectionGeneration
        val token = returnGeneration
        // Enqueue storage before any new RX can save the returned Module's frequency.
        readLaunchStation { station -> worker.execute {
            if (connection != connectionGeneration || cancellation.isCancelled(token) || closing) return@execute
            launchGeneration = token
            launch.moduleReturn(autostartEnabled(), station)
        } }
    }
    private fun autostartEnabled() =
        context.getSharedPreferences("autostart", Context.MODE_PRIVATE).getBoolean("enabled", true)
    private val controller: ModuleController by lazy { ModuleController(transport, log,
        clock = { android.os.SystemClock.elapsedRealtime() },
        moduleReturning = ::moduleReturning,
        beforeTick = { launch.guardPendingWork() },
        seekLimitMillis = { fm.heterodyne.app.ui.SeekPreferences.readSeconds(
            context.getSharedPreferences("seek", Context.MODE_PRIVATE)).toLong() * 1000 },
        frameObserved = { direction, bytes -> frames.accept(direction, bytes, System.currentTimeMillis()) }, stationChanged = { band, frequency -> main.post { stationChanged(band, frequency) } }, changed = { state ->
        if (state == ConnectionState.Disconnected) {
            connectionGeneration++
            returnGeneration = cancellation.capture()
            playbackStopping = playbackServiceActive
            frames.resetConnection()
        }
        main.post { changed(state) }
    }, transitionObserved = { snapshot -> main.post { transitionObserved(snapshot) } }) }
    private val cancellation = AutostartGeneration()
    @Volatile private var launchGeneration = cancellation.capture()
    @Volatile private var playbackServiceActive = false
    @Volatile private var playbackStopping = false
    @Volatile private var pendingAutostart: ((Boolean) -> Unit)? = null
    private fun audioActive() = audio.snapshot.state in listOf(AudioState.Starting, AudioState.Playing)
    private fun playbackActive() = playbackServiceActive || audioActive() || focus.playbackActive
    private val launch: LaunchAutostart by lazy { LaunchAutostart(controller, log,
        clock = { android.os.SystemClock.elapsedRealtime() },
        requestFocus = { completed ->
            val token = launchGeneration
            main.post {
                val acquired = if (!cancellation.isCancelled(token) && !playbackActive()) focus.acquireForAutostart() else null
                val lease = acquired?.let { AudioFocusLease(it.granted) { main.post { it.release() } } }
                worker.execute { completed(lease) }
            }
        },
        startPlayback = { completed ->
            val token = launchGeneration
            pendingAutostart = completed
            main.post {
                if (cancellation.isCancelled(token) || closing || !launch.startingPlayback || pendingAutostart !== completed) {
                    worker.execute { completeAutostart(completed, false) }
                } else {
                    autostartPlayback { accepted ->
                        if (!accepted) worker.execute { completeAutostart(completed, false) }
                        // Success is completed by playAudio, after playback takes focus ownership.
                    }
                }
            }
        },
        noFocusChanged = { denied -> main.post { noFocusChanged(denied) } },
        cancelled = { cancellation.isCancelled(launchGeneration) },
        isPlaying = ::playbackActive, isPlaybackStopping = { playbackStopping }) }

    fun openingGeneration(): Long = cancellation.capture()
    fun openApplication(station: TuneTarget?, generation: Long) {
        worker.execute {
            if (cancellation.isCancelled(generation)) return@execute
            launchGeneration = generation
            launch.open(autostartEnabled(), station)
        }
    }
    fun cancelAutostart() {
        cancellation.cancel()
        worker.execute { launch.cancel() }
    }
    private fun completeAutostart(completed: (Boolean) -> Unit, started: Boolean) {
        if (pendingAutostart === completed) pendingAutostart = null
        completed(started)
    }

    private var playbackStopped: (() -> Unit)? = null
    @Volatile private var closing = false
    private val sink by lazy { AudioTrackSink() }
    private val focus by lazy { AudioFocusController(AndroidAudioFocusManager(
        context.getSystemService(android.media.AudioManager::class.java)), sink, log) }
    private val audio: AudioPlayback by lazy {
        AudioPlayback({ controller.state == ConnectionState.Observing },
            UsbIsoSource { transport.fileDescriptor }, sink, transport, log, focus,
            threadFactory = { task -> Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                task.run()
            }, "Audio") }, onMain = { task -> main.post { task() } }) { counters ->
            main.post { audioChanged(counters) }
            if (counters.state in listOf(AudioState.Stopped, AudioState.Failed)) finishPlayback()
        }
    }
    val audioCounters get() = audio.snapshot.copy(lostLogRecords = log.lostRecords,
        logWriteFailures = log.writeFailures)
    private val permissionAction = "${context.packageName}.USB_PERMISSION"
    private val instance = java.util.UUID.randomUUID().toString()
    private var started = false
    private var polling: java.util.concurrent.ScheduledFuture<*>? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            worker.execute {
                try {
                    when (intent.action) {
                        UsbManager.ACTION_USB_DEVICE_ATTACHED -> discover()
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> device?.let {
                            moduleGone(it.deviceName)
                            discover()
                        }
                        permissionAction -> {
                            val uri = intent.data
                            if (uri?.authority != instance) return@execute
                            val token = uri.lastPathSegment?.toLongOrNull() ?: return@execute
                            val current = manager.deviceList[controller.deviceId]
                            controller.permissionResult(token, current != null && manager.hasPermission(current))
                        }
                    }
                } catch (_: Exception) { controller.error() }
            }
        }
    }
    fun start() {
        if (started) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(permissionAction)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        started = true
        polling = worker.scheduleWithFixedDelay({
            audio.receive()
            if (audio.moduleDetached) controller.deviceId?.let { moduleGone(it) }
            controller.receive()
            launch.tick()
        }, 0, 10, TimeUnit.MILLISECONDS)
        retry()
    }
    fun select(band: Band, frequencyKhz: Int) { cancelAutostart(); worker.execute { controller.select(band, frequencyKhz) } }
    fun seek(direction: Direction) { cancelAutostart(); worker.execute { controller.seek(direction) } }
    fun checkConnection() { worker.execute { controller.checkConnection() } }
    fun retry() { worker.execute { try { discover() } catch (_: Exception) { controller.error() } } }
    fun playAudio(autostart: Boolean = false, started: () -> Unit = {}, stopped: () -> Unit) {
        if (closing) { stopped(); return }
        if (!autostart) cancelAutostart()
        playbackServiceActive = true
        worker.execute {
            val completed = if (autostart) pendingAutostart else null
            if (closing || (autostart && (cancellation.isCancelled(launchGeneration) || !launch.startingPlayback))) {
                completed?.let { completeAutostart(it, false) }
                if (!audioActive()) stopped() // A cancelled old start cannot stop an already playing service.
                return@execute
            }
            playbackStopped = stopped
            val playing = audio.play()
            completed?.let { completeAutostart(it, playing) }
            if (playing || audioActive()) main.post { started() }
            if (!playing && audio.snapshot.state in listOf(AudioState.Stopped, AudioState.Failed)) finishPlayback()
        }
    }
    fun selectStation(entry: fm.heterodyne.app.stations.StationEntry) {
        cancelAutostart()
        if (!closing) worker.execute { controller.select(Band.valueOf(entry.band.name), entry.frequencyKhz) }
    }
    fun stepStation(direction: Direction) { cancelAutostart(); if (!closing) worker.execute { controller.step(direction) } }
    fun seekStation(direction: Direction) { cancelAutostart(); if (!closing) worker.execute { controller.seek(direction) } }
    fun reclaimAudioFocus(completed: () -> Unit) {
        if (!closing) main.post { focus.reclaim(); completed() }
    }
    fun updatePlaybackService(active: Boolean) {
        playbackServiceActive = active
        if (!active) worker.execute { audio.stop(); playbackStopping = false }
    }
    fun stopAudio() {
        if (!closing) worker.execute { audio.stop() }
    }
    private fun finishPlayback() {
        val stopped = playbackStopped ?: return
        playbackStopped = null
        main.post { stopped() }
    }
    private fun moduleGone(deviceId: String) {
        if (controller.deviceId != deviceId) return
        audio.onModuleDetached()
        controller.detach(deviceId)
        audio.acknowledgeDetach()
    }
    private fun discover() {
        val device = manager.deviceList.values.firstOrNull { UsbDiscovery.find(it.description()) != null } ?: return
        if (controller.deviceId != null && controller.deviceId != device.deviceName) return
        controller.found(UsbDiscovery.find(device.description())!!)
        val token = controller.requestPermission() ?: return
        if (manager.hasPermission(device)) controller.permissionResult(token, true)
        else {
            val intent = Intent(permissionAction).setPackage(context.packageName)
                .setData(Uri.parse("heterodyne-usb://$instance/$token"))
            val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            manager.requestPermission(device, pending)
        }
    }
    fun close() {
        if (!started) return
        context.unregisterReceiver(receiver)
        started = false
        polling?.cancel(false)
        closing = true
        worker.execute { try { launch.cancel(); controller.close() } finally { log.close(); worker.shutdown() } }
    }
}

private fun UsbDevice.description() = UsbDescription(deviceName, vendorId, productId,
    (0 until interfaceCount).map { index ->
        val item = getInterface(index)
        UsbInterfaceDescription(
            number = item.id,
            deviceClass = item.interfaceClass,
            alternate = item.alternateSetting,
            subclass = item.interfaceSubclass,
            protocol = item.interfaceProtocol,
            endpoints = (0 until item.endpointCount).map {
                val ep = item.getEndpoint(it)
                UsbEndpointDescription(ep.address, ep.type, ep.maxPacketSize)
            },
        )
    })

/**
 * USB lifecycle is separate from radio commands; only ModuleController owns TX policy.
 * openDevice -> claim IF 2 and IF 3, force=false; close releases those claims and connection.
 * Audio borrows that connection, claims IF 1 force=true, and selects alt 1.
 * No IF 0 claim, line coding, DTR/RTS or CDC control transfers. Not tested on hardware.
 */
private class AndroidUsbTransport(
    private val manager: UsbManager,
    private val beforeClose: () -> Boolean,
) : UsbTransport, UsbAudioPort {
    private var connection: UsbDeviceConnection? = null
    private var device: UsbDevice? = null
    private var audioInterface: UsbInterface? = null
    private var audioAlt0: UsbInterface? = null
    var fileDescriptor = -1; private set
    private val claimed = mutableListOf<UsbInterface>()
    private var output: android.hardware.usb.UsbEndpoint? = null
    private var input: android.hardware.usb.UsbEndpoint? = null
    override fun open(profile: DeviceProfile) {
        check(connection == null)
        val device = checkNotNull(manager.deviceList[profile.deviceId])
        check(manager.hasPermission(device))
        check(UsbDiscovery.find(device.description()) != null)
        connection = checkNotNull(manager.openDevice(device))
        fileDescriptor = checkNotNull(connection).fileDescriptor
        this.device = device
        try {
            for (number in listOf(profile.controlInterface, profile.dataInterface)) {
                val item = (0 until device.interfaceCount).map(device::getInterface)
                    .single { it.id == number && it.alternateSetting == 0 }
                check(connection!!.claimInterface(item, false))
                claimed.add(item)
                if (number == profile.dataInterface) {
                    val endpoints = (0 until item.endpointCount).map(item::getEndpoint)
                    input = endpoints.single { it.address == profile.bulkIn }
                    output = endpoints.single { it.address == profile.bulkOut }
                }
            }
        } catch (failure: Exception) { close(); throw failure }
    }
    override fun receive(): ByteArray {
        val bytes = ByteArray(64)
        val count = checkNotNull(connection).bulkTransfer(checkNotNull(input), bytes, bytes.size, 20)
        // Android returns -1 for both an idle read timeout and other IO failures.
        // Detach is handled by the broadcast; timeout escalation belongs to the session ticket.
        return if (count > 0) bytes.copyOf(count) else byteArrayOf()
    }
    override fun send(bytes: ByteArray) {
        val count = checkNotNull(connection).bulkTransfer(checkNotNull(output), bytes, bytes.size, 250)
        check(count == bytes.size) { "USB write incomplete" }
    }
    override fun claim() {
        check(audioInterface == null)
        val device = checkNotNull(device)
        val interfaces = (0 until device.interfaceCount).map(device::getInterface)
        val alt0 = interfaces.single { it.id == 1 && it.alternateSetting == 0 }
        val alt1 = interfaces.single { it.id == 1 && it.alternateSetting == 1 }
        check((0 until alt1.endpointCount).map(alt1::getEndpoint).any {
            it.address == 0x81 && it.type == 1 && it.maxPacketSize == 312
        })
        check(checkNotNull(connection).claimInterface(alt1, true))
        audioInterface = alt1
        audioAlt0 = alt0
    }
    override fun alt1() { check(checkNotNull(connection).setInterface(checkNotNull(audioInterface))) }
    override fun alt0() { check(checkNotNull(connection).setInterface(checkNotNull(audioAlt0))) }
    override fun release() {
        val item = audioInterface ?: return
        audioInterface = null
        audioAlt0 = null
        checkNotNull(connection).releaseInterface(item)
    }
    override fun close() {
        val opened = connection ?: return
        check(beforeClose()) { "Audio thread still owns the descriptor" }
        connection = null
        fileDescriptor = -1
        device = null
        input = null
        output = null
        try { claimed.forEach { opened.releaseInterface(it) } }
        finally { claimed.clear(); opened.close() }
    }
}

/** Closed list; no raw command entry point. Direction names follow the command catalog. */
enum class DiagnosticCommand(val label: String) {
    RequestAllInfo("requestAllInfo"), TuneUp("TUNEUP"), TuneDown("TUNEDOWN"), Band("BAND"),
    PlaySelect("playSelect — текущая частота"), SeekUp("SEEKUP"), SeekDown("SEEKDOWN")
}
