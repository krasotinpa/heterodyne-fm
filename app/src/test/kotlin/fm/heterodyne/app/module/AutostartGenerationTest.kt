package fm.heterodyne.app.module

import org.junit.Assert.*
import org.junit.Test

class AutostartGenerationTest {
    @Test fun userActionWhileStationLoadsInvalidatesTheOriginalOpening() {
        val generation = AutostartGeneration()
        val opening = generation.capture()
        generation.cancel() // Includes diagnosticCommand, without a StationsModule action.
        assertTrue(generation.isCancelled(opening))
        assertFalse(generation.isCancelled(generation.capture()))
        assertTrue(generation.isCancelled(opening)) // A later opening cannot revalidate the old one.
    }

    @Test fun cancellationIsVisibleAcrossThreads() {
        val generation = AutostartGeneration()
        val opening = generation.capture()
        val user = Thread { generation.cancel() }
        user.start(); user.join()
        assertTrue(generation.isCancelled(opening))
    }
}
