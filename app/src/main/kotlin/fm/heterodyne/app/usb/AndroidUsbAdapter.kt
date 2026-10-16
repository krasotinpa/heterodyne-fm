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
) {
    val frames = fm.heterodyne.app.DiagnosticFrames()
    private val export = fm.heterodyne.app.DiagnosticExport(
        { controller.suspendForExport(); frames.resetConnection() }, { retry() })
    fun readJournal(done: (String) -> Unit) { worker.execute {
        done(try { log.readTail(32768) } catch (_: Exception) { "Не удалось прочитать Журнал" })
    } }
    fun beginExport(done: (Boolean) -> Unit) { worker.execute {
        try { export.begin(); done(true) } catch (_: Exception) { done(false) }
    } }
    fun finishExport(output: (() -> java.io.OutputStream)? = null, done: (String?) -> Unit) {
        worker.execute { done(export.finish(output?.let { open -> { open().use { log.copyTo(it) } } })) }
    }
    fun diagnosticCommand(command: DiagnosticCommand, done: (TuneResult) -> Unit) { worker.execute {
        if (export.active) { done(TuneResult.NotConnected); return@execute }
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
    } }
    private val manager = context.getSystemService(UsbManager::class.java)
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val log by lazy {
        DiagnosticLog(java.io.File(context.filesDir, "module-diagnostics")).also {
            it.frameListener = frames::accept
            it.buildVersion(context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown")
        }
    }
    private val transport: AndroidUsbTransport = AndroidUsbTransport(manager) { audio.stop() }
    private val controller: ModuleController by lazy { ModuleController(transport, log, stationChanged = stationChanged, changed = { state ->
        if (state == ConnectionState.Disconnected) frames.resetConnection()
        changed(state)
    }) }
    private var playbackStopped: (() -> Unit)? = null
    private var audioGeneration = 0L
    @Volatile private var closing = false
    private val audio: AudioPlayback by lazy {
        val sink = AudioTrackSink()
        val focus = AudioFocusController(AndroidAudioFocusManager(
            context.getSystemService(android.media.AudioManager::class.java)), sink)
        AudioPlayback({ controller.state == ConnectionState.Observing },
            UsbIsoSource { transport.fileDescriptor }, sink, transport, log, focus) { counters ->
            audioChanged(counters)
            if (counters.state in listOf(AudioState.Stopped, AudioState.Failed)) finishPlayback()
        }
    }
    val audioCounters get() = audio.snapshot
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
                            if (controller.deviceId == it.deviceName) audio.onModuleDetached()
                            controller.detach(it.deviceName)
                            discover()
                        }
                        permissionAction -> {
                            if (export.active) return@execute
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
        polling = worker.scheduleWithFixedDelay({ controller.receive() }, 0, 10, TimeUnit.MILLISECONDS)
        retry()
    }
    fun retry() { worker.execute { try { discover() } catch (_: Exception) { controller.error() } } }
    fun playAudio(stopped: () -> Unit) {
        if (closing) { stopped(); return }
        worker.execute {
            if (closing || export.active) { stopped(); return@execute }
            playbackStopped = stopped
            if (audio.play()) {
                val generation = ++audioGeneration
                worker.execute { receiveAudio(generation) }
            } else if (audio.snapshot.state in listOf(AudioState.Stopped, AudioState.Failed)) finishPlayback()
        }
    }
    fun stopAudio() { if (!closing) worker.execute { audio.stop() } }
    private fun finishPlayback() {
        val stopped = playbackStopped ?: return
        playbackStopped = null
        stopped()
    }
    private fun receiveAudio(generation: Long) {
        if (closing || generation != audioGeneration) return
        audio.receive()
        if (!closing && audio.snapshot.state in listOf(AudioState.Starting, AudioState.Playing)) {
            worker.execute { receiveAudio(generation) }
        }
    }
    private fun discover() {
        if (export.active) return
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
        worker.execute { try { controller.close() } finally { worker.shutdown() } }
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
    private val beforeClose: () -> Unit,
) : UsbTransport, UsbAudioPort {
    private var connection: UsbDeviceConnection? = null
    private var device: UsbDevice? = null
    private var audioInterface: UsbInterface? = null
    private var audioAlt0: UsbInterface? = null
    val fileDescriptor get() = checkNotNull(connection).fileDescriptor
    private val claimed = mutableListOf<UsbInterface>()
    private var output: android.hardware.usb.UsbEndpoint? = null
    private var input: android.hardware.usb.UsbEndpoint? = null
    override fun open(profile: DeviceProfile) {
        check(connection == null)
        val device = checkNotNull(manager.deviceList[profile.deviceId])
        check(manager.hasPermission(device))
        check(UsbDiscovery.find(device.description()) != null)
        connection = checkNotNull(manager.openDevice(device))
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
        val count = checkNotNull(connection).bulkTransfer(checkNotNull(input), bytes, bytes.size, 250)
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
        try { beforeClose() }
        finally {
            connection = null
            device = null
            input = null
            output = null
            try { claimed.forEach { opened.releaseInterface(it) } }
            finally { claimed.clear(); opened.close() }
        }
    }
}

/** Closed list; no raw command entry point. Direction names follow the command catalog. */
enum class DiagnosticCommand(val label: String) {
    RequestAllInfo("requestAllInfo"), TuneUp("TUNEUP"), TuneDown("TUNEDOWN"), Band("BAND"),
    PlaySelect("playSelect — текущая частота"), SeekUp("SEEKUP"), SeekDown("SEEKDOWN")
}
