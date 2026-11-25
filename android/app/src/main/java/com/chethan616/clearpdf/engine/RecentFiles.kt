package com.chethan616.clearpdf.engine

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** A document opened before, as Home's Recent list shows it. */
data class RecentFile(
    /** Where it was opened from: a `content://` or `file://` URI. */
    val uri: String,
    val name: String,
    /** When it was last opened, ms since the epoch. */
    val opened: Long,
    val pages: Int,
    val size: Long,
    /** The page the reader was on (0-based), to reopen there. */
    val lastPage: Int = 0,
    val starred: Boolean = false,
    /** It was left in Reading Mode, to reopen that way (the reference remembers it for each file). */
    val reading: Boolean = false,
    /** Where the screen's top-left was on [lastPage] (points) and the zoom, to reopen at the same spot; x < 0: unknown. */
    val lastX: Float = -1f,
    val lastY: Float = -1f,
    val lastZoom: Float = 1f,
    /** Starred from Files without being opened: kept for its star, not shown as read lately. */
    val starOnly: Boolean = false,
)

/**
 * The recent documents, newest first, kept in a JSON file in the app's private files (at most
 * [MAX] of them). Reads and writes are small and synchronous; callers stay off the main thread.
 */
class RecentFiles(private val file: File) {
    private val lock = Any()

    fun list(): List<RecentFile> = synchronized(lock) { read() }

    fun touch(entry: RecentFile) = synchronized(lock) {
        val old = read().firstOrNull { it.uri == entry.uri }
        val merged = entry.copy(
            starred = old?.starred ?: entry.starred, reading = old?.reading ?: entry.reading,
            lastX = old?.lastX ?: entry.lastX, lastY = old?.lastY ?: entry.lastY, lastZoom = old?.lastZoom ?: entry.lastZoom,
        )
        write(listOf(merged) + read().filter { it.uri != entry.uri })
    }

    fun update(uri: String, f: (RecentFile) -> RecentFile) = synchronized(lock) {
        write(read().map { if (it.uri == uri) f(it) else it })
    }

    /**
     * Star or unstar a file: the entry for [entry]'s uri, or failing that the same file known by another
     * address (its name and size: a file opened from another app and found again on the phone), else
     * a new entry for it, set in among the others by [RecentFile.opened] (starring a file on the phone
     * does not make it the newest thing read).
     */
    fun star(entry: RecentFile, starred: Boolean) = synchronized(lock) {
        val list = read()
        val same = list.filter { it.uri == entry.uri || (it.name == entry.name && it.size == entry.size && entry.size > 0) }
        if (same.isNotEmpty()) {
            val keys = same.map { it.uri }.toSet()
            // a file kept only for its star goes once unstarred
            write(list.mapNotNull { if (it.uri in keys) it.copy(starred = starred).takeIf { r -> starred || !r.starOnly } else it })
        } else if (starred) {
            val at = list.indexOfFirst { it.opened < entry.opened }.let { if (it < 0) list.size else it }
            write(list.take(at) + entry.copy(starred = true, starOnly = true) + list.drop(at))
        }
    }

    fun remove(uris: Set<String>) = synchronized(lock) { write(read().filter { it.uri !in uris }) }

    fun clear() = synchronized(lock) { write(emptyList()) }

    private fun read(): List<RecentFile> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val a = JSONArray(file.readText())
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                RecentFile(
                    uri = o.optString("uri").ifEmpty { return@mapNotNull null },
                    name = o.optString("name", "Document.pdf"),
                    opened = o.optLong("opened"),
                    pages = o.optInt("pages"),
                    size = o.optLong("size"),
                    lastPage = o.optInt("lastPage"),
                    starred = o.optBoolean("starred"),
                    reading = o.optBoolean("reading"),
                    lastX = o.optDouble("lastX", -1.0).toFloat(),
                    lastY = o.optDouble("lastY", -1.0).toFloat(),
                    lastZoom = o.optDouble("lastZoom", 1.0).toFloat(),
                    starOnly = o.optBoolean("starOnly"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(list: List<RecentFile>) {
        val a = JSONArray()
        // at most MAX, but a starred file is never let go: its star lives here
        for (r in list.filterIndexed { i, it -> i < MAX || it.starred }) {
            a.put(
                JSONObject().put("uri", r.uri).put("name", r.name).put("opened", r.opened).put("pages", r.pages)
                    .put("size", r.size).put("lastPage", r.lastPage).put("starred", r.starred).put("reading", r.reading)
                    .put("lastX", r.lastX.toDouble()).put("lastY", r.lastY.toDouble()).put("lastZoom", r.lastZoom.toDouble())
                    .put("starOnly", r.starOnly),
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        const val MAX = 100
    }
}
