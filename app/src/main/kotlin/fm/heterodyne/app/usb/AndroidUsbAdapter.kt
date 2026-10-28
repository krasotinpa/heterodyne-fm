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
import fm.heterodyne.app.AsyncDiagnosticLog
import fm.heterodyne.app.DiagnosticLog
import fm.heterodyne.app.audio.*
import fm.heterodyne.app.module.*
import fm.heterodyne.app.receiver.Receiver
import fm.heterodyne.app.receiver.ReceiverProfiles
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Application owns one adapter. Discovery, permission and open run on its worker, outside UI. */
class AndroidUsbAdapter(
    private val context: Context,
    private val stationChanged: (Band, Int?) -> Unit = { _, _ -> },
    private val changed: (ConnectionState) -> Unit,
    private val audioChanged: (AudioCounters) -> Unit = {},
    private val transitionObserved: (TransitionSnapshot) -> Unit = {},
    seekPaused: (Long) -> Unit = {},
    private val autostartPlayback: ((Boolean) -> Unit) -> Unit = { it(false) },
    private val noFocusChanged: (Boolean) -> Unit = {},
    private val readLaunchStation: ((TuneTarget?) -> Unit) -> Unit = { it(null) },
    private val profiles: ReceiverProfiles = ReceiverProfiles.Module,
    private val additionalReceivers: List<Receiver> = emptyList(),
    private val selectionChanged: (fm.heterodyne.app.receiver.ReceiverKind?, fm.heterodyne.app.receiver.ReceiverCapabilities?) -> Unit = { _, _ -> },
) {
    private val preferences = fm.heterodyne.app.ui.ReceiverPreferences(
        context.getSharedPreferences("receiver", Context.MODE_PRIVATE))
    private val selection = fm.heterodyne.app.receiver.ReceiverSelection(profiles.kinds, preferences.kind, preferences::selectKind)
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
            if (selection.selectedKind != fm.heterodyne.app.receiver.ReceiverKind.Module) {
                done(TuneResult.NotConnected)
                return@execute
            }
            val result = when (command) {
                DiagnosticCommand.RequestAllInfo -> receiver?.checkConnection() ?: TuneResult.NotConnected
                DiagnosticCommand.TuneUp -> receiver?.step(Direction.Down)
                DiagnosticCommand.TuneDown -> receiver?.step(Direction.Up)
                DiagnosticCommand.Band -> receiver?.switchBand() ?: TuneResult.NotConnected
                DiagnosticCommand.PlaySelect -> receiver?.snapshot?.confirmed?.let { receiver?.select(it.band, it.frequencyKhz) }
                    ?: TuneResult.BandNotFM
                DiagnosticCommand.SeekUp -> receiver?.seek(Direction.Down)
                DiagnosticCommand.SeekDown -> receiver?.seek(Direction.Up)
            }
            done(result ?: TuneResult.NotConnected)
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
    private var activeDeviceId: String? = null
    private var permissionEpoch = 0L
    private var openingStation: TuneTarget? = null
    private var pendingOpening = false
    private var openingRequestedAt = 0L
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
        onReadAudioCounters = { audio.snapshot }, onStartAudio = { audio.play() }, onStopAudio = { audio.stop() },
        moduleReturning = ::moduleReturning,
        transitionStarted = { signal ->
            val method = if (!signal) TransitionSignalMethod.Off else fm.heterodyne.app.ui.TransitionSignalPreferences.readMethod(
                context.getSharedPreferences("transition-signal", Context.MODE_PRIVATE))
            main.post { transitionSignal.begin(method) }
        },
        transitionCommandSent = { sentAt -> main.post { transitionSignal.commandSent(sentAt) } },
        transitionChanged = { state ->
            when (state) {
                TransitionState.Unconfirmed, TransitionState.Unresponsive, TransitionState.Disconnected ->
                    main.post { transitionSignal.stop() }
                else -> Unit
            }
        },
        transitionConfirmed = { main.post { transitionSignal.confirm() } },
        beforeTick = { launch.guardPendingWork() },
        seekLimitMillis = { fm.heterodyne.app.ui.SeekPreferences.readSeconds(
            context.getSharedPreferences("seek", Context.MODE_PRIVATE)).toLong() * 1000 },
        frameObserved = { direction, bytes -> frames.accept(direction, bytes, System.currentTimeMillis()) }, stationChanged = { band, frequency -> if (selection.selectedKind == fm.heterodyne.app.receiver.ReceiverKind.Module) main.post { stationChanged(band, frequency) } }, changed = { state ->
        if (state == ConnectionState.Disconnected) {
            connectionGeneration++
            returnGeneration = cancellation.capture()
            playbackStopping = playbackServiceActive
            frames.resetConnection()
        }
        if (selection.selectedKind == fm.heterodyne.app.receiver.ReceiverKind.Module) main.post { changed(state) }
    }, transitionObserved = { if (selection.selectedKind == fm.heterodyne.app.receiver.ReceiverKind.Module) publishSelection() },
        seekPaused = { remaining -> main.post { seekPaused(remaining) } }) }
    private val cancellation = AutostartGeneration()
    @Volatile private var launchGeneration = cancellation.capture()
    @Volatile private var playbackServiceActive = false
    @Volatile private var playbackStopping = false
    @Volatile private var pendingAutostart: ((Boolean) -> Unit)? = null
    private fun audioActive() = receiver?.audioCounters?.state in listOf(AudioState.Starting, AudioState.Playing)
    private fun playbackActive() = playbackServiceActive || audioActive() || focus.playbackActive
    private val receiver: Receiver? get() = when (selection.selectedKind) {
        fm.heterodyne.app.receiver.ReceiverKind.Module -> controller
        else -> additionalReceivers.singleOrNull { it.kind == selection.selectedKind }
    }
    private var launchInstance: LaunchAutostart? = null
    private val launch: LaunchAutostart get() = launchInstance ?: createLaunch().also { launchInstance = it }
    private fun createLaunch() = LaunchAutostart(receiver, log,
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
        isPlaying = ::playbackActive, isPlaybackStopping = { playbackStopping })

    fun openingGeneration(): Long = cancellation.capture()
    fun openApplication(station: TuneTarget?, generation: Long) {
        worker.execute {
            if (cancellation.isCancelled(generation)) return@execute
            launchGeneration = generation
            openingRequestedAt = android.os.SystemClock.elapsedRealtime()
            openingStation = station
            pendingOpening = true
            startPendingOpening()
        }
    }
    private fun startPendingOpening() {
        if (!pendingOpening || !selection.discovered || receiver == null) return
        pendingOpening = false
        if (android.os.SystemClock.elapsedRealtime() - openingRequestedAt >= 30000) return
        launch.open(autostartEnabled(), openingStation)
    }
    fun cancelAutostart() {
        cancellation.cancel()
        worker.execute { pendingOpening = false; launch.cancel() }
    }
    private fun completeAutostart(completed: (Boolean) -> Unit, started: Boolean) {
        if (pendingAutostart === completed) pendingAutostart = null
        completed(started)
    }

    private var playbackStopped: (() -> Unit)? = null
    @Volatile private var closing = false
    private val sink by lazy { AudioTrackSink() }
    private val audioManager by lazy { context.getSystemService(android.media.AudioManager::class.java) }
    private val transitionSignal: TransitionSignal by lazy {
        TransitionSignal(StaticTransitionTone(), log,
            { !closing && audio.snapshot.state == AudioState.Playing },
            clock = { android.os.SystemClock.elapsedRealtime() },
            schedule = { delay, task -> main.postDelayed({ task() }, delay) },
            silence = { focus.setTransitionSilence(it) },
            keyClickOutput = StaticTransitionTone(fm.heterodyne.app.audio.transitionKeyClick()),
            systemKeyClick = { volume ->
                // -1f задаёт громкость платформы, 0.3f передаёт DUCK; после запуска эффект неуправляем.
                audioManager.playSoundEffect(android.media.AudioManager.FX_KEY_CLICK, volume)
            })
    }
    private val focus: AudioFocusController by lazy { AudioFocusController(AndroidAudioFocusManager(
        audioManager), sink, log, signalVolume = transitionSignal::setFocusVolume) }
    private val audio: AudioPlayback by lazy {
        AudioPlayback({ receiver?.state == ConnectionState.Observing },
            UsbIsoSource { transport.fileDescriptor }, sink, transport, log, focus,
            threadFactory = { task -> Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                task.run()
            }, "Audio") }, onMain = { task -> main.post { task() } },
            pauseContext = { controller.pauseContext(android.os.SystemClock.elapsedRealtime()) + ", " + focus.status }) { counters ->
            main.post {
                if (counters.state != AudioState.Playing) transitionSignal.stop()
                audioChanged(counters)
            }
            if (counters.state in listOf(AudioState.Stopped, AudioState.Failed)) finishPlayback()
        }
    }
    val audioCounters get() = (receiver?.audioCounters ?: AudioCounters()).copy(lostLogRecords = log.lostRecords,
        logWriteFailures = log.writeFailures)
    private val permissionAction = "${context.packageName}.USB_PERMISSION"
    private val instance = java.util.UUID.randomUUID().toString()
    private var started = false
    private var polling: java.util.concurrent.ScheduledFuture<*>? = null
    private val broadcasts = object : BroadcastReceiver() {
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
                            if (uri.pathSegments.firstOrNull()?.toLongOrNull() != permissionEpoch) return@execute
                            val token = uri.lastPathSegment?.toLongOrNull() ?: return@execute
                            val current = manager.deviceList[activeDeviceId]
                            receiver?.permissionResult(token, current != null && manager.hasPermission(current))
                            publishSelection()
                        }
                    }
                } catch (_: Exception) { receiverError() }
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
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(broadcasts, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(broadcasts, filter)
        started = true
        polling = worker.scheduleWithFixedDelay({
            audio.receive()
            if (audio.moduleDetached) controller.deviceId?.let { moduleGone(it) }
            if (selection.selectedKind == fm.heterodyne.app.receiver.ReceiverKind.Module) controller.receive()
            if (selection.selectedKind != fm.heterodyne.app.receiver.ReceiverKind.Module) publishSelection()
            launch.tick()
        }, 0, 10, TimeUnit.MILLISECONDS)
        retry()
    }
    fun select(band: Band, frequencyKhz: Int) { cancelAutostart(); worker.execute { receiver?.select(band, frequencyKhz) } }
    fun seek(direction: Direction) { cancelAutostart(); worker.execute { if (receiver?.capabilities?.seek == true) receiver?.seek(direction) } }
    fun checkConnection() { worker.execute { receiver?.checkConnection() ?: TuneResult.NotConnected } }
    fun retry() { worker.execute { try { discover() } catch (_: Exception) { receiverError() } } }
    private fun receiverError() {
        if (selection.selectedKind == fm.heterodyne.app.receiver.ReceiverKind.Module) controller.error()
        else { receiver?.stopAudio(); receiver?.close(); publishSelection() }
    }
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
            val playing = receiver?.playAudio() == true
            completed?.let { completeAutostart(it, playing) }
            if (playing || audioActive()) main.post { started() }
            if (!playing && audio.snapshot.state in listOf(AudioState.Stopped, AudioState.Failed)) finishPlayback()
        }
    }
    fun selectStation(entry: fm.heterodyne.app.stations.StationEntry) {
        if (!closing) select(Band.FM, entry.frequencyKhz)
    }
    fun stepStation(direction: Direction) { cancelAutostart(); if (!closing) worker.execute { receiver?.step(direction) } }
    fun seekStation(direction: Direction) = seek(direction)
    fun reclaimAudioFocus(completed: () -> Unit) {
        if (!closing) main.post { focus.reclaim(); completed() }
    }
    fun updatePlaybackService(active: Boolean) {
        playbackServiceActive = active
        if (!active) worker.execute { receiver?.stopAudio(); playbackStopping = false }
    }
    fun stopAudio() {
        if (!closing) worker.execute { receiver?.stopAudio() }
    }
    private fun finishPlayback() {
        val stopped = playbackStopped ?: return
        playbackStopped = null
        main.post { stopped() }
    }
    private fun moduleGone(deviceId: String) {
        if (activeDeviceId != deviceId) return
        activeDeviceId = null
        permissionEpoch++
        if (selection.selectedKind != fm.heterodyne.app.receiver.ReceiverKind.Module) {
            launch.cancel()
            receiver?.stopAudio()
            receiver?.close()
            publishSelection()
            return
        }
        if (controller.deviceId != deviceId) return
        audio.onModuleDetached()
        controller.detach(deviceId)
        audio.acknowledgeDetach()
    }
    fun selectReceiver(kind: fm.heterodyne.app.receiver.ReceiverKind, parked: () -> Boolean) {
        worker.execute {
            if (!parked() || kind !in profiles.kinds || kind == selection.selectedKind) return@execute
            pendingOpening = false
            launch.cancel()
            // stopAudio ждёт завершения звукового потока до закрытия USB (ADR 0004).
            if (receiver?.stopAudio() == false || !parked()) return@execute
            receiver?.close()
            activeDeviceId = null
            permissionEpoch++
            // Закрытие USB синхронное: движение могло начаться за это время (R28). Тогда остаётся прежний выбор.
            if (!selection.select(kind, parked())) { publishSelection(); discover(); return@execute }
            launchInstance = null
            publishSelection()
            discover()
        }
    }

    private var published = false
    private var publishedKind: fm.heterodyne.app.receiver.ReceiverKind? = null
    private var publishedState: ConnectionState? = null
    private var publishedSnapshot: TransitionSnapshot? = null
    private var publishedAudio: AudioCounters? = null
    private fun publishSelection() {
        val selected = receiver
        val kind = selection.selectedKind
        val capabilities = selected?.capabilities
        val state = selected?.state ?: ConnectionState.Disconnected
        val snapshot = selected?.snapshot?.takeUnless { state == ConnectionState.Disconnected }
            ?: TransitionSnapshot(TransitionState.Disconnected, null, null)
        val counters = selected?.audioCounters ?: AudioCounters()
        val selectionChangedNow = !published || publishedKind != kind
        if (!selectionChangedNow && state == publishedState && snapshot == publishedSnapshot && counters == publishedAudio) return
        val snapshotChangedNow = selectionChangedNow || snapshot != publishedSnapshot
        published = true
        publishedKind = kind
        publishedState = state
        publishedSnapshot = snapshot
        publishedAudio = counters
        if (kind != fm.heterodyne.app.receiver.ReceiverKind.Module && counters.state in listOf(AudioState.Stopped, AudioState.Failed))
            finishPlayback()
        main.post {
            if (selectionChangedNow) selectionChanged(kind, capabilities)
            changed(state)
            if (snapshotChangedNow) transitionObserved(snapshot)
            if (kind != fm.heterodyne.app.receiver.ReceiverKind.Module) {
                stationChanged(snapshot.confirmed?.band ?: Band.FM, snapshot.confirmed?.frequencyKhz)
                audioChanged(counters)
            }
        }
    }

    private fun discover() {
        val devices = manager.deviceList.values.mapNotNull { device ->
            UsbDiscovery.find(device.description(), profiles)?.let { device to it }
        }
        val before = selection.selectedKind
        selection.discover(devices.map { it.second })
        if (before != selection.selectedKind) { launchInstance?.cancel(); launchInstance = null }
        publishSelection()
        startPendingOpening()
        val selected = receiver ?: return
        val (device, profile) = devices.firstOrNull { it.second.kind == selection.selectedKind } ?: return
        if (selected === controller && controller.deviceId != null && controller.deviceId != device.deviceName) return
        activeDeviceId = device.deviceName
        selected.found(profile)
        val token = selected.requestPermission() ?: return
        if (manager.hasPermission(device)) selected.permissionResult(token, true)
        else {
            val intent = Intent(permissionAction).setPackage(context.packageName)
                .setData(Uri.parse("heterodyne-usb://$instance/$permissionEpoch/$token"))
            val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            manager.requestPermission(device, pending)
        }
        publishSelection()
    }
    fun close() {
        if (!started) return
        context.unregisterReceiver(broadcasts)
        started = false
        polling?.cancel(false)
        closing = true
        main.post { transitionSignal.stop() }
        worker.execute { try { launch.cancel(); receiver?.close() } finally { log.close(); worker.shutdown() } }
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
