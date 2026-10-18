package fm.heterodyne.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

class JournalExportTest {
    private class FakeDownloads : DownloadStore {
        val files = linkedMapOf<String, ByteArray>()
        var fail = false
        override fun write(name: String, copy: (OutputStream) -> Unit) {
            val output = ByteArrayOutputStream()
            copy(if (fail) object : OutputStream() {
                override fun write(value: Int) { throw IOException("disk full") }
            } else output)
            files[name] = output.toByteArray()
        }
    }
    @Test fun exportsBytesWithPublicPathAndSize() {
        val store = FakeDownloads()
        val result = JournalExport(store).save { it.write("Журнал\n".toByteArray()) }
        assertTrue(result.path.startsWith("Download/heterodyne-diagnostic-"))
        assertTrue(result.path.endsWith(".log"))
        assertEquals(13L, result.sizeBytes)
        assertEquals("Журнал\n", store.files.values.single().toString(Charsets.UTF_8))
    }
    @Test fun repeatedExportsHaveUniqueNames() {
        val store = FakeDownloads()
        val export = JournalExport(store)
        val first = export.save { it.write(1) }
        val second = export.save { it.write(2) }
        assertNotEquals(first.path, second.path)
        assertEquals(2, store.files.size)
    }
    @Test fun emptyJournalExportsZeroBytes() {
        val store = FakeDownloads()
        assertEquals(0L, JournalExport(store).save {}.sizeBytes)
        assertEquals(0, store.files.values.single().size)
    }
    @Test fun writeFailureDoesNotReportSuccess() {
        val store = FakeDownloads().apply { fail = true }
        assertThrows(IOException::class.java) { JournalExport(store).save { it.write(1) } }
        assertTrue(store.files.isEmpty())
    }
}
