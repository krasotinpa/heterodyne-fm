package fm.heterodyne.app.ui

import fm.heterodyne.app.module.*
import fm.heterodyne.app.stations.StationEntry
import org.junit.Assert.*
import org.junit.Test

class DisabledReasonTest {
    private val entries = listOf(StationEntry(fm.heterodyne.app.stations.Band.FM, 87500, "Первая"))

    @Test fun unresponsiveHasHighestPriority() {
        assertEquals(DisabledReason.Unresponsive,
            disabledReason(ConnectionState.Disconnected, TransitionState.Unresponsive, null, emptyList()))
    }

    @Test fun disconnectedOutranksUnconfirmedAndEmptySelection() {
        assertEquals(DisabledReason.Disconnected,
            disabledReason(ConnectionState.Disconnected, TransitionState.Unconfirmed, Band.FM, emptyList()))
    }

    @Test fun permissionPendingOutranksStaleDisconnectedTransition() {
        assertEquals(DisabledReason.PermissionPending,
            disabledReason(ConnectionState.PermissionPending, TransitionState.Disconnected, null, emptyList()))
    }

    @Test fun connectingOutranksUnconfirmedAndEmptySelection() {
        listOf(ConnectionState.DeviceFound, ConnectionState.Open).forEach {
            assertEquals(DisabledReason.Connecting,
                disabledReason(it, TransitionState.Unconfirmed, null, emptyList()))
        }
    }

    @Test fun unconfirmedOutranksEmptySelection() {
        assertEquals(DisabledReason.Unconfirmed,
            disabledReason(ConnectionState.Observing, TransitionState.Unconfirmed, Band.FM, emptyList()))
    }

    @Test fun emptySelectionIsLowestPriority() {
        assertEquals(DisabledReason.EmptySelection,
            disabledReason(ConnectionState.Observing, TransitionState.Idle, Band.FM, emptyList()))
    }

    @Test fun missingConfirmedBandExplainsDisabledBandButton() {
        assertEquals(DisabledReason.Connecting,
            disabledReason(ConnectionState.Observing, TransitionState.Idle, null, entries))
    }

    @Test fun commandPauseIsNotADisabledReason() {
        listOf(TransitionState.Idle, TransitionState.Waiting, TransitionState.Seeking, TransitionState.CommandPause).forEach {
            assertNull(disabledReason(ConnectionState.Observing, it, Band.FM, entries))
        }
    }
}
