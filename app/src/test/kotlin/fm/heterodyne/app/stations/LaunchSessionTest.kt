package fm.heterodyne.app.stations

import android.app.Application
import android.os.Handler
import android.os.Looper
import fm.heterodyne.app.StationsSession
import fm.heterodyne.app.module.Band as ModuleBand
import fm.heterodyne.app.module.TuneTarget
import fm.heterodyne.app.module.FakeTransport
import fm.heterodyne.app.module.DeviceProfile
import fm.heterodyne.app.module.ModuleController
import fm.heterodyne.app.module.LaunchAutostart
import fm.heterodyne.app.DiagnosticLog
import fm.heterodyne.app.audio.AudioFocusLease
import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LaunchSessionTest {
    @Test fun onlyValidPersistedLastPlayedRecordSuppliesLaunchTarget() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "launch-session.json")
        val playing = StationsState.defaults().copy(
            lastStation = SessionStation(Band.FM, 90300), lastPlayed = true)
        listOf(null, "broken", StationsJson.encode(playing).replace("90300", "90301"),
            StationsJson.encode(playing.copy(lastPlayed = false)),
            StationsJson.encode(playing.copy(lastStation = null)), StationsJson.encode(playing)).forEach { text ->
            if (text == null) file.delete() else file.writeText(text)
            val session = StationsSession(context, Handler(Looper.getMainLooper()), Executor { it.run() },
                AtomicStationsFile(file))
            var target: TuneTarget? = null
            var callbacks = 0
            session.load {}
            session.readLaunchStation { target = it; callbacks++ }
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertNull(session.screen.error)
            assertEquals(1, callbacks)
            val valid = text == StationsJson.encode(playing)
            assertEquals(if (valid) TuneTarget(ModuleBand.FM, 90300) else null, target)
            val transport = FakeTransport()
            val log = DiagnosticLog(File(context.cacheDir, "launch-session-journal"))
            val module = ModuleController(transport, log)
            val launch = LaunchAutostart(module, log, { 0L }, { it(AudioFocusLease(true) {}) }, { it(true) })
            launch.open(true, target)
            module.found(DeviceProfile("module"))
            module.permissionResult(module.requestPermission()!!, true)
            launch.tick()
            assertEquals(if (valid) 1 else 0, transport.sendCount)
            module.close()
        }
    }

    @Test fun returnReadsCurrentSavedStationAndStopFlag() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "return-session.json")
        file.writeText(StationsJson.encode(StationsState.defaults().copy(
            lastStation = SessionStation(Band.FM, 90300), lastPlayed = true)))
        val session = StationsSession(context, Handler(Looper.getMainLooper()), Executor { it.run() }, AtomicStationsFile(file))
        session.load {}
        val transport = FakeTransport()
        val log = DiagnosticLog(File(context.cacheDir, "return-session-journal"))
        var now = 0L
        var plays = 0
        lateinit var launch: LaunchAutostart
        val module = ModuleController(transport, log, clock = { now }, moduleReturning = {
            session.readLaunchStation { launch.moduleReturn(true, it) }
        })
        launch = LaunchAutostart(module, log, { now }, { it(AudioFocusLease(true) {}) }, { plays++; it(true) })
        fun connect() {
            module.found(DeviceProfile("module"))
            module.permissionResult(module.requestPermission()!!, true)
            launch.tick()
        }
        fun reply() {
            transport.enqueueReceived(javaClass.getResourceAsStream("/module/fm205.bin")!!.use { it.readBytes() })
            module.receive(); launch.tick()
        }
        connect()
        session.rememberStation(ModuleBand.FM, 108000) {}
        module.detach("module"); connect(); reply()
        assertEquals(1, plays)
        assertEquals(1, transport.sendCount)
        session.setLastPlayed(false)
        module.detach("module"); now = 40000; connect(); reply()
        assertEquals(1, plays)
        assertEquals(listOf(0x85, 0x85), transport.sentFrames.map { it[6].toInt() and 255 })
        module.close()
    }

    @Test fun reopeningAfterStopReadsTheSavedFalseFlag() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "launch-session-stop.json")
        file.writeText(StationsJson.encode(StationsState.defaults().copy(
            lastStation = SessionStation(Band.FM, 90300), lastPlayed = true)))
        val session = StationsSession(context, Handler(Looper.getMainLooper()), Executor { it.run() }, AtomicStationsFile(file))
        session.load {}
        var target: TuneTarget? = null
        session.readLaunchStation { target = it }
        assertEquals(TuneTarget(ModuleBand.FM, 90300), target)
        session.setLastPlayed(false)
        session.readLaunchStation { target = it }
        assertNull(target)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun temporaryUiErrorDoesNotInvalidateSavedPlayingStation() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "launch-session-ui-error.json")
        file.writeText(StationsJson.encode(StationsState.defaults().copy(
            lastStation = SessionStation(Band.FM, 90300), lastPlayed = true)))
        val session = StationsSession(context, Handler(Looper.getMainLooper()), Executor { it.run() }, AtomicStationsFile(file))
        session.load {}
        session.createSelection("moskva", "Основной") // A duplicate name is a temporary UI message.
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(session.screen.error)
        var target: TuneTarget? = null
        session.readLaunchStation { target = it }
        assertEquals(TuneTarget(ModuleBand.FM, 90300), target)
    }
}
