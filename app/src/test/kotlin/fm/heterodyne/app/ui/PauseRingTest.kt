package fm.heterodyne.app.ui

import fm.heterodyne.app.module.Direction
import fm.heterodyne.app.module.TransitionSnapshot
import fm.heterodyne.app.module.TransitionState
import fm.heterodyne.app.receiver.CommandPause
import fm.heterodyne.app.receiver.PauseKind
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class PauseRingTest {
    @Test fun returningAfterTenSecondsUsesPublicationTime() {
        val pause = CommandPause(PauseKind.Seek, Direction.Up, 15000, 15000)
        var now = 0L
        PauseRingClock(pause) { now }
        now = 10000
        val returned = PauseRingClock(pause) { now }
        assertEquals("Поиск ▶, пауза, осталось 5 с", returned.description())
        assertEquals(1f / 3f, returned.fraction(), .0001f)
    }

    @Test fun greyControlsAndAccentRingMeetContrastInAllThemesAndPalettes() {
        for (palette in Palette.entries) for (theme in ResolvedTheme.entries) {
            fun luminance(token: String): Double {
                val channels = DesignTokens.color(palette, theme, token).removePrefix("#").chunked(2)
                    .map { it.toInt(16) / 255.0 }.map { if (it <= .04045) it / 12.92 else ((it + .055) / 1.055).pow(2.4) }
                return channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722
            }
            fun contrast(token: String): Double {
                val a = luminance(token); val b = luminance("surface2")
                return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
            }
            assertTrue("$palette/$theme grey text", contrast("disabledText") >= 4.5)
            assertTrue("$palette/$theme ring", contrast("accent") >= 3)
        }
    }

    @Test fun seekCountdownDecreasesLinearlyOnMonotonicClockInBothBands() {
        for (duration in listOf(5000L, 15000L)) {
            var now = 100L
            val ring = PauseRingClock(CommandPause(PauseKind.Seek, Direction.Up, duration, duration, now), { now })
            assertEquals(1f, ring.fraction(), 0f)
            now += duration / 2
            assertEquals(.5f, ring.fraction(), .0001f)
            now += duration / 2
            assertEquals(0f, ring.fraction(), 0f)
            now += 100
            assertEquals(0f, ring.fraction(), 0f)
        }
    }

    @Test fun publishedRemainderDoesNotRestartWholePause() {
        var now = 100L
        val ring = PauseRingClock(CommandPause(PauseKind.Seek, Direction.Down, 5000, 2000, now), { now })
        assertEquals(.4f, ring.fraction(), .0001f)
        assertEquals("Поиск ◀, пауза, осталось 2 с", ring.description())
        now = 1101
        assertEquals("Поиск ◀, пауза, осталось 1 с", ring.description())
    }

    @Test fun directionSelectsOnlyPressedSeekButtonAndStepHasNoRingOrPauseWord() {
        for (direction in Direction.entries) {
            val pause = CommandPause(PauseKind.Seek, direction, 5000, 5000)
            val snapshot = TransitionSnapshot(TransitionState.Seeking, null, null, pause)
            assertTrue(showsPauseRing(snapshot, direction))
            assertFalse(showsPauseRing(snapshot, if (direction == Direction.Up) Direction.Down else Direction.Up))
            val ring = PauseRingClock(pause) { 0 }
            assertEquals("Поиск ${if (direction == Direction.Up) "▶" else "◀"}, пауза, осталось 5 с", ring.description())
            assertFalse(showsPauseRing(snapshot.copy(pause = pause.copy(kind = PauseKind.Step)), direction))
        }
    }
}
