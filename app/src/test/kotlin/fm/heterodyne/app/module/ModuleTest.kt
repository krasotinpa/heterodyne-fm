package fm.heterodyne.app.module

import fm.heterodyne.app.DiagnosticLog
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModuleTest {
    @get:Rule val temporary = TemporaryFolder()
    private val device = UsbDescription("module", 0x0483, 0x5740, listOf(
        UsbInterfaceDescription(0, 1), UsbInterfaceDescription(1, 1),
        UsbInterfaceDescription(2, 2, subclass = 2, protocol = 1, endpoints = listOf(UsbEndpointDescription(0x84, 3, 8))),
        UsbInterfaceDescription(3, 10, endpoints = listOf(UsbEndpointDescription(3, 2, 64), UsbEndpointDescription(0x83, 2, 64))),
    ))

    @Test fun discoveryRequiresIdentityAndVerifiedDescriptors() {
        assertNull(UsbDiscovery.find(device.copy(vendorId = 1)))
        assertNull(UsbDiscovery.find(device.copy(productId = 1)))
        assertNull(UsbDiscovery.find(device.copy(interfaces = device.interfaces.dropLast(1))))
        assertNull(UsbDiscovery.find(device.copy(interfaces = device.interfaces.map { if (it.number == 2) it.copy(deviceClass = 1) else it })))
        assertNull(UsbDiscovery.find(device.copy(interfaces = device.interfaces.map { if (it.number == 3) it.copy(endpoints = listOf(UsbEndpointDescription(0x83, 3, 64))) else it })))
        val profile = UsbDiscovery.find(device)!!
        assertEquals(2, profile.controlInterface)
        assertEquals(3, profile.dataInterface)
        assertEquals(3, profile.bulkOut)
        assertEquals(0x83, profile.bulkIn)
    }

    private fun withInterface(number: Int, change: (UsbInterfaceDescription) -> UsbInterfaceDescription) =
        device.copy(interfaces = device.interfaces.map { if (it.number == number) change(it) else it })

    @Test fun discoveryRejectsMissingControlInterruptEndpoint() {
        assertNull(UsbDiscovery.find(withInterface(2) { it.copy(endpoints = emptyList()) }))
    }

    @Test fun discoveryRejectsWrongControlSubclassAndProtocol() {
        assertNull(UsbDiscovery.find(withInterface(2) { it.copy(subclass = 0) }))
        assertNull(UsbDiscovery.find(withInterface(2) { it.copy(protocol = 0) }))
    }

    @Test fun discoveryRejectsWrongControlEndpointAddressTypeAndSize() {
        for (endpoint in listOf(
            UsbEndpointDescription(0x04, 3, 8),
            UsbEndpointDescription(0x84, 2, 8),
            UsbEndpointDescription(0x84, 3, 64),
        )) {
            assertNull(UsbDiscovery.find(withInterface(2) { it.copy(endpoints = listOf(endpoint)) }))
        }
    }

    @Test fun discoveryRejectsWrongBulkPacketSizeInEitherDirection() {
        for (address in listOf(0x03, 0x83)) {
            assertNull(UsbDiscovery.find(withInterface(3) { data ->
                data.copy(endpoints = data.endpoints.map { if (it.address == address) it.copy(maxPacketSize = 8) else it })
            }))
        }
    }

    @Test fun denialAllowsRetryWithoutOpeningOrSending() {
        val fake = FakeTransport()
        val controller = ModuleController(fake, DiagnosticLog(temporary.newFolder()))
        controller.found(UsbDiscovery.find(device)!!)
        val denied = controller.requestPermission()!!
        controller.permissionResult(denied, false)
        assertEquals(ConnectionState.Disconnected, controller.state)
        assertEquals(0, fake.openCount)
        assertEquals(0, fake.sendCount)
        val retry = controller.requestPermission()!!
        controller.permissionResult(denied, true)
        assertEquals(0, fake.openCount)
        controller.permissionResult(retry, true)
        assertEquals(ConnectionState.Observing, controller.state)
        assertEquals(1, fake.openCount)
        assertEquals(0, fake.sendCount)
    }

    @Test fun pendingDetachClearsQueueAndIgnoresStalePermissionAcrossTenReplugs() {
        val fake = FakeTransport()
        val states = mutableListOf<ConnectionState>()
        val controller = ModuleController(fake, DiagnosticLog(temporary.newFolder())) { states.add(it) }
        repeat(10) {
            controller.found(UsbDiscovery.find(device)!!)
            val request = controller.requestPermission()!!
            assertEquals(1, controller.pendingCount)
            controller.detach("other")
            assertEquals(1, controller.pendingCount)
            controller.detach("module")
            assertEquals(0, controller.pendingCount)
            assertEquals(ConnectionState.Disconnected, controller.state)
            controller.permissionResult(request, true)
            assertEquals(it, fake.openCount)
            controller.found(UsbDiscovery.find(device)!!)
            controller.permissionResult(controller.requestPermission()!!, true)
            controller.detach("module")
            assertFalse(fake.isOpen)
        }
        assertEquals(10, fake.openCount)
        assertEquals(20, fake.closeCalls)
        assertEquals(0, fake.sendCount)
        assertTrue(states.containsAll(ConnectionState.entries))
    }

    @Test fun closeIsIdempotentAndClearsPendingReceive() {
        val fake = FakeTransport()
        fake.open(UsbDiscovery.find(device)!!)
        fake.enqueueReceived(byteArrayOf(1))
        fake.close()
        fake.close()
        assertEquals(1, fake.closeCount)
        assertEquals(0, fake.pendingCount)
        assertFalse(fake.isOpen)
    }

    @Test fun failedOpenReleasesResourcesAndAllowsRetry() {
        val fake = FakeTransport()
        var fail = true
        val transport = object : UsbTransport by fake {
            override fun open(profile: DeviceProfile) {
                fake.open(profile)
                if (fail) error("synthetic open failure")
            }
        }
        val controller = ModuleController(transport, DiagnosticLog(temporary.newFolder()))
        controller.found(UsbDiscovery.find(device)!!)
        controller.permissionResult(controller.requestPermission()!!, true)
        assertEquals(ConnectionState.Disconnected, controller.state)
        assertFalse(fake.isOpen)
        assertEquals(0, controller.pendingCount)
        fail = false
        controller.permissionResult(controller.requestPermission()!!, true)
        assertEquals(ConnectionState.Observing, controller.state)
        fake.enqueueReceived(byteArrayOf(1))
        controller.detach("module")
        assertEquals(0, fake.pendingCount)
        assertFalse(fake.isOpen)
        assertEquals(0, fake.sendCount)
    }

    @Test fun journalFailureDuringOpenDetachStillReleasesConnectionAndProfile() {
        assertDetachWithBrokenJournal(open = true, closeThrows = false)
    }

    @Test fun journalFailureDuringPendingDetachStillClearsPermissionAndProfile() {
        assertDetachWithBrokenJournal(open = false, closeThrows = false)
    }

    @Test fun journalAndTransportFailuresDuringDetachStillClearStateAndProfile() {
        assertDetachWithBrokenJournal(open = true, closeThrows = true)
        assertDetachWithBrokenJournal(open = false, closeThrows = true)
    }

    private fun assertDetachWithBrokenJournal(open: Boolean, closeThrows: Boolean) {
        val dir = temporary.newFolder()
        val fake = FakeTransport()
        val transport = object : UsbTransport by fake {
            override fun close() {
                fake.close()
                if (closeThrows) throw java.io.IOException("synthetic close failure")
            }
        }
        val log = DiagnosticLog(dir)
        val controller = ModuleController(transport, log)
        controller.found(UsbDiscovery.find(device)!!)
        val request = controller.requestPermission()!!
        if (open) {
            controller.permissionResult(request, true)
            assertTrue(fake.isOpen)
            fake.enqueueReceived(byteArrayOf(1))
        } else {
            assertEquals(1, controller.pendingCount)
        }
        // Replace the journal file with a directory to force real event IO failures.
        val journal = File(dir, "diagnostic.log")
        assertTrue(journal.delete())
        assertTrue(journal.mkdir())
        assertThrows(java.io.IOException::class.java) { log.event("probe") }

        controller.detach("module")

        assertEquals(1, fake.closeCalls)
        assertFalse(fake.isOpen)
        assertEquals(0, fake.pendingCount)
        assertEquals(0, controller.pendingCount)
        assertEquals(ConnectionState.Disconnected, controller.state)
        assertNull(controller.deviceId)
        assertNull(controller.requestPermission())
        controller.permissionResult(request, true)
        assertEquals(if (open) 1 else 0, fake.openCount)
        assertEquals(0, fake.sendCount)
    }

    @Test fun lifecycleAndErrorsReachJournalWithoutRawSerial() {
        val dir = temporary.newFolder()
        val fake = FakeTransport()
        val log = DiagnosticLog(dir)
        val controller = ModuleController(fake, log)
        controller.found(UsbDiscovery.find(device)!!)
        controller.permissionResult(controller.requestPermission()!!, false)
        controller.permissionResult(controller.requestPermission()!!, true)
        log.moduleSerial("secret-serial")
        controller.error()
        controller.found(UsbDiscovery.find(device)!!)
        controller.detach("module")
        val text = File(dir, "diagnostic.log").readText()
        listOf("found", "permission requested", "permission granted", "permission denied", "open", "close", "detach", "error").forEach {
            assertTrue(it, text.contains("EVENT USB $it"))
        }
        assertFalse(text.contains("secret-serial"))
        assertEquals(0, fake.sendCount)
    }
}
