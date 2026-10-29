package fm.heterodyne.app.stations

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class HttpsCatalogReaderTest {
    @Test fun followsHttpsReleaseRedirectAndClosesBothConnections() {
        val connections = mutableListOf<Response>()
        val reader = HttpsCatalogReader { url ->
            Response(url, if (connections.isEmpty()) 302 else 200, "release".toByteArray(),
                "https://release-assets.githubusercontent.com/catalog").also { connections.add(it) }
        }
        reader.use { assertArrayEquals("release".toByteArray(), it.read("index.json")) }
        assertEquals("https://github.com/krasotinpa/heterodyne-fm-catalog/releases/latest/download/index.json",
            connections.first().url.toString())
        assertEquals(2, connections.size)
        assertTrue(connections.all { it.disconnected && !it.instanceFollowRedirects })
    }

    @Test fun rejectsHttpRedirectBeforeOpeningIt() {
        var requests = 0
        val reader = HttpsCatalogReader { url ->
            requests++
            Response(url, 302, location = "http://example.org/index.json")
        }
        reader.use { assertTrue(runCatching { it.read("index.json") }.isFailure) }
        assertEquals(1, requests)
    }

    @Test fun rejectsUnsafeFilenameAndClosedReaderWithoutNetwork() {
        var requests = 0
        val reader = HttpsCatalogReader { url -> requests++; Response(url, 200) }
        assertTrue(runCatching { reader.read("../index.json") }.isFailure)
        reader.close()
        assertTrue(runCatching { reader.read("index.json") }.isFailure)
        assertEquals(0, requests)
    }

    @Test fun rejectsOversizedFilesHttpErrorsAndRedirectLoops() {
        for ((name, status, size) in listOf(Triple("index.json", 200, 32769),
            Triple("moskva.json", 200, 65537), Triple("index.json", 404, 0), Triple("index.json", 302, 0))) {
            val connections = mutableListOf<Response>()
            HttpsCatalogReader { url ->
                Response(url, status, ByteArray(size), "https://example.org/loop").also { connections.add(it) }
            }.use { reader ->
                assertTrue(runCatching { reader.read(name) }.isFailure)
            }
            assertTrue(connections.all { it.disconnected })
            assertTrue(connections.size <= 6)
        }
    }

    @Test fun closeInterruptsCurrentReadAndPreventsNextFile() {
        lateinit var reader: HttpsCatalogReader
        lateinit var connection: Response
        reader = HttpsCatalogReader { url ->
            object : Response(url, 200, "contents".toByteArray()) {
                override fun getInputStream(): ByteArrayInputStream {
                    reader.close()
                    return super.getInputStream()
                }
            }.also { connection = it }
        }
        assertTrue(runCatching { reader.read("index.json") }.exceptionOrNull() is IOException)
        assertTrue(connection.disconnected)
        assertTrue(runCatching { reader.read("moskva.json") }.isFailure)
    }

    private open class Response(url: URL, private val status: Int,
        private val bytes: ByteArray = byteArrayOf(), private val location: String? = null) : HttpURLConnection(url) {
        var disconnected = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String): String? = if (name == "Location") location else null
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() {}
    }
}
