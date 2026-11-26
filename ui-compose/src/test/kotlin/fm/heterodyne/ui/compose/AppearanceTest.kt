package fm.heterodyne.ui.compose

import fm.heterodyne.ui.contract.*

import org.junit.Assert.*
import org.junit.Test

class AppearanceTest {
    @Test fun disabledControlsKeepReadableColorsEvenWhenSelected() {
        assertEquals(ControlColorTokens("surface", "disabledText"), controlColorTokens(enabled = false, selected = true))
        assertEquals(ControlColorTokens("surface", "disabledText"), controlColorTokens(enabled = false, selected = false))
        assertEquals(ControlColorTokens("accent", "onAccent"), controlColorTokens(enabled = true, selected = true))
        assertEquals(ControlColorTokens("surface2", "text"), controlColorTokens(enabled = true, selected = false))
    }

}
