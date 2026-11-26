package fm.heterodyne.app

import android.os.Handler
import fm.heterodyne.ui.contract.*
import fm.heterodyne.app.usb.AndroidUsbAdapter

class StationsDiagnostics(
    private val context: android.content.Context,
    private val main: Handler,
    private val usb: AndroidUsbAdapter,
) {
    internal var changed: () -> Unit = {}
    fun drivingEvent(text: String) { usb.probeEvent(text) }

    val audioCounters get() = usb.audioCounters
    val developerMode: DeveloperMode

    @Volatile private var probeEnabled = false
    @Volatile private var probeUiMode = context.resources.configuration.uiMode
    var environmentProbeLines = emptyList<String>()
        private set
    private val probeThread = lazy {
        android.os.HandlerThread("EnvironmentProbe").apply { start() }
    }
    private val probeHandler by lazy { Handler(probeThread.value.looper) }
    private val environmentProbe by lazy {
        fm.heterodyne.app.host.EnvironmentProbe(
            fm.heterodyne.app.host.AndroidEnvironmentProbe(context, probeHandler) { probeUiMode },
            android.os.SystemClock::elapsedRealtime,
        ) { if (probeEnabled) usb.probeEvent(it) }
    }
    private val probeTick = object : Runnable {
        override fun run() {
            val lines = environmentProbe.poll(probeEnabled)
            main.post { environmentProbeLines = if (probeEnabled) lines else emptyList() }
            if (probeEnabled) probeHandler.postDelayed(this, 1000)
        }
    }

    private fun enableProbe(enabled: Boolean) {
        probeEnabled = enabled
        if (!enabled && !probeThread.isInitialized()) return
        probeHandler.post {
            probeHandler.removeCallbacks(probeTick)
            if (enabled && probeEnabled) probeTick.run()
            else if (!enabled) {
                environmentProbe.poll(false)
                main.post { environmentProbeLines = emptyList() }
            }
        }
    }

    fun updateProbeUiMode(uiMode: Int) {
        probeUiMode = uiMode
        if (probeEnabled) {
            probeHandler.post {
                probeHandler.removeCallbacks(probeTick)
                if (probeEnabled) probeTick.run()
            }
        }
    }

    init {
        val preferences = context.getSharedPreferences("diagnostics", android.content.Context.MODE_PRIVATE)
        developerMode = DeveloperMode(preferences.getBoolean("developer", false)) {
            preferences.edit().putBoolean("developer", it).apply()
            enableProbe(it)
        }
        enableProbe(developerMode.enabled)
    }
    var export: ExportState = ExportState.Idle
        private set
    val exportBusy get() = export == ExportState.InProgress

    fun exportJournal(valid: () -> Boolean) {
        if (exportBusy) return
        export = ExportState.InProgress
        changed()
        usb.exportJournal(MediaStoreDownloads(context.contentResolver), valid) { result -> main.post {
            export = result?.fold({ ExportState.Exported(it.path, it.sizeBytes) }, { ExportState.Failed }) ?: ExportState.Idle
            changed()
        } }
    }

    fun readJournal(done: (String) -> Unit) = usb.readJournal { text -> main.post { done(text) } }

    fun readDetail(valid: () -> Boolean, done: (DeveloperDetail) -> Unit) {
        val counters = audioCounters
        val metrics = AudioMetrics(counters.bytes, counters.badPackets, counters.realignments, counters.underruns,
            counters.maxReadGapMs.takeIf { counters.hasReadGapSample }, counters.longPauses, counters.minBufferedFrames, counters.lostLogRecords, counters.logWriteFailures)
        val frames = usb.frames.snapshot().readOnly()
        val byte7 = usb.frames.byte7
        val lines = environmentProbeLines.readOnly()
        usb.readJournal(valid) { journal -> main.post {
            done(DeveloperDetail(emptySet(), frames, journal, metrics, lines, byte7, null))
        } }
    }
}
