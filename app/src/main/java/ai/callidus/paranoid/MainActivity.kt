package ai.callidus.paranoid

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.webkit.ProfileStore
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executor
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

class Tab(val id: Int, val web: WebView, val isPrivate: Boolean, val profile: String?) {
    var title = "New Tab"
    var url = ""
    var blocked = 0
    var progress = 100
}

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var blocker: AdBlocker
    private val tabs = mutableListOf<Tab>()
    private var active: Tab? = null
    private var nextId = 1

    private lateinit var tabBar: LinearLayout
    private lateinit var omnibox: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var content: FrameLayout
    private lateinit var status: TextView
    private lateinit var backBtn: Button
    private lateinit var fwdBtn: Button

    private var proxyReady = false
    private var vpnActive = false
    private var torSocksPort = 0
    private var torProgress = 0
    private var torError = ""
    private var torCtl: Any? = null
    private var torSvcConn: ServiceConnection? = null
    private var blackout = false
    private lateinit var lockOverlay: FrameLayout
    private var torState = ""
    private val mainExec = Executor { r -> runOnUiThread(r) }

    private val HOME = "file:///android_asset/home.html"
    private val RISKY = setOf("apk", "exe", "msi", "bat", "cmd", "scr", "js", "jar", "vbs", "ps1", "sh", "dmg", "pkg")
    private val GPC = mapOf("Sec-GPC" to "1", "DNT" to "1")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        blocker = AdBlocker(this).also { it.load() }
        buildUi()
        watchNetwork()
        applyNetworkMode()
        val link = intent?.data?.toString()
        newTab(false)
        if (link != null) go(link)
    }

    // ---------- UI ----------
    private fun flat(text: String, w: Int = LinearLayout.LayoutParams.WRAP_CONTENT, onClick: () -> Unit) = Button(this).apply {
        this.text = text; setTextColor(Color.parseColor("#D7EFE4")); setBackgroundColor(Color.TRANSPARENT)
        minWidth = 0; minimumWidth = 0; setPadding(dp(10), 0, dp(10), 0); isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(w, dp(44)); setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#05080A")) }

        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(Color.parseColor("#0B1114")) }
        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scroll.addView(tabBar)
        root.addView(scroll, LinearLayout.LayoutParams(-1, dp(40)))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.parseColor("#0F1B17"))
        }
        backBtn = flat("←") { active?.web?.goBack() }
        fwdBtn = flat("→") { active?.web?.goForward() }
        omnibox = EditText(this).apply {
            hint = "Search privately or enter address"; setHintTextColor(Color.parseColor("#6F8A80"))
            setTextColor(Color.parseColor("#D7EFE4")); setSingleLine(); textSize = 14f
            setBackgroundColor(Color.parseColor("#060B0D")); setPadding(dp(12), 0, dp(12), 0)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, _, _ -> go(text.toString()); hideKeyboard(); true }
            setOnFocusChangeListener { _, f -> if (f) post { selectAll() } }
        }
        val reload = flat("⟳") { active?.let { if (it.progress < 100) it.web.stopLoading() else it.web.reload() } }
        val menu = flat("⋮") { showMenu() }
        bar.addView(backBtn); bar.addView(fwdBtn)
        bar.addView(omnibox, LinearLayout.LayoutParams(0, dp(40), 1f))
        bar.addView(reload); bar.addView(menu)
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(48)))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(progressBar, LinearLayout.LayoutParams(-1, dp(3)))

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#19E68C")); textSize = 11f; setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundColor(Color.parseColor("#0B1114"))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        lockOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#05080A")); visibility = View.GONE; isClickable = true
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            col.addView(TextView(this@MainActivity).apply { text = "BLACKOUT ACTIVE\nApp locked, network stopped"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#19E68C")) })
            col.addView(flat("Unlock") { unlockApp() })
            addView(col, FrameLayout.LayoutParams(-1, -1))
        }
        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        frame.addView(lockOverlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(omnibox.windowToken, 0)
        omnibox.clearFocus()
    }

    private fun refreshChrome() {
        val t = active
        tabBar.removeAllViews()
        for (tab in tabs) {
            val b = TextView(this).apply {
                text = (if (tab.isPrivate) "◐ " else if (tab.profile?.startsWith("id-") == true) "◆ " else "") + tab.title.take(14)
                setTextColor(Color.parseColor(if (tab === t) "#D7EFE4" else "#6F8A80")); textSize = 12f
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0)
                setBackgroundColor(Color.parseColor(if (tab === t) (if (tab.isPrivate) "#10242B" else "#0F1B17") else "#161F23"))
                setOnClickListener { select(tab) }
                setOnLongClickListener { closeTab(tab); true }
            }
            tabBar.addView(b, LinearLayout.LayoutParams(-2, -1).apply { rightMargin = dp(2) })
        }
        if (t != null) {
            if (!omnibox.hasFocus()) omnibox.setText(t.url)
            backBtn.alpha = if (t.web.canGoBack()) 1f else .3f
            fwdBtn.alpha = if (t.web.canGoForward()) 1f else .3f
            progressBar.progress = t.progress
            progressBar.visibility = if (t.progress < 100) View.VISIBLE else View.GONE
            val https = if (t.url.startsWith("https://")) "HTTPS" else if (t.url.isEmpty()) "home" else "not HTTPS"
            status.text = "Blocked this tab: ${t.blocked} · total ${store.totalBlocked} · $https · ${netLabel()}" +
                if (t.isPrivate) " · private tab" else ""
        }
    }

    // ---------- Network mode (direct / SOCKS5 proxy such as Orbot) ----------
    private fun netLabel(): String {
        if (store.netMode == "direct") return "direct connection" + vpnLabel()
        if (store.netMode == "i2p") {
            val w = "${store.i2pHost}:${store.i2pPort}"
            return if (!proxyReady) "proxy not active: browsing blocked" else
                (if (torState == "reachable") "I2P proxy $w reachable (tunnels not checked)" else if (torState == "failed") "I2P proxy $w unreachable; fails closed" else "I2P proxy $w") + vpnLabel()
        }
        if (store.netMode == "tor") {
            if (torState == "starting") return "Tor starting... $torProgress%"
            if (torState == "error") return "Tor failed: $torError; browsing blocked"
        }
        val tgt = socksTarget()
        val where = "${tgt.first}:${tgt.second}"
        if (!proxyReady) return "proxy not active: browsing blocked"
        return when (torState) {
            "verified" -> (if (store.netMode == "tor") "Tor (built-in) verified" else "Tor verified via $where")
            "not-tor" -> "SOCKS proxy $where (NOT Tor)"
            "checking" -> "checking proxy $where..."
            "failed" -> "proxy check failed ($where); fails closed"
            else -> "SOCKS proxy $where (unverified)"
        } + vpnLabel()
    }

    private fun vpnLabel(): String = if (store.requireVpn || vpnActive) (if (vpnActive) " \u00B7 VPN active" else " \u00B7 NO VPN: blocked") else ""

    // ---------- VPN awareness (device VPN is run by another app; this app can require one) ----------
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        vpnActive = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
                val v = c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                runOnUiThread { vpnActive = v; if (store.requireVpn && !v) vpnLost(); refreshChrome() }
            }
            override fun onLost(n: Network) { runOnUiThread { vpnActive = false; if (store.requireVpn) vpnLost(); refreshChrome() } }
        })
    }

    private fun vpnLost() { tabs.forEach { it.web.stopLoading() }; toast("VPN not active: all requests blocked") }

    private fun torLike() = store.netMode == "socks" || store.netMode == "tor"

    private fun vpnBlocked() = store.requireVpn && !vpnActive

    /** Fail-closed: once a proxy mode is chosen, no direct rule is ever added, and go() refuses until the override is applied. */
    private fun applyNetworkMode() {
        proxyReady = false
        torState = ""
        if (store.netMode != "tor") stopBuiltinTor()
        val supported = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        if (store.netMode == "tor" && supported) { startBuiltinTor(); return }
        if (store.netMode == "direct") {
            if (!supported) { proxyReady = true; return }
            try { ProxyController.getInstance().clearProxyOverride(mainExec) { proxyReady = true; refreshChrome() } }
            catch (e: Exception) { proxyReady = true }
            return
        }
        if (!supported) {
            store.netMode = "direct"; proxyReady = true
            toast("This device's WebView cannot route through a proxy; staying on direct connection")
            return
        }
        try {
            val rule = if (store.netMode == "i2p") "http://${store.i2pHost}:${store.i2pPort}" else "socks5://${store.proxyHost}:${store.proxyPort}"
            val cfg = ProxyConfig.Builder().addProxyRule(rule).build()
            ProxyController.getInstance().setProxyOverride(cfg, mainExec) { proxyReady = true; verifyProxy(); refreshChrome() }
        } catch (e: Exception) {
            proxyReady = false // stay blocked rather than fall back to direct
            toast("Proxy setup failed: browsing disabled. Change network mode.")
        }
    }

    private fun verifyProxy() {
        if (store.netMode == "i2p") {
            val ih = store.i2pHost; val ip = store.i2pPort
            Thread {
                val ok = try { java.net.Socket().use { it.connect(InetSocketAddress(ih, ip), 3000) }; true } catch (e: Exception) { false }
                runOnUiThread { torState = if (ok) "reachable" else "failed"; refreshChrome() }
            }.start()
            return
        }
        torState = "checking"
        val tgt = socksTarget(); val host = tgt.first; val port = tgt.second
        Thread {
            val r = try {
                val c = URL("https://check.torproject.org/api/ip").openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))) as HttpsURLConnection
                c.connectTimeout = 15000; c.readTimeout = 20000
                val body = try { c.inputStream.bufferedReader().use { it.readText().take(2000) } } finally { c.disconnect() }
                if (JSONObject(body).optBoolean("IsTor")) "verified" else "not-tor"
            } catch (e: Exception) { "failed" }
            runOnUiThread { torState = r; refreshChrome() }
        }.start()
    }

    private fun proxyOrNull(): Proxy? =
        if (store.netMode != "socks" && store.netMode != "tor") null
        else socksTarget().let { Proxy(Proxy.Type.SOCKS, InetSocketAddress(it.first, it.second)) }

    private fun socksTarget(): Pair<String, Int> =
        if (store.netMode == "tor") Pair("127.0.0.1", torSocksPort) else Pair(store.proxyHost, store.proxyPort)

    // ---------- Built-in Tor (Guardian Project tor-android, reflection so a changed API fails at runtime, not build) ----------
    private fun torFail(msg: String) { torState = "error"; torError = msg; proxyReady = false; refreshChrome() }

    private fun startBuiltinTor() {
        torState = "starting"; torProgress = 0; torError = ""; torSocksPort = 0; torCtl = null
        refreshChrome()
        val cls = try { Class.forName("org.torproject.jni.TorService") } catch (e: Throwable) { torFail("Tor library missing"); return }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { Thread { pollTor(binder) }.start() }
            override fun onServiceDisconnected(name: ComponentName) {
                runOnUiThread { if (store.netMode == "tor") torFail("Tor service stopped") }
            }
        }
        torSvcConn = conn
        val ok = try { bindService(Intent(this, cls), conn, Context.BIND_AUTO_CREATE) } catch (e: Exception) { false }
        if (!ok) torFail("could not start Tor service")
    }

    private fun stopBuiltinTor() {
        torSvcConn?.let { try { unbindService(it) } catch (_: Exception) { } }
        torSvcConn = null; torCtl = null; torSocksPort = 0
        try { stopService(Intent(this, Class.forName("org.torproject.jni.TorService"))) } catch (_: Throwable) { }
    }

    private fun torControl(binder: IBinder): Any? = try {
        val m1 = binder.javaClass.getMethod("getService"); m1.isAccessible = true
        val svc = m1.invoke(binder)
        val m2 = svc?.javaClass?.getMethod("getTorControlConnection"); m2?.isAccessible = true
        m2?.invoke(svc)
    } catch (e: Exception) { null }

    private fun ctl(conn: Any, key: String): String? = try {
        val m = conn.javaClass.getMethod("getInfo", String::class.java); m.isAccessible = true
        m.invoke(conn, key) as? String
    } catch (e: Exception) { null }

    private fun pollTor(binder: IBinder) {
        val deadline = System.currentTimeMillis() + 240_000
        var conn: Any? = null
        while (System.currentTimeMillis() < deadline && store.netMode == "tor") {
            if (conn == null) conn = torControl(binder)
            if (conn != null) {
                val phase = ctl(conn, "status/bootstrap-phase") ?: ""
                val p = Regex("PROGRESS=(\\d+)").find(phase)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                runOnUiThread { torProgress = p; refreshChrome() }
                if (p >= 100) {
                    val l = ctl(conn, "net/listeners/socks") ?: ""
                    val port = Regex("127\\.0\\.0\\.1:(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("(\\d+)\\D*$").find(l)?.groupValues?.get(1)?.toIntOrNull()
                    if (port != null) { val c = conn; runOnUiThread { torCtl = c; torSocksPort = port; installTorProxy() }; return }
                }
            }
            try { Thread.sleep(1000) } catch (_: InterruptedException) { }
        }
        runOnUiThread { if (store.netMode == "tor") torFail("Tor did not finish starting") }
    }

    private fun installTorProxy() {
        try {
            val cfg = ProxyConfig.Builder().addProxyRule("socks5://127.0.0.1:$torSocksPort").build()
            ProxyController.getInstance().setProxyOverride(cfg, mainExec) { proxyReady = true; verifyProxy(); refreshChrome() }
        } catch (e: Exception) { torFail("proxy setup failed") }
    }

    private fun torNewIdentity() {
        val c = torCtl ?: run { toast("Built-in Tor is not running"); return }
        Thread {
            val ok = try { c.javaClass.getMethod("signal", String::class.java).invoke(c, "NEWNYM"); true } catch (e: Exception) { false }
            runOnUiThread { if (ok) { clearAll(); resetTabs(); toast("New Tor identity: tabs and data cleared") } else toast("Could not signal Tor") }
        }.start()
    }

    private fun showCircuits() {
        val c = torCtl ?: run { toast("Circuit data is only available in built-in Tor mode"); return }
        Thread {
            val txt = ctl(c, "circuit-status") ?: "(no data)"
            runOnUiThread { dlg().setTitle("Tor circuits (live from Tor)").setMessage(txt.take(3000)).setPositiveButton("OK", null).show() }
        }.start()
    }

    private fun setNetworkMode(mode: String, host: String, port: Int) {
        // Cookies and storage from one network mode must not be reused in another.
        clearAll()
        store.netMode = mode
        if (mode == "i2p") { store.i2pHost = host; store.i2pPort = port } else if (mode != "tor" && mode != "direct") { store.proxyHost = host; store.proxyPort = port }
        if (mode != "tor") stopBuiltinTor()
        resetTabs()
        applyNetworkMode()
    }

    private fun resetTabs() {
        val old = tabs.toList()
        tabs.clear(); content.removeAllViews(); active = null
        old.forEach { t ->
            t.web.destroy()
            if (t.isPrivate) t.profile?.let { try { ProfileStore.getInstance().deleteProfile(it) } catch (_: Exception) { } }
        }
        newTab(false)
    }

    private fun showNetworkMenu() {
        val items = arrayOf("Direct connection", "Tor (built-in, no Orbot needed)", "Tor via Orbot (127.0.0.1:9050)", "Custom SOCKS5 proxy...",
            "I2P via local router (127.0.0.1:4444)", "Re-check current proxy", "New Tor identity (built-in Tor)", "Show Tor circuits (built-in Tor)")
        dlg().setTitle("Network mode (switching clears all tabs and data)").setItems(items) { _, i ->
            when (i) {
                0 -> setNetworkMode("direct", store.proxyHost, store.proxyPort)
                1 -> setNetworkMode("tor", "", 0)
                2 -> setNetworkMode("socks", "127.0.0.1", 9050)
                3 -> {
                    val input = EditText(this).apply { hint = "host:port, e.g. 127.0.0.1:9050"; setSingleLine() }
                    dlg().setTitle("SOCKS5 proxy").setView(input).setPositiveButton("Use") { _, _ ->
                        val m = Regex("^([A-Za-z0-9.-]{1,253}):(\\d{1,5})$").matchEntire(input.text.toString().trim())
                        val port = m?.groupValues?.get(2)?.toInt() ?: 0
                        if (m == null || port !in 1..65535) toast("Invalid address") else setNetworkMode("socks", m.groupValues[1], port)
                    }.setNegativeButton("Cancel", null).show()
                }
                4 -> setNetworkMode("i2p", "127.0.0.1", 4444)
                5 -> if (store.netMode != "direct" && proxyReady) verifyProxy() else toast("No active proxy to check")
                6 -> torNewIdentity()
                7 -> showCircuits()
            }
        }.show()
    }

    // ---------- Tabs ----------
    private fun newTab(private: Boolean, loadHome: Boolean = true, identity: String? = null): Tab {
        val id = nextId++
        var profile: String? = null
        val web = WebView(this)
        if (identity != null && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            profile = "id-$identity"
            ProfileStore.getInstance().getOrCreateProfile(profile)
            WebViewCompat.setProfile(web, profile)
        } else if (private && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            profile = "private-$id"
            ProfileStore.getInstance().getOrCreateProfile(profile)
            WebViewCompat.setProfile(web, profile)
        }
        val tab = Tab(id, web, private, profile)
        configure(tab)
        tabs.add(tab)
        select(tab)
        if (loadHome) web.loadUrl(HOME)
        if (private && profile == null) {
            Toast.makeText(this, "This device's WebView lacks profile isolation: private tab shares cookies with normal tabs; they are cleared when it closes.", Toast.LENGTH_LONG).show()
        }
        return tab
    }

    private fun select(tab: Tab) {
        active = tab
        content.removeAllViews()
        content.addView(tab.web, FrameLayout.LayoutParams(-1, -1))
        omnibox.clearFocus()
        refreshChrome()
    }

    private fun closeTab(tab: Tab) {
        tabs.remove(tab)
        content.removeView(tab.web)
        tab.web.destroy()
        if (tab.isPrivate) {
            if (tab.profile != null) {
                try { ProfileStore.getInstance().deleteProfile(tab.profile) } catch (_: Exception) { }
            } else {
                CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData()
            }
        }
        if (tabs.isEmpty()) newTab(false) else select(tabs.last())
    }

    // ---------- Navigation ----------
    /** Returns an https URL, a search URL, or null if the input is not allowed. */
    private fun resolve(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s.length > 4096) return null
        if (store.netMode == "i2p") { // I2P mode only reaches .i2p destinations; no automatic clearnet fallback
            val full = if (s.contains("://")) s else "http://$s"
            val h = try { Uri.parse(full).host ?: "" } catch (e: Exception) { "" }
            return if (full.startsWith("http") && h.endsWith(".i2p")) full else null
        }
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(s) && !s.startsWith("http://") && !s.startsWith("https://") && !Regex("^[^\\s/]+:\\d+").containsMatchIn(s)) return null
        if (s.startsWith("https://")) return s
        if (s.startsWith("http://")) {
            val h = Uri.parse(s).host ?: ""
            return if (h.endsWith(".onion") && torLike()) s else "https://" + s.removePrefix("http://") // HTTPS-only: upgrade
        }
        val hostLike = !s.contains(' ') && Regex("^[^\\s/]+\\.[^\\s/]{2,}([/?#:].*)?$").containsMatchIn(s)
        if (hostLike) return if (s.substringBefore('/').endsWith(".onion") && torLike()) "http://$s" else "https://$s"
        return SEARCH_ENGINES.getValue(store.searchEngine) + Uri.encode(s)
    }

    private fun go(input: String) {
        if (vpnBlocked()) { toast("VPN required but none is active. Connect your VPN app first."); return }
        if (blackout) { toast("Blackout active: unlock first"); return }
        if (!proxyReady) { toast("Network mode is not active yet (or failed). Change network mode to retry."); return }
        val u = resolve(input) ?: run { Toast.makeText(this, "Blocked: only http(s) addresses are allowed", Toast.LENGTH_SHORT).show(); return }
        active?.web?.loadUrl(u, GPC)
    }

    // ---------- WebView ----------
    private fun configure(tab: Tab) {
        val w = tab.web
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false          // android_asset still works for the home page
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            setGeolocationEnabled(false)
            builtInZoomControls = true; displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false)
        if (store.fpLevel >= 2) {
            // Same reduction Chrome itself applies: generic device, major version only. Removing "; wv" blends with Chrome.
            w.settings.userAgentString = w.settings.userAgentString
                .replace(Regex("Android [^;)]+; [^)]*\\)"), "Android 10; K)")
                .replace(Regex("Chrome/(\\d+)\\.\\d+\\.\\d+\\.\\d+"), "Chrome/$1.0.0.0")
                .replace("; wv", "")
        }
        if (store.blockAds && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w, assets.open("cosmetic.js").bufferedReader().use { it.readText() }, setOf("*"))
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val js = "var __PC_LEVEL=${store.fpLevel};\n" + assets.open("fp.js").bufferedReader().use { it.readText() }
            WebViewCompat.addDocumentStartJavaScript(w, js, setOf("*"))
        }
        if (store.netMode != "direct" && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Best effort: WebRTC UDP is not carried by a SOCKS proxy, so remove the API in proxy mode.
            WebViewCompat.addDocumentStartJavaScript(w,
                "try{['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel'].forEach(function(n){Object.defineProperty(window,n,{value:undefined,configurable:false,writable:false})})}catch(e){}",
                setOf("*"))
        }
        w.setBackgroundColor(Color.parseColor("#05080A"))

        w.setDownloadListener { url, _, cd, mime, _ -> confirmDownload(url, cd, mime) }

        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val host = req.url.host ?: ""
                if (blackout || vpnBlocked() || (store.netMode == "i2p" && !host.endsWith(".i2p") && req.url.scheme != "file")) {
                    return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                if (store.blockAds && blocker.isBlocked(req.url.host)) {
                    runOnUiThread { tab.blocked++; store.totalBlocked = store.totalBlocked + 1; if (tab === active) refreshChrome() }
                    return WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (store.netMode == "i2p" && !(u.host ?: "").endsWith(".i2p")) return true
                return when (u.scheme) {
                    "https" -> if (store.netMode == "i2p") true else false
                    "http" -> {
                        val h = u.host ?: ""
                        if ((h.endsWith(".onion") && torLike()) || (h.endsWith(".i2p") && store.netMode == "i2p")) false
                        else { if (req.isForMainFrame) view.loadUrl(u.toString().replaceFirst("http://", "https://"), GPC); true }
                    }
                    else -> true // intent:, file:, javascript:, custom schemes are never followed
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                tab.url = if (url.startsWith(HOME)) "" else url
                tab.blocked = 0
                refreshChrome()
            }

            override fun onPageFinished(view: WebView, url: String) {
                tab.url = if (url.startsWith(HOME)) "" else url
                if (!tab.isPrivate && tab.profile?.startsWith("id-") != true && store.saveHistory && tab.url.startsWith("https://")) store.addHistory(view.title ?: "", tab.url)
                refreshChrome()
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel() // never proceed past certificate errors
                errorPage(view, "Connection not secure", "The site's certificate could not be verified, so the page was not loaded.")
            }

            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame && err.errorCode != ERROR_UNKNOWN) errorPage(view, "Can't load page", err.description.toString())
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) { tab.progress = p; if (tab === active) refreshChrome() }
            override fun onReceivedTitle(view: WebView, t: String?) { tab.title = if (tab.url.isEmpty()) "New Tab" else (t ?: tab.url); refreshChrome() }
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String, cb: GeolocationPermissions.Callback) { cb.invoke(origin, false, false) }
            override fun onCreateWindow(view: WebView, dialog: Boolean, userGesture: Boolean, msg: Message): Boolean {
                if (!userGesture) return false // pop-up blocked
                val t = newTabForPopup(tab.isPrivate)
                (msg.obj as WebView.WebViewTransport).webView = t.web
                msg.sendToTarget()
                return true
            }
        }
    }

    private fun newTabForPopup(private: Boolean): Tab {
        return newTab(private, loadHome = false)
    }

    private fun errorPage(view: WebView, title: String, msg: String) {
        val esc = { s: String -> s.replace("&", "&amp;").replace("<", "&lt;") }
        view.loadDataWithBaseURL(null,
            "<body style='background:#05080a;color:#d7efe4;font-family:sans-serif;padding:24px'><h2 style='color:#ff4d5e'>${esc(title)}</h2><p>${esc(msg)}</p></body>",
            "text/html", "utf-8", null)
    }

    // ---------- Downloads ----------
    private fun confirmDownload(url: String, cd: String?, mime: String?) {
        if (!url.startsWith("https://")) { Toast.makeText(this, "Download blocked: only HTTPS downloads are allowed", Toast.LENGTH_LONG).show(); return }
        val name = URLUtil.guessFileName(url, cd, mime)
        val ext = name.substringAfterLast('.', "").lowercase()
        val risky = ext in RISKY
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (risky) "Potentially dangerous file" else "Download file?")
            .setMessage("$name\n${Uri.parse(url).host}" + if (risky) "\n\nThis file type can run code or install software. Only continue if you trust the source." else "")
            .setPositiveButton(if (risky) "Download anyway" else "Download") { _, _ ->
                val req = DownloadManager.Request(Uri.parse(url)).setTitle(name).setMimeType(mime)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- Menus ----------
    private fun dlg() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun showMenu() {
        val items = arrayOf("New tab", "New private tab", "Bookmark this page", "Bookmarks", "History",
            "Privacy report", "Settings", "Network mode", "Update filter list", "Close tab", "Support development (donate)", "Identity Vault", "BLACKOUT (emergency lock)")
        dlg().setItems(items) { _, i ->
            when (i) {
                0 -> newTab(false)
                1 -> newTab(true)
                2 -> active?.let { if (it.url.startsWith("https://")) { store.addBookmark(it.title, it.url); toast("Bookmarked") } else toast("Nothing to bookmark") }
                3 -> showList("Bookmarks", store.bookmarks(), onClear = null, onRemove = { store.removeBookmark(it) })
                4 -> showList("History", store.history(), onClear = { store.clearHistory() }, onRemove = null)
                5 -> privacyReport()
                6 -> showSettings()
                7 -> showNetworkMenu()
                8 -> updateList()
                9 -> active?.let { closeTab(it) }
                10 -> showDonate()
                11 -> showIdentities()
                12 -> confirmBlackout()
            }
        }.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun showList(title: String, list: List<Entry>, onClear: (() -> Unit)?, onRemove: ((String) -> Unit)?) {
        if (list.isEmpty()) { toast("$title is empty"); return }
        val shown = list.take(100)
        val b = dlg().setTitle(title).setItems(shown.map { it.title.take(50) + "\n" + (Uri.parse(it.url).host ?: "") }.toTypedArray()) { _, i ->
            if (onRemove != null) {
                dlg().setTitle(shown[i].title.take(40)).setItems(arrayOf("Open", "Remove")) { _, j ->
                    if (j == 0) go(shown[i].url) else { onRemove(shown[i].url); toast("Removed") }
                }.show()
            } else go(shown[i].url)
        }
        if (onClear != null) b.setNegativeButton("Clear all") { _, _ -> onClear(); toast("Cleared") }
        b.show()
    }

    private fun showSettings() {
        val items = arrayOf(
            "Search engine: ${store.searchEngine}",
            "Ad/tracker blocking: ${if (store.blockAds) "on" else "off"}",
            "Save history: ${if (store.saveHistory) "on" else "off"}",
            "Clear all browsing data",
            "Fingerprint protection: ${listOf("", "Standard", "Strict", "Maximum")[store.fpLevel]}",
            "Require VPN (block all loading without one): ${if (store.requireVpn) "on" else "off"}",
            "Open Android VPN settings"
        )
        dlg().setTitle("Settings").setItems(items) { _, i ->
            when (i) {
                0 -> { val names = SEARCH_ENGINES.keys.toTypedArray()
                    dlg().setTitle("Search engine").setItems(names) { _, j -> store.searchEngine = names[j]; toast("Search: ${names[j]}") }.show() }
                1 -> { store.blockAds = !store.blockAds; toast("Blocking ${if (store.blockAds) "on" else "off"}") }
                2 -> { store.saveHistory = !store.saveHistory; toast("History ${if (store.saveHistory) "on" else "off"}") }
                3 -> clearAll()
                4 -> dlg().setTitle("Fingerprint protection").setItems(arrayOf(
                    "Standard: uniform CPU/memory values. Fewest site problems.",
                    "Strict: + generic user agent, en-US, canvas/WebGL/audio noise.",
                    "Maximum: + blank canvas, fixed screen, UTC time, 100 ms timer. Breaks some sites.")) { _, j ->
                    store.fpLevel = j + 1; resetTabs(); toast("Applied; tabs reset")
                }.show()
                5 -> { store.requireVpn = !store.requireVpn; toast("Require VPN ${if (store.requireVpn) "on" else "off"}"); refreshChrome() }
                6 -> try { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } catch (e: Exception) { toast("Cannot open VPN settings") }
            }
        }.show()
    }

    private fun clearAll() {
        tabs.forEach { it.web.clearCache(true); it.web.clearHistory(); it.web.clearFormData() }
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        store.clearHistory()
        toast("Browsing data cleared")
    }

    private fun updateList() {
        if (store.netMode == "i2p") { toast("Filter updates are not available in I2P mode (clearnet is blocked)"); return }
        val px = proxyOrNull()
        if (px != null && !proxyReady) { toast("Proxy not active"); return }
        toast("Downloading filter list (${if (px != null) "via proxy" else "direct connection"})...")
        Thread {
            val msg = try { "Filter list updated: ${blocker.update(px)} domains" } catch (e: Exception) { "Update failed: ${e.message}" }
            runOnUiThread { toast(msg) }
        }.start()
    }

    private fun privacyReport() {
        val t = active ?: return
        val host = Uri.parse(t.url).host ?: "(home)"
        val msg = """
            Site: $host
            Connection: ${if (t.url.startsWith("https://")) "HTTPS (certificate errors always refused)" else "n/a"}
            Blocked on this page: ${t.blocked}  (total ${store.totalBlocked})
            Filter list: ${blocker.size} domains, ${blocker.source}
            Third-party cookies: blocked
            Permissions (camera, mic, location): always denied
            Do Not Track / Global Privacy Control: sent on page loads you start (not on sub-requests)
            Network: ${netLabel()}
            WebRTC: ${if (store.netMode == "direct") "not restricted in direct mode" else if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) "disabled by script (best effort)" else "NOT blocked on this device: may leak your real IP"}
            Fingerprint protection: ${listOf("", "Standard", "Strict", "Maximum")[store.fpLevel]} (script-based, reduces but does not eliminate fingerprinting)
            VPN: ${if (vpnActive) "a device VPN is active" else "none detected"}${if (store.requireVpn) " (required)" else ""}
            Private tab isolation: ${if (!t.isPrivate) "n/a" else if (t.profile != null) "separate WebView profile" else "NOT isolated on this device"}

            Tor can run inside this app (built-in) or via Orbot / your own SOCKS5 proxy. This is a proxied Android WebView, not Tor Browser: it lacks Tor Browser's fingerprint uniformity and anonymity guarantees, so sites may still tell you apart from other Tor users. Cosmetic ad hiding uses a few generic selectors only. Not in this build: per-site permission prompts, reader mode, find in page, tab groups. In direct mode your IP address is visible to sites and your network sees which hosts you contact.
        """.trimIndent()
        dlg().setTitle("Privacy report").setMessage(msg).setPositiveButton("OK", null).show()
    }

    // ---------- Identity Vault (separate WebView profiles: cookies/storage/cache; NOT network anonymity) ----------
    private fun showIdentities() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) { toast("This device's WebView has no profile support"); return }
        val ids = store.identities()
        val items = ids.map { "Open: $it" } + listOf("Create identity...", "Delete identity...")
        dlg().setTitle("Identity Vault: same IP and fingerprint, separate cookies").setItems(items.toTypedArray()) { _, i ->
            when { i < ids.size -> newTab(false, true, ids[i]); i == ids.size -> createIdentity(); else -> deleteIdentity() }
        }.show()
    }

    private fun createIdentity() {
        val input = EditText(this).apply { hint = "name (letters, digits, _ -)"; setSingleLine() }
        dlg().setTitle("New identity").setView(input).setPositiveButton("Create") { _, _ ->
            val n = input.text.toString().trim()
            if (!Regex("^[A-Za-z0-9_-]{1,24}$").matches(n)) toast("Invalid name") else { store.addIdentity(n); newTab(false, true, n) }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun deleteIdentity() {
        val ids = store.identities()
        if (ids.isEmpty()) { toast("No identities"); return }
        dlg().setTitle("Delete identity and its data").setItems(ids.toTypedArray()) { _, i ->
            val n = ids[i]
            tabs.filter { it.profile == "id-$n" }.forEach { closeTab(it) }
            try { ProfileStore.getInstance().deleteProfile("id-$n") } catch (_: Exception) { }
            store.removeIdentity(n); toast("Deleted $n")
        }.show()
    }

    // ---------- Blackout protocol ----------
    private fun confirmBlackout() {
        val input = EditText(this).apply { hint = "Type BLACKOUT to confirm"; setSingleLine() }
        dlg().setTitle("BLACKOUT PROTOCOL")
            .setMessage("Closes all tabs and private sessions, clears cookies, cache, storage and history, stops Tor/proxy, blocks all network, and locks the app. Bookmarks are kept. Secure erasure from flash storage, OS caches, crash logs or external logs cannot be guaranteed.")
            .setView(input)
            .setPositiveButton("Activate") { _, _ -> if (input.text.toString().trim() == "BLACKOUT") blackoutNow() else toast("Not confirmed") }
            .setNegativeButton("Cancel", null).show()
    }

    private fun blackoutNow() {
        blackout = true
        proxyReady = false            // no direct fallback while locked
        clearAll()
        stopBuiltinTor()
        resetTabs()
        lockOverlay.visibility = View.VISIBLE
    }

    private fun unlockApp() {
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isDeviceSecure) {
            val i = km.createConfirmDeviceCredentialIntent("Unlock PARANOID CALLIDUS", "Confirm your screen lock")
            if (i != null) { startActivityForResult(i, 77); return }
        }
        finishUnlock()
    }

    private fun finishUnlock() { blackout = false; lockOverlay.visibility = View.GONE; applyNetworkMode(); refreshChrome() }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK) finishUnlock()
    }

    // ---------- Donations (M-PESA Paybill; payment happens in the user's own M-PESA/bank app) ----------
    private val PAYBILL = "522522"
    private val ACCOUNT = "1355643775"

    private fun showDonate() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        fun text(t: String, size: Float = 14f, color: String = "#D7EFE4") = TextView(this).apply {
            this.text = t; textSize = size; setTextColor(Color.parseColor(color)); setPadding(0, dp(4), 0, dp(4))
        }
        fun copyRow(label: String, value: String) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(text("$label\n$value", 16f, "#19E68C"), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(flat("Copy") {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
                toast("$label copied")
            })
            box.addView(row)
        }
        box.addView(text("Optional, voluntary support for development. Nothing is unlocked or tracked by donating.", 12f, "#6F8A80"))
        copyRow("M-PESA Paybill (Business no.)", PAYBILL)
        copyRow("Account no.", ACCOUNT)
        box.addView(text("1. Open M-PESA \u2192 Lipa na M-PESA \u2192 Pay Bill\n2. Business no. $PAYBILL\n3. Account no. $ACCOUNT\n4. Enter amount and your PIN\n5. Before confirming, check the name M-PESA shows for this paybill/account.", 13f))
        box.addView(flat("Open phone dialer with *334#") {
            try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode("*334#")))) } catch (e: Exception) { toast("Cannot open dialer") }
        })
        try {
            val bmp = assets.open("donate_qr.jpg").use { BitmapFactory.decodeStream(it) }
            val big = Bitmap.createScaledBitmap(bmp, bmp.width * 4, bmp.height * 4, false) // nearest-neighbour keeps the QR sharp
            box.addView(ImageView(this).apply { setImageBitmap(big); setBackgroundColor(Color.WHITE); adjustViewBounds = true },
                LinearLayout.LayoutParams(dp(220), dp(220)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })
            box.addView(text("QR code supplied by the developer; it is a bank-app QR, so scan it with that bank's app. If scanning fails, use the Paybill steps above.", 11f, "#6F8A80"))
        } catch (e: Exception) { }
        box.addView(text("This app never sees your PIN, phone number or payment; everything happens in your own M-PESA/bank app.", 11f, "#6F8A80"))
        dlg().setTitle("Support PARANOID CALLIDUS").setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Close", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val t = active
        when {
            t != null && t.web.canGoBack() -> t.web.goBack()
            tabs.size > 1 && t != null -> closeTab(t)
            else -> super.onBackPressed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = super.onKeyDown(keyCode, event)
}
