package fm.heterodyne.app.usb

import fm.heterodyne.ui.contract.RejectReason
import fm.heterodyne.app.reason
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
    private val stationChanged: (Band, Int, Long) -> Unit,
    private val observed: (ReceiverObservation) -> Unit,
    private val audioChanged: (AudioState) -> Unit,
    private val focusChanged: (fm.heterodyne.ui.contract.FocusEffect) -> Unit,
    seekPaused: (Long) -> Unit = {},
    private val autostartPlayback: ((Boolean) -> Unit) -> Unit = { it(false) },
    private val noFocusChanged: (Boolean) -> Unit = {},
    private val readLaunchStation: ((TuneTarget?) -> Unit) -> Unit = { it(null) },
    private val profiles: ReceiverProfiles = ReceiverProfiles.Module,
    private val additionalReceivers: List<Receiver> = emptyList(),
    private val fixture: fm.heterodyne.app.receiver.ReceiverFixture? = null,
    private val worker: java.util.concurrent.ScheduledExecutorService = moduleWorker(),
) {
    private val preferences = fm.heterodyne.app.host.ReceiverPreferences(
        context.getSharedPreferences("receiver", Context.MODE_PRIVATE))
    private val selection = fm.heterodyne.app.receiver.ReceiverSelection(profiles.kinds, preferences.kind, preferences::selectKind)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    val frames = fm.heterodyne.app.DiagnosticFrames()
    // Journal reads wait for the Journal thread; keep them off the Module thread so commands and detach never queue behind disk IO.
    private val journalReader = Executors.newSingleThreadExecutor { task -> Thread(task, "JournalReader").apply { isDaemon = true } }
    // Probe worker only enqueues; persistent writes belong to AsyncDiagnosticLog. No USB operation.
    fun probeEvent(text: String) { log.event(text) }
    fun readJournal(valid: () -> Boolean = { true }, done: (String) -> Unit) { journalReader.execute {
        if (!valid()) { done(""); return@execute }
        done(try { log.readTail(32768) } catch (_: Exception) { "Не удалось прочитать Журнал" })
    } }
    fun exportJournal(store: fm.heterodyne.app.DownloadStore,
                      valid: () -> Boolean, done: (Result<fm.heterodyne.app.JournalExportResult>?) -> Unit) {
        // Snapshot inside the read barrier (ring is capped at 10 MB); slow storage then never stalls the Journal thread.
        journalReader.execute { done(if (!valid()) null else runCatching {
            val snapshot = java.io.ByteArrayOutputStream().also(log::copyTo).toByteArray()
            fm.heterodyne.app.JournalExport(store).save { it.write(snapshot) }
        }) }
    }
    /** Both admission and the eventual worker result belong to the initiating context. */
    internal fun command(valid: () -> Boolean, result: (TuneResult) -> Unit,
                         perform: (Receiver) -> TuneResult) {
        worker.execute {
            if (!valid()) return@execute
            val outcome = receiver?.let(perform) ?: TuneResult.NotConnected
            postOutcome(outcome, valid) { result(outcome) }
        }
    }

    // A failed send may synchronously disconnect its own receiver. Report that failure
    // in the disconnected generation, until another session or screen supersedes it.
    private fun postOutcome(result: TuneResult, valid: () -> Boolean, deliver: () -> Unit) {
        val generation = connectionGeneration
        main.post {
            if (valid() || (result == TuneResult.TransportError && generation == connectionGeneration)) deliver()
        }
    }

    private data class PendingTune(val valid: () -> Boolean, val failed: (TuneResult) -> Unit)
    private var pendingTune: PendingTune? = null

    internal fun tune(khz: Int, valid: () -> Boolean, done: (RejectReason?) -> Unit) {
        worker.execute {
            if (!valid()) return@execute
            val selected = receiver
            val result = selected?.select(Band.FM, khz) ?: TuneResult.NotConnected
            val retained = selected?.snapshot?.target == TuneTarget(Band.FM, khz)
            if (retained) pendingTune = PendingTune(valid) { failure ->
                postOutcome(failure, valid) { done(failure.reason()) }
            }
            val reason = when {
                result != TuneResult.RateLimited -> result.reason()
                retained -> null // The controller kept the latest target until playSelect is due.
                else -> RejectReason.Busy
            }
            postOutcome(result, valid) { done(reason) }
        }
    }
    private val manager by lazy { context.getSystemService(UsbManager::class.java) }
    private val log by lazy {
        AsyncDiagnosticLog(create = {
            DiagnosticLog(java.io.File(context.filesDir, "module-diagnostics"),
                receiverKind = if (fixture == null) fm.heterodyne.app.receiver.ReceiverKind.Module
                    else fm.heterodyne.app.receiver.ReceiverKind("preflight")).also {
                it.buildVersion(context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown")
            }
        }, priority = { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) })
    }
    private val hardwareTransport by lazy { AndroidUsbTransport(manager) { audio.stop() } }
    private val transport: UsbTransport get() = fixture?.transport ?: hardwareTransport
    private var activeDeviceId: String? = null
    private var permissionEpoch = 0L
    private var openingStation: TuneTarget? = null
    private var pendingOpening = false
    private var openingRequestedAt = 0L
    @Volatile var connectionGeneration = 0L
        private set
    private fun postCurrent(action: () -> Unit) {
        val generation = connectionGeneration
        main.post { if (generation == connectionGeneration) action() }
    }

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
            val method = if (!signal) TransitionSignalMethod.Off else fm.heterodyne.app.host.TransitionSignalPreferences.readMethod(
                context.getSharedPreferences("transition-signal", Context.MODE_PRIVATE))
            postCurrent { transitionSignal.begin(method) }
        },
        transitionCommandSent = { sentAt -> postCurrent { transitionSignal.commandSent(sentAt) } },
        transitionChanged = { state ->
            when (state) {
                TransitionState.Unconfirmed, TransitionState.Unresponsive, TransitionState.Disconnected ->
                    postCurrent { transitionSignal.stop() }
                else -> Unit
            }
        },
        transitionConfirmed = { postCurrent { transitionSignal.confirm() } },
        deferredFailure = { pendingTune?.failed?.invoke(it) }, beforeTick = ::guardPending,
        seekLimitMillis = { fm.heterodyne.app.host.SeekPreferences.readSeconds(
            context.getSharedPreferences("seek", Context.MODE_PRIVATE)).toLong() * 1000 },
        frameObserved = { direction, bytes -> frames.accept(direction, bytes, System.currentTimeMillis()) }, stationChanged = { band, frequency ->
            val generation = connectionGeneration
            if (frequency != null && selection.selectedKind == fm.heterodyne.app.receiver.ReceiverKind.Module)
                postCurrent { stationChanged(band, frequency, generation) }
        }, changed = { state ->
            if (state == ConnectionState.Open || state == ConnectionState.Disconnected) connectionGeneration++
            if (state == ConnectionState.Disconnected) {
                pendingOpening = false
                cancellation.cancel()
                returnGeneration = cancellation.capture()
                main.post { transitionSignal.stop() }
                playbackStopping = playbackServiceActive
                frames.resetConnection()
            }
            publishSelection()
        }, transitionObserved = { publishSelection() },
        seekPaused = { remaining -> postCurrent { seekPaused(remaining) } }) }
    private fun guardPending() {
        if (pendingTune?.valid?.invoke() == false) controller.discardUnsentTarget()
        if (controller.target == null) pendingTune = null
        launch.guardPendingWork()
    }
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
        noFocusChanged = { denied -> val token = launchGeneration; postCurrent { if (!denied || !cancellation.isCancelled(token)) noFocusChanged(denied) } },
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
        audioManager), sink, log, effectChanged = focusChanged, signalVolume = transitionSignal::setFocusVolume) }
    private val audio: AudioPlayback by lazy {
        AudioPlayback({ receiver?.state == ConnectionState.Observing },
            fixture?.source ?: UsbIsoSource { hardwareTransport.fileDescriptor }, sink,
            fixture?.audioPort ?: hardwareTransport, log, focus,
            threadFactory = { task -> Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                task.run()
            }, "Audio") }, onMain = { task -> main.post { task() } },
            pauseContext = { controller.pauseContext(android.os.SystemClock.elapsedRealtime()) + ", " + focus.status }) { counters ->
            postCurrent {
                if (counters.state != AudioState.Playing) transitionSignal.stop()
                audioChanged(counters.state)
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
        if (fixture == null) {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(broadcasts, filter, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(broadcasts, filter)
        }
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
    fun wheelSelect(frequencyKhz: Int, valid: () -> Boolean) {
        cancelAutostart()
        command(valid, {}) { pendingTune = null; it.select(Band.FM, frequencyKhz) }
    }
    fun wheelSeek(direction: Direction, valid: () -> Boolean) {
        cancelAutostart()
        command(valid, {}) { it.seek(direction) }
    }
    fun retry(valid: () -> Boolean = { true }) { worker.execute { if (!valid()) return@execute; try { discover() } catch (_: Exception) { receiverError() } } }
    private fun receiverError() {
        if (selection.selectedKind == fm.heterodyne.app.receiver.ReceiverKind.Module) controller.error()
        else { receiver?.stopAudio(); receiver?.close(); publishSelection() }
    }
    fun playAudio(autostart: Boolean, started: () -> Unit, stopped: () -> Unit, valid: () -> Boolean) {
        if (closing) { stopped(); return }
        if (!autostart) cancelAutostart()
        playbackServiceActive = true
        worker.execute {
            val completed = if (autostart) pendingAutostart else null
            if (closing || !valid() || (autostart && (cancellation.isCancelled(launchGeneration) || !launch.startingPlayback))) {
                completed?.let { completeAutostart(it, false) }
                if (!audioActive()) stopped() // A cancelled old start cannot stop an already playing service.
                return@execute
            }
            playbackStopped = stopped
            val playing = receiver?.playAudio() == true
            completed?.let { completeAutostart(it, playing) }
            if (playing || audioActive()) main.post { if (valid() && playbackServiceActive) started() }
            if (!playing && audio.snapshot.state in listOf(AudioState.Stopped, AudioState.Failed)) finishPlayback()
        }
    }
    fun reclaimAudioFocus(completed: () -> Unit) {
        if (!closing && playbackServiceActive) { focus.reclaim(); completed() }
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
            connectionGeneration++
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
    fun selectReceiver(kind: fm.heterodyne.app.receiver.ReceiverKind, current: () -> Boolean, parked: () -> Boolean) {
        worker.execute {
            if (!current() || !parked() || kind !in profiles.kinds || kind == selection.selectedKind) return@execute
            pendingOpening = false
            launch.cancel()
            // stopAudio ждёт завершения звукового потока до закрытия USB (ADR 0004).
            if (receiver?.stopAudio() == false || !parked()) return@execute
            receiver?.close()
            activeDeviceId = null
            permissionEpoch++
            // Закрытие USB синхронное: движение могло начаться за это время (R28). Тогда остаётся прежний выбор.
            if (!selection.select(kind, parked())) { publishSelection(); discover(); return@execute }
            connectionGeneration++
            launchInstance = null
            publishSelection()
            discover()
        }
    }

    private var published: ReceiverObservation? = null
    private fun publishSelection() {
        val selected = receiver
        val connection = selected?.state ?: ConnectionState.Disconnected
        val snapshot = selected?.snapshot?.takeIf { connection == ConnectionState.Observing }
            ?: TransitionSnapshot(TransitionState.Disconnected, null, null)
        val next = ReceiverObservation(connectionGeneration, selection.selectedKind, selected?.capabilities,
            connection, snapshot, selected?.audioCounters?.state ?: AudioState.Stopped)
        if (published == next) return
        val previous = published
        published = next
        if (next.kind != fm.heterodyne.app.receiver.ReceiverKind.Module &&
            next.audio in listOf(AudioState.Stopped, AudioState.Failed)) finishPlayback()
        postCurrent {
            observed(next)
            if (next.kind != fm.heterodyne.app.receiver.ReceiverKind.Module && next.tuning.confirmed != previous?.tuning?.confirmed)
                next.tuning.confirmed?.let { stationChanged(it.band, it.frequencyKhz, next.generation) }
        }
    }

    private var fixtureConnected = true
    internal fun setFixtureConnected(connected: Boolean) {
        val current = checkNotNull(fixture)
        worker.execute {
            if (fixtureConnected != connected) {
                fixtureConnected = connected
                if (!connected) moduleGone(current.profile.deviceId)
                discover()
            }
        }
    }

    private fun discover() {
        val devices = fixture?.let { if (fixtureConnected) listOf(it.profile) + it.otherDevices else it.otherDevices } ?: manager.deviceList.values.mapNotNull { device ->
            UsbDiscovery.find(device.description(), profiles)
        }
        val before = selection.selectedKind
        selection.discover(devices)
        if (before != selection.selectedKind) { connectionGeneration++; launchInstance?.cancel(); launchInstance = null }
        publishSelection()
        startPendingOpening()
        val selected = receiver ?: return
        val profile = devices.firstOrNull { it.kind == selection.selectedKind } ?: return
        if (selected === controller && controller.deviceId != null && controller.deviceId != profile.deviceId) return
        activeDeviceId = profile.deviceId
        selected.found(profile)
        val token = selected.requestPermission() ?: return
        val device = if (fixture == null) manager.deviceList[profile.deviceId] ?: return else null
        if (fixture != null || manager.hasPermission(checkNotNull(device))) selected.permissionResult(token, true)
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
        if (fixture == null) context.unregisterReceiver(broadcasts)
        started = false
        polling?.cancel(false)
        closing = true
        postCurrent { transitionSignal.stop() }
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

internal fun moduleWorker() = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "Module") }

data class ReceiverObservation(val generation: Long, val kind: fm.heterodyne.app.receiver.ReceiverKind?,
    val capabilities: fm.heterodyne.app.receiver.ReceiverCapabilities?, val connection: ConnectionState,
    val tuning: TransitionSnapshot, val audio: AudioState)
