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
    private var limit = 20000L
    private val pauses = mutableListOf<Long>()
    private var signals = 0
    private val starts = mutableListOf<Boolean>()
    private val controller by lazy {
        ModuleController(fake, DiagnosticLog(temporary.newFolder()), transitionConfirmed = { signals++ }, transitionStarted = { starts.add(it) }, clock = { now }, seekLimitMillis = { limit }, seekPaused = { pauses.add(it) }).also {
            it.found(DeviceProfile("module"))
            it.permissionResult(it.requestPermission()!!, true)
        }
    }
    private fun receive(name: String) {
        controller
        fake.enqueueReceived(javaClass.getResourceAsStream("/module/$name.bin")!!.use { it.readBytes() })
        controller.receive()
    }
    // Synthetic 0x22 variants exercise session behavior; they are not hardware evidence.
    private fun receiveAm(cid: Int) {
        val frame = javaClass.getResourceAsStream("/module/am31.bin")!!.use { it.readBytes() }
        frame[7] = cid.toByte()
        frame[frame.lastIndex] = (-frame.sliceArray(5 until frame.lastIndex)
            .sumOf { it.toInt() and 255 }).toByte()
        controller
        fake.enqueueReceived(frame)
        controller.receive()
    }
    @Test fun repeatedSelectStartsOneTransitionAndLastTargetWins() {
        receive("fm28")
        controller.select(Band.FM, 108000)
        controller.select(Band.FM, 87500)
        assertEquals(listOf(true), starts)
        assertEquals(TuneTarget(Band.FM, 87500), controller.snapshot.target)
        now = 1000
        controller.tick()
        receive("fm0")
        assertEquals(TuneTarget(Band.FM, 87500), controller.snapshot.confirmed)
        assertEquals(1, signals)
    }

    @Test fun signalOnlyForMatchingTransitionAndNeverForStepSeekOrLateReply() {
        receive("fm28")
        controller.tune(108000)
        receive("fm0")
        assertEquals(0, signals)
        receive("fm205")
        receive("fm205")
        assertEquals(1, signals)
        now = 2000
        controller.step(Direction.Up)
        receive("fm0")
        now = 4000
        controller.seek(Direction.Up)
        receive("fm28")
        now = 5000; controller.tick()
        controller.tune(108000)
        now = 8000; controller.tick()
        receive("fm205")
        assertEquals(1, signals)
    }

    @Test fun detachReturnProbesOnceAndRespectsTenSecondLimit() {
        receive("fm28")
        controller.detach("module")
        assertEquals(ConnectionState.Disconnected, controller.state)
        controller.found(DeviceProfile("module"))
        controller.permissionResult(controller.requestPermission()!!, true)
        assertEquals(1, fake.sendCount)
        assertEquals(0x85, fake.sentFrames.single()[6].toInt() and 255)
        controller.tick()
        receive("fm28")
        now = 9999
        assertEquals(TuneResult.RateLimited, controller.checkConnection())
        assertEquals(1, fake.sendCount)
        now = 10000
        assertEquals(TuneResult.Sent, controller.checkConnection())
        assertEquals(2, fake.sendCount)
    }
    @Test fun diagnosticBandUsesControllerPauseAndWaitsForReply() {
        receive("fm28")
        assertEquals(TuneResult.Sent, controller.switchBand())
        assertEquals(TuneResult.RateLimited, controller.switchBand())
        assertEquals(0x84, fake.sentFrames.last()[6].toInt() and 255)
        receive("am31")
        now = 1999
        assertEquals(TuneResult.RateLimited, controller.switchBand())
        now = 2000
        assertEquals(TuneResult.Sent, controller.switchBand())
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
    @Test fun completedSeekReportsRemainingBandPauseWithoutSending() {
        for ((fixture, pause) in listOf("fm28" to 5000L, "am31" to 15000L)) {
            now += 20000
            receive(fixture)
            val start = now
            assertEquals(TuneResult.Sent, controller.seek(Direction.Up))
            now = start + 100
            receive(fixture)
            now = start + 1000
            controller.tick()
            val sent = fake.sendCount
            pauses.clear()
            assertEquals(TuneResult.RateLimited, controller.seek(Direction.Down))
            assertEquals(listOf(pause - 1000), pauses)
            assertEquals(sent, fake.sendCount)
            now = start + pause
            assertEquals(TuneResult.Sent, controller.seek(Direction.Down))
            assertEquals(listOf(pause - 1000), pauses)
        }
    }

    @Test fun activeSeekStepAndTargetDoNotReportSeekPause() {
        receive("fm28")
        controller.seek(Direction.Up)
        now = 1000
        assertEquals(TuneResult.RateLimited, controller.seek(Direction.Down))
        assertTrue(pauses.isEmpty())
        receive("fm28")
        now = 1300
        controller.tick()
        controller.step(Direction.Up)
        controller.seek(Direction.Down)
        assertTrue(pauses.isEmpty())
        receive("fm28")
        controller.tune(108000)
        controller.seek(Direction.Down)
        assertTrue(pauses.isEmpty())
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
        now = 25000; controller.tick()
        assertEquals("Поиск не завершён", controller.snapshot.banner)
        receive("am31")
        assertEquals(TuneResult.Sent, controller.seek(Direction.Up))
        now = 26000; receive("am31")
        now = 26300; controller.tick()
        now = 39999
        assertEquals(TuneResult.RateLimited, controller.seek(Direction.Down))
        now = 40000
        assertEquals(TuneResult.Sent, controller.seek(Direction.Down))
    }
    @Test fun seekExpiresWithoutRepliesAndNeverRetries() {
        receive("am31")
        controller.seek(Direction.Up)
        now = 19999; controller.tick()
        assertEquals("идёт поиск…", controller.snapshot.banner)
        now = 20000; controller.tick()
        assertEquals("Поиск не завершён", controller.snapshot.banner)
        now = 60000; controller.tick()
        assertEquals(1, fake.sendCount)
    }
    @Test fun amStepWorksBeyondSelectRangeAndDropsPressesBeforeFiveHundredMilliseconds() {
        receiveAm(58)
        assertEquals(TuneResult.Sent, controller.step(Direction.Up))
        assertEquals(0x89, fake.sentFrames.last()[6].toInt() and 255)
        assertEquals(TuneTarget(Band.AM, 1044), controller.snapshot.confirmed)
        now = 10; receiveAm(59)
        assertEquals(TuneTarget(Band.AM, 1053), controller.snapshot.confirmed)
        now = 499
        assertEquals(TuneResult.RateLimited, controller.step(Direction.Down))
        now = 500; controller.tick()
        assertEquals(1, fake.sendCount)
        assertEquals(TuneResult.Sent, controller.step(Direction.Down))
        assertEquals(0x88, fake.sentFrames.last()[6].toInt() and 255)
        now = 510; receiveAm(58)
        now = 1000
        receiveAm(21)
        assertEquals(TuneResult.Sent, controller.step(Direction.Down))
        now = 1010; receiveAm(20)
        assertEquals(TuneTarget(Band.AM, 702), controller.snapshot.confirmed)
        now = 1500
        assertEquals(TuneResult.Sent, controller.step(Direction.Down))
        now = 1510; receiveAm(19)
        assertEquals(TuneTarget(Band.AM, 693), controller.snapshot.confirmed)
        now = 10000; controller.tick()
        assertEquals(4, fake.sendCount)
    }
    @Test fun amSeekUpOnEmptyAirWrapsAndReturnsToStart() {
        amSeekOnEmptyAir(Direction.Up, (41..122).toList() + (0..40).toList(), 0x87)
    }
    @Test fun amSeekDownOnEmptyAirWrapsAndReturnsToStart() {
        amSeekOnEmptyAir(Direction.Down, (39 downTo 0).toList() + (122 downTo 40).toList(), 0x86)
    }
    private fun amSeekOnEmptyAir(direction: Direction, cids: List<Int>, opcode: Int) {
        receiveAm(40)
        assertEquals(TuneResult.Sent, controller.seek(direction))
        assertEquals(opcode, fake.sentFrames.single()[6].toInt() and 255)
        cids.forEach { cid ->
            now += 87
            receiveAm(cid)
            assertEquals(TransitionState.Seeking, controller.transitionState)
            assertEquals(TuneResult.RateLimited, controller.seek(direction))
            if (cid == 0) assertEquals(TuneTarget(Band.AM, 522), controller.snapshot.confirmed)
            if (cid == 122) assertEquals(TuneTarget(Band.AM, 1620), controller.snapshot.confirmed)
        }
        assertEquals(TuneTarget(Band.AM, 882), controller.snapshot.confirmed)
        now += 299; controller.tick()
        assertEquals("идёт поиск…", controller.snapshot.banner)
        now += 1; controller.tick()
        assertEquals(TransitionState.Idle, controller.transitionState)
        assertNull(controller.snapshot.banner)
        now = 14999
        assertEquals(TuneResult.RateLimited, controller.seek(direction))
        now = 15000; controller.tick()
        assertEquals(1, fake.sendCount)
        assertEquals(TuneResult.Sent, controller.seek(direction))
        assertEquals(2, fake.sendCount)
    }
    @Test fun amSeekStartsAtModuleEdgesOutsideSelectRange() {
        receiveAm(0)
        assertEquals(TuneResult.Sent, controller.seek(Direction.Down))
        assertEquals(0x86, fake.sentFrames.single()[6].toInt() and 255)
        now = 100; receiveAm(122)
        assertEquals(TuneTarget(Band.AM, 1620), controller.snapshot.confirmed)
        now = 15000
        assertEquals(TuneResult.Sent, controller.seek(Direction.Up))
        assertEquals(0x87, fake.sentFrames.last()[6].toInt() and 255)
    }
    @Test fun fmSeekCannotRestartWhileRepliesContinueAfterFiveSecondPause() {
        receive("fm205")
        assertEquals(TuneResult.Sent, controller.seek(Direction.Up))
        assertEquals(0x87, fake.sentFrames.single()[6].toInt() and 255)
        for (time in 100L..5200L step 100L) {
            now = time; receive("fm0")
        }
        assertEquals(TuneTarget(Band.FM, 87500), controller.snapshot.confirmed)
        assertEquals(TuneResult.RateLimited, controller.seek(Direction.Down))
        assertEquals(1, fake.sendCount)
        now = 5499; controller.tick()
        assertEquals(TransitionState.Seeking, controller.transitionState)
        now = 5500; controller.tick()
        assertEquals(TuneResult.Sent, controller.seek(Direction.Down))
        assertEquals(0x86, fake.sentFrames.last()[6].toInt() and 255)
    }
    @Test fun seekDeadlineReleasesCommandsDespiteContinuousReplies() {
        receive("fm28")
        controller.seek(Direction.Down)
        for (time in 100L..19900L step 100L) {
            now = time; receive("fm0")
            assertEquals("идёт поиск…", controller.snapshot.banner)
        }
        now = 20000; receive("fm205")
        assertEquals("Поиск не завершён", controller.snapshot.banner)
        assertEquals(TuneTarget(Band.FM, 108000), controller.snapshot.confirmed)
        now = 20100; receive("fm28")
        assertEquals("Поиск не завершён", controller.snapshot.banner)
        assertEquals(TuneTarget(Band.FM, 90300), controller.snapshot.confirmed)
        assertEquals(1, fake.sendCount)
        assertEquals(TuneResult.Sent, controller.step(Direction.Down))
    }
    @Test fun configuredDeadlineIsCapturedAtEachSeekAndAmPauseRemainsFifteenSeconds() {
        limit = 10000
        receive("am31")
        controller.seek(Direction.Up)
        limit = 30000
        now = 10000; controller.tick()
        assertEquals("Поиск не завершён", controller.snapshot.banner)
        assertEquals(1, fake.sendCount)
        now = 14999
        assertEquals(TuneResult.RateLimited, controller.seek(Direction.Down))
        now = 15000
        assertEquals(TuneResult.Sent, controller.seek(Direction.Down))
        now = 44999; controller.tick()
        assertEquals("идёт поиск…", controller.snapshot.banner)
        now = 45000; controller.tick()
        assertEquals("Поиск не завершён", controller.snapshot.banner)
        assertEquals(2, fake.sendCount)
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
