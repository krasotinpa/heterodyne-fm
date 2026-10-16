package fm.heterodyne.app

import org.junit.Assert.*
import org.junit.Test

class DeveloperModeTest {
    @Test fun enablesOnlyAfterSevenParkedTapsAndPersistsUntilDisabled() {
        var saved = false
        val mode = DeveloperMode(false) { saved = it }
        repeat(6) { mode.tap(true) }
        assertFalse(mode.enabled)
        mode.tap(false)
        repeat(6) { mode.tap(true) }
        assertFalse(mode.enabled)
        mode.tap(true)
        assertTrue(saved)
        assertTrue(DeveloperMode(saved) {}.enabled)
        mode.disable()
        assertFalse(saved)
    }
}
