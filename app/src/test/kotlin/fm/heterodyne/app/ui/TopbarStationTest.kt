package fm.heterodyne.app.ui

import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.TuneTarget
import fm.heterodyne.app.stations.SessionStation
import fm.heterodyne.app.stations.StationsState
import org.junit.Assert.*
import org.junit.Test

class TopbarStationTest {
    @Test fun confirmedStationOverridesSavedStation() {
        val state = StationsState.defaults().copy(lastStation = SessionStation(87500))
        assertEquals(TuneTarget(Band.FM, 90300), topbarStation(TuneTarget(Band.FM, 90300), state))
    }
    @Test fun savedStationIsDisplayedWithoutLastPlayed() {
        val state = StationsState.defaults().copy(lastStation = SessionStation(90300), lastPlayed = false)
        assertEquals(TuneTarget(Band.FM, 90300), topbarStation(null, state))
    }
    @Test fun noStationLeavesPlaceholder() {
        assertNull(topbarStation(null, StationsState.defaults()))
        assertNull(topbarStation(null, null))
    }
}
