package fm.heterodyne.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationIdentityTest {
    @Test
    fun applicationIdIdentifiesHeterodyneFm() {
        val expected = if (BuildConfig.BUILD_TYPE == "preflight") "fm.heterodyne.app.preflight" else "fm.heterodyne.app"
        assertEquals(expected, BuildConfig.APPLICATION_ID)
    }
}
