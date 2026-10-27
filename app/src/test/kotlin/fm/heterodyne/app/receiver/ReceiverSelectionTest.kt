package fm.heterodyne.app.receiver

import fm.heterodyne.app.module.*
import org.junit.Assert.*
import org.junit.Test

class ReceiverSelectionTest {
    private val module = FakeReceiver(ReceiverKind.Module)
    private val other = FakeReceiver()
    private val kinds = listOf(module.kind, other.kind)

    @Test fun singleConnectedWinsAtStartupAndDetachNeverSwitches() {
        val saved = mutableListOf<ReceiverKind>()
        val selection = ReceiverSelection(kinds, ReceiverKind.Module) { saved.add(it) }
        selection.discover(listOf(DeviceProfile("other", other.kind)))
        assertEquals(other.kind, selection.selectedKind)
        assertEquals(listOf(other.kind), saved)
        selection.discover(listOf(DeviceProfile("module")))
        assertEquals(other.kind, selection.selectedKind)
    }

    @Test fun bothConnectedUseSavedChoiceAndSwitchOnlyWhenParked() {
        val selection = ReceiverSelection(kinds, other.kind) {}
        selection.discover(listOf(DeviceProfile("module"), DeviceProfile("other", other.kind)))
        assertEquals(other.kind, selection.selectedKind)
        assertFalse(selection.select(ReceiverKind.Module, parked = false))
        assertEquals(other.kind, selection.selectedKind)
        assertTrue(selection.select(ReceiverKind.Module, parked = true))
        assertEquals(ReceiverKind.Module, selection.selectedKind)
    }

    @Test fun bothWithoutSavedChoiceWaitForUser() {
        val selection = ReceiverSelection(kinds, null) {}
        selection.discover(listOf(DeviceProfile("module"), DeviceProfile("other", other.kind)))
        assertNull(selection.selectedKind)
    }
}
