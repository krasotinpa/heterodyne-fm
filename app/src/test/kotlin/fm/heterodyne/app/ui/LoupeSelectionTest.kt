package fm.heterodyne.app.ui

import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.TuneTarget
import fm.heterodyne.app.stations.CatalogStation
import fm.heterodyne.app.stations.Band as StationBand
import org.junit.Assert.*
import org.junit.Test

class LoupeSelectionTest {
    private val stations = listOf(108000, 87500, 97700).map {
        CatalogStation("$it", "$it", StationBand.FM, it)
    } + CatalogStation("am", "AM", StationBand.AM, 999)
    @Test fun nearestStationUsesWholeFmBandAndClampsEdges() {
        assertEquals(87500, nearestLoupeStation(stations, -1f)?.frequencyKhz)
        assertEquals(97700, nearestLoupeStation(stations, .5f)?.frequencyKhz)
        assertEquals(108000, nearestLoupeStation(stations, 2f)?.frequencyKhz)
        assertNull(nearestLoupeStation(emptyList(), .5f))
    }
    @Test fun cancellationStartsBeyondHalfHeightOutsideEitherEdge() {
        assertFalse(isLoupeCancelled(-42f, 84f))
        assertTrue(isLoupeCancelled(-43f, 84f))
        assertFalse(isLoupeCancelled(126f, 84f))
        assertTrue(isLoupeCancelled(127f, 84f))
    }
    @Test fun releaseRejectsCancellationAndCurrentFrequency() {
        val station = stations.first()
        assertNull(loupeRelease(station, true, null))
        assertNull(loupeRelease(station, false, TuneTarget(Band.FM, 108000)))
        assertEquals(station.copyEntry(), loupeRelease(station, false, TuneTarget(Band.FM, 87500)))
    }
    @Test fun windowLeavesEmptySlotsAtBandEdge() {
        assertEquals(listOf(null, null, null, stations[1], stations[2], stations[0], null),
            loupeWindow(stations, stations[1], 7))
    }
}
