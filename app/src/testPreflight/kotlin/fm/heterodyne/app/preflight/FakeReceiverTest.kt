package fm.heterodyne.app.preflight

import fm.heterodyne.app.DiagnosticLog
import fm.heterodyne.app.module.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FakeReceiverTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun ordinaryControllerConfirmsOnlySyntheticReplies() {
        val transport = FakeModuleTransport()
        val receiver = ModuleController(transport, DiagnosticLog(temporary.newFolder()))
        receiver.found(DeviceProfile("preflight-fake-module"))
        receiver.permissionResult(receiver.requestPermission()!!, true)
        receiver.receive()
        assertEquals(TuneTarget(Band.FM, 90300), receiver.snapshot.confirmed)
        assertEquals(TuneResult.Sent, receiver.select(Band.FM, 97700))
        assertEquals(TuneTarget(Band.FM, 90300), receiver.snapshot.confirmed)
        receiver.receive()
        assertEquals(TuneTarget(Band.FM, 97700), receiver.snapshot.confirmed)
        receiver.close()
        assertFalse(transport.isOpen)
    }
    @Test fun lateFullReplyIsDiscardedWhenTheSyntheticSessionCloses() {
        val profile = DeviceProfile("preflight-fake-module")
        val transport = FakeModuleTransport()
        val receiver = ModuleController(transport, DiagnosticLog(temporary.newFolder()))
        receiver.found(profile)
        receiver.permissionResult(receiver.requestPermission()!!, true)
        receiver.receive()
        transport.holdSelection()
        assertEquals(TuneResult.Sent, receiver.select(Band.FM, 97700))
        receiver.detach(profile.deviceId)
        assertThrows(IllegalStateException::class.java) { transport.takeHeldReply() }
        receiver.found(profile)
        receiver.permissionResult(receiver.requestPermission()!!, true)
        receiver.receive()
        assertEquals(TuneTarget(Band.FM, 90300), receiver.snapshot.confirmed)
        assertNull(receiver.snapshot.target)
    }

    @Test fun oldReplyTailBeforeAnyFreshReplyCannotConfirmTheOldSelection() {
        val profile = DeviceProfile("preflight-fake-module")
        val fake = FakeModuleTransport()
        var orphan: ByteArray? = null
        val transport = object : UsbTransport by fake {
            override fun receive() = orphan?.also { orphan = null } ?: fake.receive()
        }
        val receiver = ModuleController(transport, DiagnosticLog(temporary.newFolder()))
        receiver.found(profile)
        receiver.permissionResult(receiver.requestPermission()!!, true)
        receiver.receive()
        fake.holdSelection(splitAt = 20)
        assertEquals(TuneResult.Sent, receiver.select(Band.FM, 97700))
        receiver.receive() // Old prefix is in the real ModuleController decoder.
        val tail = fake.takeHeldReply()
        receiver.detach(profile.deviceId)
        receiver.found(profile)
        receiver.permissionResult(receiver.requestPermission()!!, true)
        val revision = receiver.stationRevision
        orphan = tail
        receiver.receive()
        assertEquals(revision, receiver.stationRevision)
        assertNull(receiver.snapshot.confirmed)
        assertNull(receiver.snapshot.target)
        receiver.receive()
        assertTrue(receiver.stationRevision > revision)
        assertEquals(TuneTarget(Band.FM, 90300), receiver.snapshot.confirmed)
        receiver.close()
    }
}
