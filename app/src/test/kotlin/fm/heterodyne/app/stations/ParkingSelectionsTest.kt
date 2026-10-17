package fm.heterodyne.app.stations

import org.junit.Assert.*
import org.junit.Test

class ParkingSelectionsTest {
    @Test fun stationCanBeMovedAndRemovedWithoutChangingOriginal() {
        val first = StationEntry(Band.FM, 90300)
        val second = StationEntry(Band.FM, 91000)
        val original = StationSelection.empty("Основной").add(first).value!!.add(second).value!!
        val moved = original.move(0, 1).value!!
        assertEquals(listOf(second, first), moved.entries)
        assertEquals(listOf(first, second), original.entries)
        assertEquals(listOf(second), moved.remove(first).value!!.entries)
        assertEquals(ChangeError.NOT_FOUND, original.move(-1, 1).error)
    }
    @Test fun renameAndDeleteKeepAnActiveSelectionAndRejectInvalidNames() {
        val initial = RegionSelections.defaults().createSelection("Дорога").value!!.activate("Дорога").value!!
        assertEquals(ChangeError.INVALID_NAME, initial.rename("Дорога", " ").error)
        assertEquals(ChangeError.DUPLICATE_NAME, initial.rename("Дорога", "Основной").error)
        val renamed = initial.rename("Дорога", "Выходной").value!!
        assertEquals("Выходной", renamed.activeName)
        val remaining = renamed.delete("Выходной").value!!
        assertEquals("Основной", remaining.active.name)
        assertEquals(ChangeError.LAST_SELECTION, remaining.delete("Основной").error)
        assertEquals(ChangeError.NOT_FOUND, initial.delete("Нет").error)
    }

    @Test fun duplicateFrequencyIsRejectedRegardlessOfStationName() {
        val selection = StationSelection.empty("Подборка").add(StationEntry(Band.FM, 90300, "Первая")).value!!
        assertEquals(ChangeError.DUPLICATE_FREQUENCY, selection.add(StationEntry(Band.FM, 90300, "Другая")).error)
        assertEquals(1, selection.entries.size)
    }
}
