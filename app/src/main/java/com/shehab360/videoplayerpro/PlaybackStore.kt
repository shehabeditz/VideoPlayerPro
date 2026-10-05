package com.shehab360.videoplayerpro

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val uri: String,
    val title: String,
    val lastPlayed: Long,
    val duration: Long
)

/**
 * Resume position, History ও Bookmark।
 * (পরে ডাটা বড় হলে Room এ সরানো যাবে; API এই ক্লাসের পেছনেই থাকবে)
 */
object PlaybackStore {

    private const val MAX_HISTORY = 300

    private lateinit var positions: SharedPreferences   // আগের "VideoPlaybackState" ফাইলই, পুরোনো ডাটা থাকবে
    private lateinit var meta: SharedPreferences

    private val history = ArrayList<HistoryEntry>()
    private val played = HashSet<String>()

    fun init(context: Context) {
        val app = context.applicationContext
        positions = app.getSharedPreferences("VideoPlaybackState", Context.MODE_PRIVATE)
        meta = app.getSharedPreferences("PlayerMeta", Context.MODE_PRIVATE)
        loadHistory()
    }

    // ---------------- Resume position ----------------
    @Synchronized
    fun getPosition(uri: String): Long = positions.getLong(uri, 0L)

    /** duration জানা না থাকলে (<=0) সেভ করা হয় না — লাইভ/অলোড ভিডিওতে ভুল মান ঢোকা আটকাতে */
    @Synchronized
    fun savePosition(uri: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        val value = if (positionMs >= durationMs - 2000L) 0L else positionMs.coerceAtLeast(0L)
        positions.edit().putLong(uri, value).apply()
        updateHistoryDuration(uri, durationMs)
    }

    @Synchronized
    fun clearPosition(uri: String) {
        positions.edit().putLong(uri, 0L).apply()
    }

    // ---------------- History ----------------
    @Synchronized
    fun isPlayed(uri: String): Boolean = played.contains(uri)

    @Synchronized
    fun recordHistory(uri: String, title: String) {
        val old = history.firstOrNull { it.uri == uri }
        history.removeAll { it.uri == uri }
        history.add(0, HistoryEntry(uri, title, System.currentTimeMillis(), old?.duration ?: 0L))
        while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
        played.add(uri)
        persistHistory()
    }

    @Synchronized
    private fun updateHistoryDuration(uri: String, durationMs: Long) {
        val i = history.indexOfFirst { it.uri == uri }
        if (i >= 0 && history[i].duration != durationMs) {
            history[i] = history[i].copy(duration = durationMs)
            persistHistory()
        }
    }

    @Synchronized
    fun getHistory(): List<HistoryEntry> = ArrayList(history)

    @Synchronized
    fun removeHistory(uri: String) {
        history.removeAll { it.uri == uri }
        persistHistory()
    }

    @Synchronized
    fun clearHistory() {
        history.clear()
        played.clear()
        meta.edit().remove("history").apply()
        positions.edit().clear().apply()
    }

    private fun persistHistory() {
        val arr = JSONArray()
        history.forEach {
            arr.put(
                JSONObject()
                    .put("u", it.uri)
                    .put("t", it.title)
                    .put("l", it.lastPlayed)
                    .put("d", it.duration)
            )
        }
        meta.edit().putString("history", arr.toString()).apply()
    }

    private fun loadHistory() {
        history.clear()
        played.clear()
        val raw = meta.getString("history", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val e = HistoryEntry(o.getString("u"), o.optString("t", ""), o.optLong("l"), o.optLong("d"))
                history.add(e)
                played.add(e.uri)
            }
        } catch (e: Exception) {
            history.clear()
        }
    }

    // ---------------- Bookmarks ----------------
    @Synchronized
    fun getBookmarks(uri: String): List<Long> =
        (meta.getString("bm_$uri", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }.sorted()

    @Synchronized
    fun addBookmark(uri: String, positionMs: Long) {
        val list = (getBookmarks(uri) + positionMs).distinct().sorted()
        meta.edit().putString("bm_$uri", list.joinToString(",")).apply()
    }

    @Synchronized
    fun removeBookmark(uri: String, positionMs: Long) {
        val list = getBookmarks(uri).filter { it != positionMs }
        meta.edit().putString("bm_$uri", list.joinToString(",")).apply()
    }
}
