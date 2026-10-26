package fm.heterodyne.app

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

interface Journal {
    fun tx(frame: ByteArray)
    fun rx(frame: ByteArray)
    fun event(text: String)
}

/**
 * Persistent UTF-8 journal. Give one instance exclusive ownership of a private directory.
 * Times are epoch milliseconds. No Android APIs, user name or head-unit name are collected.
 * Records larger than the entire budget are dropped. IO errors propagate to the caller.
 * Rotation drops oldest whole records to leave about 10% of the budget free,
 * unless the new record needs that space. The newest record always fits within the budget.
 * Writes are synchronous; power-loss durability is not guaranteed.
 */
class DiagnosticLog(
    directory: File,
    private val maxBytes: Long = 10L * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
    salt: ByteArray = byteArrayOf(),
    private val hash: (ByteArray) -> ByteArray = {
        MessageDigest.getInstance("SHA-256").digest(it)
    },
) : Journal {
    var frameListener: ((String, ByteArray, Long) -> Unit)? = null

    @Synchronized
    fun readTail(maxBytes: Int): String {
        require(maxBytes >= 0) { "Tail limit must not be negative" }
        if (maxBytes == 0 || !file.exists()) return ""
        return RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val offset = (length - maxBytes).coerceAtLeast(0)
            val startsAtLine = if (offset == 0L) true else {
                input.seek(offset - 1)
                input.read() == '\n'.code
            }
            input.seek(offset)
            val bytes = ByteArray((length - offset).toInt())
            input.readFully(bytes)
            // Discard the partial record, including any split UTF-8 character.
            val start = if (startsAtLine) 0 else {
                val newline = bytes.indexOf('\n'.code.toByte())
                if (newline < 0) bytes.size else newline + 1
            }
            String(bytes, start, bytes.size - start, Charsets.UTF_8)
        }
    }

    @Synchronized
    fun copyTo(output: java.io.OutputStream) {
        if (file.exists()) file.inputStream().use { it.copyTo(output) }
    }

    private fun notifyFrame(direction: String, frame: ByteArray) {
        try { frameListener?.invoke(direction, frame.copyOf(), clock()) } catch (_: Exception) { /* Passive observer. */ }
    }

    private val salt = salt.copyOf()
    private val file = File(directory, "diagnostic.log")
    private val temporary = File(directory, "diagnostic.log.tmp")
    private var lastRx: Rx? = null

    private data class Rx(val hex: String, val first: Long, val count: Long, val offset: Long)

    init {
        require(maxBytes > 0) { "Journal limit must be positive" }
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create journal directory" }
        require(directory.listFiles()?.all { it == file || it == temporary } == true) {
            "Journal requires a dedicated directory"
        }
        Files.deleteIfExists(temporary.toPath())
        // Retain complete records after an interrupted write and enforce a changed budget.
        if (file.exists()) {
            val bytes = file.readBytes()
            val end = bytes.indexOfLast { it == '\n'.code.toByte() } + 1
            var start = 0
            while (end - start > maxBytes) {
                while (start < end && bytes[start++] != '\n'.code.toByte()) { /* skip record */ }
            }
            if (start != 0 || end != bytes.size) file.writeBytes(bytes.copyOfRange(start, end))
            if (end > start) {
                var lastStart = end - 1
                while (lastStart > start && bytes[lastStart - 1] != '\n'.code.toByte()) lastStart--
                val line = bytes.copyOfRange(lastStart, end - 1).toString(Charsets.UTF_8)
                val match = Regex("(-?[0-9]+) RX ([0-9a-f]*) ×([0-9]+) first=(-?[0-9]+) last=(-?[0-9]+)")
                    .matchEntire(line)
                if (match != null) {
                    val first = match.groupValues[4].toLongOrNull()
                    val count = match.groupValues[3].toLongOrNull()
                    if (first != null && count != null && count > 0) {
                        lastRx = Rx(match.groupValues[2], first, count, (lastStart - start).toLong())
                    }
                }
            }
        }
    }

    @Synchronized
    override fun tx(frame: ByteArray) {
        notifyFrame("TX", frame)
        lastRx = null
        write("${clock()} TX ${frame.hex()}\n")
    }

    @Synchronized
    override fun rx(frame: ByteArray) {
        notifyFrame("RX", frame)
        val hex = frame.hex()
        val now = clock()
        val previous = lastRx?.takeIf { it.hex == hex }
        val first = previous?.first ?: now
        val count = (previous?.count ?: 0) + 1
        val offset = write("$first RX $hex ×$count first=$first last=$now\n", previous?.offset)
        lastRx = offset?.let { Rx(hex, first, count, it) }
    }

    /** Callers supply diagnostic text only, without user or head-unit identifiers. */
    @Synchronized
    override fun event(text: String) {
        lastRx = null
        val escaped = text.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")
        write("${clock()} EVENT $escaped\n")
    }

    @Synchronized
    fun buildVersion(version: String) {
        event("build=$version")
    }

    /** Hash at the boundary: the raw serial is never passed to event or file IO. */
    @Synchronized
    fun moduleSerial(serial: String) {
        val digest = hash(salt + serial.toByteArray(Charsets.UTF_8))
        require(digest.size == 32) { "Serial hash must be a SHA-256 digest" }
        event("moduleSerialSha256=${digest.hex()}")
    }

    // During rotation both copies occupy up to 2× the budget; at rest the file is ≤ the budget.
    // The original remains intact until the synced replacement is atomically renamed.
    private fun write(record: String, replaceFrom: Long? = null): Long? {
        val bytes = record.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) return null
        val offset = RandomAccessFile(file, "rw").use { output ->
            val retainedLength = replaceFrom ?: output.length()
            if (retainedLength + bytes.size > maxBytes) {
                val old = ByteArray(retainedLength.toInt())
                output.readFully(old)
                val targetBytes = maxOf(maxBytes - maxBytes / 10, bytes.size.toLong())
                var start = 0
                while (old.size - start + bytes.size > targetBytes) {
                    while (start < old.size && old[start++] != '\n'.code.toByte()) { /* skip record */ }
                }
                FileOutputStream(temporary).use { replacement ->
                    replacement.write(old, start, old.size - start)
                    replacement.write(bytes)
                    replacement.fd.sync()
                }
                (old.size - start).toLong()
            } else {
                if (replaceFrom != null) output.setLength(replaceFrom)
                output.seek(retainedLength)
                output.write(bytes)
                return retainedLength
            }
        }
        Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        return offset
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
