package fm.heterodyne.app.stations

import android.app.Application
import android.os.Handler
import android.os.Looper
import fm.heterodyne.app.StationsSession
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ParkingSessionTest {
    @Test fun invalidAndDuplicateNamesStayAtFieldAndSuccessfulSaveClearsThem() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "stations.json").delete()
        val queued = java.util.ArrayDeque<Runnable>()
        val session = StationsSession(context, Handler(Looper.getMainLooper()), java.util.concurrent.Executor { queued.add(it) })
        session.load {}
        drain(queued)
        session.createSelection("moskva", "Дорога")
        drain(queued)
        for (rename in listOf(false, true)) {
            for ((name, message) in listOf(" " to "Введите имя Подборки", "Основной" to "Подборка с таким именем уже есть")) {
                if (rename) session.renameSelection("moskva", "Дорога", name)
                else session.createSelection("moskva", name)
                drain(queued)
                assertEquals(message, session.screen.nameError)
                assertNull(session.screen.error)
                // Even a save of the unchanged state clears the field error.
                session.selectRegion("moskva")
                drain(queued)
                assertNull(session.screen.nameError)
            }
        }
        session.createSelection("moskva", "")
        drain(queued)
        session.createSelection("moskva", "Новая")
        drain(queued)
        assertNull(session.screen.nameError)
        session.renameSelection("moskva", "Дорога", "Основной")
        drain(queued)
        assertEquals("Подборка с таким именем уже есть", session.screen.nameError)
        session.renameSelection("moskva", "Дорога", "Поездка")
        drain(queued)
        assertNull(session.screen.nameError)
        session.createSelection("moskva", "")
        drain(queued)
        session.clearNameError()
        drain(queued)
        assertNull(session.screen.nameError)
        session.deleteSelection("moskva", "missing")
        drain(queued)
        assertEquals("Подборка или Станция не найдены", session.screen.error)
        assertNull(session.screen.nameError)
    }

    @Test fun failedStopWriteIsRetriedWhenRememberingStationAndSurvivesRestart() {
        val context = RuntimeEnvironment.getApplication()
        val handler = Handler(Looper.getMainLooper())
        val queued = java.util.ArrayDeque<Runnable>()
        val storage = java.util.concurrent.Executor { queued.add(it) }
        var contents: String? = null
        var failWrite = false
        val file = object : StationsFile {
            override fun read(): String? = contents
            override fun writeAtomic(text: String) {
                if (failWrite) throw java.io.IOException("temporary write failure")
                contents = text
            }
        }
        val session = StationsSession(context, handler, storage, file)
        session.load {}
        drain(queued)
        session.setLastPlayed(true)
        drain(queued)
        assertTrue(session.screen.state!!.lastPlayed)

        failWrite = true
        session.setLastPlayed(false)
        drain(queued)
        assertEquals("Не удалось сохранить состояние", session.screen.error)
        assertTrue(session.screen.state!!.lastPlayed)

        failWrite = false
        var remembered = false
        session.rememberStation(fm.heterodyne.app.module.Band.FM, 90300) { remembered = true }
        drain(queued)
        assertTrue(remembered)
        assertNull(session.screen.error)
        val restarted = StationsSession(context, handler, storage, file)
        restarted.load {}
        drain(queued)
        assertEquals(SessionStation(90300), restarted.screen.state!!.lastStation)
        assertFalse(restarted.screen.state!!.lastPlayed)
    }

    @Test fun lastPlayedSurvivesRestartAndOtherSessionChangesUntilExplicitStop() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "stations.json").delete()
        val handler = Handler(Looper.getMainLooper())
        val queued = java.util.ArrayDeque<Runnable>()
        var session = StationsSession(context, handler, java.util.concurrent.Executor { queued.add(it) })
        session.load {}
        drain(queued)
        session.setLastPlayed(true)
        assertFalse(session.screen.state!!.lastPlayed)
        drain(queued)
        fun restartAndCheck(expected: Boolean) {
            session = StationsSession(context, handler, java.util.concurrent.Executor { queued.add(it) })
            session.load {}
            drain(queued)
            assertEquals(expected, session.screen.state!!.lastPlayed)
        }
        restartAndCheck(true)
        session.rememberStation(fm.heterodyne.app.module.Band.FM, 90300) {}
        drain(queued)
        restartAndCheck(true)
        assertEquals(SessionStation(90300), session.screen.state!!.lastStation)
        session.selectRegion("yaroslavl")
        drain(queued)
        restartAndCheck(true)
        assertEquals("yaroslavl", session.screen.state!!.selectedRegion)
        session.createSelection("yaroslavl", "Поездка")
        drain(queued)
        restartAndCheck(true)
        session.activateSelection("Поездка")
        drain(queued)
        restartAndCheck(true)
        assertEquals("Поездка", session.screen.state!!.regions.getValue("yaroslavl").activeName)
        session.setLastPlayed(false)
        drain(queued)
        restartAndCheck(false)
    }

    @Test fun selectedRegionAndItsSelectionsSurviveSessionRestart() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "stations.json").delete()
        val handler = Handler(Looper.getMainLooper())
        val session = StationsSession(context, handler)
        load(session)
        session.selectRegion("yaroslavl")
        session.createSelection("yaroslavl", "Поездка")
        session.activateSelection("Поездка")
        session.addStation("yaroslavl", "Поездка", StationEntry(90300))
        // load is queued after all mutations on the same storage queue.
        load(session)
        val restarted = StationsSession(context, handler)
        load(restarted)
        val state = restarted.screen.state!!
        assertEquals("yaroslavl", state.selectedRegion)
        assertEquals("Поездка", state.regions.getValue(state.selectedRegion).activeName)
        assertEquals(listOf(StationEntry(90300)), state.regions.getValue(state.selectedRegion).active.entries)
        assertEquals("Ярославль", restarted.screen.catalogs.first { it.region.id == state.selectedRegion }.region.name)
    }

    @Test fun delayedEditFromOldRegionDoesNotChangeNewRegion() {
        val context = RuntimeEnvironment.getApplication()
        val entry = StationEntry(90300)
        val populated = RegionSelections.defaults().let { it.replaceActive(it.active.add(entry).value!!) }
        val initial = StationsState("moskva", mapOf("moskva" to populated, "yaroslavl" to populated))
        assertTrue(StationsRepository(AtomicStationsFile(File(context.filesDir, "stations.json"))).save(initial))
        val queued = java.util.ArrayDeque<Runnable>()
        val session = StationsSession(context, Handler(Looper.getMainLooper()), java.util.concurrent.Executor { queued.add(it) })
        session.load {}
        drain(queued)
        val oldRegionId = session.screen.state!!.selectedRegion
        assertEquals("moskva", oldRegionId)
        session.selectRegion("yaroslavl")
        session.removeStation(oldRegionId, "Основной", entry)
        // Both operations are pending while the UI still holds the Moscow snapshot.
        assertEquals("moskva", session.screen.state!!.selectedRegion)
        drain(queued)
        assertEquals(initial.copy(selectedRegion = "yaroslavl"), session.screen.state)
    }

    @Test fun delayedMoveDoesNotMoveAnotherStationAfterRemoval() {
        val context = RuntimeEnvironment.getApplication()
        val first = StationEntry(90300)
        val second = StationEntry(91000)
        val third = StationEntry(92000)
        val region = RegionSelections.defaults().let {
            it.replaceActive(it.active.add(first).value!!.add(second).value!!.add(third).value!!)
        }
        val initial = StationsState.defaults().copy(regions = StationsState.defaults().regions + ("moskva" to region))
        assertTrue(StationsRepository(AtomicStationsFile(File(context.filesDir, "stations.json"))).save(initial))
        val queued = java.util.ArrayDeque<Runnable>()
        val session = StationsSession(context, Handler(Looper.getMainLooper()), java.util.concurrent.Executor { queued.add(it) })
        session.load {}
        drain(queued)
        session.removeStation("moskva", "Основной", first)
        session.moveStation("moskva", "Основной", second, 0)
        drain(queued)
        assertEquals(listOf(second, third), session.screen.state!!.regions.getValue("moskva").active.entries)
    }

    private fun drain(queued: java.util.ArrayDeque<Runnable>) {
        while (queued.isNotEmpty()) queued.removeFirst().run()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun load(session: StationsSession) {
        val loaded = CountDownLatch(1)
        session.load { loaded.countDown() }
        assertTrue("Storage queue did not complete", loaded.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
    }
}
