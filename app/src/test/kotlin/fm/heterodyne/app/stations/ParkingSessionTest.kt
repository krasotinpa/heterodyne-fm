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
    @Test fun selectedRegionAndItsSetsSurviveSessionRestart() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "stations.json").delete()
        val handler = Handler(Looper.getMainLooper())
        val session = StationsSession(context, handler)
        load(session)
        session.selectRegion("yaroslavl")
        session.createSet("yaroslavl", "Поездка")
        session.activateSet("Поездка")
        session.addStation("yaroslavl", "Поездка", StationEntry(Band.FM, 90300))
        // load is queued after all mutations on the same storage queue.
        load(session)
        val restarted = StationsSession(context, handler)
        load(restarted)
        val state = restarted.screen.state!!
        assertEquals("yaroslavl", state.selectedRegion)
        assertEquals("Поездка", state.regions.getValue(state.selectedRegion).activeName)
        assertEquals(listOf(StationEntry(Band.FM, 90300)), state.regions.getValue(state.selectedRegion).active.entries)
        assertEquals("Ярославль", restarted.screen.catalogs.first { it.region.id == state.selectedRegion }.region.name)
    }

    @Test fun delayedEditFromOldRegionDoesNotChangeNewRegion() {
        val context = RuntimeEnvironment.getApplication()
        val entry = StationEntry(Band.FM, 90300)
        val populated = RegionSets.defaults().let { it.replaceActive(it.active.add(entry).value!!) }
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
        val first = StationEntry(Band.FM, 90300)
        val second = StationEntry(Band.FM, 91000)
        val third = StationEntry(Band.FM, 92000)
        val region = RegionSets.defaults().let {
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
