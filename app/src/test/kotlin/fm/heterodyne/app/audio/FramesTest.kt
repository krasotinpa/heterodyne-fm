package fm.heterodyne.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FramesTest {
    private fun stream(lead: Int, mono: Boolean): ByteArray {
        val bytes = ByteArray(lead + 4800 * 4)
        repeat(4800) { n ->
            val left = (8000 * sin(n / 7.0)).toInt().toShort()
            val right = if (mono) left else (8000 * cos(n / 5.0)).toInt().toShort()
            val i = lead + n * 4
            bytes[i] = left.toByte()
            bytes[i + 1] = (left.toInt() shr 8).toByte()
            bytes[i + 2] = right.toByte()
            bytes[i + 3] = (right.toInt() shr 8).toByte()
        }
        return bytes
    }

    @Test fun prototypeOffsetCases() {
        for ((bytes, expected) in listOf(
            stream(0, true) to 0,
            stream(2, true) to 2,
            stream(0, false) to 2,
            ByteArray(19200) to 2,
        )) assertEquals(expected, Frames.frameOffset(bytes, bytes.size))
        assertEquals(32767, Frames.s16(byteArrayOf(0xff.toByte(), 0x7f), 0))
        assertEquals(-32768, Frames.s16(byteArrayOf(0, 0x80.toByte()), 0))
    }
}
