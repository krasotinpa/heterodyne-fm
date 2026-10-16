package fm.heterodyne.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationIdentityTest {
    @Test
    fun applicationIdIdentifiesHeterodyneFm() {
        assertEquals("fm.heterodyne.app", BuildConfig.APPLICATION_ID)
    }
}
