package fm.heterodyne.app.module

/** Blocking IO belongs on the adapter's worker. Closing must release pending IO. */
interface UsbTransport {
    fun open(profile: DeviceProfile)
    fun receive(): ByteArray
    fun send(bytes: ByteArray)
    fun close()
}

class FakeTransport : UsbTransport {
    val sentFrames = mutableListOf<ByteArray>()
    var openCount = 0; private set
    var sendCount = 0; private set
    var closeCount = 0; private set
    var closeCalls = 0; private set
    var isOpen = false; private set
    private val received = ArrayDeque<ByteArray>()
    val pendingCount get() = received.size
    override fun open(profile: DeviceProfile) {
        check(!isOpen)
        openCount++
        isOpen = true
    }
    fun enqueueReceived(bytes: ByteArray) { check(isOpen); received.addLast(bytes.copyOf()) }
    override fun receive(): ByteArray { check(isOpen); return received.removeFirst() }
    override fun send(bytes: ByteArray) { check(isOpen); sendCount++; sentFrames.add(bytes.copyOf()) }
    override fun close() {
        closeCalls++
        if (isOpen) closeCount++
        isOpen = false
        received.clear()
    }
}
