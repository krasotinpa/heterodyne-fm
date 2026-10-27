package fm.heterodyne.app.module

import fm.heterodyne.app.DiagnosticLog
import fm.heterodyne.app.audio.AudioFocusLease
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LaunchAutostartTest {
    @get:Rule val temporary = TemporaryFolder()
    private val transport = FakeTransport()
    private var now = 0L
    private var focusRequests = 0
    private var plays = 0
    private var granted = true
    private var noFocus = false
    private var releases = 0
    private fun lease(granted: Boolean = true) = AudioFocusLease(granted) { releases++ }
    private val log by lazy { DiagnosticLog(temporary.newFolder()) }
    private val starts = mutableListOf<Boolean>()
    private val module by lazy { ModuleController(transport, log, clock = { now }, transitionStarted = { starts.add(it) }) }
    private val launch by lazy { LaunchAutostart(module, log, { now },
        requestFocus = { done -> focusRequests++; done(lease(granted)) }, startPlayback = { plays++; it(true) },
        noFocusChanged = { noFocus = it }) }

    private fun connect() {
        module.found(DeviceProfile("module"))
        module.permissionResult(module.requestPermission()!!, true)
        launch.tick()
    }
    private fun receive(name: String) {
        transport.enqueueReceived(javaClass.getResourceAsStream("/module/$name.bin")!!.use { it.readBytes() })
        module.receive(); launch.tick()
    }

    @Test fun openingSelectsTargetWithoutSignalAndManualSelectSignals() {
        launch.open(true, TuneTarget(Band.FM, 108000))
        connect(); receive("fm28"); receive("fm205")
        assertEquals(TuneTarget(Band.FM, 108000), module.snapshot.confirmed)
        assertEquals(1, plays)
        assertEquals(listOf(false), starts)
        now = 2000
        module.select(Band.FM, 90300)
        assertEquals(listOf(false, true), starts)
    }

    @Test fun returnReusesProbeAndWaitsTwoSecondsBeforeSelectAndPlayback() {
        connect(); receive("fm28"); module.detach("module"); now = 10000
        connect()
        launch.moduleReturn(true, TuneTarget(Band.AM, 801))
        receive("fm28")
        assertEquals(listOf(0x85, 0x84), transport.sentFrames.map { it[6].toInt() and 255 })
        now = 10230; receive("am31")
        now = 11999; module.tick(); launch.tick()
        assertEquals(2, transport.sendCount)
        now = 12000; module.tick(); launch.tick()
        assertEquals(listOf(0x85, 0x84, 0x82), transport.sentFrames.map { it[6].toInt() and 255 })
        receive("am31")
        assertEquals(1, plays)
        assertEquals(TuneTarget(Band.AM, 801), module.snapshot.confirmed)
        assertEquals(listOf(false), starts)
        assertTrue(log.readTail(32768).contains("autostart reason=module-return result=selected"))
    }

    @Test fun returnSkipsDisabledStoppedMissingAndInvalidTargetsWithOnlyOneProbe() {
        connect()
        listOf(false to TuneTarget(Band.FM, 90300), true to null,
            true to TuneTarget(Band.FM, 90301), true to TuneTarget(Band.DAB, 90300)).forEach { (enabled, station) ->
            module.detach("module"); now += 40000; connect()
            launch.moduleReturn(enabled, station); receive("fm28")
            now += 3000; module.tick(); launch.tick()
        }
        assertEquals(List(4) { 0x85 }, transport.sentFrames.map { it[6].toInt() and 255 })
        assertEquals(0, focusRequests)
        assertEquals(0, plays)
    }

    @Test fun twoReturnsWithinThirtySecondsOnlyStartOnceAndNeverQueueRetry() {
        connect(); module.detach("module"); connect()
        launch.moduleReturn(true, TuneTarget(Band.FM, 90300)); receive("fm28")
        module.detach("module"); now = 10000; connect()
        launch.moduleReturn(true, TuneTarget(Band.FM, 108000)); receive("fm28")
        now = 60000; module.tick(); launch.tick()
        assertEquals(listOf(0x85, 0x85), transport.sentFrames.map { it[6].toInt() and 255 })
        assertEquals(1, plays)
        assertEquals(1, focusRequests)
        assertTrue(log.readTail(32768).contains("autostart reason=module-return result=rate-limited"))
    }

    @Test fun applicationOpeningAndReturnShareOneAttemptLimit() {
        launch.open(true, TuneTarget(Band.FM, 90300)); connect(); receive("fm28")
        module.detach("module"); now = 10000; connect()
        launch.moduleReturn(true, TuneTarget(Band.FM, 108000)); receive("fm28")
        assertEquals(1, focusRequests)
        assertEquals(1, plays)
        assertEquals(listOf(0x85, 0x85), transport.sentFrames.map { it[6].toInt() and 255 })
        now = 30000; launch.open(true, TuneTarget(Band.FM, 90300)); receive("fm28")
        assertEquals(2, plays)
    }

    @Test fun returnDenialShowsNoFocusWithoutSelectionOrRetry() {
        connect(); module.detach("module"); connect()
        granted = false
        launch.moduleReturn(true, TuneTarget(Band.FM, 108000)); receive("fm28")
        assertTrue(noFocus)
        granted = true; now = 60000; launch.tick(); receive("fm28")
        assertTrue(noFocus)
        assertEquals(listOf(0x85), transport.sentFrames.map { it[6].toInt() and 255 })
        assertEquals(1, focusRequests)
        assertEquals(0, plays)
        assertTrue(log.readTail(32768).contains("autostart reason=module-return result=no-focus"))
    }

    @Test fun userCancelsReturnAfterBandAndBeforePlayback() {
        connect(); module.detach("module"); connect()
        launch.moduleReturn(true, TuneTarget(Band.AM, 801)); receive("fm28")
        launch.cancel()
        receive("am31"); now = 2000; module.tick(); launch.tick()
        assertEquals(listOf(0x85, 0x84), transport.sentFrames.map { it[6].toInt() and 255 })
        assertEquals(0, plays)
        assertEquals(1, releases)
    }

    @Test fun returnUsesFreshProbeReplyReceivedBeforeFocusCompletion() {
        connect(); module.detach("module"); connect()
        var completed: ((AudioFocusLease?) -> Unit)? = null
        val delayed = LaunchAutostart(module, log, { now }, { completed = it }, { plays++; it(true) })
        delayed.moduleReturn(true, TuneTarget(Band.FM, 90300))
        now = 2000
        receive("fm28")
        now = 3001
        completed!!(lease()); delayed.tick()
        assertEquals(1, plays)
        assertEquals(listOf(0x85), transport.sentFrames.map { it[6].toInt() and 255 })
    }

    @Test fun returnWaitsForDeferredProbeAndIgnoresEarlierReply() {
        connect(); module.checkConnection()
        module.detach("module"); now = 100; connect()
        launch.moduleReturn(true, TuneTarget(Band.FM, 90300)); receive("fm28")
        assertEquals(0, focusRequests)
        now = 10000; module.tick(); launch.tick()
        assertEquals(0, plays)
        receive("fm28")
        assertEquals(1, plays)
        assertEquals(listOf(0x85, 0x85), transport.sentFrames.map { it[6].toInt() and 255 })
    }

    @Test fun returnDoesNotStartWhenAudioAlreadyPlays() {
        connect(); module.detach("module"); connect()
        val playing = LaunchAutostart(module, log, { now }, { focusRequests++; it(lease()) },
            { plays++; it(true) }, isPlaying = { true })
        playing.moduleReturn(true, TuneTarget(Band.FM, 108000)); receive("fm28")
        assertEquals(0, focusRequests)
        assertEquals(0, plays)
        assertEquals(listOf(0x85), transport.sentFrames.map { it[6].toInt() and 255 })
    }

    @Test fun returnKeepsProbeReplyWhileOldPlaybackServiceStopsPastProbeDeadline() {
        connect(); module.detach("module"); connect()
        var stopping = true
        val waiting = LaunchAutostart(module, log, { now },
            { focusRequests++; it(lease()) }, { plays++; it(true) },
            isPlaying = { stopping }, isPlaybackStopping = { stopping })
        waiting.moduleReturn(true, TuneTarget(Band.FM, 90300))
        now = 2000; receive("fm28"); waiting.tick()
        assertEquals(0, focusRequests)
        assertEquals(0, plays)
        now = 3001; stopping = false
        waiting.tick(); waiting.tick()
        assertEquals(1, focusRequests)
        assertEquals(1, plays)
        now = 60000; module.tick(); waiting.tick()
        assertEquals(1, plays)
        assertEquals(listOf(0x85), transport.sentFrames.map { it[6].toInt() and 255 })
    }

    @Test fun matchingReplyStartsLocallyWithOneProbeAndNoPlaySelect() {
        launch.open(true, TuneTarget(Band.FM, 90300))
        connect()
        assertEquals(1, focusRequests)
        assertEquals(listOf(0x85), transport.sentFrames.map { it[6].toInt() and 255 })
        assertEquals(0, plays)
        receive("fm28")
        assertEquals(1, plays)
        now = 60000; module.tick(); launch.tick(); receive("fm28")
        assertEquals(1, transport.sendCount)
        assertEquals(1, plays)
    }
    @Test fun differentBandWaitsTwoSecondsAndUsesOneTransitionWithoutRetry() {
        launch.open(true, TuneTarget(Band.AM, 801)); connect(); receive("fm28")
        assertEquals(listOf(0x85, 0x84), transport.sentFrames.map { it[6].toInt() and 255 })
        now = 230; receive("am31")
        assertEquals(2, transport.sendCount)
        now = 1999; module.tick(); launch.tick()
        assertEquals(2, transport.sendCount)
        now = 2000; module.tick(); launch.tick()
        assertEquals(listOf(0x85, 0x84, 0x82), transport.sentFrames.map { it[6].toInt() and 255 })
        assertEquals(0, plays)
        receive("am31")
        assertEquals(1, plays)
        now = 60000; module.tick(); launch.tick()
        assertEquals(3, transport.sendCount)
    }

    @Test fun disabledMissingStoppedOrInvalidStationNeverRequestsFocusOrSends() {
        connect()
        listOf(false to TuneTarget(Band.FM, 90300), true to null,
            true to TuneTarget(Band.FM, 90301), true to TuneTarget(Band.AM, 522),
            true to TuneTarget(Band.DAB, 90300)).forEach { (enabled, station) ->
            launch.open(enabled, station); launch.tick()
        }
        assertEquals(0, focusRequests)
        assertEquals(0, transport.sendCount)
        assertEquals(0, plays)
    }

    @Test fun deniedFocusHasStatusAndNeverRetries() {
        granted = false
        launch.open(true, TuneTarget(Band.FM, 90300)); connect()
        assertTrue(noFocus)
        assertEquals(0, transport.sendCount)
        assertEquals(1, releases)
        granted = true
        now = 60000; launch.tick(); receive("fm28")
        assertTrue(noFocus)
        assertEquals(1, focusRequests)
        assertEquals(0, transport.sendCount)
        assertEquals(0, plays)
        assertTrue(log.readTail(32768).contains("autostart reason=application-open result=no-focus"))
    }

    @Test fun sameBandSelectsOnceAndRecordsConfirmedResult() {
        launch.open(true, TuneTarget(Band.FM, 108000)); connect(); receive("fm28")
        assertEquals(listOf(0x85, 0x82), transport.sentFrames.map { it[6].toInt() and 255 })
        assertEquals(205, transport.sentFrames.last()[8].toInt() and 255)
        assertEquals(0, plays)
        receive("fm205")
        assertEquals(1, plays)
        assertTrue(log.readTail(32768).contains("autostart reason=application-open result=selected"))
    }

    @Test fun cancellationBeforeConnectAndWhileWaitingForProbeDoesNotSelect() {
        launch.open(true, TuneTarget(Band.FM, 108000)); launch.cancel(); connect(); receive("fm28")
        assertEquals(0, transport.sendCount)
        launch.open(true, TuneTarget(Band.FM, 108000))
        assertEquals(1, transport.sendCount)
        launch.cancel(); receive("fm28")
        now = 60000; launch.tick(); module.tick()
        assertEquals(1, transport.sendCount)
        assertEquals(0, plays)
    }

    @Test fun cancellationDiscardsUnsentSelectAfterBand() {
        launch.open(true, TuneTarget(Band.AM, 801)); connect(); receive("fm28")
        launch.cancel()
        receive("am31"); now = 2000; module.tick(); launch.tick()
        assertEquals(listOf(0x85, 0x84), transport.sentFrames.map { it[6].toInt() and 255 })
        assertEquals(0, plays)
    }

    @Test fun delayedFocusCompletionAfterUserActionCannotSend() {
        var completion: ((AudioFocusLease?) -> Unit)? = null
        val delayed = LaunchAutostart(module, log, { now }, { completion = it }, { plays++; it(true) })
        delayed.open(true, TuneTarget(Band.FM, 90300)); connect(); delayed.tick()
        delayed.cancel(); completion!!(lease()); delayed.tick(); receive("fm28")
        assertEquals(0, transport.sendCount)
        assertEquals(0, plays)
    }

    @Test fun limitIsThirtySecondsAndDoesNotQueueAnotherAttempt() {
        launch.open(true, TuneTarget(Band.FM, 90300)); connect(); receive("fm28")
        now = 29999; launch.open(true, TuneTarget(Band.FM, 90300)); launch.tick()
        assertEquals(1, focusRequests)
        assertEquals(1, transport.sendCount)
        now = 30000; launch.tick()
        assertEquals(1, transport.sendCount)
        launch.open(true, TuneTarget(Band.FM, 90300))
        assertEquals(2, focusRequests)
        assertEquals(2, transport.sendCount)
        assertEquals(1, plays) // A fresh 0x22 is required even if the old snapshot matches.
        receive("fm28")
        assertEquals(2, plays)
    }

    @Test fun unrelatedReplyAndLateStationCannotExtendProbeDeadline() {
        launch.open(true, TuneTarget(Band.FM, 108000)); connect()
        now = 2000
        transport.enqueueReceived(byteArrayOf(0xfa.toByte(), 0x55, 0, 0, 2, 0x27, 0xd9.toByte()))
        module.receive(); launch.tick()
        now = 3000; launch.tick(); receive("fm28")
        now = 60000; module.tick(); launch.tick()
        assertEquals(1, transport.sendCount)
        assertEquals(0, plays)
        assertEquals(1, releases)
    }

    @Test fun transitionTimeoutAndDetachDoNotRetryOrPlay() {
        launch.open(true, TuneTarget(Band.FM, 108000)); connect(); receive("fm28")
        now = 3000; module.tick(); launch.tick(); receive("fm205")
        now = 60000; module.tick(); launch.tick()
        assertEquals(2, transport.sendCount)
        assertEquals(0, plays)
        assertEquals(1, releases)
    }

    @Test fun targetReplyAtDeadlineCannotTurnTimedOutTransitionIntoPlayback() {
        launch.open(true, TuneTarget(Band.FM, 108000)); connect(); receive("fm28")
        now = 3000; receive("fm205")
        assertEquals(0, plays)
        assertEquals(2, transport.sendCount)
    }

    @Test fun userSelectionAfterAutostartBandWaitsForOutstandingBandReply() {
        launch.open(true, TuneTarget(Band.AM, 801)); connect(); receive("fm28")
        launch.cancel()
        assertEquals(TuneResult.RateLimited, module.select(Band.FM, 108000))
        assertEquals(2, transport.sendCount)
        now = 230; receive("am31")
        assertEquals(2, transport.sendCount)
        now = 2000; module.tick(); launch.tick()
        assertEquals(0x84, transport.sentFrames.last()[6].toInt() and 255)
        receive("fm28")
        assertEquals(0x82, transport.sentFrames.last()[6].toInt() and 255)
        assertEquals(0, plays)
    }

    @Test fun timeoutClearsSelectionDelayedByBandLimitBeforeReleasingFocus() {
        connect(); receive("fm28")
        assertEquals(TuneResult.Sent, module.switchBand())
        receive("am31")
        now = 1000
        launch.open(true, TuneTarget(Band.FM, 108000)); receive("am31")
        assertEquals(listOf(0x84, 0x85), transport.sentFrames.map { it[6].toInt() and 255 })
        now = 2000; module.tick(); receive("fm28")
        assertEquals(listOf(0x84, 0x85, 0x84), transport.sentFrames.map { it[6].toInt() and 255 })
        now = 4000; launch.tick()
        assertEquals(1, releases)
        assertNull(module.target)
        module.tick(); now = 10000; module.tick(); launch.tick()
        assertEquals(3, transport.sendCount)
        assertEquals(0, plays)
    }

    @Test fun cancellationFlagAtTickPreventsProbeReplyFromStartingTransition() {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val guarded = LaunchAutostart(module, log, { now }, { it(lease()) }, { plays++; it(true) },
            cancelled = cancelled::get)
        connect()
        guarded.open(true, TuneTarget(Band.AM, 801))
        transport.enqueueReceived(javaClass.getResourceAsStream("/module/fm28.bin")!!.use { it.readBytes() })
        module.receive()
        cancelled.set(true) // Main has cancelled; its queued worker cancel has not run yet.
        guarded.tick()
        assertEquals(listOf(0x85), transport.sentFrames.map { it[6].toInt() and 255 })
        assertNull(module.target)
        assertEquals(1, releases)
        assertEquals(0, plays)
    }

    @Test fun cancellationFlagBeforeFocusCallbackPreventsAnyTx() {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        var completed: ((AudioFocusLease?) -> Unit)? = null
        val guarded = LaunchAutostart(module, log, { now }, { completed = it }, { plays++; it(true) },
            cancelled = cancelled::get)
        connect()
        guarded.open(true, TuneTarget(Band.FM, 90300))
        cancelled.set(true)
        completed!!(lease())
        assertEquals(0, transport.sendCount)
        assertEquals(1, releases)
        assertFalse(noFocus)
        assertEquals(0, plays)
    }

    @Test fun cancellationDuringReceivePreventsItsInternalTickFromSendingPendingSelect() {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        var afterRead: () -> Unit = {}
        val wrapped = object : UsbTransport by transport {
            override fun receive(): ByteArray = transport.receive().also { afterRead() }
        }
        lateinit var guarded: LaunchAutostart
        val guardedModule = ModuleController(wrapped, log, clock = { now }, beforeTick = { guarded.cancelIfRequested() })
        guarded = LaunchAutostart(guardedModule, log, { now }, { it(lease()) }, { plays++; it(true) },
            cancelled = cancelled::get)
        guardedModule.found(DeviceProfile("module"))
        guardedModule.permissionResult(guardedModule.requestPermission()!!, true)
        guarded.open(true, TuneTarget(Band.AM, 801))
        fun reply(name: String) {
            transport.enqueueReceived(javaClass.getResourceAsStream("/module/$name.bin")!!.use { it.readBytes() })
            guardedModule.receive(); guarded.tick()
        }
        reply("fm28")
        now = 230; reply("am31")
        assertEquals(listOf(0x85, 0x84), transport.sentFrames.map { it[6].toInt() and 255 })
        now = 1999
        afterRead = { now = 2000; cancelled.set(true) }
        transport.enqueueReceived(byteArrayOf(0))
        guardedModule.receive()
        assertEquals(2, transport.sendCount)
        assertNull(guardedModule.target)
        assertEquals(1, releases)
        assertEquals(0, plays)
    }

    @Test fun pendingExpiresThirtySecondsAfterOpenWithoutCommands() {
        launch.open(true, TuneTarget(Band.FM, 90300))
        now = 29999; launch.tick()
        assertFalse(log.readTail(32768).contains("result=module-timeout"))
        now = 30000; launch.tick()
        assertTrue(log.readTail(32768).contains("result=module-timeout"))
        connect(); receive("fm28")
        assertEquals(0, focusRequests)
        assertEquals(0, transport.sendCount)
    }

    @Test fun refusedPlaybackStartIsLoggedAndReleasesOnlyItsFocus() {
        connect()
        val refused = LaunchAutostart(module, log, { now }, { it(lease()) },
            { throw IllegalStateException("service refused") })
        refused.open(true, TuneTarget(Band.FM, 90300))
        transport.enqueueReceived(javaClass.getResourceAsStream("/module/fm28.bin")!!.use { it.readBytes() })
        module.receive(); refused.tick()
        assertEquals(1, releases)
        assertTrue(log.readTail(32768).contains("result=start-refused"))
        assertEquals(0, plays)
    }

    @Test fun openingWhilePlaybackIsActiveDoesNotRequestOrReleaseFocus() {
        connect()
        val playing = LaunchAutostart(module, log, { now },
            { focusRequests++; it(lease()) }, { plays++; it(true) }, isPlaying = { true })
        playing.open(true, TuneTarget(Band.FM, 108000))
        playing.cancel(); now = 60000; playing.tick()
        assertEquals(0, focusRequests)
        assertEquals(0, releases)
        assertEquals(0, transport.sendCount)
        assertTrue(log.readTail(32768).contains("result=already-playing"))
    }

    @Test fun pendingSurvivesPermissionDenialAndCanConnectBeforeItsDeadline() {
        launch.open(true, TuneTarget(Band.FM, 90300))
        module.found(DeviceProfile("module"))
        module.permissionResult(module.requestPermission()!!, false)
        launch.tick()
        assertEquals(0, focusRequests)
        assertEquals(0, transport.sendCount)
        now = 29999; connect(); receive("fm28")
        assertEquals(1, plays)
        assertEquals(1, transport.sendCount)
    }

    @Test fun serviceRefusalAfterSchedulingReleasesTheAcquiredFocus() {
        var completed: ((Boolean) -> Unit)? = null
        val starting = LaunchAutostart(module, log, { now }, { it(lease()) }, { completed = it })
        connect(); starting.open(true, TuneTarget(Band.FM, 90300))
        transport.enqueueReceived(javaClass.getResourceAsStream("/module/fm28.bin")!!.use { it.readBytes() })
        module.receive(); starting.tick()
        assertTrue(starting.startingPlayback)
        assertEquals(0, releases)
        completed!!(false)
        assertFalse(starting.startingPlayback)
        assertEquals(1, releases)
        assertTrue(log.readTail(32768).contains("result=start-refused"))
    }

    @Test fun cancellationBeforePlaybackTakesOwnershipReleasesOnceAndIgnoresLateSuccess() {
        var completed: ((Boolean) -> Unit)? = null
        val starting = LaunchAutostart(module, log, { now }, { it(lease()) }, { completed = it })
        connect(); starting.open(true, TuneTarget(Band.FM, 90300))
        transport.enqueueReceived(javaClass.getResourceAsStream("/module/fm28.bin")!!.use { it.readBytes() })
        module.receive(); starting.tick()
        starting.cancel(); completed!!(true)
        assertEquals(1, releases)
        assertFalse(starting.startingPlayback)
        assertFalse(log.readTail(32768).contains("result=already-selected"))
    }

    @Test fun existingFocusAtAcquisitionNeverSendsOrReleasesIt() {
        connect()
        val existing = LaunchAutostart(module, log, { now }, { it(null) }, { plays++; it(true) })
        existing.open(true, TuneTarget(Band.FM, 90300)); existing.cancel()
        assertEquals(0, transport.sendCount)
        assertEquals(0, releases)
        assertTrue(log.readTail(32768).contains("result=already-playing"))
    }

    @Test fun stationLoadedAfterUserActionCannotStartButANewOpeningCan() {
        connect()
        val generation = AutostartGeneration()
        val original = generation.capture()
        generation.cancel()
        val stale = LaunchAutostart(module, log, { now }, { focusRequests++; it(lease()) }, { plays++; it(true) },
            cancelled = { generation.isCancelled(original) })
        stale.open(true, TuneTarget(Band.FM, 90300))
        assertEquals(0, focusRequests)
        assertEquals(0, transport.sendCount)
        val freshToken = generation.capture()
        val fresh = LaunchAutostart(module, log, { now }, { focusRequests++; it(lease()) }, { plays++; it(true) },
            cancelled = { generation.isCancelled(freshToken) })
        fresh.open(true, TuneTarget(Band.FM, 90300))
        assertEquals(1, focusRequests)
        assertEquals(1, transport.sendCount)
    }

    @Test fun playbackBecomingActiveBeforeFocusReplyPreventsProbe() {
        connect()
        var active = false
        var completed: ((AudioFocusLease?) -> Unit)? = null
        val waiting = LaunchAutostart(module, log, { now }, { completed = it }, { plays++; it(true) }, isPlaying = { active })
        waiting.open(true, TuneTarget(Band.AM, 801))
        active = true; completed!!(lease())
        assertEquals(0, transport.sendCount)
        assertTrue(log.readTail(32768).contains("result=already-playing"))
    }

    @Test fun playbackBecomingActiveDuringProbePreventsSelection() {
        connect()
        var active = false
        val waiting = LaunchAutostart(module, log, { now }, { it(lease()) }, { plays++; it(true) }, isPlaying = { active })
        waiting.open(true, TuneTarget(Band.AM, 801))
        transport.enqueueReceived(javaClass.getResourceAsStream("/module/fm28.bin")!!.use { it.readBytes() })
        module.receive()
        active = true; waiting.tick()
        assertEquals(1, transport.sendCount)
        assertNull(module.target)
        assertTrue(log.readTail(32768).contains("result=already-playing"))
    }

    @Test fun reopeningWhileAutostartServiceIsActiveDoesNotCancelItsOwnedFocus() {
        connect()
        var active = false
        var completed: ((Boolean) -> Unit)? = null
        val starting = LaunchAutostart(module, log, { now }, { it(lease()) }, { completed = it }, isPlaying = { active })
        starting.open(true, TuneTarget(Band.FM, 90300))
        transport.enqueueReceived(javaClass.getResourceAsStream("/module/fm28.bin")!!.use { it.readBytes() })
        module.receive(); starting.tick()
        active = true
        starting.open(true, TuneTarget(Band.FM, 108000))
        assertTrue(starting.startingPlayback)
        assertEquals(0, releases)
        completed!!(true)
        assertFalse(starting.startingPlayback)
        assertEquals(0, releases)
        assertEquals(1, transport.sendCount)
    }
}
