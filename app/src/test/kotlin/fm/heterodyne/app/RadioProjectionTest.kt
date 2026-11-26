package fm.heterodyne.app

import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.ConnectionState
import fm.heterodyne.app.module.TransitionState
import fm.heterodyne.app.stations.Catalog
import fm.heterodyne.app.stations.CatalogSource
import fm.heterodyne.app.stations.Region
import fm.heterodyne.ui.contract.DisabledReason
import org.junit.Assert.*
import org.junit.Test

class RadioProjectionTest {
    @Test fun regionNamesUseRussianOrderBeforeTheyReachTheScreen() {
        val catalogs = listOf("Жуков", "ёлка", "Ель", "Ёлка", "ель").mapIndexed { i, name ->
            Catalog(1, "test", Region("r$i", name), CatalogSource("test", "https://example.org"), emptyList())
        }
        assertEquals(listOf("Ель", "ель", "ёлка", "Ёлка", "Жуков"),
            StationsScreenState(catalogs = catalogs).view().regions.map { it.name })
    }

    @Test fun receiverProblemsOutrankAnEmptySelection() {
        fun reason(connection: ConnectionState, phase: TransitionState, band: Band? = Band.FM) =
            disabledReason(connection, phase, band, emptySelection = true)
        assertEquals(DisabledReason.Unresponsive, reason(ConnectionState.Disconnected, TransitionState.Unresponsive))
        assertEquals(DisabledReason.Disconnected, reason(ConnectionState.Disconnected, TransitionState.Unconfirmed))
        assertEquals(DisabledReason.PermissionPending, reason(ConnectionState.PermissionPending, TransitionState.Disconnected))
        for (connection in listOf(ConnectionState.DeviceFound, ConnectionState.Open)) {
            assertEquals(DisabledReason.Connecting, reason(connection, TransitionState.Unconfirmed))
        }
        assertEquals(DisabledReason.Unconfirmed, reason(ConnectionState.Observing, TransitionState.Unconfirmed))
        assertEquals(DisabledReason.Connecting, reason(ConnectionState.Observing, TransitionState.Idle, null))
        assertEquals(DisabledReason.EmptySelection, reason(ConnectionState.Observing, TransitionState.Idle))
        for (phase in listOf(TransitionState.Seeking, TransitionState.SeekIncomplete)) {
            assertNull(reason(ConnectionState.Observing, phase))
        }
        assertNull(disabledReason(ConnectionState.Observing, TransitionState.CommandPause, Band.FM, emptySelection = false))
    }
}
