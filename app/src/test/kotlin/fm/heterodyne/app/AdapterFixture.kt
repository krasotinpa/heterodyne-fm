package fm.heterodyne.app

import android.os.Handler
import android.os.Looper
import fm.heterodyne.app.audio.AudioSource
import fm.heterodyne.app.audio.UsbAudioPort
import fm.heterodyne.app.host.ReceiverPreferences
import fm.heterodyne.app.module.DeviceProfile
import fm.heterodyne.app.module.FakeTransport
import fm.heterodyne.app.receiver.FakeReceiver
import fm.heterodyne.app.receiver.ReceiverFixture
import fm.heterodyne.app.receiver.ReceiverKind
import fm.heterodyne.app.receiver.ReceiverProfiles
import fm.heterodyne.app.stations.catalogFixtureReader
import fm.heterodyne.ui.contract.*
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

open class AdapterFixture(
    profiles: ReceiverProfiles = ReceiverProfiles.Module,
    timeSource: TimeSource = TimeSource.Monotonic,
    private val otherDevice: DeviceProfile? = null,
) {
    val context = RuntimeEnvironment.getApplication()
    val main = Handler(Looper.getMainLooper())
    val transport = FakeTransport()
    val storage = ArrayDeque<Runnable>()
    val network = ArrayDeque<Runnable>()
    var catalogOpens = 0
    val session = StationsSession(context, main, storage = { storage.add(it) },
        network = { network.add(it) }, onlineCatalog = {
            catalogOpens++
            catalogFixtureReader()
        })
    private val worker = object : ScheduledThreadPoolExecutor(1) {
        val tasks = ArrayDeque<Runnable>()
        lateinit var poll: Runnable
        override fun execute(task: Runnable) { tasks.add(task) }
        override fun scheduleWithFixedDelay(task: Runnable, initial: Long, delay: Long, unit: TimeUnit) =
            super.scheduleWithFixedDelay(task, 1, 1, TimeUnit.DAYS).also { poll = task }
    }
    private val audio = object : AudioSource, UsbAudioPort {
        override fun open() = Unit
        override fun read(out: ByteArray) = 0
        override fun badPackets() = 0L
        override fun close() = Unit
        override fun claim() = Unit
        override fun alt1() = Unit
        override fun alt0() = Unit
        override fun release() = Unit
    }
    init {
        if (otherDevice != null) {
            ReceiverPreferences(context.getSharedPreferences("receiver", android.content.Context.MODE_PRIVATE))
                .selectKind(ReceiverKind.Module)
        }
    }
    var starts = 0
    val module = StationsModule(context, main, session, profiles = profiles,
        additionalReceivers = listOfNotNull(otherDevice?.let { FakeReceiver(it.kind) }),
        fixture = ReceiverFixture(DeviceProfile("synthetic"), transport, audio, audio, listOfNotNull(otherDevice)),
        worker = worker, startPlayback = ::startPlayback)
    private fun startPlayback() {
        starts++
        if (otherDevice != null && module.selectedReceiver == otherDevice.kind) {
            module.playAudio(stopped = { module.updatePlaybackService(false) })
        }
    }
    val adapter = ApplicationAdapter(context, module, session, StationsDiagnostics(context, main, module.usb), timeSource)
    val radio: RadioUi = adapter
    fun send(action: RadioAction) = runBlocking { radio.dispatch(action) }
    fun drainStorage() {
        while (storage.isNotEmpty()) storage.removeFirst().run()
        shadowOf(main.looper).idle()
    }
    fun drainWorker() {
        while (worker.tasks.isNotEmpty()) worker.tasks.removeFirst().run()
    }
    fun connect() {
        adapter.foreground(true)
        session.load {}
        drainStorage()
        module.usb.start()
        drainWorker()
        shadowOf(main.looper).idle()
    }
    fun receive(cid: Int, fm: Boolean = true, mode: Int = if (fm) 0x20 else 0) {
        val sum = -(0x22 + mode + cid)
        val frame = ByteArray(71)
        byteArrayOf(0xfa.toByte(), 0x55, 0, 0, 66, 0x22, mode.toByte(), cid.toByte()).copyInto(frame)
        frame[70] = sum.toByte()
        transport.enqueueReceived(frame)
        worker.poll.run()
    }
    fun poll() = worker.poll.run()
    fun idleMain() = shadowOf(main.looper).idle()
    fun close() {
        module.usb.close()
        drainWorker()
        worker.shutdownNow()
    }
}
