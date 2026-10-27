package fm.heterodyne.app.audio

import fm.heterodyne.app.module.Direction
import fm.heterodyne.app.stations.Band
import fm.heterodyne.app.stations.StationEntry
import org.junit.Assert.*
import org.junit.Test

class WheelControllerTest {

    @Test fun fmOnlyWheelSkipsAmAndLongPressDoesNotSeek() {
        val receiver = fm.heterodyne.app.receiver.FakeReceiver()
        val wheel = wheel(entries[1])
        wheel.update(entries, entries[1], receiver.capabilities)
        wheel.next()
        assertEquals(entries[0], wheel.cursor)
        timer.advance(1000)
        assertEquals(listOf(entries[0]), selected)
        wheel.longPress(Direction.Up)
        timer.advance(1000)
        assertTrue(seeks.isEmpty())
    }

    @Test fun capabilityChangeCancelsPendingUnsupportedStation() {
        val wheel = wheel(entries[1])
        wheel.next()
        wheel.update(entries, entries[1], fm.heterodyne.app.receiver.FakeReceiver().capabilities)
        timer.advance(1000)
        assertTrue(selected.isEmpty())
    }
    private val entries = listOf(StationEntry(Band.FM, 87500), StationEntry(Band.FM, 90300), StationEntry(Band.AM, 999))
    private val timer = WheelTimerFake()
    private val selected = mutableListOf<StationEntry>()
    private val seeks = mutableListOf<Direction>()
    private fun wheel(current: StationEntry? = entries[0]) = WheelController(timer, selected::add, seek = seeks::add).also {
        it.update(entries, current)
    }

    @Test fun cursorMovesImmediatelyAndOnlyLastTargetIsSentAfterQuietSecond() {
        val wheel = wheel()
        wheel.next()
        assertEquals(entries[1], wheel.cursor)
        timer.advance(900)
        wheel.next()
        assertEquals(entries[2], wheel.cursor)
        timer.advance(999)
        assertTrue(selected.isEmpty())
        timer.advance(1)
        assertEquals(listOf(entries[2]), selected)
    }
    @Test fun longPressCancelsSelectionAndSeeksOnceUntilRelease() {
        val wheel = wheel()
        wheel.next()
        timer.advance(800)
        wheel.longPress(Direction.Up)
        repeat(20) { wheel.longPress(Direction.Up); wheel.next() }
        timer.advance(2000)
        assertTrue(selected.isEmpty())
        assertEquals(listOf(Direction.Up), seeks)
        wheel.keyUp()
        wheel.longPress(Direction.Down)
        assertEquals(listOf(Direction.Up, Direction.Down), seeks)
    }

    @Test fun wrapsBothWaysAndStartsAtEdgesForUnknownStation() {
        val wheel = wheel(StationEntry(Band.FM, 100000))
        wheel.previous()
        assertEquals(entries.last(), wheel.cursor)
        wheel.next()
        assertEquals(entries.first(), wheel.cursor)
        wheel.previous()
        timer.advance(1000)
        assertEquals(listOf(entries.last()), selected)
        wheel.update(entries, StationEntry(Band.FM, 99000))
        wheel.next()
        assertEquals(entries.first(), wheel.cursor)
    }

    @Test fun repeatedKeyDownDoesNotMoveCursorButFirstLongFlagStillSeeks() {
        val wheel = wheel()
        wheel.keyDown(Direction.Down, false, 0)
        repeat(20) { wheel.keyDown(Direction.Down, false, 1) }
        assertEquals(entries.last(), wheel.cursor)
        wheel.keyDown(Direction.Down, true, 1)
        repeat(20) { wheel.keyDown(Direction.Down, true, 2) }
        wheel.keyUp()
        timer.advance(1000)
        assertTrue(selected.isEmpty())
        assertEquals(listOf(Direction.Down), seeks)
        wheel.keyDown(Direction.Up, false, 0)
        timer.advance(1000)
        assertEquals(listOf(entries[1]), selected)
    }

    @Test fun changedInputDiscardsEvenAnAlreadyDispatchedTimer() {
        var callback: () -> Unit = {}
        val wheel = WheelController(WheelScheduler { _, action -> callback = action; {} }, selected::add, seeks::add)
        wheel.update(entries, entries[0])
        wheel.next()
        wheel.update(emptyList(), null)
        callback()
        assertTrue(selected.isEmpty())
    }

    @Test fun emptySelectionIgnoresShortPressesButSeeksOnceOnFirstLongFlag() {
        val wheel = wheel()
        wheel.update(emptyList(), null)
        wheel.next()
        wheel.previous()
        wheel.keyDown(Direction.Down, false, 0)
        timer.advance(1000)
        assertTrue(selected.isEmpty())
        assertTrue(seeks.isEmpty())
        wheel.keyDown(Direction.Down, true, 1)
        repeat(20) { wheel.keyDown(Direction.Down, true, 2) }
        timer.advance(1000)
        assertTrue(selected.isEmpty())
        assertEquals(listOf(Direction.Down), seeks)
    }

    @Test fun emptySelectionAndCloseDiscardCommands() {
        val wheel = wheel()
        wheel.next()
        wheel.update(emptyList(), null)
        wheel.next()
        wheel.previous()
        timer.advance(1000)
        assertTrue(selected.isEmpty())
        wheel.update(entries, entries[0])
        wheel.next()
        wheel.close()
        timer.advance(1000)
        assertTrue(selected.isEmpty())
    }

}

private class WheelTimerFake : WheelScheduler {
    private var now = 0L
    private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
    override fun after(delayMs: Long, action: () -> Unit): () -> Unit {
        val task = now + delayMs to action
        tasks.add(task)
        return { tasks.remove(task) }
    }
    fun advance(ms: Long) {
        now += ms
        val due = tasks.filter { it.first <= now }
        tasks.removeAll(due.toSet())
        due.forEach { it.second() }
    }
}
