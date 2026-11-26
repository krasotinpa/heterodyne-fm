package fm.heterodyne.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekHintTest {
    @Test fun seekHintRoundsUpOnlyWhileDriving() {
        assertEquals(4, seekPauseSeconds(true, 4000))
        assertEquals(5, seekPauseSeconds(true, 4001))
        assertEquals(1, seekPauseSeconds(true, 1))
        assertEquals(null, seekPauseSeconds(false, 4000))
    }
}
