package io.github.thecodingdad.gamelodge.host

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import io.github.thecodingdad.gamelodge.data.companion.Fingerprint
import io.github.thecodingdad.gamelodge.util.Log
import io.nayuki.qrcodegen.QrCode
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.KeyStore
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** Whether the connection for launchers (the API server) runs, and switching it on (pairing needs it). */
/** Name and metadata provider keys of a standalone server, edited in the web admin. Keys are write-only. */
interface ServerSettingsForm {
    val name: String
    val steamGridDbKeySet: Boolean
    val igdbClientId: String?
    val igdbClientSecretSet: Boolean

    /** Null keeps a value, "" clears it. */
    fun save(name: String?, steamGridDbKey: String?, igdbClientId: String?, igdbClientSecret: String?)
}

interface PairingGate {
    val ready: Boolean
    fun fix()
}

/**
 * The web UI for pairing from another device (as the companion had it): HTTPS with the server's
 * certificate, one admin account ([AdminAuth]), only from private networks. Pages are plain HTML
 * forms (post, redirect, get); every form carries the session's CSRF token, and posts must come
 * from this very origin. On this PC the launcher's own settings page does the same without login.
 */
class WebAdmin(
    private val identity: ServerIdentity,
    private val auth: AdminAuth,
    private val devices: DeviceRegistry,
    private val pairing: PairingManager,
    private val gate: PairingGate? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Permissions a paired device can be given here (the metadata server only has metadata). */
    private val permissionChoices: List<String> = listOf(Permissions.METADATA, Permissions.HOST_GAMES, Permissions.HOST_GAMES_WRITE, Permissions.KEYS),
    /** Product name in titles and the menu (default: the launcher's). */
    private val product: String? = null,
    /** Server name and provider keys, edited here (metadata server); none in the launcher. */
    private val serverSettings: ServerSettingsForm? = null,
    /** Also answer public addresses (metadata server behind a reverse proxy; off by default). */
    private val allowPublic: Boolean = false,
) {
    private class Session(val id: String, val version: Int, val issued: Long, var seen: Long, val csrf: String) {
        var flash: String? = null
        var flashError = false
        var totpSecret: ByteArray? = null
        var recovery: List<String>? = null
    }

    private class Request(val ex: HttpExchange, val lang: Locale, val address: String, val session: Session?, val form: Map<String, String>) {
        operator fun get(name: String) = form[name]
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    /** One-time sign-in links for this PC (token → valid until). */
    private val localTokens = ConcurrentHashMap<String, Long>()
    private var server: HttpsServer? = null
    private val random = SecureRandom()

    val port: Int? get() = server?.address?.port

    fun start(port: Int) {
        if (server != null) return
        val keys = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("server", identity.privateKey, CharArray(0), arrayOf(identity.certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, CharArray(0)) }
        val ssl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
        server = HttpsServer.create(InetSocketAddress(port), 0).apply {
            httpsConfigurator = object : HttpsConfigurator(ssl) {
                override fun configure(params: HttpsParameters) {
                    params.setSSLParameters(ssl.defaultSSLParameters.apply { protocols = arrayOf("TLSv1.3", "TLSv1.2") })
                }
            }
            createContext("/") { ex ->
                runCatching { handle(ex) }.onFailure {
                    Log.w("Web UI request failed", it)
                    runCatching { ex.sendResponseHeaders(500, -1) }
                    ex.close()
                }
            }
            executor = Executors.newFixedThreadPool(4) { r -> Thread(r, "web-ui").apply { isDaemon = true } }
            start()
        }
        Log.i("Web UI listening on port $port")
    }

    /**
     * Address that opens the web UI signed in on this PC (tray, settings): valid once, for two
     * minutes, only from this PC itself. Null while the web UI is off.
     */
    fun localLoginUrl(): String? {
        val port = port ?: return null
        val now = clock()
        localTokens.entries.removeIf { it.value < now }
        val token = token()
        localTokens[token] = now + LOCAL_LOGIN_MS
        return "https://127.0.0.1:$port/auth/local?token=$token"
    }

    fun stop() {
        server?.stop(0)
        server = null
        sessions.clear()
    }

    private fun handle(ex: HttpExchange) {
        val h = ex.responseHeaders
        h["X-Frame-Options"] = listOf("DENY")
        h["X-Content-Type-Options"] = listOf("nosniff")
        // Not "no-referrer": with it browsers send "Origin: null" on form posts and the origin check fails.
        h["Referrer-Policy"] = listOf("same-origin")
        h["Cross-Origin-Opener-Policy"] = listOf("same-origin")
        h["Permissions-Policy"] = listOf("camera=(), microphone=(), geolocation=()")
        h["Content-Security-Policy"] = listOf(
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; " +
                "frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'",
        )
        h["Strict-Transport-Security"] = listOf("max-age=31536000")
        h["Cache-Control"] = listOf("no-store")

        val remote = ex.remoteAddress?.address
        if (remote == null || (!allowPublic && !isPrivate(remote))) {
            Log.w("Web UI request from public address refused")
            return status(ex, 403)
        }
        val address = remote.hostAddress
        val path = ex.requestURI.rawPath
        when (path) {
            "/app.css" -> return asset(ex, "app.css", "text/css; charset=utf-8")
            "/app.js" -> return asset(ex, "app.js", "text/javascript; charset=utf-8")
            "/icon.svg" -> return asset(ex, "icon.svg", "image/svg+xml")
        }
        val post = ex.requestMethod == "POST"
        if (!post && ex.requestMethod != "GET") return status(ex, 405)
        // Forms only from pages of this origin (browsers send Origin on every POST).
        if (post && !sameOrigin(ex)) return status(ex, 403)
        val form = if (post) readForm(ex) ?: return status(ex, 413) else query(ex)
        val session = session(ex)
        val r = Request(ex, language(ex), address, session, form)

        when (path) {
            "/setup" -> return setup(r)
            "/login" -> return login(r)
            "/auth/local" -> {
                // One-time link from the launcher on this PC: signs in without a password.
                val until = form["token"]?.let { localTokens.remove(it) }
                return if (!post && remote.isLoopbackAddress && until != null && until >= clock()) signIn(ex) else redirect(ex, "/login")
            }
        }
        if (session == null) return redirect(ex, if (auth.needsSetup) "/setup" else "/login")
        if (post && !constantEquals(form["csrf"], session.csrf)) return status(ex, 403)
        when {
            path == "/" -> redirect(ex, "/pairing")
            path == "/logout" && post -> {
                sessions.remove(session.id)
                ex.responseHeaders.add("Set-Cookie", "$COOKIE=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict")
                redirect(ex, "/login")
            }
            path == "/pairing" && !post -> pairingPage(r)
            path == "/pairing/status" && !post -> text(ex, pairingSignature())
            path == "/pairing/open" && post -> {
                pairing.open()
                redirect(ex, "/pairing")
            }
            path == "/pairing/close" && post -> {
                pairing.close()
                redirect(ex, "/pairing")
            }
            path == "/pairing/pin" && post -> {
                val ok = pairing.enterPin(form["request"].orEmpty(), form["pin"].orEmpty().filter(Char::isDigit))
                flash(session, if (ok) "pairing_pin_sent" else "pairing_pin_invalid", error = !ok)
                redirect(ex, "/pairing")
            }
            path == "/server/on" && post -> {
                gate?.fix()
                redirect(ex, "/pairing")
            }
            path == "/devices" && !post -> devicesPage(r)
            path == "/devices/rename" && post -> {
                val name = form["name"].orEmpty().trim()
                if (name.isNotEmpty()) devices.rename(form["id"].orEmpty(), name.take(64))
                redirect(ex, "/devices")
            }
            path == "/devices/remove" && post -> {
                devices.remove(form["id"].orEmpty())
                redirect(ex, "/devices")
            }
            path == "/devices/permissions" && post -> {
                devices.setPermissions(form["id"].orEmpty(), permissionChoices.filter { form["perm_$it"] == "on" }.toSet())
                flash(session, "saved")
                redirect(ex, "/devices")
            }
            path == "/server" && !post && serverSettings != null -> serverPage(r, serverSettings)
            path == "/server/save" && post && serverSettings != null -> {
                // Empty key fields keep the stored key; the checkboxes clear them.
                fun key(field: String) = if (form["clear_$field"] == "on") "" else form[field]?.trim()?.ifEmpty { null }
                serverSettings.save(form["name"]?.trim()?.ifEmpty { null }, key("sgdb"), form["igdb_id"]?.trim(), key("igdb_secret"))
                flash(session, "saved")
                redirect(ex, "/server")
            }
            path == "/security" && !post -> securityPage(r)
            path == "/security/password" && post -> {
                val error = auth.changePassword(form["current"], form["password"], form["confirm"], address)
                if (error == null) {
                    // All sessions end with the new account version; sign in again.
                    sessions.clear()
                    redirect(ex, "/login?changed=1")
                } else {
                    flash(session, error, error = true)
                    redirect(ex, "/security")
                }
            }
            path == "/security/totp/begin" && post -> {
                session.totpSecret = Totp.newSecret()
                redirect(ex, "/security")
            }
            path == "/security/totp/cancel" && post -> {
                session.totpSecret = null
                session.recovery = null
                redirect(ex, "/security")
            }
            path == "/security/totp/enable" && post -> {
                val secret = session.totpSecret
                val codes = secret?.let { auth.enableTotp(it, form["code"], form["password"], address) }
                if (codes == null) {
                    flash(session, "totp_failed", error = true)
                } else {
                    session.totpSecret = null
                    session.recovery = codes
                }
                redirect(ex, "/security")
            }
            path == "/security/totp/disable" && post -> {
                if (!auth.disableTotp(form["password"], address)) flash(session, "password_wrong", error = true)
                redirect(ex, "/security")
            }
            else -> page(r, t(r, "not_found"), null) { append("<h1>").append(e(t(r, "not_found"))).append("</h1>") }
        }
    }

    // ---- Account ----

    private fun setup(r: Request) {
        if (!auth.needsSetup) return redirect(r.ex, "/login")
        var error: String? = null
        if (r.ex.requestMethod == "POST") {
            error = auth.setup(r["code"], r["password"], r["confirm"], r.address)
            if (error == null) return signIn(r.ex)
        }
        page(r, t(r, "setup_title"), null, narrow = true) {
            append("<h1>").append(e(t(r, "setup_title"))).append("</h1><p>").append(e(t(r, "setup_intro"))).append("</p>")
            error?.let { append("<p class=\"error\" role=\"alert\">").append(e(t(r, it, AdminAuth.MIN_PASSWORD))).append("</p>") }
            append("<form method=\"post\" action=\"/setup\">")
            field(t(r, "setup_code"), "code", "text", "off", autofocus = true, placeholder = "XXXX-XXXX-XXXX")
            field(t(r, "new_password"), "password", "password", "new-password")
            field(t(r, "confirm_password"), "confirm", "password", "new-password")
            append("<p class=\"hint\">").append(e(t(r, "password_rules", AdminAuth.MIN_PASSWORD))).append("</p>")
            append("<button type=\"submit\" class=\"primary\">").append(e(t(r, "setup_save"))).append("</button></form>")
        }
    }

    private fun login(r: Request) {
        if (auth.needsSetup) return redirect(r.ex, "/setup")
        var error: String? = null
        if (r.ex.requestMethod == "POST") {
            val (outcome, retry) = auth.login(r["password"], r["code"], r.address)
            when (outcome) {
                LoginOutcome.OK -> return signIn(r.ex)
                LoginOutcome.CODE_REQUIRED -> error = t(r, "totp_required")
                LoginOutcome.THROTTLED -> error = t(r, "throttled", (retry + 999) / 1000)
                LoginOutcome.INVALID -> error = t(r, "login_failed")
            }
        } else if (r["changed"] == "1") {
            error = t(r, "password_changed")
        }
        page(r, t(r, "login_title"), null, narrow = true) {
            append("<h1>").append(e(t(r, "login_title"))).append("</h1>")
            error?.let { append("<p class=\"error\" role=\"alert\">").append(e(it)).append("</p>") }
            append("<form method=\"post\" action=\"/login\">")
            field(t(r, "password"), "password", "password", "current-password", autofocus = true)
            if (auth.totpEnabled) {
                field(t(r, "totp_code"), "code", "text", "one-time-code", numeric = true)
                append("<p class=\"hint\">").append(e(t(r, "totp_code_hint"))).append("</p>")
            }
            append("<button type=\"submit\" class=\"primary\">").append(e(t(r, "login"))).append("</button></form>")
        }
    }

    private fun signIn(ex: HttpExchange) {
        val now = clock()
        val session = Session(token(), auth.sessionVersion, now, now, token())
        sessions.values.removeIf { !current(it, now) }
        sessions[session.id] = session
        // Session cookie: gone with the browser, never sent to other sites or scripts.
        ex.responseHeaders.add("Set-Cookie", "$COOKIE=${session.id}; Path=/; Secure; HttpOnly; SameSite=Strict")
        redirect(ex, "/pairing")
    }

    private fun session(ex: HttpExchange): Session? {
        val id = ex.requestHeaders["Cookie"].orEmpty().flatMap { it.split(';') }.map { it.trim() }
            .firstOrNull { it.startsWith("$COOKIE=") }?.substringAfter('=') ?: return null
        val session = sessions[id] ?: return null
        val now = clock()
        if (!current(session, now)) {
            sessions.remove(id)
            return null
        }
        session.seen = now
        return session
    }

    /** Same account version (no password change or reset since), not idle too long, not too old. */
    private fun current(s: Session, now: Long) =
        s.version == auth.sessionVersion && now - s.seen < IDLE_TIMEOUT_MS && now - s.issued < MAX_SESSION_MS

    // ---- Pages ----

    private fun pairingPage(r: Request) {
        pairing.refresh()
        val status = pairing.status.value
        val now = clock()
        page(r, t(r, "nav_pairing"), "pairing", pairingSig = pairingSignature()) {
            append("<h1>").append(e(t(r, "nav_pairing"))).append("</h1><p>").append(e(t(r, "pairing_intro"))).append("</p>")
            flash(r)
            if (gate != null && !gate.ready) {
                append("<section class=\"card\"><p class=\"error\">").append(e(t(r, "server_off"))).append("</p>")
                postButton(r, "/server/on", t(r, "server_turn_on"), "primary")
                append("</section>")
            }
            append("<section class=\"card\">")
            val locked = status.lockedUntil
            val open = status.openUntil
            when {
                locked != null -> append("<p class=\"error\">").append(e(t(r, "pairing_locked_prefix")))
                    .append(" <span data-left=\"").append(locked - now).append("\"></span></p>")
                open != null -> {
                    append("<p class=\"status ok\">").append(e(t(r, "pairing_open_prefix")))
                        .append(" <span data-left=\"").append(open - now).append("\"></span></p>")
                    postButton(r, "/pairing/close", t(r, "pairing_close"), "secondary")
                }
                else -> postButton(r, "/pairing/open", t(r, "pairing_start"), "primary")
            }
            append("<p class=\"hint\">").append(e(t(r, "server_fingerprint"))).append(": <code>")
                .append(e(Fingerprint.pretty(identity.fingerprint))).append("</code></p></section>")
            if (status.requests.isNotEmpty()) {
                append("<h2>").append(e(t(r, "pairing_requests"))).append("</h2>")
                for (req in status.requests) {
                    append("<section class=\"card\"><div class=\"row\"><div><strong>").append(e(req.deviceName))
                        .append("</strong><div class=\"hint\">").append(e(req.address.orEmpty())).append("</div></div>")
                    if (req.state == PairingState.WAITING_FOR_PIN) {
                        append("<form class=\"pin\" method=\"post\" action=\"/pairing/pin\">")
                        csrf(r)
                        append("<input type=\"hidden\" name=\"request\" value=\"").append(e(req.id)).append("\">")
                        append("<input name=\"pin\" inputmode=\"numeric\" maxlength=\"6\" autocomplete=\"off\" placeholder=\"000000\" aria-label=\"PIN\">")
                        append("<button type=\"submit\" class=\"primary\">").append(e(t(r, "pairing_confirm"))).append("</button></form>")
                    } else {
                        append("<span class=\"status\">").append(e(t(r, "pairing_running"))).append("</span>")
                    }
                    append("</div></section>")
                }
            }
        }
    }

    private fun devicesPage(r: Request) {
        val list = devices.devices.value
        val renaming = r["rename"]
        val removing = r["remove"]
        val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(r.lang).withZone(ZoneId.systemDefault())
        page(r, t(r, "nav_devices"), "devices") {
            append("<h1>").append(e(t(r, "nav_devices"))).append("</h1><p>").append(e(t(r, "devices_intro"))).append("</p>")
            flash(r)
            if (list.isEmpty()) append("<p class=\"hint\">").append(e(t(r, "devices_none"))).append("</p>")
            for (d in list) {
                append("<section class=\"card\"><div class=\"row\">")
                if (renaming == d.id) {
                    append("<form class=\"inline\" method=\"post\" action=\"/devices/rename\">")
                    csrf(r)
                    append("<input type=\"hidden\" name=\"id\" value=\"").append(e(d.id)).append("\">")
                    append("<input name=\"name\" maxlength=\"64\" data-autofocus value=\"").append(e(d.name))
                        .append("\" aria-label=\"").append(e(t(r, "device_name"))).append("\">")
                    append("<button type=\"submit\" class=\"primary\">").append(e(t(r, "save"))).append("</button>")
                    append("<a class=\"button secondary\" href=\"/devices\">").append(e(t(r, "cancel"))).append("</a></form>")
                } else {
                    append("<div><strong>").append(e(d.name)).append("</strong><div class=\"hint\">")
                        .append(e(t(r, "device_paired", time.format(Instant.ofEpochSecond(d.paired)))))
                    d.lastSeen?.let { seen ->
                        append(" · ").append(e(t(r, "device_seen", time.format(Instant.ofEpochSecond(seen)), d.lastAddress.orEmpty())))
                    }
                    append("</div></div><div class=\"actions\">")
                    append("<a class=\"button secondary\" href=\"/devices?rename=").append(e(d.id)).append("\">").append(e(t(r, "rename"))).append("</a>")
                    if (removing == d.id) {
                        postButton(r, "/devices/remove", t(r, "remove_confirm"), "danger", "id" to d.id)
                        append("<a class=\"button secondary\" href=\"/devices\">").append(e(t(r, "cancel"))).append("</a>")
                    } else {
                        append("<a class=\"button danger\" href=\"/devices?remove=").append(e(d.id)).append("\">").append(e(t(r, "remove"))).append("</a>")
                    }
                    append("</div>")
                }
                append("</div>")
                append("<form method=\"post\" action=\"/devices/permissions\"><div class=\"perms\">")
                csrf(r)
                append("<input type=\"hidden\" name=\"id\" value=\"").append(e(d.id)).append("\">")
                for (p in permissionChoices) {
                    append("<label class=\"check\"><input type=\"checkbox\" name=\"perm_").append(e(p)).append("\"")
                    if (d.may(p)) append(" checked")
                    append("> ").append(e(t(r, "perm_" + p.replace('.', '_')))).append("</label>")
                }
                append("<button type=\"submit\" class=\"secondary\">").append(e(t(r, "save"))).append("</button></div></form>")
                append("<div class=\"hint mono\">").append(e(Fingerprint.pretty(d.fingerprint))).append("</div></section>")
            }
        }
    }

    private fun serverPage(r: Request, settings: ServerSettingsForm) {
        page(r, t(r, "nav_server"), "server") {
            append("<h1>").append(e(t(r, "nav_server"))).append("</h1><p>").append(e(t(r, "server_intro"))).append("</p>")
            flash(r)
            append("<section class=\"card\"><form method=\"post\" action=\"/server/save\">")
            csrf(r)
            append("<label>").append(e(t(r, "server_name"))).append("<input name=\"name\" maxlength=\"64\" value=\"").append(e(settings.name)).append("\"></label>")
            val stored = t(r, "key_stored")
            append("<h2>SteamGridDB</h2>")
            guide(r, "sgdb", "https://www.steamgriddb.com/profile/preferences/api", 4)
            field(t(r, "sgdb_key"), "sgdb", "password", "off", placeholder = if (settings.steamGridDbKeySet) stored else null)
            if (settings.steamGridDbKeySet) append("<label class=\"check\"><input type=\"checkbox\" name=\"clear_sgdb\"> ").append(e(t(r, "key_clear"))).append("</label>")
            append("<h2>IGDB</h2>")
            guide(r, "igdb", "https://dev.twitch.tv/console/apps", 5)
            append("<label>").append(e(t(r, "igdb_client_id"))).append("<input name=\"igdb_id\" autocomplete=\"off\" value=\"").append(e(settings.igdbClientId.orEmpty())).append("\"></label>")
            field(t(r, "igdb_client_secret"), "igdb_secret", "password", "off", placeholder = if (settings.igdbClientSecretSet) stored else null)
            if (settings.igdbClientSecretSet) append("<label class=\"check\"><input type=\"checkbox\" name=\"clear_igdb_secret\"> ").append(e(t(r, "key_clear"))).append("</label>")
            append("<p class=\"hint\">").append(e(t(r, "keys_hint"))).append("</p>")
            append("<button type=\"submit\" class=\"primary\">").append(e(t(r, "save"))).append("</button></form></section>")
        }
    }

    /** How to get a provider's key, step by step, with a link to its page (opens in a new tab). */
    private fun StringBuilder.guide(r: Request, provider: String, url: String, steps: Int) {
        append("<details class=\"guide\"><summary>").append(e(t(r, "guide_$provider"))).append("</summary><ol>")
        for (i in 1..steps) append("<li>").append(e(t(r, "guide_${provider}_$i"))).append("</li>")
        append("</ol><p><a href=\"").append(e(url)).append("\" target=\"_blank\" rel=\"noopener noreferrer\">")
            .append(e(t(r, "guide_open"))).append("</a></p></details>")
    }

    private fun securityPage(r: Request) {
        val s = r.session!!
        page(r, t(r, "nav_security"), "security") {
            append("<h1>").append(e(t(r, "nav_security"))).append("</h1>")
            flash(r)
            append("<section class=\"card\"><h2>").append(e(t(r, "change_password"))).append("</h2>")
            append("<form method=\"post\" action=\"/security/password\">")
            csrf(r)
            field(t(r, "current_password"), "current", "password", "current-password")
            field(t(r, "new_password"), "password", "password", "new-password")
            field(t(r, "confirm_password"), "confirm", "password", "new-password")
            append("<p class=\"hint\">").append(e(t(r, "password_rules", AdminAuth.MIN_PASSWORD))).append("</p>")
            append("<button type=\"submit\" class=\"primary\">").append(e(t(r, "save"))).append("</button></form></section>")

            append("<section class=\"card\"><h2>").append(e(t(r, "totp_title"))).append("</h2>")
            val recovery = s.recovery
            val secret = s.totpSecret
            when {
                recovery != null -> {
                    append("<p class=\"status ok\">").append(e(t(r, "totp_enabled_now"))).append("</p><p>")
                        .append(e(t(r, "recovery_codes_intro"))).append("</p><ul class=\"codes\">")
                    recovery.forEach { append("<li><code>").append(e(it)).append("</code></li>") }
                    append("</ul>")
                    postButton(r, "/security/totp/cancel", t(r, "done"), "primary")
                }
                auth.totpEnabled -> {
                    append("<p class=\"status ok\">").append(e(t(r, "totp_on", auth.recoveryCodesLeft))).append("</p>")
                    append("<form method=\"post\" action=\"/security/totp/disable\">")
                    csrf(r)
                    field(t(r, "current_password"), "password", "password", "current-password")
                    append("<button type=\"submit\" class=\"danger\">").append(e(t(r, "totp_disable"))).append("</button></form>")
                }
                secret != null -> {
                    append("<p>").append(e(t(r, "totp_scan"))).append("</p><div class=\"qr\">")
                        .append(qrSvg(Totp.uri(secret, t(r, "product"), "admin"))).append("</div>")
                    append("<p class=\"hint\">").append(e(t(r, "totp_manual"))).append(": <code>").append(Totp.base32(secret)).append("</code></p>")
                    append("<form method=\"post\" action=\"/security/totp/enable\">")
                    csrf(r)
                    field(t(r, "totp_code"), "code", "text", "one-time-code", numeric = true)
                    field(t(r, "current_password"), "password", "password", "current-password")
                    append("<div class=\"actions\"><button type=\"submit\" class=\"primary\">").append(e(t(r, "totp_enable"))).append("</button>")
                    append("<button type=\"submit\" class=\"secondary\" formaction=\"/security/totp/cancel\">").append(e(t(r, "cancel"))).append("</button></div></form>")
                }
                else -> {
                    append("<p>").append(e(t(r, "totp_intro"))).append("</p>")
                    postButton(r, "/security/totp/begin", t(r, "totp_setup"), "primary")
                }
            }
            append("</section>")
            append("<section class=\"card\"><h2>").append(e(t(r, "sessions_title"))).append("</h2><p class=\"hint\">")
                .append(e(t(r, "sessions_hint", IDLE_TIMEOUT_MS / 60_000, MAX_SESSION_MS / 3_600_000))).append("</p></section>")
        }
    }

    /** Changes whenever the pairing page would look different (the page reloads then). */
    private fun pairingSignature(): String {
        pairing.refresh()
        val s = pairing.status.value
        return listOf(s.openUntil, s.lockedUntil, gate?.ready, s.requests.joinToString { "${it.id}:${it.state}" }).joinToString("|").hashCode().toString(16)
    }

    // ---- HTML ----

    private inline fun page(r: Request, title: String, nav: String?, narrow: Boolean = false, pairingSig: String? = null, body: StringBuilder.() -> Unit) {
        val sb = StringBuilder(4096)
        sb.append("<!doctype html><html lang=\"").append(r.lang.language).append("\"><head><meta charset=\"utf-8\">")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>").append(e(title)).append(" · ")
            .append(e(t(r, "product"))).append("</title><link rel=\"stylesheet\" href=\"/app.css\"><link rel=\"icon\" href=\"/icon.svg\">")
            .append("<script src=\"/app.js\" defer></script></head><body")
        pairingSig?.let { sb.append(" data-pairing=\"").append(it).append("\"") }
        sb.append(">")
        if (r.session != null && nav != null) {
            sb.append("<div class=\"shell\"><nav class=\"side\"><div class=\"brand\"><img src=\"/icon.svg\" alt=\"\"><span>")
                .append(e(t(r, "product"))).append("</span></div>")
            val items = listOfNotNull(
                Triple("pairing", "nav_pairing", "⇄"), Triple("devices", "nav_devices", "▣"),
                if (serverSettings != null) Triple("server", "nav_server", "⚙") else null, Triple("security", "nav_security", "✱"),
            )
            for ((href, key, icon) in items) {
                sb.append("<a class=\"nav-item").append(if (href == nav) " active" else "").append("\" href=\"/").append(href)
                    .append("\"><span class=\"icon\">").append(icon).append("</span><span>").append(e(t(r, key))).append("</span></a>")
            }
            sb.append("<form method=\"post\" action=\"/logout\" class=\"logout\">")
            sb.csrf(r)
            sb.append("<button type=\"submit\" class=\"link\">").append(e(t(r, "logout"))).append("</button></form></nav><main class=\"content\">")
            sb.body()
            sb.append("</main></div>")
        } else {
            sb.append("<div class=\"center\"><div class=\"card").append(if (narrow) " narrow" else "").append("\">")
            sb.body()
            sb.append("</div></div>")
        }
        sb.append("</body></html>")
        html(r.ex, sb.toString())
    }

    private fun StringBuilder.csrf(r: Request) {
        r.session?.let { append("<input type=\"hidden\" name=\"csrf\" value=\"").append(it.csrf).append("\">") }
    }

    private fun StringBuilder.postButton(r: Request, action: String, label: String, style: String, vararg hidden: Pair<String, String>) {
        append("<form method=\"post\" action=\"").append(action).append("\" class=\"inline\">")
        csrf(r)
        hidden.forEach { (k, v) -> append("<input type=\"hidden\" name=\"").append(e(k)).append("\" value=\"").append(e(v)).append("\">") }
        append("<button type=\"submit\" class=\"").append(style).append("\">").append(e(label)).append("</button></form>")
    }

    private fun StringBuilder.field(label: String, name: String, type: String, autocomplete: String, autofocus: Boolean = false, numeric: Boolean = false, placeholder: String? = null) {
        append("<label>").append(e(label)).append("<input name=\"").append(name).append("\" type=\"").append(type)
            .append("\" autocomplete=\"").append(autocomplete).append("\"")
        if (numeric) append(" inputmode=\"numeric\"")
        if (autofocus) append(" autofocus")
        placeholder?.let { append(" placeholder=\"").append(e(it)).append("\"") }
        append("></label>")
    }

    private fun StringBuilder.flash(r: Request) {
        val s = r.session ?: return
        val key = s.flash ?: return
        append("<p class=\"").append(if (s.flashError) "error" else "status ok").append("\" role=\"status\">")
            .append(e(t(r, key, AdminAuth.MIN_PASSWORD))).append("</p>")
        s.flash = null
    }

    private fun flash(s: Session, key: String, error: Boolean = false) {
        s.flash = key
        s.flashError = error
    }

    // ---- HTTP ----

    private fun sameOrigin(ex: HttpExchange): Boolean {
        val host = ex.requestHeaders.getFirst("Host") ?: return false
        val origin = ex.requestHeaders.getFirst("Origin")
            ?: ex.requestHeaders.getFirst("Referer")?.let { ref -> runCatching { java.net.URI(ref).let { "${it.scheme}://${it.rawAuthority}" } }.getOrNull() }
            ?: return false
        return origin.equals("https://$host", ignoreCase = true)
    }

    private fun readForm(ex: HttpExchange): Map<String, String>? {
        val data = ex.requestBody.use { it.readNBytes(MAX_BODY + 1) }
        if (data.size > MAX_BODY) return null
        return decode(String(data, Charsets.UTF_8))
    }

    private fun query(ex: HttpExchange) = decode(ex.requestURI.rawQuery.orEmpty())

    private fun decode(text: String): Map<String, String> = text.split('&').filter { it.isNotEmpty() }.mapNotNull {
        val (k, v) = (it.split('=', limit = 2) + "").take(2)
        runCatching { URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8") }.getOrNull()
    }.toMap()

    private fun language(ex: HttpExchange): Locale {
        val accept = ex.requestHeaders.getFirst("Accept-Language").orEmpty()
        val first = accept.split(',').map { it.substringBefore(';').trim().lowercase() }.firstOrNull { it.startsWith("de") || it.startsWith("en") }
        return if (first?.startsWith("de") == true) Locale.GERMAN else Locale.ENGLISH
    }

    private fun t(r: Request, key: String, vararg args: Any): String {
        if (key == "product" && product != null) return product
        val bundle = ResourceBundle.getBundle("web.strings", r.lang, ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES))
        var text = if (bundle.containsKey(key)) bundle.getString(key) else key
        args.forEachIndexed { i, a -> text = text.replace("{$i}", a.toString()) }
        return text
    }

    private fun asset(ex: HttpExchange, name: String, type: String) {
        val data = WebAdmin::class.java.getResourceAsStream("/web/$name")?.use { it.readBytes() } ?: return status(ex, 404)
        ex.responseHeaders["Cache-Control"] = listOf("public, max-age=3600")
        send(ex, 200, data, type)
    }

    private fun html(ex: HttpExchange, html: String) = send(ex, 200, html.toByteArray(), "text/html; charset=utf-8")

    private fun text(ex: HttpExchange, text: String) = send(ex, 200, text.toByteArray(), "text/plain; charset=utf-8")

    private fun redirect(ex: HttpExchange, to: String) {
        ex.responseHeaders["Location"] = listOf(to)
        status(ex, 303)
    }

    private fun status(ex: HttpExchange, code: Int) {
        ex.sendResponseHeaders(code, -1)
        ex.close()
    }

    private fun send(ex: HttpExchange, code: Int, data: ByteArray, type: String) {
        ex.responseHeaders["Content-Type"] = listOf(type)
        ex.sendResponseHeaders(code, data.size.toLong())
        ex.responseBody.use { it.write(data) }
    }

    private fun token() = ByteArray(32).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun constantEquals(a: String?, b: String) = a != null && java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        const val DEFAULT_PORT = 47912
        private const val COOKIE = "__Host-session"
        const val IDLE_TIMEOUT_MS = 30 * 60_000L
        const val MAX_SESSION_MS = 12 * 3_600_000L
        const val LOCAL_LOGIN_MS = 2 * 60_000L
        private const val MAX_BODY = 16 * 1024

        fun e(s: String): String {
            val sb = StringBuilder(s.length + 16)
            for (c in s) when (c) {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '&' -> sb.append("&amp;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
            return sb.toString()
        }

        /** Loopback, private ranges (RFC 1918, ULA, link-local) and carrier-grade NAT (Tailscale and other VPNs). */
        fun isPrivate(address: InetAddress): Boolean {
            var a = address
            if (a is Inet6Address) {
                val b = a.address
                // IPv4-mapped (::ffff:a.b.c.d)
                if (b.take(10).all { it == 0.toByte() } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte()) a = InetAddress.getByAddress(b.copyOfRange(12, 16))
            }
            if (a.isLoopbackAddress) return true
            if (a is Inet4Address) {
                val b = a.address.map { it.toInt() and 0xff }
                return b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) ||
                    (b[0] == 169 && b[1] == 254) || (b[0] == 100 && b[1] in 64..127)
            }
            if (a is Inet6Address) return a.isLinkLocalAddress || a.isSiteLocalAddress || (a.address[0].toInt() and 0xfe) == 0xfc
            return false
        }

        fun qrSvg(text: String): String {
            val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
            val border = 2
            val size = qr.size + border * 2
            val path = StringBuilder()
            for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr.getModule(x, y)) path.append("M${x + border},${y + border}h1v1h-1z")
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $size $size\" shape-rendering=\"crispEdges\">" +
                "<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path d=\"$path\" fill=\"#000\"/></svg>"
        }
    }
}
