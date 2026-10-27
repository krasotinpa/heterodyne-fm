package fm.heterodyne.app.module

import fm.heterodyne.app.Journal
import fm.heterodyne.app.receiver.*
import org.junit.Assert.*
import org.junit.Test

class ReceiverTest {
    private val journal = object : Journal {
        override fun tx(frame: ByteArray) {}
        override fun rx(frame: ByteArray) {}
        override fun event(text: String) {}
    }

    @Test fun moduleDeclaresOnlyAcceptedCapabilities() {
        val receiver: Receiver = ModuleController(FakeTransport(), journal)
        assertEquals(ReceiverKind.Module, receiver.kind)
        assertEquals(setOf(Band.FM, Band.AM), receiver.capabilities.bands)
        assertTrue(receiver.capabilities.seek)
        assertFalse(receiver.capabilities.cancelSeek)
        assertFalse(receiver.capabilities.rds)
        assertFalse(receiver.capabilities.signalLevel)
        assertFalse(receiver.capabilities.stereo)
        assertEquals(ConnectionState.Disconnected, receiver.state)
        assertNull(receiver.snapshot.confirmed)
    }

    @Test fun autostartUsesSecondKindWithoutModuleFrames() {
        val fake = FakeReceiver()
        val receiver: Receiver = fake
        receiver.permissionResult(1, true)
        var plays = 0
        val launch = LaunchAutostart(receiver, journal, { 0L },
            requestFocus = { it(fm.heterodyne.app.audio.AudioFocusLease(true) {}) }, startPlayback = { plays++; it(true) })
        launch.open(true, TuneTarget(Band.FM, 90300))
        launch.tick()
        launch.tick()
        assertEquals(listOf(TuneTarget(Band.FM, 90300)), fake.selected)
        assertEquals(1, plays)
        launch.tick()
        assertEquals(1, plays)
    }

    @Test fun registeredSecondKindStillRequiresItsOwnDescriptorProfile() {
        val kind = ReceiverKind("test")
        val registry = ReceiverProfiles.Module.withProfile(ReceiverProfile(kind, 1, 2) { it.interfaces.isEmpty() })
        val device = UsbDescription("fake", 1, 2, emptyList())
        assertEquals(kind, UsbDiscovery.find(device, registry)?.kind)
        assertNull(UsbDiscovery.find(device.copy(productId = 3), registry))
        assertNull(UsbDiscovery.find(device.copy(interfaces = listOf(UsbInterfaceDescription(0, 0))), registry))
        assertNull(UsbDiscovery.find(device))
    }
}
