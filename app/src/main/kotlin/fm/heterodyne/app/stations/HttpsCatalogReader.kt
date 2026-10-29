package fm.heterodyne.app.stations

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Один ручной сеанс загрузки. close прекращает активный запрос и запрещает следующие. */
class HttpsCatalogReader(
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : CatalogReader {
    private val lock = Any()
    private var closed = false
    private var active: HttpURLConnection? = null
    private val deadline = System.nanoTime() + 120_000_000_000L

    override fun read(name: String): ByteArray {
        safeName(name)
        var url = URL("https://github.com/krasotinpa/heterodyne-fm-catalog/releases/latest/download/$name")
        repeat(6) {
            checkOpen()
            require(url.protocol == "https" && url.userInfo == null) { "HTTPS required" }
            val connection = connect(url)
            synchronized(lock) {
                checkOpen()
                active = connection
            }
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                when (connection.responseCode) {
                    200 -> return connection.inputStream.use { input ->
                        val guarded = object : java.io.FilterInputStream(input) {
                            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                                checkOpen()
                                return super.read(bytes, offset, length).also { checkOpen() }
                            }
                        }
                        guarded.boundedBytes(limitFor(name))
                    }
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: throw IOException("Missing redirect")
                        url = URL(url, location)
                    }
                    else -> throw IOException("HTTP ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
                synchronized(lock) { if (active === connection) active = null }
            }
        }
        throw IOException("Too many redirects")
    }

    private fun checkOpen() = synchronized(lock) {
        if (closed || System.nanoTime() >= deadline) throw IOException("Download cancelled or timed out")
    }

    override fun close() {
        val connection = synchronized(lock) { closed = true; active.also { active = null } }
        connection?.disconnect()
    }
}
