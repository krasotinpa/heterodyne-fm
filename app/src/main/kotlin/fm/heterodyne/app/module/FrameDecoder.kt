package fm.heterodyne.app.module

/** Model B; at most 4096 bytes retained, independently of USB chunk boundaries. */
internal class FrameDecoder(private val frame: (ByteArray) -> Unit) {
    private var buffer = byteArrayOf()
    fun clear() { buffer = byteArrayOf() }
    private fun byte(index: Int) = buffer[index].toInt() and 0xff
    private fun length(start: Int) = 5 + (byte(start + 3) shl 8) + byte(start + 4)
    private fun marker(start: Int) = byte(start) == 0xfa && byte(start + 1) == 0x55
    private fun valid(start: Int, size: Int): Boolean =
        (start + 5 until start + size).sumOf { byte(it) } % 256 == 0

    private fun allowed(start: Int, size: Int) = size in 7..4096 &&
        (buffer.size <= start + 5 || byte(start + 5) != 0x22 || size == 71)

    fun accept(chunk: ByteArray) {
        var offset = 0
        while (offset < chunk.size) {
            val end = minOf(chunk.size, offset + 4096 - buffer.size)
            buffer += chunk.copyOfRange(offset, end)
            offset = end
            while (buffer.size >= 2) {
                if (!marker(0)) { buffer = buffer.drop(1).toByteArray(); continue }
                if (buffer.size < 5) break
                val size = length(0)
                if (!allowed(0, size)) {
                    buffer = buffer.drop(1).toByteArray(); continue
                }
                if (buffer.size < size) {
                    // Prefer a complete validated frame over an incomplete candidate.
                    val next = (1..buffer.size - 7).firstOrNull {
                        marker(it) && allowed(it, length(it)) &&
                            it + length(it) <= buffer.size && valid(it, length(it))
                    }
                    if (next == null) break
                    buffer = buffer.drop(next).toByteArray()
                    continue
                }
                if (!valid(0, size)) { buffer = buffer.drop(1).toByteArray(); continue }
                val complete = buffer.copyOfRange(0, size)
                buffer = buffer.drop(size).toByteArray()
                frame(complete)
            }
        }
    }
}
