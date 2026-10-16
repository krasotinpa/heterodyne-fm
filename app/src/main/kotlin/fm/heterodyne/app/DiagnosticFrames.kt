package fm.heterodyne.app

import fm.heterodyne.app.module.FrameDecoder

/** Bounded live USB records; RX decoding is independent of read boundaries. */
class DiagnosticFrames(private val capacity: Int = 100) {
    init { require(capacity > 0) }
    private val records = ArrayDeque<String>()
    @Volatile var byte7: Int? = null
        private set
    private val decoder = FrameDecoder { frame ->
        if (frame[5].toInt() == 0x24 && frame.size > 8) byte7 = frame[7].toInt() and 255
    }
    @Synchronized fun accept(direction: String, bytes: ByteArray, time: Long) {
        if (records.size == capacity) records.removeFirst()
        records.addLast("$time $direction " + bytes.take(4096).joinToString("") { "%02x".format(it.toInt() and 255) })
        if (direction == "RX") decoder.accept(bytes)
    }
    @Synchronized fun resetConnection() { decoder.clear(); byte7 = null }
    @Synchronized fun snapshot(): List<String> = records.toList()
}
