package fm.heterodyne.app.preflight

import fm.heterodyne.app.audio.UsbAudioPort
import fm.heterodyne.app.module.DeviceProfile
import fm.heterodyne.app.module.FakeTransport
import fm.heterodyne.app.module.UsbTransport

/** Synthetic Module replies. No Android USB, native handles or hardware operations. */
class FakeModuleTransport : UsbTransport, UsbAudioPort {
    private val fake = FakeTransport()
    val isOpen get() = fake.isOpen
    private var cid = 28
    private var fm = true
    private var sequence = 0
    private var claimed = false
    private var alternate = false

    override fun open(profile: DeviceProfile) {
        fake.open(profile)
        reply()
    }

    override fun send(bytes: ByteArray) {
        require(bytes.size >= 8 && bytes[0] == 0xfa.toByte() && bytes[1] == 0x55.toByte()
            && bytes[3] == 0.toByte() && (bytes[4].toInt() and 255) == bytes.size - 5
            && bytes[5] == 0x80.toByte() && bytes.drop(5).sumOf { it.toInt() and 255 } % 256 == 0)
        val payload = bytes.slice(6 until bytes.lastIndex).map { it.toInt() and 255 }
        when (payload) {
            listOf(0x85, 7) -> Unit
            listOf(0x84) -> { fm = !fm }
            listOf(0x88), listOf(0x86) -> { cid = Math.floorMod(cid - 1, 206) }
            listOf(0x89), listOf(0x87) -> { cid = (cid + 1) % 206 }
            else -> {
                require(payload.size == 5 && payload[0] == 0x82 && payload[1] == 0x20
                    && payload[2] in 0..205 && payload[3] == 0 && payload[4] == payload[2])
                cid = payload[2]
            }
        }
        fake.send(bytes)
        reply()
    }

    private fun reply() {
        // Synthetic 71-byte 0x22 fixture, not an observation of real hardware.
        val frame = ByteArray(71)
        frame[0] = 0xfa.toByte(); frame[1] = 0x55
        frame[2] = sequence.toByte(); sequence = (sequence + 1) % 254
        frame[4] = 66; frame[5] = 0x22
        frame[6] = if (fm) 0x20 else 0
        frame[7] = cid.toByte()
        frame[70] = (-frame.slice(5 until 70).sumOf { it.toInt() and 255 }).toByte()
        fake.enqueueReceived(frame)
    }

    override fun receive(): ByteArray {
        check(fake.isOpen)
        return if (fake.pendingCount == 0) byteArrayOf() else fake.receive()
    }
    override fun claim() { check(fake.isOpen && !claimed); claimed = true }
    override fun alt1() { check(claimed); alternate = true }
    override fun alt0() { check(claimed); alternate = false }
    override fun release() { check(!alternate); claimed = false }
    override fun close() { check(!claimed); fake.close() }
}
