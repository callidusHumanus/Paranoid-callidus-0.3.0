package ai.callidus.paranoid

import android.content.Context
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Host-based blocker. A request is only reported as blocked when isBlocked() returned true
 * and the caller actually replaced the response.
 */
class AdBlocker(private val ctx: Context) {
    @Volatile private var hosts: Set<String> = emptySet()
    @Volatile var source: String = "bundled starter list"
    val size: Int get() = hosts.size

    private val domainRe = Regex("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}$")

    private fun parse(lines: Sequence<String>, into: MutableSet<String>) {
        for (raw in lines) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val t = line.split(Regex("\\s+"))
            val d = when {
                t.size >= 2 && (t[0] == "127.0.0.1" || t[0] == "0.0.0.0") -> t[1]
                t.size == 1 -> t[0]
                else -> continue
            }.lowercase()
            if (d != "localhost" && domainRe.matches(d)) into.add(d)
        }
    }

    fun load() {
        val set = HashSet<String>()
        ctx.assets.open("blocklist.txt").bufferedReader().useLines { parse(it, set) }
        val f = File(ctx.filesDir, "hosts.txt")
        if (f.exists()) { f.bufferedReader().useLines { parse(it, set) }; source = "bundled + Peter Lowe's list" }
        hosts = set
    }

    fun isBlocked(host: String?): Boolean {
        var h = host?.lowercase() ?: return false
        val set = hosts
        while (true) {
            if (h in set) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }

    /** Downloads Peter Lowe's list over verified HTTPS. This is a direct (non-proxied) connection. */
    fun update(proxy: java.net.Proxy? = null): Int {
        val url = URL("https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext")
        val c = (if (proxy != null) url.openConnection(proxy) else url.openConnection()) as HttpsURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            val tmp = File(ctx.filesDir, "hosts.tmp")
            var total = 0L
            c.inputStream.use { i -> tmp.outputStream().use { o ->
                val buf = ByteArray(8192); while (true) { val n = i.read(buf); if (n < 0) break
                    total += n; if (total > 5_000_000) throw RuntimeException("list too large"); o.write(buf, 0, n) } } }
            val test = HashSet<String>(); tmp.bufferedReader().useLines { parse(it, test) }
            if (test.size < 100) { tmp.delete(); throw RuntimeException("list looks invalid") }
            tmp.renameTo(File(ctx.filesDir, "hosts.txt"))
            load()
            return test.size
        } finally { c.disconnect() }
    }
}
