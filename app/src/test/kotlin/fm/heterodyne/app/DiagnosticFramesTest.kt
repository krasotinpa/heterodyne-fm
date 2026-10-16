package fm.heterodyne.app

import org.junit.Assert.*
import org.junit.Test

class DiagnosticFramesTest {
    @Test fun keepsBoundedLiveTrafficAndDecodesSplitRxOnly() {
        val frames = DiagnosticFrames(2)
        // Synthetic valid 0x24 with byte +7 = 200.
        val rx = byteArrayOf(-6, 85, 1, 0, 4, 0x24, 0, -56, 20)
        frames.accept("RX", rx.copyOfRange(0, 7), 10)
        assertNull(frames.byte7)
        frames.accept("RX", rx.copyOfRange(7, 9), 11)
        assertEquals(200, frames.byte7)
        frames.accept("TX", byteArrayOf(1), 12)
        assertEquals(2, frames.snapshot().size)
        assertTrue(frames.snapshot().last().contains("12 TX 01"))
    }
}
