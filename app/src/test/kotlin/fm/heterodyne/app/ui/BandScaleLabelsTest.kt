package fm.heterodyne.app.ui

import fm.heterodyne.app.stations.*
import fm.heterodyne.app.module.Band as ModuleBand
import org.junit.Assert.*
import org.junit.Test

class BandScaleLabelsTest {
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
