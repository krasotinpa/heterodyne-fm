package fm.heterodyne.app.ui

import org.junit.Assert.*
import org.junit.Test

class DrivingStateTest {
    @Test fun `driving starts after three continuous seconds at two metres per second`() {
        var state = DrivingState()
        assertFalse(state.driving)
        state = state.update(2f, 100)
        state = state.update(2f, 3099)
        assertFalse(state.driving)
        assertTrue(state.update(2f, 3100).driving)
    }

    private fun moving() = DrivingState().update(2f, 0).update(2f, 3000)

    @Test fun `parking needs five continuous seconds below half a metre per second`() {
        var state = moving().update(0.49f, 4000)
        state = state.update(0f, 8999)
        assertTrue(state.driving)
        assertFalse(state.update(0.49f, 9000).driving)
    }

    @Test fun `middle range including half a metre preserves either state`() {
        for (speed in listOf(0.5f, 1f, 1.999f)) {
            assertTrue(moving().update(speed, 100000).driving)
            assertFalse(DrivingState().update(speed, 100000).driving)
        }
    }

    @Test fun `interruption resets the fast timer`() {
        for (speed in listOf<Float?>(0f, 0.5f, 1.99f, null)) {
            var state = DrivingState().update(2f, 0).update(speed, 2999)
            state = state.update(2f, 3000).update(2f, 5999)
            assertFalse(state.driving)
            assertTrue(state.update(2f, 6000).driving)
        }
    }

    @Test fun `interruption resets the slow timer`() {
        for (speed in listOf(0.5f, 1f, 2f)) {
            var state = moving().update(0f, 4000).update(speed, 8999)
            state = state.update(0f, 9000).update(0f, 13999)
            assertTrue(state.driving)
            assertFalse(state.update(0f, 14000).driving)
        }
    }

    @Test fun `missing or invalid speed parks immediately and resets timers`() {
        for (speed in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            val state = moving().update(speed, 3001)
            assertFalse(state.driving)
            assertFalse(state.update(2f, 3002).driving)
        }
    }

    @Test fun `polling advances timers and records transitions without developer mode`() {
        var now = 0L
        var speed: Float? = 2f
        var listening = false
        val source = object : DrivingSpeedSource {
            override fun start() { listening = true }
            override fun read() = speed
            override fun stop() { listening = false }
        }
        val updates = mutableListOf<Boolean>()
        val events = mutableListOf<String>()
        val monitor = DrivingMonitor(source, { now }, updates::add, events::add)
        monitor.start()
        assertTrue(listening)
        now = 3000
        monitor.poll()
        monitor.poll()
        assertEquals(listOf(true), updates)
        assertTrue(events.single().contains("driving=true, speed=2.0 m/s, reason="))
        speed = null
        monitor.poll()
        assertEquals(listOf(true, false), updates)
        assertTrue(events.last().contains("reason=no GNSS data"))
        speed = 2f
        monitor.poll()
        now = 6000
        monitor.poll()
        monitor.stop()
        assertFalse(listening)
        assertEquals(listOf(true, false, true, false), updates)
        monitor.start()
        assertEquals(4, updates.size)
        monitor.stop()
    }

    @Test fun `polling parks after five seconds without a new speed sample and stops reading in background`() {
        var now = 0L
        var speed: Float? = 2f
        var reads = 0
        val source = object : DrivingSpeedSource {
            override fun start() = Unit
            override fun read(): Float? { reads++; return speed }
            override fun stop() = Unit
        }
        val updates = mutableListOf<Boolean>()
        val monitor = DrivingMonitor(source, { now }, updates::add, {})
        monitor.start()
        now = 3000
        monitor.poll()
        speed = 0f
        now = 4000
        monitor.poll()
        now = 8999
        monitor.poll()
        assertEquals(listOf(true), updates)
        now = 9000
        monitor.poll()
        assertEquals(listOf(true, false), updates)
        monitor.stop()
        val foregroundReads = reads
        monitor.poll()
        assertEquals(foregroundReads, reads)
    }
}
