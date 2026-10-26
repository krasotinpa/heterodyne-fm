package fm.heterodyne.app.ui

import fm.heterodyne.app.stations.*
import fm.heterodyne.app.module.Band as ModuleBand
import org.junit.Assert.*
import org.junit.Test

class BandScaleLabelsTest {
    @Test fun denseStationsLeaveSpaceBetweenHighlightsAndOrdinaryMarks() {
        // Two stations 100 kHz apart on a 1000 px FM scale are 4.88 px apart.
        val frequencies = listOf(97700, 97800, 97900)
        val stripe = scaleMarkWidth(ModuleBand.FM, 97800, frequencies, 1000f, 8f, true)
        val ordinary = scaleMarkWidth(ModuleBand.FM, 97700, frequencies, 1000f, 5f, false)
        assertTrue(stripe > ordinary)
        assertTrue((stripe + ordinary) / 2 < 4.87f)
        assertEquals(8f, scaleMarkWidth(ModuleBand.AM, 855, listOf(855), 1000f, 8f, true), .001f)
    }

    @Test fun denseConfirmedStationDoesNotThinDistantMarks() {
        val frequencies = listOf(97700, 97800, 105000, 107000)
        assertTrue(scaleMarkWidth(ModuleBand.FM, 97800, frequencies, 1000f, 8f, true) < 4f)
        assertEquals(3f, scaleMarkWidth(ModuleBand.FM, 105000, frequencies, 1000f, 3f, false), .001f)
        assertEquals(5f, scaleMarkWidth(ModuleBand.FM, 107000, frequencies, 1000f, 5f, false), .001f)
    }

    @Test fun scaleLabelsContainOnlyEdgesAndConfirmedFrequency() {
        assertEquals(listOf("87,5", "97,8", "108,0"), scaleFrequencyLabels(ModuleBand.FM, 97800))
        assertEquals(listOf("522", "855", "1620"), scaleFrequencyLabels(ModuleBand.AM, 855))
        assertTrue(scaleFrequencyLabels(ModuleBand.DAB, 0).isEmpty())
    }

    @Test fun frequencyPositionUsesFullDisplayBand() {
        assertEquals(0f, bandFraction(ModuleBand.FM, 87500), .0001f)
        assertEquals(.5f, bandFraction(ModuleBand.FM, 97750), .0001f)
        assertEquals(1f, bandFraction(ModuleBand.FM, 108000), .0001f)
        assertEquals(0f, bandFraction(ModuleBand.AM, 522), .0001f)
        assertTrue(bandFraction(ModuleBand.AM, 531) > bandFraction(ModuleBand.AM, 522))
        assertTrue(bandFraction(ModuleBand.AM, 1611) < bandFraction(ModuleBand.AM, 1620))
        assertEquals(1f, bandFraction(ModuleBand.AM, 1620), .0001f)
    }
}
