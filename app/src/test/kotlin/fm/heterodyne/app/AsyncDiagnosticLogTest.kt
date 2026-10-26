package fm.heterodyne.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AsyncDiagnosticLogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun overflowEvictsOnlyRxAndPreservesTxAndEventsWithoutBlocking() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val disk = DiagnosticLog(temporary.newFolder(), clock = { 0L })
        val log = AsyncDiagnosticLog({ entered.countDown(); resume.await(); disk }, capacity = 3)
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        try {
            log.tx(byteArrayOf(2))
            log.event("first")
            log.rx(byteArrayOf(1))
            log.event("second")
            assertEquals(1L, log.lostRecords)
        } finally {
            resume.countDown()
        }
        val text = log.readTail(32768)
        assertTrue(text.contains("0 TX 02\n"))
        assertTrue(text.contains("first"))
        assertTrue(text.contains("second"))
        assertTrue(text.contains("потеряно 1"))
        assertFalse(text.contains(" RX "))
        log.close()
    }

    @Test fun failedWriteIsCountedAndFollowingEventStillWritten() {
        val disk = DiagnosticLog(temporary.newFolder())
        var fail = true
        val log = AsyncDiagnosticLog({ disk }, beforeWrite = {
            if (fail) { fail = false; throw java.io.IOException() }
        })
        log.event("fails")
        log.event("survives")
        assertTrue(log.readTail(32768).contains("survives"))
        assertEquals(1L, log.writeFailures)
        log.close()
    }
}
