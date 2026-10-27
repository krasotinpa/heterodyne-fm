package fm.heterodyne.app.module

import fm.heterodyne.app.DiagnosticLog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RestartTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fake = FakeTransport()
    private var now = 0L
    private val controller by lazy { ModuleController(fake, DiagnosticLog(temporary.newFolder()), clock = { now }) }
    private fun connect() {
        controller.found(DeviceProfile("module"))
        controller.permissionResult(controller.requestPermission()!!, true)
    }
    private fun receive(name: String = "fm28") {
        fake.enqueueReceived(javaClass.getResourceAsStream("/module/$name.bin")!!.use { it.readBytes() })
        controller.receive()
    }
    @Test fun startupAndReturnNeverSelectWithoutAutostartDecision() {
        controller.tick()
        assertEquals(0, fake.sendCount)
        connect(); receive()
        assertEquals(0, fake.sendCount)
        controller.detach("module")
        now = 10000; controller.tick()
        assertEquals(0, fake.sendCount)
        connect()
        assertEquals(1, fake.sendCount) // Return probe, without select until a band reply.
        assertEquals(0x85, fake.sentFrames.single()[6].toInt() and 255)
        receive(); now = 20000; controller.tick(); receive()
        assertEquals(1, fake.sendCount)
        controller.error(19); now = 30000
        connect(); receive()
        assertEquals(2, fake.sendCount)
    }

    @Test fun noStationMeansOnlyReturnProbeWithoutSelect() {
        connect(); receive(); controller.detach("module"); connect(); receive()
        now = 60000; controller.tick()
        assertEquals(1, fake.sendCount)
    }
    @Test fun manualSelectionBeforeBandReplyDoesNotAddAutomaticSelection() {
        connect(); receive(); controller.detach("module"); connect()
        assertEquals(TuneResult.BandNotFM, controller.select(Band.FM, 87500))
        receive(); now = 60000; controller.tick()
        assertEquals(1, fake.sendCount)
    }
    @Test fun missingBandReplyAndRepeatedDetachNeverAddCommands() {
        connect(); controller.detach("module"); connect()
        now = 3000; controller.tick(); receive()
        assertEquals(1, fake.sendCount)
        controller.detach("module"); connect(); controller.detach("module")
        now = 60000; controller.tick()
        assertEquals(1, fake.sendCount)
    }
    @Test fun manualSelectionDuringPermissionDoesNotAddAutomaticSelection() {
        connect(); receive(); controller.detach("module")
        controller.found(DeviceProfile("module"))
        val permission = controller.requestPermission()!!
        assertEquals(TuneResult.NotConnected, controller.select(Band.FM, 87500))
        controller.permissionResult(permission, true)
        receive()
        assertEquals(1, fake.sendCount)
    }

    @Test fun returnWithoutSpontaneousRxOnlyProbesAndTimeoutNeverSelects() {
        connect(); controller.detach("module"); connect()
        assertEquals(1, fake.sendCount)
        assertEquals(0x85, fake.sentFrames.single()[6].toInt() and 255)
        now = 2999; controller.tick()
        assertEquals(1, fake.sendCount)
        now = 3000; controller.tick()
        now = 60000; controller.tick(); receive()
        assertEquals(1, fake.sendCount)
    }

    @Test fun fastReturnWaitsForProbeLimitWithoutSelecting() {
        connect()
        assertEquals(TuneResult.Sent, controller.checkConnection())
        now = 100; controller.detach("module"); connect()
        // This RX precedes the deferred return probe and must not trigger select.
        receive()
        now = 9999; controller.tick()
        assertEquals(1, fake.sendCount)
        now = 10000; controller.tick()
        assertEquals(2, fake.sendCount)
        assertEquals(0x85, fake.sentFrames.last()[6].toInt() and 255)
        now = 12999; receive()
        assertEquals(2, fake.sendCount)
        receive(); now = 30000; controller.tick()
        assertEquals(2, fake.sendCount)
    }

    @Test fun unrelatedReplyDoesNotExtendReturnBandDeadline() {
        connect(); controller.detach("module"); connect()
        now = 2000
        fake.enqueueReceived(byteArrayOf(0xfa.toByte(), 0x55, 0, 0, 2, 0x27, 0xd9.toByte()))
        controller.receive()
        now = 3000; receive()
        assertEquals(1, fake.sendCount)
    }

    @Test fun eightReturnsOnlySendEightProbes() {
        connect(); receive()
        repeat(8) {
            controller.detach("module")
            now += 10000
            connect(); receive()
            controller.tick()
        }
        assertEquals(List(8) { 0x85 }, fake.sentFrames.map { it[6].toInt() and 255 })
    }
}
