# PARANOID CALLIDUS for Android (v0.1.0)

Native Kotlin browser on Android's Chromium WebView.

## Get the APK
1. Create a GitHub repo, push this folder.
2. Actions tab -> "Build APK" -> open the run -> download artifact `paranoid-callidus-apk`.
3. Unzip, copy `app-debug.apk` to the phone, allow "install unknown apps" for your file manager, install.
(Debug-signed: fine for personal use; not Play Store ready.)

## What works (written, NOT yet compiled or run by the author)
Tabs, private tabs (separate WebView profile when supported), HTTPS-only upgrade, address/search bar with
DuckDuckGo/Brave/Startpage/Mojeek, history, bookmarks, download confirm with risky-type warning, ad/tracker host
blocking (starter list + Peter Lowe update), third-party cookies off, all permissions denied, cert errors never bypassed,
GPC/DNT headers on top-level loads, privacy report, decorative globe home.

## Not in this build
Tor, VPN, I2P, fingerprint protection, cosmetic filtering, per-site permissions, Identity Vault, Blackout.

## v0.1.1: network mode (Tor via Orbot / SOCKS5)
Menu -> Network mode. Install Orbot (Guardian Project), start it, choose "Tor via Orbot". Uses Android WebView proxy override
(socks5, remote DNS by the proxy), no direct rule is ever added so it fails closed. Verified against check.torproject.org and
shown in the status bar only when that check says IsTor=true. .onion http addresses are allowed in proxy mode only.
Switching mode clears all tabs and data. This is NOT Tor Browser; no fingerprint uniformity.

## v0.1.1: network mode (Tor via Orbot / SOCKS5)
Menu -> Network mode. Install Orbot (Guardian Project), start it, choose "Tor via Orbot". Uses the Android WebView proxy
override (socks5, DNS resolved by the proxy). No direct rule is ever added, so it fails closed. The status bar says "Tor verified"
only when check.torproject.org reports IsTor=true. .onion http addresses are allowed in proxy mode only.
Switching mode clears all tabs and data. This is NOT Tor Browser: no fingerprint uniformity.

## v0.2.0: fingerprint protection, I2P, VPN requirement
- Fingerprint protection (Settings): Standard / Strict / Maximum, script injected at document start in every frame (assets/fp.js).
  Standard: CPU/memory uniform. Strict: + Chrome-style reduced user agent, en-US, canvas/WebGL/audio noise (seeded).
  Maximum: + blank canvas readback, fixed screen, UTC time getters, 100 ms timer; breaks some sites.
  Logic tested in Node with mocks (navigator/screen/time/timer/audio); canvas/WebGL paths NOT tested. Accept-Language header,
  fonts, and TLS/network fingerprints are not changed. Detectable by determined sites; reduces, does not eliminate.
- I2P mode: browses .i2p only, through an I2P router you run yourself (HTTP proxy, default 127.0.0.1:4444). All clearnet requests are
  blocked (no automatic cross-network fallback). The app checks that the proxy port is reachable, not tunnel health.
- VPN: device-wide VPNs are run by a VPN app (WireGuard, etc.). "Require VPN" blocks all loading unless Android reports a VPN
  network, and stops loading the moment it drops. For a system-level kill switch use Android's Always-on VPN + "Block connections
  without VPN". This app does not contain a VPN client.
- Tor: still through Orbot / SOCKS5 proxy. Not bundled; not Tor Browser.

## v0.2.1: donations
Menu -> "Support development (donate)": shows Paybill 522522 / Account 1355643775 with copy buttons, steps, a dialer shortcut (*334#),
and the supplied QR (assets/donate_qr.jpg). Payment happens in the donor's own M-PESA/bank app. One-tap automatic payment (STK push)
would need a backend plus Safaricom Daraja credentials for the shortcode; not included.

## v0.3.0
- Built-in Tor (Menu -> Network mode -> "Tor (built-in)"): Guardian Project tor-android runs inside the app, no Orbot needed.
  Bootstrap % in the status bar, verified against check.torproject.org, "New Tor identity" (NEWNYM), live circuit list. Fails closed.
  Uses reflection against org.torproject.jni.TorService; if the library API differs, it reports "Tor failed" instead of crashing.
- Identity Vault (separate WebView profiles), Blackout protocol (type BLACKOUT; locks app, device-credential unlock), generic cosmetic ad hiding.
- Still not built: per-site permission prompts, reader mode, find in page, tab groups, WireGuard client (use a VPN app + Require VPN).

## v0.3.0
Built-in Tor (tor-android library; Network mode -> "Tor (built-in)"), live bootstrap %, Tor Project verification, New Tor identity,
live circuit list. Identity Vault (separate WebView profiles). Blackout protocol (type BLACKOUT; clears data, stops Tor, blocks
network, locks app until your screen lock confirms). Generic cosmetic ad hiding. Still not Tor Browser (no fingerprint uniformity).
Not built: per-site permission prompts, reader mode, find in page, tab groups.
