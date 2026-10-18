package fm.heterodyne.app

import org.junit.Assert.*
import org.junit.Test

class DeveloperModeTest {
    @Test fun switchEnablesOnlyWhenParkedAndPersistsUntilManuallyDisabled() {
        val saved = mutableListOf<Boolean>()
        val mode = DeveloperMode(false) { saved.add(it) }
        mode.setEnabled(true, parked = false)
        assertFalse(mode.enabled)
        assertTrue(saved.isEmpty())
        mode.setEnabled(true, parked = true)
        assertTrue(mode.enabled)
        assertTrue(DeveloperMode(saved.last()) {}.enabled)
        mode.setEnabled(true, parked = false)
        assertTrue(mode.enabled)
        mode.setEnabled(false, parked = false)
        assertFalse(mode.enabled)
        assertEquals(listOf(true, false), saved)
    }
}
