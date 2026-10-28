package fm.heterodyne.app.ui

import fm.heterodyne.app.module.*
import fm.heterodyne.app.stations.StationEntry
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class DisabledReasonTest {

    @Test fun disabledTextAndIconsMeetContrastOnSurfaceInAllThemesAndPalettes() {
        for (palette in Palette.entries) for (theme in ResolvedTheme.entries) {
            fun luminance(token: String): Double {
                val channels = DesignTokens.color(palette, theme, token).removePrefix("#").chunked(2)
                    .map { it.toInt(16) / 255.0 }
                    .map { if (it <= .04045) it / 12.92 else ((it + .055) / 1.055).pow(2.4) }
                return channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722
            }
            val foreground = luminance("disabledText")
            val background = luminance("surface")
            val contrast = (maxOf(foreground, background) + .05) / (minOf(foreground, background) + .05)
            assertTrue("$palette/$theme disabled text: $contrast", contrast >= 4.5)
            assertTrue("$palette/$theme disabled icon: $contrast", contrast >= 3)
        }
    }

    private val entries = listOf(StationEntry(87500, "Первая"))

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
