package fm.heterodyne.app.ui

import fm.heterodyne.app.module.*
import org.junit.Assert.*
import org.junit.Test

class ScaleCursorTest {
    @Test fun savedStationIsFadedBeforeConfirmation() {
        val cursor = scaleCursor(null, TuneTarget(Band.FM, 90300), TransitionState.Disconnected)
        assertEquals(TuneTarget(Band.FM, 90300), cursor?.target)
        assertEquals(true, cursor?.faded)
    }

    @Test fun confirmedStationOverridesSavedStation() {
        val confirmed = TuneTarget(Band.FM, 97700)
        val cursor = scaleCursor(confirmed, TuneTarget(Band.FM, 90300), TransitionState.Idle)
        assertEquals(confirmed, cursor?.target)
        assertEquals(false, cursor?.faded)
    }

    @Test fun lostConfirmationOrConnectionFadesConfirmedStation() {
        val confirmed = TuneTarget(Band.FM, 97700)
        listOf(TransitionState.Unconfirmed, TransitionState.Unresponsive, TransitionState.Disconnected).forEach { state ->
            val cursor = scaleCursor(confirmed, TuneTarget(Band.FM, 90300), state)
            assertEquals(confirmed, cursor?.target)
            assertEquals(true, cursor?.faded)
        }
    }

    @Test fun noStationLeavesNoCursor() {
        assertNull(scaleCursor(null, null, TransitionState.Idle))
    }

    @Test fun confirmedNonFmHidesSavedStation() {
        assertNull(scaleCursor(TuneTarget(Band.AM, 666), TuneTarget(Band.FM, 90300), TransitionState.Idle))
    }

    @Test fun savedNonFmLeavesNoCursor() {
        assertNull(scaleCursor(null, TuneTarget(Band.AM, 666), TransitionState.Idle))
    }
}
