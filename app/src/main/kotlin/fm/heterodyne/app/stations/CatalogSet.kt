package fm.heterodyne.app.stations

import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.isSelectableFrequency
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Collections
import java.util.zip.ZipInputStream
import org.json.JSONObject

private const val INDEX_LIMIT = 32 * 1024
private const val REGION_LIMIT = 64 * 1024
private const val ZIP_LIMIT = 8 * 1024 * 1024
private const val MAX_REGIONS = 100
private fun limitFor(name: String) = if (name == "index.json") INDEX_LIMIT else REGION_LIMIT
private val identifier = Regex("[a-z0-9]+(-[a-z0-9]+)*")

/** Читает файл набора; источник не определяет правила проверки. */
fun interface CatalogReader { fun read(name: String): ByteArray }

private fun safeName(name: String) {
    require(name.isNotEmpty() && '/' !in name && '\\' !in name && ".." !in name) { "Unsafe file name: $name" }
}

private fun InputStream.boundedBytes(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
        require(output.size() <= limit) { "File exceeds $limit bytes" }
    }
    return output.toByteArray()
}

class DirectoryCatalogReader(private val dir: File) : CatalogReader {
    override fun read(name: String): ByteArray {
        safeName(name)
        return File(dir, name).inputStream().use { it.boundedBytes(limitFor(name)) }
    }
}

class AssetsCatalogReader(private val open: (String) -> InputStream) : CatalogReader {
    override fun read(name: String): ByteArray {
        safeName(name)
        return open(name).use { it.boundedBytes(limitFor(name)) }
    }
}

/** Конструктор закрывает переданный поток после чтения архива. */
class ZipCatalogReader(input: InputStream) : CatalogReader {
    constructor(file: File) : this(file.inputStream())
    private val files: Map<String, ByteArray>
    init {
        val archive = input.use { it.boundedBytes(ZIP_LIMIT) }
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                safeName(entry.name)
                require(!entry.isDirectory && entry.name !in entries) { "Duplicate or directory ZIP entry" }
                require(entries.size < MAX_REGIONS + 1) { "Too many ZIP entries" }
                entries[entry.name] = zip.boundedBytes(limitFor(entry.name))
                zip.closeEntry()
            }
        }
        require(entries.containsKey("index.json")) { "Missing index.json" }
        files = entries
    }
    override fun read(name: String): ByteArray = files.getValue(name).copyOf()
}

class CatalogSet(val revision: String, catalogs: List<Catalog>) {
    val catalogs: List<Catalog> = Collections.unmodifiableList(catalogs.map {
        it.copy(stations = Collections.unmodifiableList(it.stations.toList()))
    })
    override fun equals(other: Any?) = other is CatalogSet && revision == other.revision && catalogs == other.catalogs
    override fun hashCode() = 31 * revision.hashCode() + catalogs.hashCode()
}

private fun JSONObject.strictString(key: String): String {
    val value = get(key)
    require(value is String) { "Expected string: $key" }
    return value
}

private fun date(value: Any): String {
    require(value is String && Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) { "Invalid revision" }
    require(LocalDate.parse(value).year in 1..9999) { "Invalid revision" }
    return value
}
private fun name(value: Any): String {
    require(value is String && value.any { !it.isWhitespace() && it !in "\u0085\u00a0\u2007\u202f" } && value.codePointCount(0, value.length) <= 80) { "Invalid name" }
    return value
}
private fun integer(value: Any): Int {
    require(value is Int || value is Long) { "Expected integer" }
    val number = (value as Number).toLong()
    require(number in Int.MIN_VALUE..Int.MAX_VALUE) { "Integer out of range" }
    return number.toInt()
}
private fun json(raw: ByteArray, limit: Int): JSONObject {
    require(raw.size <= limit) { "File exceeds $limit bytes" }
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
    StrictJson(text).validate()
    return JSONObject(text)
}
private fun sha256(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }

