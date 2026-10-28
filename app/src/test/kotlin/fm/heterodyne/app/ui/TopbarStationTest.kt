package fm.heterodyne.app.ui

import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.TuneTarget
import fm.heterodyne.app.stations.SessionStation
import fm.heterodyne.app.stations.StationsState
import fm.heterodyne.app.stations.Band as StationBand
import org.junit.Assert.*
import org.junit.Test

class TopbarStationTest {
    @Test fun confirmedStationWinsOverSavedStation() {
        val state = StationsState.defaults().copy(lastStation = SessionStation(StationBand.AM, 801))
        assertEquals(TuneTarget(Band.FM, 90300), topbarStation(TuneTarget(Band.FM, 90300), state))
    }

    @Test fun savedStationKeepsItsBandWithoutLastPlayed() {
        val state = StationsState.defaults().copy(lastStation = SessionStation(StationBand.AM, 801),
            band = StationBand.FM, lastPlayed = false)
        assertEquals(TuneTarget(Band.AM, 801), topbarStation(null, state))
    }

    @Test fun noStationLeavesPlaceholder() {
        assertNull(topbarStation(null, StationsState.defaults()))
        assertNull(topbarStation(null, null))
    }
}
