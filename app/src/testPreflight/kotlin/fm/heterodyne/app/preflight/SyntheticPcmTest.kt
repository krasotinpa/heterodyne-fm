package fm.heterodyne.app.preflight

import org.junit.Assert.*
import org.junit.Test

class SyntheticPcmTest {
    @Test fun producesBoundedStereoS16leAndCloses() {
        val source = SyntheticPcmSource()
        val pcm = ByteArray(2496)
        source.open()
        assertEquals(1920, source.read(pcm)) // 480 stereo frames, 10 ms at 48 kHz.
        assertEquals(0, pcm[0].toInt())
        assertEquals(0, pcm[1].toInt())
        assertEquals(0, pcm[48].toInt())
        assertEquals(8, pcm[49].toInt()) // 1 kHz sine peak: 2048, little endian.
        for (i in 0 until 1920 step 4) {
            assertEquals(pcm[i], pcm[i + 2])
            assertEquals(pcm[i + 1], pcm[i + 3])
        }
        assertEquals(0L, source.badPackets())
        source.close()
        assertThrows(IllegalStateException::class.java) { source.read(pcm) }
    }
}
