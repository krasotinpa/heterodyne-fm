package fm.heterodyne.ui.compose

import fm.heterodyne.ui.contract.*

import org.junit.Assert.*
import org.junit.Test

class BandScaleLabelsTest {
    @Test fun denseStationsLeaveSpaceBetweenHighlightsAndOrdinaryMarks() {
        // Two stations 100 kHz apart on a 1000 px FM scale are 4.88 px apart.
        val frequencies = listOf(97700, 97800, 97900)
        val stripe = scaleMarkWidth(97800, frequencies, 1000f, 8f, true)
        val ordinary = scaleMarkWidth(97700, frequencies, 1000f, 5f, false)
        assertTrue(stripe > ordinary)
        assertTrue((stripe + ordinary) / 2 < 4.87f)

    }

    @Test fun denseConfirmedStationDoesNotThinDistantMarks() {
        val frequencies = listOf(97700, 97800, 105000, 107000)
        assertTrue(scaleMarkWidth(97800, frequencies, 1000f, 8f, true) < 4f)
        assertEquals(3f, scaleMarkWidth(105000, frequencies, 1000f, 3f, false), .001f)
        assertEquals(5f, scaleMarkWidth(107000, frequencies, 1000f, 5f, false), .001f)
    }

    @Test fun scaleLabelsContainOnlyEdgesAndConfirmedFrequency() {
        assertEquals(listOf("87,5", "97,8", "108,0"), scaleFrequencyLabels(97800))
    }

    @Test fun frequencyPositionUsesFullDisplayBand() {
        assertEquals(0f, bandFraction(87500), .0001f)
        assertEquals(.5f, bandFraction(97750), .0001f)
        assertEquals(1f, bandFraction(108000), .0001f)
    }
}
