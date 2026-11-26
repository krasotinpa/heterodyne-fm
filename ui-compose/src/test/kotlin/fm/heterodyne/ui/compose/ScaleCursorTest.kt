package fm.heterodyne.ui.compose

import fm.heterodyne.ui.contract.*

import org.junit.Assert.*
import org.junit.Test

class ScaleCursorTest {
    @Test fun savedStationIsFadedBeforeConfirmation() {
        val cursor = scaleCursor(null, station(90300), TuningPhase.Disconnected)
        assertEquals(90300, cursor?.frequencyKhz)
        assertEquals(true, cursor?.faded)
    }

    @Test fun confirmedStationOverridesSavedStation() {
        val confirmed = station(97700)
        val cursor = scaleCursor(confirmed, station(90300), TuningPhase.Idle)
        assertEquals(confirmed.frequencyKhz, cursor?.frequencyKhz)
        assertEquals(false, cursor?.faded)
    }

    @Test fun lostConfirmationOrConnectionFadesConfirmedStation() {
        val confirmed = station(97700)
        listOf(TuningPhase.Unconfirmed, TuningPhase.Unresponsive, TuningPhase.Disconnected).forEach { state ->
            val cursor = scaleCursor(confirmed, station(90300), state)
            assertEquals(confirmed.frequencyKhz, cursor?.frequencyKhz)
            assertEquals(true, cursor?.faded)
        }
    }

    @Test fun noStationLeavesNoCursor() {
        assertNull(scaleCursor(null, null, TuningPhase.Idle))
    }
}
