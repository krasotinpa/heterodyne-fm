package fm.heterodyne.app.audio

import fm.heterodyne.app.module.Direction
import fm.heterodyne.app.stations.StationEntry
import org.junit.Assert.*
import org.junit.Test

class WheelControllerTest {

    @Test fun wheelVisitsAllStationsAndUnsupportedLongPressDoesNotSeek() {
        val receiver = fm.heterodyne.app.receiver.FakeReceiver()
        val wheel = wheel(entries[1])
        wheel.update(entries, entries[1], receiver.capabilities)
        wheel.next()
        assertEquals(entries[2], wheel.cursor)
        timer.advance(1000)
        assertEquals(listOf(entries[2]), selected)
        wheel.longPress(Direction.Up)
        timer.advance(1000)
        assertTrue(seeks.isEmpty())
    }

    @Test fun selectionChangeCancelsPendingStation() {
        val wheel = wheel(entries[1])
        wheel.previous()
        wheel.previous()
        assertEquals(entries[2], wheel.cursor)
        wheel.update(entries.take(2), entries[1], fm.heterodyne.app.receiver.FakeReceiver().capabilities)
        timer.advance(1000)
        assertEquals(listOf(entries[0]), selected)
    }
    private val entries = listOf(StationEntry(87500), StationEntry(90300), StationEntry(99900))
    private val timer = WheelTimerFake()
    private val selected = mutableListOf<StationEntry>()
    private val sentAt = mutableListOf<Long>()
    private val seeks = mutableListOf<Direction>()
    private fun wheel(current: StationEntry? = entries[0]) = WheelController(timer, { selected.add(it); sentAt.add(timer.now) }, seek = seeks::add, clock = { timer.now }).also {
        it.update(entries, current)
    }

    @Test fun singlePressAfterAtLeastOneSecondIsSentImmediately() {
        val wheel = wheel()
        wheel.next()
        assertEquals(listOf(entries[1]), selected)
        timer.advance(1000)
        wheel.next()
        assertEquals(listOf(entries[1], entries[2]), selected)
        timer.advance(1500)
        wheel.previous()
        assertEquals(listOf(entries[1], entries[2], entries[1]), selected)
        timer.advance(2000)
        assertEquals(listOf(0L, 1000L, 2500L), sentAt)
    }

    @Test fun threeQuickPressesSendFirstAndThenOnlyLastTargetAfterQuietSecond() {
        val wheel = wheel()
        wheel.next()
        assertEquals(entries[1], wheel.cursor)
        assertEquals(listOf(entries[1]), selected)
        timer.advance(100)
        wheel.next()
        assertEquals(entries[2], wheel.cursor)
        timer.advance(100)
        wheel.next()
        assertEquals(entries[0], wheel.cursor)
        timer.advance(799)
        assertEquals(listOf(entries[1]), selected)
        timer.advance(200)
        assertEquals(listOf(entries[1]), selected)
        timer.advance(1)
        assertEquals(listOf(entries[1], entries[0]), selected)
        assertEquals(listOf(0L, 1200L), sentAt)
    }

    @Test fun pressLessThanOneSecondAfterSendWaits() {
        val wheel = wheel()
        wheel.next()
        timer.advance(999)
        wheel.previous()
        assertEquals(listOf(entries[1]), selected)
        timer.advance(999)
        assertEquals(listOf(entries[1]), selected)
        timer.advance(1)
        assertEquals(listOf(entries[1], entries[0]), selected)
        assertEquals(listOf(0L, 1999L), sentAt)
    }

    @Test fun selectionsStayAtLeastOneSecondApartAcrossSeveralSequences() {
        val wheel = wheel()
        wheel.next()
        timer.advance(1000)
        wheel.previous()
        timer.advance(100)
        wheel.next()
        timer.advance(100)
        wheel.previous()
        timer.advance(1000)
        timer.advance(999)
        wheel.next()
        timer.advance(1000)
        wheel.previous()
        timer.advance(1000)
        wheel.next()
        timer.advance(2000)
        wheel.previous()
        timer.advance(2000)
        assertEquals(listOf(0L, 1000L, 2200L, 4199L, 5199L, 6199L, 7199L), sentAt)
        assertTrue(sentAt.zipWithNext().all { (first, second) -> second - first >= 1000 })
    }
    @Test fun longPressCancelsSelectionAndSeeksOnceUntilRelease() {
        val wheel = wheel()
        wheel.next()
        wheel.next()
        timer.advance(800)
        wheel.longPress(Direction.Up)
        repeat(20) { wheel.longPress(Direction.Up); wheel.next() }
        timer.advance(2000)
        assertEquals(listOf(entries[1]), selected)
        assertEquals(listOf(Direction.Up), seeks)
        wheel.keyUp()
        wheel.longPress(Direction.Down)
        assertEquals(listOf(Direction.Up, Direction.Down), seeks)
    }

    @Test fun wrapsBothWaysAndStartsAtEdgesForUnknownStation() {
        val wheel = wheel(StationEntry(100000))
        wheel.previous()
        assertEquals(entries.last(), wheel.cursor)
        wheel.next()
        assertEquals(entries.first(), wheel.cursor)
        wheel.previous()
        timer.advance(1000)
        assertEquals(listOf(entries.last(), entries.last()), selected)
        wheel.update(entries, StationEntry(99000))
        wheel.next()
        assertEquals(entries.first(), wheel.cursor)
    }

    @Test fun repeatedKeyDownDoesNotMoveCursorButFirstLongFlagStillSeeks() {
        val wheel = wheel()
        wheel.keyDown(Direction.Down, false, 0)
        repeat(20) { wheel.keyDown(Direction.Down, false, 1) }
        assertEquals(entries.last(), wheel.cursor)
        wheel.next()
        wheel.keyDown(Direction.Down, true, 1)
        repeat(20) { wheel.keyDown(Direction.Down, true, 2) }
        wheel.keyUp()
        timer.advance(1000)
        assertEquals(listOf(entries.last()), selected)
        assertEquals(listOf(Direction.Down), seeks)
        wheel.keyDown(Direction.Up, false, 0)
        timer.advance(1000)
        assertEquals(listOf(entries.last(), entries[1]), selected)
    }

    @Test fun changedInputDiscardsEvenAnAlreadyDispatchedTimer() {
        var callback: () -> Unit = {}
        val wheel = WheelController(WheelScheduler { _, action -> callback = action; {} }, selected::add, seeks::add, clock = { timer.now })
        wheel.update(entries, entries[0])
        wheel.next()
        wheel.next()
        wheel.update(emptyList(), null)
        callback()
        assertEquals(listOf(entries[1]), selected)
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
        wheel.next()
        wheel.update(emptyList(), null)
        wheel.next()
        wheel.previous()
        timer.advance(1000)
        assertEquals(listOf(entries[1]), selected)
        wheel.update(entries, entries[0])
        wheel.next()
        wheel.next()
        wheel.close()
        timer.advance(1000)
        assertEquals(listOf(entries[1], entries[1]), selected)
    }

}

private class WheelTimerFake : WheelScheduler {
    var now = 0L
        private set
    private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
    override fun after(delayMs: Long, action: () -> Unit): () -> Unit {
        val task = now + delayMs to action
        tasks.add(task)
        return { tasks.remove(task) }
    }
    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val task = tasks.filter { it.first <= end }.minByOrNull { it.first } ?: break
            tasks.remove(task)
            now = task.first
            task.second()
        }
        now = end
    }
}
