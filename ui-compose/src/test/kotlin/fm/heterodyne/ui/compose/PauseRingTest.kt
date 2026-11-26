package fm.heterodyne.ui.compose

import fm.heterodyne.ui.contract.*

import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class PauseRingTest {
    @Test fun returningAfterThreeSecondsUsesPublicationTime() {
        val clock = TestTimeSource()
        val pause = CommandPauseView(PauseKind.Seek, Direction.Up, 5000, clock.markNow() + 5000.milliseconds)
        clock += 3000.milliseconds
        val returned = PauseRingClock(pause)
        assertEquals("Поиск ▶, пауза, осталось 2 с", returned.description())
        assertEquals(.4f, returned.fraction(), .0001f)
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

    @Test fun seekCountdownDecreasesLinearlyOnMonotonicClock() {
        for (duration in listOf(5000L)) {
            val clock = TestTimeSource()
            val ring = PauseRingClock(CommandPauseView(PauseKind.Seek, Direction.Up, duration, clock.markNow() + duration.milliseconds))
            assertEquals(1f, ring.fraction(), 0f)
            clock += (duration / 2).milliseconds
            assertEquals(.5f, ring.fraction(), .0001f)
            clock += (duration / 2).milliseconds
            assertEquals(0f, ring.fraction(), 0f)
            clock += 100.milliseconds
            assertEquals(0f, ring.fraction(), 0f)
        }
    }

    @Test fun publishedRemainderDoesNotRestartWholePause() {
        val clock = TestTimeSource()
        val ring = PauseRingClock(CommandPauseView(PauseKind.Seek, Direction.Down, 5000, clock.markNow() + 2000.milliseconds))
        assertEquals(.4f, ring.fraction(), .0001f)
        assertEquals("Поиск ◀, пауза, осталось 2 с", ring.description())
        clock += 1001.milliseconds
        assertEquals("Поиск ◀, пауза, осталось 1 с", ring.description())
    }

    @Test fun directionSelectsOnlyPressedSeekButtonAndStepHasNoRingOrPauseWord() {
        for (direction in Direction.entries) {
            val pause = pause(PauseKind.Seek, direction, 5000)
            val snapshot = tuning(TuningPhase.Seeking, pause = pause)
            assertTrue(showsPauseRing(snapshot, direction))
            assertFalse(showsPauseRing(snapshot, if (direction == Direction.Up) Direction.Down else Direction.Up))
            val ring = PauseRingClock(pause)
            assertEquals("Поиск ${if (direction == Direction.Up) "▶" else "◀"}, пауза, осталось 5 с", ring.description())
            assertFalse(showsPauseRing(snapshot.copy(pause = pause.copy(kind = PauseKind.Step)), direction))
        }
    }
}
