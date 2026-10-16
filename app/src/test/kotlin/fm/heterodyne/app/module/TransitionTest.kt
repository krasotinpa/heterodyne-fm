package fm.heterodyne.app.module

import fm.heterodyne.app.DiagnosticLog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransitionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fake = FakeTransport()
    private var now = 0L
    private val controller by lazy {
        ModuleController(fake, DiagnosticLog(temporary.newFolder()), clock = { now }).also {
            it.found(DeviceProfile("module"))
            it.permissionResult(it.requestPermission()!!, true)
        }
    }
    private fun receive(name: String) {
        controller
        fake.enqueueReceived(javaClass.getResourceAsStream("/module/$name.bin")!!.use { it.readBytes() })
        controller.receive()
    }
    @Test fun waitingAndTimeoutRequireTargetReply() {
        receive("fm28")
        assertEquals(TransitionState.Idle, controller.transitionState)
        controller.tune(108000)
        now = 300; controller.tick()
        assertEquals(TransitionState.Idle, controller.transitionState)
        now = 301; controller.tick()
        assertEquals(TransitionState.Waiting, controller.transitionState)
        receive("fm0")
        assertEquals(TransitionState.Waiting, controller.transitionState)
        now = 3000; controller.tick()
        assertEquals(TransitionState.Unconfirmed, controller.transitionState)
        now = 9000; controller.tick()
        assertEquals(1, fake.sendCount)
        receive("fm205")
        assertEquals(TransitionState.Idle, controller.transitionState)
    }
    @Test fun lastTargetReplacesPendingBeforeLimitExpires() {
        receive("fm28")
        controller.tune(87500)
        now = 100; controller.tune(97700)
        now = 999; controller.tune(90300)
        assertEquals(1, fake.sendCount)
        assertEquals(TuneTarget(Band.FM, 90300), controller.target)
        now = 1000
        assertEquals(TuneResult.Sent, controller.tune(108000))
        assertEquals(2, fake.sendCount)
        assertEquals(205, fake.sentFrames.last()[8].toInt() and 255)
        now = 2000; controller.tick()
        assertEquals(2, fake.sendCount)
    }
    @Test fun stepPauseDropsPressesAndStillTimesOutWithoutReply() {
        receive("fm28")
        assertEquals(TuneResult.Sent, controller.step(Direction.Up))
        assertEquals(TransitionState.CommandPause, controller.transitionState)
        now = 499
        assertEquals(TuneResult.RateLimited, controller.step(Direction.Down))
        now = 500; controller.tick()
        assertEquals(TransitionState.Waiting, controller.transitionState)
        now = 3000; controller.tick()
        assertEquals(TransitionState.Unconfirmed, controller.transitionState)
        assertEquals(1, fake.sendCount)
    }
    @Test fun crossBandUsesOneDeadlineAndNeverSendsAfterTimeout() {
        receive("fm28")
        assertEquals(TuneResult.Sent, controller.select(Band.AM, 711))
        assertEquals(0x84, fake.sentFrames.last()[6].toInt() and 255)
        now = 2800; receive("am31")
        assertEquals(2, fake.sendCount)
        assertEquals(21, fake.sentFrames.last()[8].toInt() and 255)
        now = 3000; controller.tick()
        assertEquals(TransitionState.Unconfirmed, controller.transitionState)
        receive("fm28")
        now = 9000; controller.tick()
        assertEquals(2, fake.sendCount)
    }
    @Test fun sentBandCannotBeCancelledAndOppositeTargetWaitsTwoSeconds() {
        receive("fm28")
        controller.select(Band.AM, 711)
        now = 100; controller.select(Band.FM, 108000)
        assertEquals(1, fake.sendCount)
        now = 230; receive("am31")
        now = 1999; controller.tick()
        assertEquals(1, fake.sendCount)
        now = 2000; controller.tick()
        assertEquals(0x84, fake.sentFrames.last()[6].toInt() and 255)
        now = 2230; receive("fm28")
        assertEquals(3, fake.sendCount)
        receive("fm205")
        assertEquals(TransitionState.Idle, controller.transitionState)
    }
    @Test fun healthCheckTimeoutAndAnyValidFrameRecoverWithoutRetry() {
        receive("fm28")
        controller.tune(108000)
        now = 3000; controller.tick()
        assertEquals(TuneResult.Sent, controller.checkConnection())
        now = 6000; controller.tick()
        assertEquals(TransitionState.Unresponsive, controller.transitionState)
        assertEquals(TuneResult.RateLimited, controller.checkConnection())
        fake.enqueueReceived(byteArrayOf(0xfa.toByte(), 0x55, 0, 0, 2, 0x27, 0xd9.toByte()))
        controller.receive()
        assertEquals(TransitionState.Idle, controller.transitionState)
        now = 13000
        assertEquals(TuneResult.Sent, controller.checkConnection())
        assertEquals(3, fake.sendCount)
    }
    @Test fun seekSeriesEndsAfterQuietAndRespectsBandSpecificPause() {
        receive("fm28")
        assertEquals(TuneResult.Sent, controller.seek(Direction.Up))
        assertEquals(TransitionState.Seeking, controller.transitionState)
        now = 100; receive("fm0")
        now = 399; controller.tick()
        assertEquals(TransitionState.Seeking, controller.transitionState)
        now = 400; controller.tick()
        assertEquals(TransitionState.Idle, controller.transitionState)
        now = 4999
        assertEquals(TuneResult.RateLimited, controller.seek(Direction.Down))
        now = 5000
        assertEquals(TuneResult.Sent, controller.seek(Direction.Down))
        now = 20000; controller.tick()
        assertEquals(TransitionState.Idle, controller.transitionState)
        receive("am31")
        assertEquals(TuneResult.Sent, controller.seek(Direction.Up))
        now = 21000; receive("am31")
        now = 21300; controller.tick()
        now = 34999
        assertEquals(TuneResult.RateLimited, controller.seek(Direction.Down))
        now = 35000
        assertEquals(TuneResult.Sent, controller.seek(Direction.Down))
    }
    @Test fun errno19ClearsTargetAndReturnOnlyProbesOnce() {
        receive("fm28")
        controller.tune(108000)
        now = 100; controller.tune(97700)
        controller.error(19)
        assertEquals(TransitionState.Disconnected, controller.transitionState)
        assertNull(controller.target)
        now = 2000; controller.tick()
        assertEquals(1, fake.sendCount)
        controller.permissionResult(controller.requestPermission()!!, true)
        assertEquals(2, fake.sendCount)
        assertEquals(0x85, fake.sentFrames.last()[6].toInt() and 255)
        receive("fm28")
        now = 20000; controller.tick()
        assertEquals(2, fake.sendCount)
    }
    @Test fun fastReturnDefersOneProbeUntilCheckLimitExpiresWithoutRetry() {
        assertEquals(TuneResult.Sent, controller.checkConnection())
        now = 100
        controller.detach("module")
        controller.found(DeviceProfile("module"))
        controller.permissionResult(controller.requestPermission()!!, true)
        assertEquals(1, fake.sendCount)
        now = 9999; controller.tick()
        assertEquals(1, fake.sendCount)
        now = 10000; controller.tick()
        assertEquals(2, fake.sendCount)
        assertEquals(0x85, fake.sentFrames.last()[6].toInt() and 255)
        now = 13000; controller.tick()
        assertEquals(TransitionState.Unresponsive, controller.transitionState)
        now = 20000; controller.tick()
        assertEquals(2, fake.sendCount)
    }
    @Test fun disconnectCancelsDeferredReturnProbe() {
        assertEquals(TuneResult.Sent, controller.checkConnection())
        now = 100
        controller.detach("module")
        controller.found(DeviceProfile("module"))
        controller.permissionResult(controller.requestPermission()!!, true)
        assertEquals(1, fake.sendCount)
        now = 200; controller.detach("module")
        now = 10000; controller.tick()
        assertEquals(ConnectionState.Disconnected, controller.state)
        assertEquals(1, fake.sendCount)
        controller.found(DeviceProfile("module"))
        controller.permissionResult(controller.requestPermission()!!, true)
        assertEquals(2, fake.sendCount)
        now = 20000; controller.tick()
        assertEquals(2, fake.sendCount)
    }
    @Test fun completedSeekShowsPauseWhenAnotherPressIsDropped() {
        receive("fm28")
        controller.seek(Direction.Up)
        now = 100; receive("fm0")
        now = 400; controller.tick()
        assertEquals(TuneResult.RateLimited, controller.seek(Direction.Up))
        assertEquals(TransitionState.CommandPause, controller.transitionState)
        now = 4999; controller.tick()
        assertEquals(TransitionState.CommandPause, controller.transitionState)
        now = 5000; controller.tick()
        assertEquals(TransitionState.Idle, controller.transitionState)
        assertEquals(1, fake.sendCount)
    }
    @Test fun observerSeesLatestTargetConfirmedStationAndBannerAndJournal() {
        val directory = temporary.newFolder()
        val snapshots = mutableListOf<TransitionSnapshot>()
        val transport = FakeTransport()
        val module = ModuleController(transport, DiagnosticLog(directory), clock = { now },
            transitionObserved = { snapshots.add(it) })
        module.found(DeviceProfile("module"))
        module.permissionResult(module.requestPermission()!!, true)
        transport.enqueueReceived(javaClass.getResourceAsStream("/module/fm28.bin")!!.use { it.readBytes() })
        module.receive()
        module.tune(108000)
        now = 100; module.tune(97700)
        assertEquals(TuneTarget(Band.FM, 97700), snapshots.last().target)
        assertEquals(TuneTarget(Band.FM, 90300), snapshots.last().confirmed)
        now = 1000; module.tick()
        now = 4000; module.tick()
        assertEquals("Нет ответа от Модуля", snapshots.last().banner)
        module.checkConnection()
        now = 7000; module.tick()
        assertEquals("Модуль не отвечает, переподключите USB", snapshots.last().banner)
        module.error(19)
        assertEquals("Модуль отключён", snapshots.last().banner)
        val journal = java.io.File(directory, "diagnostic.log").readText()
        listOf("Idle", "Unconfirmed", "Unresponsive", "Disconnected").forEach {
            assertTrue(journal.contains("EVENT R26 $it"))
        }
    }
    @Test fun pendingStepCannotBeOvertakenByAnotherCommand() {
        receive("fm28")
        controller.step(Direction.Up)
        assertEquals(TuneResult.RateLimited, controller.seek(Direction.Up))
        assertEquals(TuneResult.RateLimited, controller.checkConnection())
        assertEquals(TuneResult.RateLimited, controller.select(Band.AM, 711))
        now = 500
        assertEquals(TuneResult.RateLimited, controller.step(Direction.Down))
        assertEquals(1, fake.sendCount)
        receive("fm0")
        assertEquals(TuneResult.Sent, controller.step(Direction.Down))
    }
    @Test fun stepWaitingStartsOnlyAfterThreeHundredMilliseconds() {
        receive("fm28")
        controller.step(Direction.Up)
        now = 300; controller.tick()
        assertEquals(TransitionState.CommandPause, controller.transitionState)
        now = 301; controller.tick()
        assertEquals(TransitionState.Waiting, controller.transitionState)
        now = 500; controller.tick()
        assertEquals(TransitionState.Waiting, controller.transitionState)
    }
    @Test fun deferredSelectionGetsFullDeadlineFromActualSend() {
        receive("fm28")
        controller.tune(87500)
        receive("fm0")
        now = 100; controller.tune(108000)
        now = 401; controller.tick()
        assertEquals(TransitionState.Idle, controller.transitionState)
        now = 1000; controller.tick()
        assertEquals(2, fake.sendCount)
        now = 1300; controller.tick()
        assertEquals(TransitionState.Idle, controller.transitionState)
        now = 1301; controller.tick()
        assertEquals(TransitionState.Waiting, controller.transitionState)
        now = 3999; controller.tick()
        assertEquals(TransitionState.Waiting, controller.transitionState)
        now = 4000; controller.tick()
        assertEquals(TransitionState.Unconfirmed, controller.transitionState)
    }
}
