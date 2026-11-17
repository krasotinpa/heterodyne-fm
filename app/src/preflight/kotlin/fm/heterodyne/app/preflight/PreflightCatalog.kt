package fm.heterodyne.app.preflight

import android.content.Context
import fm.heterodyne.app.stations.CatalogReader
import fm.heterodyne.app.stations.HttpsCatalogReader
import org.json.JSONObject

/** Fixture bytes pass the same parser and atomic installation as HTTPS. */
class PreflightCatalog(private val context: Context, private val mode: String) : CatalogReader {
    init { require(mode in setOf("https", "fixture", "failure")) }
    private val https = if (mode == "https") HttpsCatalogReader() else null
    override fun read(name: String): ByteArray {
        https?.let { return it.read(name) }
        if (mode == "failure") throw java.io.IOException("Synthetic offline fixture")
        val raw = context.assets.open("catalogs/$name").use { it.readBytes() }
        return if (name == "index.json") JSONObject(raw.toString(Charsets.UTF_8))
            .put("revision", "9999-01-01").toString().toByteArray(Charsets.UTF_8) else raw
    }
    override fun close() { https?.close() }
}
