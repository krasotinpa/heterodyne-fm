package fm.heterodyne.app

import fm.heterodyne.app.module.DeviceProfile
import kotlin.time.TestTimeSource
import android.app.Application
import android.media.AudioManager
import fm.heterodyne.app.receiver.ReceiverKind
import fm.heterodyne.app.receiver.ReceiverProfile
import fm.heterodyne.app.receiver.ReceiverProfiles
import fm.heterodyne.ui.contract.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AdapterContractTest : RadioUiContractTest() {
    private val clock = TestTimeSource()
    private val other = ReceiverKind("fixture")
    private val fixture = AdapterFixture(
        ReceiverProfiles.Module.withProfile(ReceiverProfile(other, "Без Поиска", 0, 0) { false }),
        clock, DeviceProfile("second-device", other))

    @After fun close() = fixture.close()

    override fun openRadio(noReply: Boolean): RadioUi = with(fixture) {
        connect()
        receive(28) // Module reports 90.3 MHz.
        settle()
        radio
    }

    override fun confirmTarget() = with(fixture) {
        receive(137)
        settle()
    }
    override fun startMoving() = fixture.module.updateDriving(true)

    override fun settle() = with(fixture) {
        drainWorker()
        idleMain()
        drainStorage()
    }

    override fun advance(millis: Long) = with(fixture) {
        clock += millis.milliseconds
        ShadowSystemClock.advanceBy(Duration.ofMillis(millis))
        if (transport.isOpen) transport.enqueueReceived(byteArrayOf())
        poll()
        settle()
    }

    @Test fun stepPauseKeepsItsDeadlineAcrossSubscriptionsAndRejectsAnEarlyRepeat() = with(fixture) {
        openRadio()
        assertEquals(Admission.Accepted, send(RadioAction.Step(Direction.Up)))
        settle()
        // first() subscribes, reads the replayed snapshot, then cancels that subscription.
        fun subscribe() = runBlocking { radio.state.first() }
        val pause = requireNotNull(subscribe().tuning.pause)
        assertEquals(PauseKind.Step, pause.kind)
        assertEquals(Direction.Up, pause.direction)
        assertEquals(500L, pause.totalMillis)
        advance(200)
        val again = requireNotNull(subscribe().tuning.pause)
        assertEquals(pause.deadline, again.deadline)
        assertEquals(300L, (-again.deadline.elapsedNow()).inWholeMilliseconds)
        assertEquals(Admission.Rejected(RejectReason.RateLimited), send(RadioAction.Step(Direction.Down)))
        advance(300)
        assertNull(subscribe().tuning.pause)
        assertEquals(listOf(0x89), transport.sentFrames.map { it[6].toInt() and 255 })
    }

    @Test fun oldTuneCompletionCannotStartPlaybackAfterDetach() = with(fixture) {
        openRadio()
        assertEquals(Admission.Accepted, send(RadioAction.TuneFrequency(101200)))
        drainWorker() // TX succeeded. The owner has not delivered the completion to main yet.
        module.usb.setFixtureConnected(false)
        settle()
        assertEquals(Connection.Disconnected, radio.state.value.receiver.connection)
        assertEquals(0, starts)
        assertFalse(requireNotNull(session.screen.state).lastPlayed)
        assertEquals(1, transport.sendCount)
    }

    @Test fun deniedLaunchFocusPublishesR25WithoutProbePlaybackOrRetry() = with(fixture) {
        openRadio()
        session.setLastPlayed(true)
        drainStorage()
        val manager = shadowOf(context.getSystemService(AudioManager::class.java))
        manager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        module.openApplication()
        drainStorage() // Load the persisted launch target.
        drainWorker()  // Schedule the Android focus request.
        idleMain()
        settle()       // Deliver the focus result to the launch state machine.
        val deniedRequest = requireNotNull(manager.lastAudioFocusRequest)
        assertEquals(0, transport.sendCount)
        assertEquals(FocusEffect.AutostartDenied, radio.state.value.playback.focus)
        manager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        advance(30001)
        assertSame(deniedRequest, manager.lastAudioFocusRequest)
        assertEquals(0, transport.sendCount)
        assertEquals(PlaybackStatus.Stopped, radio.state.value.playback.status)
        assertNull(shadowOf(context).nextStartedService)
        assertEquals(Admission.Accepted, send(RadioAction.Start))
        assertEquals(1, starts)
    }

    @Test fun motionCancelsQueuedWriteAndDownloadedCatalogAfterParkingReopens() = with(fixture) {
        openRadio()
        val before = radio.state.value.stations
        val ref = SelectionRef(requireNotNull(before.activeRegionId), "Отложенная")
        assertEquals(Admission.Accepted, send(RadioAction.EnterParking))
        assertEquals(Admission.Accepted, send(RadioAction.UpdateCatalog))
        drainStorage()
        assertEquals(CatalogUpdate.InProgress, radio.state.value.stations.catalogUpdate)
        assertEquals(Admission.Rejected(RejectReason.Busy), send(RadioAction.UpdateCatalog))
        network.removeFirst().run() // Download complete, installation is still queued.
        assertEquals(1, catalogOpens)
        assertEquals(Admission.Accepted, send(RadioAction.Edit(SelectionEdit.Create(ref))))
        module.updateDriving(true)
        module.updateDriving(false)
        assertEquals(Admission.Accepted, send(RadioAction.EnterParking))
        settle()
        assertEquals(before, radio.state.value.stations)
        session.load {}
        drainStorage()
        assertEquals(before, radio.state.value.stations)
        assertEquals(0, transport.sendCount)
        // The same download can install in the new parking session.
        assertEquals(Admission.Accepted, send(RadioAction.UpdateCatalog))
        network.removeFirst().run()
        settle()
        assertEquals(CatalogUpdate.Updated("2026-10-30"), radio.state.value.stations.catalogUpdate)
    }

    @Test fun r26LimitsManualLinkChecksAndNeverRetriesOnTimeout() = with(fixture) {
        openRadio()
        fun tuneAndExpire() {
            assertEquals(Admission.Accepted, send(RadioAction.TuneFrequency(101200)))
            settle()
            advance(3000)
            assertEquals(TuningPhase.Unconfirmed, radio.state.value.tuning.phase)
        }
        tuneAndExpire()
        assertEquals(Admission.Accepted, send(RadioAction.CheckConnection))
        settle()
        advance(3000)
        assertEquals(TuningPhase.Unresponsive, radio.state.value.tuning.phase)
        tuneAndExpire()
        advance(3999) // 9,999 ms since the previous probe.
        assertEquals(Admission.Accepted, send(RadioAction.CheckConnection))
        settle()
        assertEquals(ActionIssue(Control.CheckConnection, RejectReason.RateLimited), radio.state.value.actionIssue)
        advance(1)
        assertEquals(Admission.Accepted, send(RadioAction.CheckConnection))
        settle()
        advance(30000)
        assertEquals(TuningPhase.Unresponsive, radio.state.value.tuning.phase)
        assertNull(radio.state.value.actionIssue)
        assertEquals(listOf(0x82, 0x85, 0x82, 0x85), transport.sentFrames.map { it[6].toInt() and 255 })
    }

    override fun detachBeforeReply() = with(fixture) {
        receive(137) // Queue the matching observation on main, then lose this generation.
        module.usb.setFixtureConnected(false)
        settle()
    }
}
