package fm.heterodyne.app.ui

import fm.heterodyne.app.stations.*
import fm.heterodyne.app.module.Band as ModuleBand
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BandScaleLabelsTest {
    @Test fun moscowLabelsKeepPriorityAndDoNotOverlap() {
        val stations = Catalog.parse(File("src/main/assets/catalogs/moskva.json").readText()).stations +
            CatalogStation("synthetic", "Тестовая", Band.FM, 108000)
        assertEquals(53, stations.size)
        val labels = chooseLabels(stations, ModuleBand.FM, 1100f, 9f, 8f, listOf(0, 20))
        assertEquals(21, labels.size)
        assertTrue(labels.any { it.index == 0 })
        assertTrue(labels.any { it.index == 20 })
        labels.groupBy { it.row }.values.forEach { row ->
            row.sortedBy { it.left }.zipWithNext().forEach { (a, b) ->
                assertTrue("${a.index} overlaps ${b.index}", a.right + 8f <= b.left)
            }
        }
        assertTrue(labels.all { it.left >= 0 && it.right <= 1100 })
        println("Москва: ${labels.size}/53, 1100 px, наложений 0")
    }

    @Test fun densePriorityLabelsUseSeparateRowsAndNarrowScalesStaySafe() {
        val stations = listOf(
            CatalogStation("a", "Первая", Band.FM, 90300),
            CatalogStation("b", "Ближайшая", Band.FM, 90400),
            CatalogStation("am", "AM", Band.AM, 855),
        )
        val labels = chooseLabels(stations, ModuleBand.FM, 1100f, 9f, 8f, listOf(0, 1))
        assertEquals(setOf(0, 1), labels.map { it.index }.toSet())
        assertEquals(2, labels.map { it.row }.distinct().size)
        assertTrue(chooseLabels(stations, ModuleBand.FM, 10f, 9f, 8f).isEmpty())
        assertEquals("Очень длинн…", scaleLabelText("Очень длинное имя"))
    }

    @Test fun frequencyPositionUsesFullDisplayBand() {
        assertEquals(0f, bandFraction(ModuleBand.FM, 87500), .0001f)
        assertEquals(.5f, bandFraction(ModuleBand.FM, 97750), .0001f)
        assertEquals(1f, bandFraction(ModuleBand.FM, 108000), .0001f)
        assertEquals(0f, bandFraction(ModuleBand.AM, 522), .0001f)
        assertTrue(bandFraction(ModuleBand.AM, 531) > bandFraction(ModuleBand.AM, 522))
        assertTrue(bandFraction(ModuleBand.AM, 1611) < bandFraction(ModuleBand.AM, 1620))
        assertEquals(1f, bandFraction(ModuleBand.AM, 1620), .0001f)
    }
}
