package fm.heterodyne.app.stations

import org.junit.Assert.*
import org.junit.Test

class ParkingSetsTest {
    @Test fun stationCanBeMovedAndRemovedWithoutChangingOriginal() {
        val first = StationEntry(Band.FM, 90300)
        val second = StationEntry(Band.FM, 91000)
        val original = StationSet.empty("Основной").add(first).value!!.add(second).value!!
        val moved = original.move(0, 1).value!!
        assertEquals(listOf(second, first), moved.entries)
        assertEquals(listOf(first, second), original.entries)
        assertEquals(listOf(second), moved.remove(first).value!!.entries)
        assertEquals(ChangeError.NOT_FOUND, original.move(-1, 1).error)
    }
    @Test fun renameAndDeleteKeepAnActiveSetAndRejectInvalidNames() {
        val initial = RegionSets.defaults().createSet("Дорога").value!!.activate("Дорога").value!!
        assertEquals(ChangeError.INVALID_NAME, initial.rename("Дорога", " ").error)
        assertEquals(ChangeError.DUPLICATE_NAME, initial.rename("Дорога", "Основной").error)
        val renamed = initial.rename("Дорога", "Выходной").value!!
        assertEquals("Выходной", renamed.activeName)
        val remaining = renamed.delete("Выходной").value!!
        assertEquals("Основной", remaining.active.name)
        assertEquals(ChangeError.LAST_SET, remaining.delete("Основной").error)
        assertEquals(ChangeError.NOT_FOUND, initial.delete("Нет").error)
    }

    @Test fun duplicateFrequencyIsRejectedRegardlessOfStationName() {
        val set = StationSet.empty("Набор").add(StationEntry(Band.FM, 90300, "Первая")).value!!
        assertEquals(ChangeError.DUPLICATE_FREQUENCY, set.add(StationEntry(Band.FM, 90300, "Другая")).error)
        assertEquals(1, set.entries.size)
    }
}
