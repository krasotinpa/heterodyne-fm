package fm.heterodyne.app

import java.io.OutputStream
import java.util.UUID

/** Publishes a complete download or removes the incomplete entry on failure. */
interface DownloadStore {
    fun write(name: String, copy: (OutputStream) -> Unit)
}

data class JournalExportResult(val path: String, val sizeBytes: Long)

class JournalExport(private val store: DownloadStore) {
    fun save(copy: (OutputStream) -> Unit): JournalExportResult {
        val name = "heterodyne-diagnostic-${System.currentTimeMillis()}-${UUID.randomUUID()}.log"
        var size = 0L
        store.write(name) { output ->
            val counting = object : OutputStream() {
                override fun write(value: Int) { output.write(value); size++ }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    output.write(bytes, offset, length)
                    size += length
                }
                override fun flush() = output.flush()
            }
            copy(counting)
            counting.flush()
        }
        return JournalExportResult("Download/$name", size)
    }
}