/** Любая ошибка отвергает весь набор. Catalog.parse остаётся совместимым со старыми вызовами. */
fun loadCatalogSet(reader: CatalogReader): CatalogSet {
    val index = json(reader.read("index.json"), INDEX_LIMIT)
    require(integer(index.get("schema")) == 1) { "Unknown index schema" }
    val revision = date(index.get("revision"))
    val regions = index.getJSONArray("regions")
    require(regions.length() in 1..MAX_REGIONS) { "Invalid region count" }
    val ids = mutableSetOf<String>()
    val files = mutableSetOf<String>()
    val catalogs = (0 until regions.length()).map { i ->
        val row = regions.getJSONObject(i)
        val id = row.strictString("id")
        require(identifier.matches(id) && ids.add(id)) { "Invalid or duplicate region id" }
        val regionName = name(row.get("name"))
        val file = row.strictString("file")
        safeName(file)
        require(file == "$id.json" && files.add(file)) { "Invalid or duplicate region file" }
        val regionRevision = date(row.get("revision"))
        val hash = row.strictString("sha256")
        require(Regex("[0-9a-f]{64}").matches(hash)) { "Invalid SHA-256" }
        val raw = reader.read(file)
        require(raw.size <= REGION_LIMIT && sha256(raw) == hash) { "Region size or SHA-256 mismatch" }
        require(raw.lastOrNull() == '\n'.code.toByte() && '\r'.code.toByte() !in raw) { "Expected LF and final newline" }
        val root = json(raw, REGION_LIMIT)
        require(integer(root.get("schema")) == 1) { "Unknown region schema" }
        require(date(root.get("revision")) == regionRevision) { "Region revision mismatch" }
        val region = root.getJSONObject("region")
        require(region.strictString("id") == id) { "Region id mismatch" }
        require(name(region.get("name")) == regionName) { "Region name mismatch" }
        val source = root.getJSONObject("source")
        require(source.strictString("text").isNotEmpty() && source.strictString("url").isNotEmpty()) { "Missing source" }
        val stations = root.getJSONArray("stations")
        require(stations.length() in 1..500) { "Invalid station count" }
        val stationIds = mutableSetOf<String>()
        for (j in 0 until stations.length()) {
            val station = stations.getJSONObject(j)
            val sid = station.strictString("id")
            require(identifier.matches(sid) && sid.startsWith("$id-") && stationIds.add(sid)) { "Invalid or duplicate station id" }
            name(station.get("name"))
            require(station.get("band") == "FM") { "Only FM is supported" }
            require(isSelectableFrequency(Band.FM, integer(station.get("frequencyKhz")))) { "Invalid FM frequency" }
        }
        Catalog.parse(raw.toString(Charsets.UTF_8))
    }
    return CatalogSet(revision, catalogs)
}

/** org.json допускает расширенный синтаксис; здесь принимаем только JSON. */
private class StrictJson(private val text: String) {
    private var at = 0
    fun validate() { value(0); whitespace(); require(at == text.length) { "Trailing JSON data" } }
    private fun whitespace() { while (at < text.length && text[at] in " \t\r\n") at++ }
    private fun take(c: Char): Boolean { whitespace(); return if (at < text.length && text[at] == c) { at++; true } else false }
    private fun expect(c: Char) { require(take(c)) { "Expected $c" } }
    private fun value(depth: Int) {
        require(depth < 64) { "JSON nesting limit" }
        whitespace()
        require(at < text.length) { "Missing JSON value" }
        when (text[at]) {
            '{' -> { at++; if (!take('}')) { do { string(); expect(':'); value(depth + 1) } while (take(',')); expect('}') } }
            '[' -> { at++; if (!take(']')) { do { value(depth + 1) } while (take(',')); expect(']') } }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> {
                val match = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").find(text, at)
                require(match != null && match.range.first == at) { "Invalid JSON number" }
                at = match.range.last + 1
            }
        }
    }
    private fun literal(word: String) { require(text.startsWith(word, at)); at += word.length }
    private fun string() {
        expect('"')
        while (at < text.length) {
            val c = text[at++]
            if (c == '"') return
            require(c >= ' ') { "Control character in JSON string" }
            if (c == '\\') {
                require(at < text.length)
                val escape = text[at++]
                if (escape == 'u') {
                    repeat(4) { require(at < text.length && text[at++] in "0123456789abcdefABCDEF") }
                } else require(escape in "\"\\/bfnrt") { "Invalid JSON escape" }
            }
        }
        error("Unterminated JSON string")
    }
}
