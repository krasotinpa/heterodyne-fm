package fm.heterodyne.ui.sandbox

import fm.heterodyne.ui.contract.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScriptedRadioUiTest {
    @Test fun workingReceiverStartsAndStopsWithoutChangingTheConfirmedStation() = runBlocking {
        val radio: RadioUi = ScriptedRadioUi(Scenario.Working)
        val station = StationView(90300, "Маяк стенда")
        assertEquals(station, radio.state.value.tuning.confirmed)
        assertEquals(Admission.Accepted, radio.dispatch(RadioAction.Start))
        assertEquals(PlaybackStatus.Playing, radio.state.value.playback.status)
        assertEquals(Admission.Accepted, radio.dispatch(RadioAction.Stop))
        assertEquals(PlaybackStatus.Stopped, radio.state.value.playback.status)
        assertEquals(station, radio.state.value.tuning.confirmed)
    }

    @Test fun missingReplyUsesTheScenarioClockAndKeepsTheLastConfirmation() = runBlocking {
        val radio = ScriptedRadioUi(Scenario.TuneTimeout)
        val state = { radio.state.value }
        assertEquals(101200, state().tuning.targetKhz)
        radio.advanceBy(300)
        assertEquals(TuningPhase.Idle, state().tuning.phase)
        radio.advanceBy(1)
        assertEquals(TuningPhase.Waiting, state().tuning.phase)
        radio.advanceBy(2698)
        assertEquals(TuningPhase.Waiting, state().tuning.phase)
        radio.advanceBy(1)
        assertEquals(TuningPhase.Unconfirmed, state().tuning.phase)
        assertNull(state().tuning.targetKhz)
        assertEquals(90300, state().tuning.confirmed?.frequencyKhz)
        assertTrue(Control.CheckConnection in state().enabled)
        assertEquals(Admission.Accepted, radio.dispatch(RadioAction.CheckConnection))
        assertFalse(Control.CheckConnection in state().enabled)
        radio.advanceBy(3000)
        assertEquals(TuningPhase.Unresponsive, state().tuning.phase)
        assertEquals(90300, state().tuning.confirmed?.frequencyKhz)
    }

    @Test fun stopRemainsAvailableAfterDetach() = runBlocking {
        val radio = ScriptedRadioUi(Scenario.Working)
        assertEquals(Admission.Accepted, radio.dispatch(RadioAction.Start))
        radio.detach()

        assertEquals(Connection.Disconnected, radio.state.value.receiver.connection)
        assertTrue(Control.Stop in radio.state.value.enabled)
        assertEquals(Admission.Accepted, radio.dispatch(RadioAction.Stop))
        assertEquals(PlaybackStatus.Stopped, radio.state.value.playback.status)
    }

    @Test fun manualTuneBecomesAvailableWhenTheLinkCheckExpires() = runBlocking {
        val radio = ScriptedRadioUi(Scenario.TuneTimeout)
        val tune = RadioAction.TuneFrequency(97700)
        radio.advanceBy(3000)
        assertEquals(Admission.Accepted, radio.dispatch(RadioAction.CheckConnection))
        assertFalse(Control.TuneFrequency in radio.state.value.enabled)
        assertEquals(Admission.Rejected(RejectReason.Busy), radio.dispatch(tune))

        radio.advanceBy(3000)
        assertTrue(Control.TuneFrequency in radio.state.value.enabled)
        assertEquals(Admission.Accepted, radio.dispatch(tune))
        assertEquals(97700, radio.state.value.tuning.targetKhz)
        assertEquals(90300, radio.state.value.tuning.confirmed?.frequencyKhz)
    }

    @Test fun repeatSeekCannotExtendTheDeadlineOrChangeItsDirection() = runBlocking {
        val radio = ScriptedRadioUi(Scenario.Seek)
        radio.advanceBy(19_999)
        assertEquals(Admission.Rejected(RejectReason.Busy), radio.dispatch(RadioAction.Seek(Direction.Down)))
        assertEquals(Direction.Up, radio.state.value.tuning.pause?.direction)
        radio.advanceBy(1)
        assertEquals(TuningPhase.SeekIncomplete, radio.state.value.tuning.phase)
        assertNull(radio.state.value.tuning.pause)
    }

    @Test fun selectingAndRestartingDuringTransientLossKeepTheScriptedFocus() = runBlocking {
        val radio = ScriptedRadioUi(Scenario.Focus)
        radio.focus(FocusEffect.Muted)
        radio.dispatch(RadioAction.SelectStation("stand", StationRef.Catalog("101200")))
        assertEquals(PlaybackView(PlaybackStatus.Playing, FocusEffect.Muted), radio.state.value.playback)
        radio.dispatch(RadioAction.Stop)
        radio.dispatch(RadioAction.Start)
        assertEquals(FocusEffect.Muted, radio.state.value.playback.focus)
        radio.focus(FocusEffect.Normal)
        assertEquals(FocusEffect.Normal, radio.state.value.playback.focus)
    }
}
