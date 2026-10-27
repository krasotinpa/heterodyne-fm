package fm.heterodyne.app

import fm.heterodyne.app.ui.*
import org.junit.Assert.*
import org.junit.Test

class EnvironmentProbeTest {
    @Test fun formatsSixSourcesAndReasons() {
        val values = ProbeValues(0x23, ProbeReading.Value(2 to 3),
            ProbeReading.Value(12.5f), true, ProbeReading.Unavailable("нет сервиса"),
            ProbeReading.Unavailable("нет разрешения"))
        assertEquals(listOf(
            "1. Ночной бит uiMode: ночь (0x20)",
            "2. UiModeManager: nightMode=2, currentModeType=3",
            "3. Датчик освещённости: есть, 13 лк",
            "4. Automotive: да",
            "5. CarPropertyManager / PERF_VEHICLE_SPEED: нет сервиса",
            "6. Скорость GNSS (GPS): нет разрешения",
        ), values.lines())
    }
    private class Source : EnvironmentProbeSource {
        var reads = 0
        var starts = 0
        var stops = 0
        var values = ProbeValues(0x10, ProbeReading.Unavailable("нет сервиса"),
            ProbeReading.Unavailable("нет датчика"), false,
            ProbeReading.Unavailable("нет класса"), ProbeReading.Unavailable("нет разрешения"))
        override fun start() { starts++ }
        override fun read(): ProbeValues { reads++; return values }
        override fun stop() { stops++ }
    }

    @Test fun disabledModeDoesNotPollOrWriteAndReenableStartsFresh() {
        val source = Source()
        val events = mutableListOf<String>()
        val poller = EnvironmentProbe(source, { 0L }, events::add)
        repeat(3) { poller.poll(false) }
        assertEquals(0, source.reads)
        assertEquals(0, source.starts)
        assertTrue(events.isEmpty())
        poller.poll(true)
        poller.poll(false)
        poller.poll(false)
        assertEquals(1, source.stops)
        assertEquals(1, source.reads)
        assertEquals(listOf("probe: " + source.values.lines().joinToString("; ")), events)
        poller.poll(true)
        assertEquals(2, events.size)
        assertEquals(2, source.starts)
    }

    @Test fun writesOnChangeAndAtThirtySecondsWithFakeClock() {
        val source = Source()
        val events = mutableListOf<String>()
        var now = 0L
        val poller = EnvironmentProbe(source, { now }, events::add)
        poller.poll(true)
        now = 29_999
        poller.poll(true)
        assertEquals(1, events.size)
        now = 30_000
        poller.poll(true)
        assertEquals(2, events.size)
        now = 31_000
        source.values = source.values.copy(uiMode = 0x20)
        assertEquals(source.values.lines(), poller.poll(true))
        assertEquals(3, events.size)
        now = 60_999
        poller.poll(true)
        assertEquals(3, events.size)
        now = 61_000
        poller.poll(true)
        assertEquals(4, events.size)
    }
    @Test fun permissionIsRequestedOnlyOnceWhileEnabled() {
        var requests = 0
        var saved = false
        val permission = ProbePermission(false, { saved = true }, { requests++ })
        permission.update(enabled = false, granted = false)
        assertEquals(0, requests)
        assertFalse(saved)
        permission.update(enabled = true, granted = false)
        assertEquals(1, requests)
        assertTrue(saved)
        permission.update(enabled = false, granted = false)
        permission.update(enabled = true, granted = false)
        assertEquals(1, requests)
        ProbePermission(saved, {}, { requests++ }).update(true, false)
        ProbePermission(false, {}, { requests++ }).update(true, true)
        assertEquals(1, requests)
    }
    @Test fun formatsPresentSpeedsAndUnavailableServicesWithoutEmptyValues() {
        val values = ProbeValues(0x13, ProbeReading.Unavailable("нет сервиса UiModeManager"),
            ProbeReading.Unavailable("нет датчика TYPE_LIGHT"), false,
            ProbeReading.Value(5.5f), ProbeReading.Value(0f))
        assertEquals(listOf(
            "1. Ночной бит uiMode: день (0x10)",
            "2. UiModeManager: нет сервиса UiModeManager",
            "3. Датчик освещённости: нет датчика TYPE_LIGHT",
            "4. Automotive: нет",
            "5. CarPropertyManager / PERF_VEHICLE_SPEED: 5.5 м/с",
            "6. Скорость GNSS (GPS): 0.0 м/с",
        ), values.lines())
        assertEquals("1. Ночной бит uiMode: не определён (0x0)", values.copy(uiMode = 3).lines().first())
    }

    @Test(expected = IllegalArgumentException::class)
    fun unavailableSourceRequiresReason() { ProbeReading.Unavailable(" ") }
}
