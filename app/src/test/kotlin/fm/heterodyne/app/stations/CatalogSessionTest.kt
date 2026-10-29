package fm.heterodyne.app.stations

import android.app.Application
import android.os.Handler
import android.os.Looper
import fm.heterodyne.app.StationsSession
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CatalogSessionTest {
    @Test fun failedDownloadKeepsBuiltinCatalogAndSelections() {
        val context = RuntimeEnvironment.getApplication()
        val session = StationsSession(context, Handler(Looper.getMainLooper()), Executor { it.run() },
            network = Executor { it.run() }, onlineCatalog = { CatalogReader { throw java.io.IOException("offline") } })
        session.load {}; shadowOf(Looper.getMainLooper()).idle()
        val before = session.screen
        session.allowCatalogUpdate(true)
        session.updateCatalog(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before.catalogs, session.screen.catalogs)
        assertEquals(before.state, session.screen.state)
        assertEquals("Не удалось обновить Справочник", session.screen.catalogUpdateResult)
        assertFalse(session.screen.catalogUpdateBusy)
    }

    @Test fun manualUpdateInstallsNewRevisionWithoutChangingSelectionsAndSkipsSameRevision() {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.filesDir, "catalog")
        val file = File(context.filesDir, "stations.json")
        try {
            val session = StationsSession(context, Handler(Looper.getMainLooper()), Executor { it.run() },
                network = Executor { it.run() }, onlineCatalog = { testSetReader() })
            session.load {}; shadowOf(Looper.getMainLooper()).idle()
            val before = session.screen.state
            session.allowCatalogUpdate(true)
            session.updateCatalog(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("test"), session.screen.catalogs.map { it.region.id })
            assertEquals(before, session.screen.state)
            assertEquals("Справочник обновлён: Ревизия 2026-10-30", session.screen.catalogUpdateResult)
            session.updateCatalog(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Нет обновлений", session.screen.catalogUpdateResult)
            val restarted = StationsSession(context, Handler(Looper.getMainLooper()), Executor { it.run() })
            restarted.load {}; shadowOf(Looper.getMainLooper()).idle()
            assertEquals(session.screen.catalogs, restarted.screen.catalogs)
        } finally { dir.deleteRecursively(); file.delete() }
    }

    @Test fun drivingAndBackgroundRejectNetworkAndCancelQueuedAndActiveUpdates() {
        val context = RuntimeEnvironment.getApplication()
        val queued = java.util.ArrayDeque<Runnable>()
        var opened = 0
        var closed = false
        lateinit var session: StationsSession
        session = StationsSession(context, Handler(Looper.getMainLooper()), Executor { it.run() },
            network = Executor { queued.add(it) }, onlineCatalog = {
                opened++
                val reader = testSetReader()
                object : CatalogReader {
                    override fun read(name: String): ByteArray {
                        val bytes = reader.read(name)
                        if (name == "index.json") session.allowCatalogUpdate(false)
                        return bytes
                    }
                    override fun close() { closed = true }
                }
            })
        session.load {}; shadowOf(Looper.getMainLooper()).idle()
        val before = session.screen.catalogs
        session.updateCatalog()
        assertTrue(queued.isEmpty())
        session.allowCatalogUpdate(true)
        session.updateCatalog()
        session.updateCatalog()
        assertEquals(1, queued.size)
        session.allowCatalogUpdate(false)
        queued.remove().run()
        assertEquals(0, opened)
        session.allowCatalogUpdate(true)
        session.updateCatalog()
        queued.remove().run()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(closed)
        assertEquals(1, opened)
        assertEquals(before, session.screen.catalogs)
        assertFalse(session.screen.catalogUpdateBusy)
        assertNull(session.screen.catalogUpdateResult)
        session.updateCatalog()
        assertTrue(queued.isEmpty())
    }

    @Test fun cancellationDoesNotWaitForDiskAndRejectsPreparedUpdateAfterReentry() {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.filesDir, "catalog")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val tasks = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val store = CatalogStore(dir, AssetsCatalogReader { context.assets.open("catalogs/$it") },
            writer = { file, bytes ->
                if (file.name == "test.json") {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                file.writeBytes(bytes)
            })
        val session = StationsSession(context, Handler(Looper.getMainLooper()), Executor { tasks.add(it) },
            network = Executor { it.run() }, onlineCatalog = { testSetReader() }, catalogStore = store)
        var worker: Thread? = null
        try {
            session.load {}; tasks.remove().run(); shadowOf(Looper.getMainLooper()).idle()
            val before = session.screen.catalogs
            session.allowCatalogUpdate(true)
            session.updateCatalog()
            tasks.remove().run() // busy
            worker = Thread(tasks.remove()).also { it.start() }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val cancel = java.util.concurrent.FutureTask { session.allowCatalogUpdate(false) }
            Thread(cancel).start()
            cancel.get(1, java.util.concurrent.TimeUnit.SECONDS)
            session.allowCatalogUpdate(true)
            release.countDown()
            worker.join(5000)
            assertFalse(worker.isAlive)
            while (tasks.isNotEmpty()) tasks.remove().run()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(before, session.screen.catalogs)
            assertEquals("2026-10-29", store.active().revision)
            assertFalse(session.screen.catalogUpdateBusy)
            assertNull(session.screen.catalogUpdateResult)
        } finally { release.countDown(); worker?.join(5000); dir.deleteRecursively() }
    }

    @Test fun removedRegionKeepsItsLastKnownNameAndSelections() {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.filesDir, "catalog")
        val file = File(context.filesDir, "stations.json")
        fun session() = StationsSession(context, Handler(Looper.getMainLooper()), Executor { it.run() })
        fun load(session: StationsSession) { session.load {}; shadowOf(Looper.getMainLooper()).idle() }
        try {
            file.delete()
            val original = session()
            load(original)
            val selections = original.screen.state!!.regions
            val store = CatalogStore(dir, AssetsCatalogReader { context.assets.open("catalogs/$it") })
            assertTrue(store.install(testSetReader()).isSuccess)
            val updated = session()
            load(updated)
            assertEquals(listOf("Москва", "Тест", "Ярославль"), updated.screen.regions.map { it.name })
            assertEquals(selections, updated.screen.state!!.regions)
            updated.selectRegion("moskva")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(selections.getValue("moskva").active.entries, updated.activeEntries)
            store.reset()
            val restored = session()
            load(restored)
            assertEquals(listOf("Москва", "Ярославль"), restored.screen.regions.map { it.name })
            assertEquals(selections, restored.screen.state!!.regions)
        } finally { dir.deleteRecursively(); file.delete() }
    }

    @Test fun onlyObsoleteRegionCanBeDeletedAndFailureKeepsItsSelections() {
        val context = RuntimeEnvironment.getApplication()
        val selections = RegionSelections.defaults().createSelection("Дорога").value!!
        var contents = StationsJson.encode(StationsState("moskva", mapOf("retired" to selections)))
        var fail = false
        val file = object : StationsFile {
            override fun read() = contents
            override fun writeAtomic(text: String) {
                if (fail) throw java.io.IOException("full")
                contents = text
            }
        }
        fun session() = StationsSession(context, Handler(Looper.getMainLooper()), Executor { it.run() }, file)
        fun idle() = shadowOf(Looper.getMainLooper()).idle()
        val session = session()
        session.load {}; idle()
        session.selectRegion("retired"); idle()
        fail = true
        session.deleteObsoleteRegion("retired"); idle()
        assertEquals(selections, session.screen.state!!.regions.getValue("retired"))
        assertNotNull(session.screen.error)
        fail = false
        session.deleteObsoleteRegion("moskva"); idle()
        assertEquals("retired", session.screen.state!!.selectedRegion)
        session.deleteObsoleteRegion("retired"); idle()
        assertEquals("moskva", session.screen.state!!.selectedRegion)
        assertFalse("retired" in session.screen.state!!.regions)
        val restarted = session()
        restarted.load {}; idle()
        assertFalse("retired" in restarted.screen.state!!.regions)
    }

    @Test fun obsoleteRegionCanBeSelectedAndKeepsSelectionsAfterRestart() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "stations.json")
        val selections = RegionSelections.defaults().let {
            it.replaceActive(it.active.add(StationEntry(90300, "Моя Станция")).value!!)
        }
        file.writeText(StationsJson.encode(StationsState("moskva", mapOf("retired" to selections))))
        fun session() = StationsSession(context, Handler(Looper.getMainLooper()), Executor { it.run() })
        fun load(session: StationsSession) { session.load {}; shadowOf(Looper.getMainLooper()).idle() }
        try {
            val first = session()
            load(first)
            first.selectRegion("retired")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("retired", first.screen.state!!.selectedRegion)
            assertEquals(selections.active.entries, first.activeEntries)
            val restarted = session()
            load(restarted)
            assertEquals("moskva", restarted.screen.state!!.selectedRegion)
            restarted.selectRegion("retired")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(selections.active.entries, restarted.activeEntries)
            assertEquals(selections, restarted.screen.state!!.regions.getValue("retired"))
        } finally { file.delete() }
    }

    @Test fun installedSetWithoutMoscowSupportsMissingAndSavedState() {
        val context = RuntimeEnvironment.getApplication()
        val reader = testSetReader()
        val dir = File(context.filesDir, "catalog")
        val file = File(context.filesDir, "stations.json")
        try {
            val store = CatalogStore(dir, AssetsCatalogReader { context.assets.open("catalogs/$it") })
            assertTrue(store.install(reader).isSuccess)
            for (saved in listOf(false, true)) {
                val initial = StationsState.defaults().copy(selectedRegion = "unknown")
                if (saved) file.writeText(StationsJson.encode(initial)) else file.delete()
                val session = StationsSession(context, Handler(Looper.getMainLooper()), Executor { it.run() })
                session.load {}
                shadowOf(Looper.getMainLooper()).idle()
                assertNull(session.screen.error)
                assertEquals(listOf("test"), session.screen.catalogs.map { it.region.id })
                assertEquals("test", session.screen.state!!.selectedRegion)
                assertEquals(initial.regions, session.screen.state!!.regions)
            }
        } finally {
            dir.deleteRecursively()
            file.delete()
        }
    }

    private fun testSetReader(): CatalogReader {
        val raw = javaClass.getResourceAsStream("/catalog-fixtures/valid/test.json")!!.use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        val index = JSONObject().put("schema", 1).put("revision", "2026-10-30")
            .put("regions", JSONArray().put(JSONObject().put("id", "test").put("name", "Тест")
                .put("file", "test.json").put("sha256", hash).put("revision", "2026-01-01")))
        return CatalogReader { if (it == "index.json") index.toString().toByteArray() else raw }
    }
}
