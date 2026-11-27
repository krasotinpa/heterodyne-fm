package fm.heterodyne.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import fm.heterodyne.ui.compose.RadioContent
import android.app.Application
import fm.heterodyne.ui.contract.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ApplicationAdapterTest : AdapterFixture() {
    @org.junit.After fun tearDown() = close()

    @Config(qualifiers = "w1600dp-h800dp-land")
    @OptIn(ExperimentalTestApi::class)
    @Test fun contractScreenShowsOnlyObservedTuningAndParkingSendsNoRadioCommand() {
        connect()
        receive(28)
        shadowOf(main.looper).idle()
        runAndroidComposeUiTest<ComponentActivity> {
            setContent { RadioContent(radio) }
            onNodeWithTag("efir-frequency").assertTextEquals("90,3")
            onNodeWithContentDescription("Стоянка").assertIsDisplayed().assertIsEnabled().performClick()
            runOnIdle { assertEquals(ScreenMode.Parking, radio.state.value.mode) }
            onNodeWithText("Подборки").performClick()
            onNodeWithText("Добавить Станцию").performClick()
            onNodeWithText("Отмена").performClick()
            onNodeWithText("← Эфир").performClick()
            onNodeWithTag("efir-frequency").assertTextEquals("90,3")
        }
        drainWorker()
        assertEquals(0, transport.sendCount)
    }

    @Config(qualifiers = "w1600dp-h800dp-land")
    @OptIn(ExperimentalTestApi::class)
    @Test fun leavingDeveloperToolsRevokesQueuedDiagnosticWhileParkingStaysOpen() {
        connect()
        receive(28)
        shadowOf(main.looper).idle()
        send(RadioAction.EnterParking)
        send(RadioAction.SetDeveloper(true))
        runAndroidComposeUiTest<ComponentActivity> {
            setContent { RadioContent(radio, onToolsClosed = adapter::closeTools) }
            onNodeWithText("Режим разработчика").performClick()
            runOnIdle {
                assertEquals(Admission.Accepted, send(RadioAction.RunDiagnostic(DiagnosticAction.StepFrequencyUp)))
            }
            onNodeWithText("Настройки").performClick()
            runOnIdle {
                drainWorker()
                assertEquals(ScreenMode.Parking, radio.state.value.mode)
                assertNull(radio.state.value.diagnostics.detail)
                assertEquals(0, transport.sendCount)
            }
        }
    }

    @Test fun publishedFocusFollowsPlaybackAndIgnoresOldFocusCallbacks() {
        val manager = object : fm.heterodyne.app.audio.AudioFocusManager {
            lateinit var listener: (fm.heterodyne.app.audio.AudioFocusChange) -> Unit
            override fun request(listener: (fm.heterodyne.app.audio.AudioFocusChange) -> Unit): Boolean {
                this.listener = listener
                return true
            }
            override fun abandon() = Unit
        }
        val effects = mutableListOf<FocusEffect>()
        val focus = fm.heterodyne.app.audio.AudioFocusController(manager,
            fm.heterodyne.app.audio.AudioTrackSink(), DiagnosticLog(context.cacheDir.resolve("focus")),
            effectChanged = effects::add)
        focus.start()
        val callback = manager.listener
        for (effect in listOf(fm.heterodyne.app.audio.AudioFocusChange.Duck, fm.heterodyne.app.audio.AudioFocusChange.Transient,
            fm.heterodyne.app.audio.AudioFocusChange.Loss, fm.heterodyne.app.audio.AudioFocusChange.Gain)) callback(effect)
        focus.stop()
        callback(fm.heterodyne.app.audio.AudioFocusChange.Duck)
        assertEquals(listOf(FocusEffect.Normal, FocusEffect.Ducking, FocusEffect.Muted,
            FocusEffect.PermanentLossContinuing, FocusEffect.Normal, FocusEffect.None), effects)
    }

    @Test fun queuedAutostartReadCannotRestartAfterUserStop() {
        connect()
        receive(28)
        shadowOf(main.looper).idle()
        session.setLastPlayed(true)
        drainStorage()
        module.openApplication()
        assertEquals(Admission.Accepted, send(RadioAction.Stop))
        drainStorage()
        drainWorker()
        shadowOf(main.looper).idle()
        assertEquals(0, starts)
        assertEquals(0, transport.sendCount)
        assertFalse(session.screen.state!!.lastPlayed)
    }

    @Test fun parkingEditAndDiagnosticCannotRunAfterExitAndReentry() {
        connect()
        receive(28)
        shadowOf(main.looper).idle()
        assertEquals(Admission.Accepted, send(RadioAction.EnterParking))
        assertEquals(Admission.Accepted, send(RadioAction.SetDeveloper(true)))
        val ref = SelectionRef(checkNotNull(radio.state.value.stations.activeRegionId), "Отложенная")
        assertEquals(Admission.Accepted, send(RadioAction.Edit(SelectionEdit.Create(ref))))
        assertEquals(Admission.Accepted, send(RadioAction.RunDiagnostic(DiagnosticAction.StepFrequencyUp)))
        send(RadioAction.ExitParking)
        send(RadioAction.EnterParking)
        drainWorker()
        drainStorage()
        assertEquals(0, transport.sendCount)
        assertFalse(radio.state.value.stations.selections.any { it.name == ref.name })
        assertNull(radio.state.value.actionIssue)
    }

    @Test fun stopBetweenTuneAndItsCompletionKeepsPlaybackStopped() {
        connect()
        receive(28)
        shadowOf(main.looper).idle()
        send(RadioAction.TuneFrequency(97700))
        drainWorker()
        send(RadioAction.Stop)
        shadowOf(main.looper).idle()
        drainStorage()
        assertEquals(0, starts)
        assertFalse(session.screen.state!!.lastPlayed)
    }

    @Test fun workerReportsBusyWhenStepPrecedesTuneInTheQueue() {
        connect()
        receive(28)
        shadowOf(main.looper).idle()
        assertEquals(Admission.Accepted, send(RadioAction.Step(Direction.Up)))
        assertEquals(Admission.Accepted, send(RadioAction.TuneFrequency(97700)))
        drainWorker()
        shadowOf(main.looper).idle()
        assertEquals(1, transport.sendCount)
        val snapshot = radio.state.value
        assertEquals(ActionIssue(Control.TuneFrequency, RejectReason.Busy), snapshot.actionIssue)
        assertNull(snapshot.tuning.targetKhz)
        assertEquals(90300, snapshot.tuning.confirmed?.frequencyKhz)
        assertEquals(500L, snapshot.tuning.pause?.totalMillis)
        assertEquals(0, starts)
    }

    @Test fun diagnosticBandRechecksAmAtTheActualSend() {
        connect()
        send(RadioAction.EnterParking)
        send(RadioAction.SetDeveloper(true))
        val command = RadioAction.RunDiagnostic(DiagnosticAction.ReturnToFm)
        assertEquals(Admission.Rejected(RejectReason.IncompatibleState), send(command))
        receive(28, fm = false)
        shadowOf(main.looper).idle()
        assertEquals(Admission.Accepted, send(command))
        receive(28) // FM arrives before the diagnostic worker runs.
        drainWorker()
        shadowOf(main.looper).idle()
        assertEquals(ActionIssue(Control.RunDiagnostic, RejectReason.IncompatibleState), radio.state.value.actionIssue)
        assertEquals(Admission.Rejected(RejectReason.IncompatibleState), send(command))
        assertEquals(0, transport.sendCount)
    }

    @Test fun acceptedTargetSurvivesMatchingRxBeforeItsSend() {
        connect()
        receive(28)
        shadowOf(main.looper).idle()
        drainStorage()
        assertEquals(Admission.Accepted, send(RadioAction.TuneFrequency(87500)))
        assertEquals(0, transport.sendCount)
        drainWorker()
        shadowOf(main.looper).idle()
        assertEquals(1, transport.sendCount)
        assertEquals(90300, radio.state.value.tuning.confirmed?.frequencyKhz)
        assertEquals(90300, radio.state.value.tuning.saved?.frequencyKhz)
        assertEquals(87500, radio.state.value.tuning.targetKhz)
        assertEquals(Admission.Accepted, send(RadioAction.TuneFrequency(108000)))
        drainWorker()
        receive(205)
        shadowOf(main.looper).idle()
        assertEquals(1, transport.sendCount)
        assertNull(radio.state.value.actionIssue)
        assertEquals(108000, radio.state.value.tuning.targetKhz)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
        receive(10)
        assertEquals(2, transport.sendCount)
        receive(205)
        shadowOf(main.looper).idle()
        assertNull(radio.state.value.tuning.targetKhz)
        assertEquals(108000, radio.state.value.tuning.confirmed?.frequencyKhz)
    }

    @Test fun screenSeekExpiresButWheelStillOperatesWithoutAnActivity() {
        connect()
        receive(28)
        shadowOf(main.looper).idle()
        assertEquals(Admission.Accepted, send(RadioAction.Seek(Direction.Up)))
        drainWorker()
        shadowOf(main.looper).idle()
        assertEquals(TuningPhase.Seeking, radio.state.value.tuning.phase)
        module.updateDriving(true)
        adapter.foreground(false)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(20))
        receive(29)
        shadowOf(main.looper).idle()
        assertEquals(TuningPhase.SeekIncomplete, radio.state.value.tuning.phase)
        assertEquals(Admission.Rejected(RejectReason.NotForeground), send(RadioAction.Seek(Direction.Down)))
        module.seekStation(fm.heterodyne.app.module.Direction.Down)
        drainWorker()
        assertEquals(listOf(0x87, 0x86), transport.sentFrames.map { it[6].toInt() and 255 })
    }

    @Test fun changedBandRejectsDeferredSelectionWithoutSendingBand() {
        connect()
        receive(28)
        shadowOf(main.looper).idle()
        send(RadioAction.TuneFrequency(87500))
        drainWorker()
        receive(0)
        shadowOf(main.looper).idle()
        send(RadioAction.TuneFrequency(97700))
        drainWorker()
        receive(0, mode = 0x40)
        shadowOf(main.looper).idle()
        assertEquals(1, transport.sendCount)
        assertNull(radio.state.value.tuning.targetKhz)
        assertEquals(ActionIssue(Control.TuneFrequency, RejectReason.IncompatibleState), radio.state.value.actionIssue)
    }

    @Test fun parkingAfterSendPreservesPendingConfirmation() {
        connect()
        receive(28)
        shadowOf(main.looper).idle()
        send(RadioAction.TuneFrequency(97700))
        drainWorker()
        send(RadioAction.EnterParking)
        receive(30)
        shadowOf(main.looper).idle()
        assertEquals(97700, radio.state.value.tuning.targetKhz)
        receive(102)
        shadowOf(main.looper).idle()
        assertNull(radio.state.value.tuning.targetKhz)
        assertEquals(97700, radio.state.value.tuning.confirmed?.frequencyKhz)
    }

    @Test fun userCheckSkipsProbeWhenRxRestoresConnectionInQueue() {
        connect()
        receive(28)
        shadowOf(main.looper).idle()
        send(RadioAction.TuneFrequency(97700))
        drainWorker()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(3))
        transport.enqueueReceived(byteArrayOf())
        poll()
        shadowOf(main.looper).idle()
        assertEquals(TuningPhase.Unconfirmed, radio.state.value.tuning.phase)
        assertEquals(Admission.Accepted, send(RadioAction.CheckConnection))
        receive(28)
        drainWorker()
        shadowOf(main.looper).idle()
        assertEquals(1, transport.sendCount)
        assertEquals(ActionIssue(Control.CheckConnection, RejectReason.IncompatibleState), radio.state.value.actionIssue)
    }

    @Test fun transportErrorIsPublishedWithItsDisconnect() {
        connect()
        receive(28)
        shadowOf(main.looper).idle()
        transport.close()
        assertEquals(Admission.Accepted, send(RadioAction.Step(Direction.Up)))
        drainWorker()
        shadowOf(main.looper).idle()
        assertEquals(Connection.Disconnected, radio.state.value.receiver.connection)
        assertEquals(ActionIssue(Control.Step, RejectReason.TransportError), radio.state.value.actionIssue)
    }

    @Test fun oldEditResultCannotShowInReopenedParking() {
        connect()
        send(RadioAction.EnterParking)
        val list = radio.state.value.stations
        val ref = SelectionRef(checkNotNull(list.activeRegionId), checkNotNull(list.activeSelectionName))
        send(RadioAction.Edit(SelectionEdit.Create(ref)))
        while (storage.isNotEmpty()) storage.removeFirst().run()
        send(RadioAction.ExitParking)
        send(RadioAction.EnterParking)
        drainStorage()
        assertNull(radio.state.value.stations.editIssue)
        assertNull(session.screen.nameError)
    }

    @Test fun lateRxCannotConfirmOrPersistInTheNextSession() {
        connect()
        receive(28)
        shadowOf(main.looper).idle()
        drainStorage()
        assertEquals(90300, radio.state.value.tuning.saved?.frequencyKhz)
        receive(40) // Leave the observation in the main queue.
        module.usb.setFixtureConnected(false)
        module.usb.setFixtureConnected(true)
        drainWorker()
        shadowOf(main.looper).idle()
        drainStorage()
        val next = radio.state.value
        assertEquals(Connection.Observing, next.receiver.connection)
        assertNull(next.tuning.confirmed)
        assertNull(next.tuning.targetKhz)
        assertEquals(90300, next.tuning.saved?.frequencyKhz)
    }
}
