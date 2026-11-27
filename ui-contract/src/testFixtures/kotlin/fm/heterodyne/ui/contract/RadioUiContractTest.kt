package fm.heterodyne.ui.contract

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The hosts supply events. Every assertion observes the same public RadioUi v1. */
abstract class RadioUiContractTest {
    protected abstract fun openRadio(noReply: Boolean = false): RadioUi
    protected abstract fun confirmTarget()
    protected abstract fun startMoving()
    protected abstract fun settle()
    protected abstract fun detachBeforeReply()
    protected abstract fun advance(millis: Long)

    @Test fun tuneKeepsLastObservationUntilReplyAndStopPreservesTheStation() = runBlocking {
        val radio = openRadio()
        val before = radio.state.value
        assertTrue(Control.TuneFrequency in before.enabled)
        assertEquals(Admission.Rejected(RejectReason.InvalidInput), radio.dispatch(RadioAction.TuneFrequency(101201)))
        assertEquals(Admission.Rejected(RejectReason.StaleContext),
            radio.dispatch(RadioAction.SelectStation("expired-region", StationRef.Catalog("missing"))))
        settle()
        assertEquals(before, radio.state.value)
        assertEquals(Admission.Accepted, radio.dispatch(RadioAction.TuneFrequency(101200)))
        settle()
        val pending = radio.state.value
        assertEquals(101200, pending.tuning.targetKhz)
        assertEquals(90300, pending.tuning.confirmed?.frequencyKhz)
        assertEquals(90300, pending.tuning.saved?.frequencyKhz)
        assertNull(pending.actionIssue)
        confirmTarget()
        val tuned = radio.state.value.tuning
        assertNull(tuned.targetKhz)
        assertEquals(TuningPhase.Idle, tuned.phase)
        assertEquals(101200, tuned.confirmed?.frequencyKhz)
        assertEquals(tuned.confirmed, tuned.saved)
        assertEquals(Admission.Accepted, radio.dispatch(RadioAction.Stop))
        settle()
        assertEquals(PlaybackStatus.Stopped, radio.state.value.playback.status)
        assertEquals(tuned, radio.state.value.tuning)
    }

    @Test fun missingReplyExpiresAndOnlyAnExplicitLinkCheckTimesOutTheConnection() = runBlocking {
        val radio = openRadio(noReply = true)
        assertEquals(Admission.Accepted, radio.dispatch(RadioAction.TuneFrequency(97700)))
        settle()
        advance(300)
        assertEquals(TuningPhase.Idle, radio.state.value.tuning.phase)
        advance(1)
        assertEquals(TuningPhase.Waiting, radio.state.value.tuning.phase)
        advance(2698)
        assertEquals(97700, radio.state.value.tuning.targetKhz)
        advance(1)
        val expired = radio.state.value
        assertEquals(TuningPhase.Unconfirmed, expired.tuning.phase)
        assertEquals(DisabledReason.Unconfirmed, expired.disabledReason)
        assertNull(expired.tuning.targetKhz)
        assertEquals(90300, expired.tuning.confirmed?.frequencyKhz)
        advance(10000)
        assertEquals(expired, radio.state.value)
        assertTrue(Control.CheckConnection in expired.enabled)
        assertEquals(Admission.Accepted, radio.dispatch(RadioAction.CheckConnection))
        settle()
        val retry = RadioAction.TuneFrequency(101200)
        when (val admission = radio.dispatch(retry)) {
            Admission.Accepted -> {
                settle()
                assertEquals(ActionIssue(Control.TuneFrequency, RejectReason.Busy), radio.state.value.actionIssue)
            }
            is Admission.Rejected -> assertEquals(RejectReason.Busy, admission.reason)
        }
        assertNull(radio.state.value.tuning.targetKhz)
        advance(3000)
        val silent = radio.state.value
        assertEquals(Connection.Observing, silent.receiver.connection)
        assertEquals(TuningPhase.Unresponsive, silent.tuning.phase)
        assertEquals(DisabledReason.Unresponsive, silent.disabledReason)
        assertEquals(expired.tuning.confirmed, silent.tuning.confirmed)
        assertTrue(Control.TuneFrequency in silent.enabled)
        assertEquals(Admission.Accepted, radio.dispatch(retry))
        settle()
        assertEquals(101200, radio.state.value.tuning.targetKhz)
        assertEquals(silent.tuning.confirmed, radio.state.value.tuning.confirmed)
        assertNull(radio.state.value.actionIssue)
    }

