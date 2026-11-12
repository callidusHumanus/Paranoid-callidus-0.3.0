package ai.callidus.paranoid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Entry(val title: String, val url: String)

val SEARCH_ENGINES = linkedMapOf(
    "DuckDuckGo" to "https://duckduckgo.com/?q=",
    "Brave Search" to "https://search.brave.com/search?q=",
    "Startpage" to "https://www.startpage.com/do/search?q=",
    "Mojeek" to "https://www.mojeek.com/search?q="
)

/** Local-only storage (no sync, no telemetry). Excluded from Android backup via manifest rules. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("pc", Context.MODE_PRIVATE)

    var searchEngine: String
        get() = p.getString("engine", "DuckDuckGo")!!.takeIf { it in SEARCH_ENGINES } ?: "DuckDuckGo"
        set(v) = p.edit().putString("engine", v).apply()
    var blockAds: Boolean
        get() = p.getBoolean("blockAds", true)
        set(v) = p.edit().putBoolean("blockAds", v).apply()
    var saveHistory: Boolean
        get() = p.getBoolean("saveHistory", true)
        set(v) = p.edit().putBoolean("saveHistory", v).apply()
    var netMode: String
        get() = p.getString("netMode", "direct")!!.takeIf { it == "direct" || it == "socks" || it == "i2p" || it == "tor" } ?: "direct"
        set(v) = p.edit().putString("netMode", v).apply()
    var proxyHost: String
        get() = p.getString("proxyHost", "127.0.0.1")!!
        set(v) = p.edit().putString("proxyHost", v).apply()
    var proxyPort: Int
        get() = p.getInt("proxyPort", 9050)
        set(v) = p.edit().putInt("proxyPort", v).apply()
    var fpLevel: Int
        get() = p.getInt("fpLevel", 1).coerceIn(1, 3)
        set(v) = p.edit().putInt("fpLevel", v).apply()
    var requireVpn: Boolean
        get() = p.getBoolean("requireVpn", false)
        set(v) = p.edit().putBoolean("requireVpn", v).apply()
    var i2pHost: String
        get() = p.getString("i2pHost", "127.0.0.1")!!
        set(v) = p.edit().putString("i2pHost", v).apply()
    var i2pPort: Int
        get() = p.getInt("i2pPort", 4444)
        set(v) = p.edit().putInt("i2pPort", v).apply()
    var totalBlocked: Long
        get() = p.getLong("totalBlocked", 0)
        set(v) = p.edit().putLong("totalBlocked", v).apply()

    private fun read(key: String): MutableList<Entry> {
        val out = mutableListOf<Entry>()
        try {
            val a = JSONArray(p.getString(key, "[]"))
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); out.add(Entry(o.getString("t"), o.getString("u"))) }
        } catch (_: Exception) { }
        return out
    }
    private fun write(key: String, list: List<Entry>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url)) }
        p.edit().putString(key, a.toString()).apply()
    }

    fun history(): List<Entry> = read("history")
    fun addHistory(title: String, url: String) {
        val l = read("history"); l.removeAll { it.url == url }; l.add(0, Entry(title.ifBlank { url }, url))
        write("history", l.take(500))
    }
    fun clearHistory() = write("history", emptyList())

    fun identities(): List<String> {
        val out = mutableListOf<String>()
        try { val a = JSONArray(p.getString("identities", "[]")); for (i in 0 until a.length()) out.add(a.getString(i)) } catch (_: Exception) { }
        return out
    }
    fun addIdentity(n: String) { val l = identities().toMutableList(); if (n !in l) { l.add(n); p.edit().putString("identities", JSONArray(l).toString()).apply() } }
    fun removeIdentity(n: String) { p.edit().putString("identities", JSONArray(identities().filter { it != n }).toString()).apply() }

    fun bookmarks(): List<Entry> = read("bookmarks")
    fun addBookmark(title: String, url: String) {
        val l = read("bookmarks"); if (l.none { it.url == url }) { l.add(0, Entry(title.ifBlank { url }, url)); write("bookmarks", l) }
    }
    fun removeBookmark(url: String) = write("bookmarks", read("bookmarks").filter { it.url != url })
}
