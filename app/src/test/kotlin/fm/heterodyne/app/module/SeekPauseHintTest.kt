package fm.heterodyne.app.module

import android.app.Application
import android.os.Handler
import android.os.Looper
import fm.heterodyne.app.DiagnosticLog
import fm.heterodyne.app.StationsModule
import fm.heterodyne.app.StationsSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SeekPauseHintTest {
    @Test fun rejectedDrivingSeekShowsHintForTwoSecondsWithoutSending() {
        val context = RuntimeEnvironment.getApplication()
        val handler = Handler(Looper.getMainLooper())
        val session = StationsSession(context, handler, java.util.concurrent.Executor { it.run() })
        val stations = StationsModule(context, handler, session, startPlayback = {})
        stations.updateDriving(true)
        var now = 0L
        val fake = FakeTransport()
        val controller = ModuleController(fake, DiagnosticLog(context.cacheDir.resolve("hint-test")),
            clock = { now }, seekPaused = stations::onSeekPaused)
        controller.found(DeviceProfile("module"))
        controller.permissionResult(controller.requestPermission()!!, true)
        fun receive() {
            fake.enqueueReceived(javaClass.getResourceAsStream("/module/fm28.bin")!!.use { it.readBytes() })
            controller.receive()
        }
        receive(); controller.seek(Direction.Up)
        now = 100; receive()
        now = 1000; controller.tick()
        assertEquals(TuneResult.RateLimited, controller.seek(Direction.Down))
        assertEquals(1, fake.sendCount)
        assertEquals("Поиск доступен через 4 с", stations.seekHint)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1999))
        assertNotNull(stations.seekHint)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertNull(stations.seekHint)
        stations.updateDriving(false)
        controller.seek(Direction.Down)
        assertNull(stations.seekHint)
    }
}
