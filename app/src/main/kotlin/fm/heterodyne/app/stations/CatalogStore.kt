package fm.heterodyne.app.stations

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** writer и move позволяют проверить сбои записи и смены указателя. */
class CatalogStore(
    private val dir: File,
    private val builtin: CatalogReader,
    private val move: (File, File) -> Unit = { from, to ->
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
    private val writer: (File, ByteArray) -> Unit = { file, bytes ->
        FileOutputStream(file).use { stream -> stream.write(bytes); stream.fd.sync() }
    },
) {
    fun active(): CatalogSet = synchronized(ioLock) {
        val pointed = pointedDirectory()
        val bundled = loadCatalogSet(builtin)
        if (pointed != null) {
            cleanup(pointed)
            val installed = try { loadCatalogSet(DirectoryCatalogReader(pointed)) } catch (_: Exception) { null }
            if (installed != null && installed.revision > bundled.revision) return@synchronized installed
        }
        bundled
    }

    fun install(reader: CatalogReader): Result<CatalogSet> = synchronized(ioLock) {
        runCatching {
            val current = active()
            // Сохраняем именно проверенные байты, даже если источник меняется между чтениями.
            val bytes = linkedMapOf<String, ByteArray>()
            val candidate = loadCatalogSet(CatalogReader { name -> reader.read(name).copyOf().also { bytes[name] = it } })
            require(candidate.revision > current.revision) { "Revision ${candidate.revision} must be newer than ${current.revision}" }
            check(dir.isDirectory || dir.mkdirs()) { "Cannot create catalog directory" }
            val next = Files.createTempDirectory(dir.toPath(), "rev-${candidate.revision}-").toFile()
            try {
                for ((name, raw) in bytes) writer(File(next, name), raw)
                // Проверяем результат записи до публикации.
                require(loadCatalogSet(DirectoryCatalogReader(next)) == candidate) { "Written catalog differs" }
                pointTo(next.name)
            } catch (failure: Exception) {
                runCatching { next.deleteRecursively() }
                throw failure
            }
            cleanup(next)
            candidate
        }
    }

    fun reset(): Unit = synchronized(ioLock) {
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create catalog directory" }
        pointTo("")
        cleanup(null)
    }

    private fun pointTo(name: String) {
        val temporary = File(dir, "active.tmp")
        try {
            writer(temporary, name.toByteArray(Charsets.UTF_8))
            move(temporary, File(dir, "active"))
        } finally { temporary.delete() }
    }

    private fun pointedDirectory(): File? = try {
        val pointer = File(dir, "active")
        if (pointer.length() in 1..128) {
            val name = pointer.readText(Charsets.UTF_8)
            if (revisionDirectory.matches(name)) File(dir, name) else null
        } else null
    } catch (_: Exception) { null }

    private fun cleanup(keep: File?) {
        // Ошибка уборки не превращает уже выполненную замену в отказ.
        runCatching {
            File(dir, "active.tmp").delete()
            dir.listFiles()?.filter { revisionDirectory.matches(it.name) && it != keep }?.forEach {
                if (Files.isSymbolicLink(it.toPath())) it.delete() else it.deleteRecursively()
            }
        }
    }

    private companion object {
        val ioLock = Any()
        val revisionDirectory = Regex("rev-[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]+")
    }
}
