package fm.heterodyne.app.stations

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class CatalogSetTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture() = File("src/test/resources/catalog-fixtures/valid/test.json").readBytes()
    private fun bundle(raw: ByteArray = fixture(), revision: String = "2026-10-29"): File {
        val dir = temp.newFolder()
        File(dir, "test.json").writeBytes(raw)
        val row = JSONObject().put("id", "test").put("name", "Тест").put("file", "test.json")
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) })
            .put("revision", "2026-01-01")
        File(dir, "index.json").writeText(JSONObject().put("schema", 1).put("revision", revision)
            .put("regions", JSONArray().put(row)).toString())
        return dir
    }
    @Test fun acceptsValidFixture() {
        val set = loadCatalogSet(DirectoryCatalogReader(bundle()))
        assertEquals("2026-10-29", set.revision)
        assertEquals(listOf(87500, 108000), set.catalogs.single().stations.map { it.frequencyKhz })
    }
    @Test fun rejectsEveryInvalidFixture() {
        val cases = File("src/test/resources/catalog-fixtures/invalid").listFiles()!!.sortedBy { it.name }
        val reasons = mapOf(
            "band-not-fm" to "Only FM", "blank-station-name" to "Invalid name",
            "crlf" to "Expected LF", "duplicate-station-id" to "Invalid or duplicate station id",
            "frequency-above-fm" to "Invalid FM frequency", "frequency-below-fm" to "Invalid FM frequency",
            "frequency-not-integer" to "Expected integer", "frequency-off-step" to "Invalid FM frequency",
            "no-source" to "source", "region-id-uppercase" to "Region id mismatch",
            "station-id-wrong-prefix" to "Invalid or duplicate station id", "unknown-schema" to "Unknown region schema",
        )
        assertEquals(reasons.keys + "bad-revision", cases.map { it.name }.toSet())
        for (case in cases) {
            val reader = DirectoryCatalogReader(bundle(File(case, "test.json").readBytes()))
            val failure = runCatching { loadCatalogSet(reader) }.exceptionOrNull()
            if (case.name == "bad-revision") {
                assertTrue("${case.name}: $failure", failure is java.time.format.DateTimeParseException)
            } else {
                assertTrue("${case.name}: $failure", failure?.message?.contains(reasons.getValue(case.name)) == true)
            }
        }
    }
    @Test fun rejectsCorruptionAndDuplicateRegions() {
        val dir = bundle()
        File(dir, "test.json").appendText(" ")
        assertTrue(runCatching { loadCatalogSet(DirectoryCatalogReader(dir)) }.isFailure)
        val duplicate = bundle()
        val index = JSONObject(File(duplicate, "index.json").readText())
        index.getJSONArray("regions").put(index.getJSONArray("regions").getJSONObject(0))
        File(duplicate, "index.json").writeText(index.toString())
        assertTrue(runCatching { loadCatalogSet(DirectoryCatalogReader(duplicate)) }.isFailure)
    }
    @Test fun rejectsSizeLimits() {
        for (file in listOf("index.json", "test.json")) {
            val dir = bundle()
            File(dir, file).writeBytes(ByteArray(if (file == "index.json") 32769 else 65537) { 32 })
            assertTrue(file, runCatching { loadCatalogSet(DirectoryCatalogReader(dir)) }.isFailure)
        }
    }
    @Test fun builtinAssetsPass() {
        val set = loadCatalogSet(DirectoryCatalogReader(File("src/main/assets/catalogs")))
        assertEquals("2026-10-29", set.revision)
        assertEquals(setOf("moskva", "yaroslavl"), set.catalogs.map { it.region.id }.toSet())
    }
    @Test fun installationSurvivesRestartAndReset() {
        val builtin = DirectoryCatalogReader(bundle(revision = "2026-01-01"))
        val dir = temp.newFolder()
        val store = CatalogStore(dir, builtin)
        val installed = store.install(DirectoryCatalogReader(bundle()))
        assertTrue(installed.toString(), installed.isSuccess)
        assertEquals("2026-10-29", CatalogStore(dir, builtin).active().revision)
        CatalogStore(dir, builtin).reset()
        assertEquals("2026-01-01", CatalogStore(dir, builtin).active().revision)
    }
    @Test fun equalAndOlderRevisionsReturnReasonAndKeepActive() {
        val builtin = DirectoryCatalogReader(bundle())
        val dir = temp.newFolder()
        for (revision in listOf("2026-10-29", "2026-10-28")) {
            val result = CatalogStore(dir, builtin).install(DirectoryCatalogReader(bundle(revision = revision)))
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("newer"))
            assertEquals("2026-10-29", CatalogStore(dir, builtin).active().revision)
        }
    }
    @Test fun failedWritesKeepPreviousSetAfterRestart() {
        val builtin = DirectoryCatalogReader(bundle(revision = "2026-01-01"))
        val dir = temp.newFolder()
        assertTrue(CatalogStore(dir, builtin).install(DirectoryCatalogReader(bundle())).isSuccess)
        for (failAt in 1..3) {
            var writes = 0
            val store = CatalogStore(dir, builtin) { file, bytes ->
                file.writeBytes(bytes.copyOf(bytes.size / 2))
                if (++writes == failAt) throw java.io.IOException("injected write failure")
                file.writeBytes(bytes)
            }
            val result = store.install(DirectoryCatalogReader(bundle(revision = "2026-10-30")))
            assertTrue("write $failAt", result.isFailure)
            assertEquals("2026-10-29", store.active().revision)
            assertEquals("2026-10-29", CatalogStore(dir, builtin).active().revision)
        }
    }
    @Test fun corruptInstalledSetFallsBackAndOrphansAreRemoved() {
        val builtin = DirectoryCatalogReader(bundle(revision = "2026-01-01"))
        val dir = temp.newFolder()
        val store = CatalogStore(dir, builtin)
        assertTrue(store.install(DirectoryCatalogReader(bundle())).isSuccess)
        val orphan = File(dir, "rev-2026-10-30-123").apply { mkdir() }
        File(orphan, "test.json").writeText("partial")
        assertEquals("2026-10-29", store.active().revision)
        assertFalse(orphan.exists())
        val activeDir = dir.listFiles()!!.single { it.isDirectory }
        File(activeDir, "test.json").writeText("corrupt")
        assertEquals("2026-01-01", CatalogStore(dir, builtin).active().revision)
        assertTrue(store.install(DirectoryCatalogReader(bundle())).isSuccess)
        assertEquals("2026-10-29", CatalogStore(dir, builtin).active().revision)
    }
    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
            for ((name, raw) in entries) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(raw)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
    @Test fun zipFolderAndAssetsProduceSameSet() {
        val dir = bundle()
        val archive = zip(dir.listFiles()!!.map { it.name to it.readBytes() })
        val file = temp.newFile().apply { writeBytes(archive) }
        val expected = loadCatalogSet(DirectoryCatalogReader(dir))
        assertEquals(expected, loadCatalogSet(ZipCatalogReader(archive.inputStream())))
        assertEquals(expected, loadCatalogSet(ZipCatalogReader(file)))
        assertEquals(expected, loadCatalogSet(AssetsCatalogReader { File(dir, it).inputStream() }))
    }
    @Test fun zipRejectsUnsafeDuplicateAndOversizedEntries() {
        for (name in listOf("../test.json", "sub/test.json", "..", "sub\\test.json", "sub/")) {
            assertTrue(name, runCatching { ZipCatalogReader(zip(listOf(name to fixture())).inputStream()) }.isFailure)
        }
        // Меняем равнодлинное имя второй записи в локальном заголовке и центральном каталоге.
        val duplicate = zip(listOf("test.json" to fixture(), "best.json" to fixture())).toString(Charsets.ISO_8859_1)
            .replace("best.json", "test.json").toByteArray(Charsets.ISO_8859_1)
        assertTrue(runCatching { ZipCatalogReader(duplicate.inputStream()) }.isFailure)
        assertTrue(runCatching { ZipCatalogReader(ByteArray(8 * 1024 * 1024 + 1).inputStream()) }.isFailure)
        assertTrue(runCatching { ZipCatalogReader(zip(listOf("test.json" to ByteArray(65537))).inputStream()) }.isFailure)
    }
    @Test fun strictIndexAndRegionValidation() {
        val indexChanges: List<(JSONObject) -> Unit> = listOf(
            { it.put("schema", true) }, { it.put("schema", 1.5) }, { it.put("schema", 2) },
            { it.put("revision", "2026-02-30") }, { it.put("revision", "0000-01-01") },
            { it.put("regions", JSONArray()) },
            { it.getJSONArray("regions").getJSONObject(0).put("file", "other.json") },
            { it.getJSONArray("regions").getJSONObject(0).put("name", " ") },
            { it.getJSONArray("regions").getJSONObject(0).put("revision", "2026-01-02") },
            { it.getJSONArray("regions").getJSONObject(0).put("sha256", "x".repeat(64)) },
            { val rows = it.getJSONArray("regions"); repeat(100) { rows.put(rows.getJSONObject(0)) } },
        )
        for ((i, change) in indexChanges.withIndex()) {
            val dir = bundle()
            val index = JSONObject(File(dir, "index.json").readText())
            change(index)
            File(dir, "index.json").writeText(index.toString())
            assertTrue("index $i", runCatching { loadCatalogSet(DirectoryCatalogReader(dir)) }.isFailure)
        }
        val regionChanges: List<(JSONObject) -> Unit> = listOf(
            { it.put("schema", true) }, { it.put("schema", "1") },
            { it.getJSONObject("region").put("id", "other") },
            { it.getJSONObject("region").put("name", "x".repeat(81)) },
            { it.getJSONObject("source").put("url", "") },
            { it.getJSONObject("source").put("text", 42) },
            { it.put("stations", JSONArray()) },
            { val rows = it.getJSONArray("stations"); repeat(500) { rows.put(rows.getJSONObject(0)) } },
            { it.getJSONArray("stations").getJSONObject(0).put("frequencyKhz", true) },
            { it.getJSONArray("stations").getJSONObject(0).put("name", "x".repeat(81)) },
            { it.getJSONArray("stations").getJSONObject(0).put("id", "test--a") },
        )
        for ((i, change) in regionChanges.withIndex()) {
            val root = JSONObject(fixture().toString(Charsets.UTF_8))
            change(root)
            assertTrue("region $i", runCatching {
                loadCatalogSet(DirectoryCatalogReader(bundle((root.toString() + "\n").toByteArray())))
            }.isFailure)
        }
        for (raw in listOf(fixture().dropLast(1).toByteArray(),
            fixture().toString(Charsets.UTF_8).replace("87500", "87500.0").toByteArray(),
            fixture().toString(Charsets.UTF_8).replace("87500", "87500e0").toByteArray(),
            fixture().toString(Charsets.UTF_8).replace("\"schema\"", "'schema'").toByteArray(),
            fixture() + "{}\n".toByteArray(), fixture() + byteArrayOf(0xff.toByte()))) {
            assertTrue(runCatching { loadCatalogSet(DirectoryCatalogReader(bundle(raw))) }.isFailure)
        }
    }
    @Test fun returnedListsCannotBeChanged() {
        val set = loadCatalogSet(DirectoryCatalogReader(bundle()))
        assertTrue(runCatching { (set.catalogs as MutableList<Catalog>).clear() }.isFailure)
        assertTrue(runCatching { (set.catalogs.single().stations as MutableList<CatalogStation>).clear() }.isFailure)
    }
    @Test fun catalogSetTakesAnImmutableSnapshot() {
        val parsed = loadCatalogSet(DirectoryCatalogReader(bundle())).catalogs.single()
        val stations = parsed.stations.toMutableList()
        val catalogs = mutableListOf(parsed.copy(stations = stations))
        val set = CatalogSet("2026-10-29", catalogs)
        catalogs.clear()
        stations.clear()
        assertEquals(2, set.catalogs.single().stations.size)
    }
    @Test fun invalidPointerKeepsInstalledDirectoryUntilPointerIsRestored() {
        val builtin = DirectoryCatalogReader(bundle(revision = "2026-01-01"))
        for (problem in listOf("missing", "invalid", "unreadable")) {
            val dir = temp.newFolder()
            val store = CatalogStore(dir, builtin)
            assertTrue(store.install(DirectoryCatalogReader(bundle())).isSuccess)
            val pointer = File(dir, "active")
            val savedPointer = pointer.readText()
            val installed = File(dir, savedPointer)
            pointer.delete()
            when (problem) {
                "invalid" -> pointer.writeText("broken")
                "unreadable" -> pointer.mkdir()
            }
            assertEquals("2026-01-01", store.active().revision)
            assertTrue(problem, installed.isDirectory)
            pointer.delete()
            pointer.writeText(savedPointer)
            assertEquals("2026-10-29", CatalogStore(dir, builtin).active().revision)
        }
    }
    @Test fun updatedBuiltinWinsAtEqualOrNewerRevisionAndSetsInstallThreshold() {
        val oldBuiltin = DirectoryCatalogReader(bundle(revision = "2026-01-01"))
        val dir = temp.newFolder()
        assertTrue(CatalogStore(dir, oldBuiltin).install(DirectoryCatalogReader(bundle())).isSuccess)
        val pointer = File(dir, "active").readText()
        val raw = fixture().toString(Charsets.UTF_8).replace("Радио А", "Из APK").toByteArray()
        for (revision in listOf("2026-10-29", "2026-10-30")) {
            val builtin = DirectoryCatalogReader(bundle(raw, revision))
            val store = CatalogStore(dir, builtin)
            assertEquals(loadCatalogSet(builtin), store.active())
            assertTrue(File(dir, pointer).isDirectory)
            assertEquals(pointer, File(dir, "active").readText())
            assertTrue(store.install(DirectoryCatalogReader(bundle(revision = revision))).isFailure)
        }
    }
    @Test fun rejectsNamesMadeOnlyOfPythonWhitespace() {
        for (blank in listOf("\u00a0", "\u2007", "\u202f", "\u0085", "\u00a0\u2007\u202f")) {
            val root = JSONObject(fixture().toString(Charsets.UTF_8))
            root.getJSONArray("stations").getJSONObject(0).put("name", blank)
            val reader = DirectoryCatalogReader(bundle((root.toString() + "\n").toByteArray()))
            val failure = runCatching { loadCatalogSet(reader) }.exceptionOrNull()
            assertTrue("blank name: $blank; $failure", failure?.message?.contains("Invalid name") == true)
        }
    }
    @Test fun failedPointerMoveKeepsPreviousSetAfterRestart() {
        val builtin = DirectoryCatalogReader(bundle(revision = "2026-01-01"))
        val dir = temp.newFolder()
        assertTrue(CatalogStore(dir, builtin).install(DirectoryCatalogReader(bundle())).isSuccess)
        var moveAttempted = false
        val store = CatalogStore(dir, builtin, move = { from, _ ->
            moveAttempted = true
            assertTrue(from.isFile)
            assertTrue(from.readText().startsWith("rev-2026-10-30-"))
            throw java.io.IOException("injected move failure")
        })
        val result = store.install(DirectoryCatalogReader(bundle(revision = "2026-10-30")))
        assertTrue(moveAttempted)
        assertEquals("injected move failure", result.exceptionOrNull()?.message)
        assertEquals("2026-10-29", store.active().revision)
        assertEquals("2026-10-29", CatalogStore(dir, builtin).active().revision)
    }
}
