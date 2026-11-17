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
}