    @Test fun startAndStopChangeThePlaybackOfTheSelectedReceiver() = runBlocking {
        val radio = openRadio()
        val other = radio.state.value.receiver.availableProfiles.single { it.id != radio.state.value.receiver.selected }
        assertEquals(Admission.Accepted, radio.dispatch(RadioAction.EnterParking))
        assertEquals(Admission.Accepted, radio.dispatch(RadioAction.ChangeSetting(SettingChange.Receiver(other.id))))
        settle()
        assertEquals(other.id, radio.state.value.receiver.selected)
        assertEquals(Admission.Accepted, radio.dispatch(RadioAction.ExitParking))
        assertEquals(PlaybackStatus.Stopped, radio.state.value.playback.status)
        assertEquals(Admission.Accepted, radio.dispatch(RadioAction.Start))
        settle()
        advance(0)
        assertEquals(PlaybackStatus.Playing, radio.state.value.playback.status)
        val confirmed = radio.state.value.tuning.confirmed
        assertEquals(Admission.Accepted, radio.dispatch(RadioAction.Stop))
        settle()
        advance(0)
        assertEquals(PlaybackStatus.Stopped, radio.state.value.playback.status)
        assertEquals(confirmed, radio.state.value.tuning.confirmed)
    }

    @Test fun activeSeekRejectsAnotherGestureAndExpiresAtItsOriginalLimit() = runBlocking {
        val radio = openRadio()
        assertEquals(20, radio.state.value.settings.seekLimitSeconds)
        assertEquals(Admission.Accepted, radio.dispatch(RadioAction.Seek(Direction.Up)))
        settle()
        val seeking = radio.state.value
        assertEquals(TuningPhase.Seeking, seeking.tuning.phase)
        assertEquals(Direction.Up, seeking.tuning.pause?.direction)
        assertFalse(Control.Seek in seeking.enabled)
        assertEquals(Admission.Rejected(RejectReason.Busy), radio.dispatch(RadioAction.Seek(Direction.Down)))
        startMoving()
        advance(19999)
        assertEquals(TuningPhase.Seeking, radio.state.value.tuning.phase)
        advance(1)
        assertEquals(TuningPhase.SeekIncomplete, radio.state.value.tuning.phase)
        assertNull(radio.state.value.tuning.pause)
        assertEquals(seeking.tuning.confirmed, radio.state.value.tuning.confirmed)
    }

    @Test fun motionClosesParkingAndRevokesScreenSeekAndSettings() = runBlocking {
        val radio = openRadio()
        assertEquals(Admission.Accepted, radio.dispatch(RadioAction.EnterParking))
        assertTrue(Control.ChangeSetting in radio.state.value.enabled)
        val light = RadioAction.ChangeSetting(SettingChange.Theme(ThemeChoice.Light))
        assertEquals(Admission.Accepted, radio.dispatch(light))
        assertEquals(ThemeChoice.Light, radio.state.value.settings.theme)
        startMoving()
        val moving = radio.state.value
        assertTrue(moving.moving)
        assertEquals(ScreenMode.Efir, moving.mode)
        assertFalse(Control.ChangeSetting in moving.enabled)
        assertFalse(Control.Seek in moving.enabled)
        assertFalse(Control.EnterParking in moving.enabled)
        assertEquals(Admission.Rejected(RejectReason.WrongMode), radio.dispatch(RadioAction.EnterParking))
        assertEquals(Admission.Rejected(RejectReason.WrongMode), radio.dispatch(RadioAction.Seek(Direction.Up)))
        assertEquals(Admission.Rejected(RejectReason.WrongMode),
            radio.dispatch(RadioAction.ChangeSetting(SettingChange.Theme(ThemeChoice.Dark))))
        settle()
        assertEquals(moving, radio.state.value)
    }

    @Test fun detachDiscardsPendingConfirmationAndKeepsTheSelectedReceiver() = runBlocking {
        val radio = openRadio()
        val before = radio.state.value
        assertEquals(Connection.Observing, before.receiver.connection)
        assertEquals(ReceiverId("module"), before.receiver.selected)
        assertTrue(before.receiver.availableProfiles.size > 1)
        assertEquals(90300, before.tuning.saved?.frequencyKhz)
        assertEquals(Admission.Accepted, radio.dispatch(RadioAction.TuneFrequency(101200)))
        settle()
        assertEquals(101200, radio.state.value.tuning.targetKhz)
        detachBeforeReply()
        advance(3000)
        val offline = radio.state.value
        assertEquals(before.receiver.selected, offline.receiver.selected)
        assertEquals(Connection.Disconnected, offline.receiver.connection)
        assertEquals(DisabledReason.Disconnected, offline.disabledReason)
        assertNull(offline.tuning.confirmed)
        assertNull(offline.tuning.targetKhz)
        assertNull(offline.tuning.pause)
        assertEquals(before.tuning.saved, offline.tuning.saved)
        assertEquals(PlaybackStatus.Stopped, offline.playback.status)
        assertNull(offline.actionIssue)
        assertFalse(Control.TuneFrequency in offline.enabled)
        assertEquals(Admission.Rejected(RejectReason.NotConnected), radio.dispatch(RadioAction.TuneFrequency(101200)))
        assertTrue(Control.Stop in offline.enabled)
        assertEquals(Admission.Accepted, radio.dispatch(RadioAction.Stop))
    }
}
