package io.github.thecodingdad.gamelodge.xbox

import io.github.thecodingdad.gamelodge.data.db.LauncherDatabase
import io.github.thecodingdad.gamelodge.data.db.MetaField
import io.github.thecodingdad.gamelodge.data.db.TargetType
import io.github.thecodingdad.gamelodge.data.library.LibrarySync
import io.github.thecodingdad.gamelodge.data.library.SourceEntry
import io.github.thecodingdad.gamelodge.data.settings.SettingsRepository
import io.github.thecodingdad.gamelodge.data.settings.XboxUnlicensed
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.Locale

/** An Xbox console added as host, with what GameLodge knows about it right now. */
data class XboxHost(
    val console: XboxConsole,
    /** The account's console list didn't have it at the last look (removed from the account). */
    val missing: Boolean = false,
) {
    val hostUuid: String get() = SourceEntry.xboxHost(console.id)
}

/** Account state for the UI. */
sealed interface XboxAccount {
    data object SignedOut : XboxAccount
    data class SignedIn(val gamertag: String?) : XboxAccount
}

/**
 * The Xbox side of GameLodge: the Microsoft account, the consoles added as hosts (power state
 * polled while the launcher is in front) and their installed games in the library.
 */
class XboxRepository(
    private val http: OkHttpClient,
    val auth: XboxAuth,
    private val db: LauncherDatabase,
    private val sync: LibrarySync,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val client = XboxClient(http, auth)

    private val _account = MutableStateFlow<XboxAccount>(if (auth.isSignedIn) XboxAccount.SignedIn(auth.gamertag) else XboxAccount.SignedOut)
    val account: StateFlow<XboxAccount> = _account.asStateFlow()

    private val _hosts = MutableStateFlow<Map<String, XboxHost>>(emptyMap())
    /** Added consoles by console id. */
    val hosts: StateFlow<Map<String, XboxHost>> = _hosts.asStateFlow()

    private val _running = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Title id of the game running on a console (console id), from the account's presence. */
    val running: StateFlow<Map<String, Long>> = _running.asStateFlow()

    /** The game [titleId] runs on [consoleId] now (started here), before the presence says so. */
    fun markRunning(consoleId: String, titleId: Long?) {
        _running.value = if (titleId == null) _running.value - consoleId else _running.value + (consoleId to titleId)
    }

    /** Owned products (license of their own) with the time they were looked up. */
    private val owned = HashMap<String, Pair<Boolean, Long>>()
    private val gamesMutex = Mutex()
    private var lastGamesSync = HashMap<String, Long>()
    private var polling: Job? = null

    init {
        scope.launch {
            val added = settings.current().xboxConsoles
            _hosts.value = added.associateWith { id -> XboxHost(XboxConsole(id, id, "", XboxPower.UNKNOWN, true, true)) }
            if (auth.isSignedIn && added.isNotEmpty()) refresh()
        }
        // Switching between hiding and dimming games without a license: the library follows at once.
        scope.launch {
            settings.settings.map { it.xboxUnlicensed }.distinctUntilChanged().drop(1).collect {
                lastGamesSync.clear()
                _hosts.value.keys.forEach { id -> runCatching { syncGames(id) } }
            }
        }
    }

    // ---------------------------------------------------------------- account

    suspend fun startSignIn(): XboxDeviceCode = auth.startSignIn()

    /** Waits for the sign-in; true when signed in. */
    suspend fun awaitSignIn(code: XboxDeviceCode): Boolean {
        val ok = auth.awaitSignIn(code)
        if (ok) _account.value = XboxAccount.SignedIn(auth.gamertag)
        return ok
    }

    /** Signs out; the added consoles and their games go too. */
    suspend fun signOut() {
        auth.signOut()
        _account.value = XboxAccount.SignedOut
        _hosts.value.keys.toList().forEach { remove(it) }
    }

    private fun onAuthError(e: Throwable) {
        if (e is XboxAuthException && e.signedOut) _account.value = XboxAccount.SignedOut
    }

    // ---------------------------------------------------------------- consoles

    /** All consoles of the account (for adding them). */
    suspend fun accountConsoles(): List<XboxConsole> = runCatching { client.consoles() }
        .onFailure(::onAuthError).getOrThrow()

    suspend fun add(consoles: List<XboxConsole>) {
        settings.update { it.copy(xboxConsoles = it.xboxConsoles + consoles.map { c -> c.id }) }
        _hosts.value = _hosts.value + consoles.associate { it.id to XboxHost(it) }
        consoles.forEach { c -> scope.launch { runCatching { syncGames(c.id) }.onFailure { Log.w("Xbox games of ${c.name}: ${it.message}") } } }
    }

    /** Removes a console and its games from the library. */
    suspend fun remove(consoleId: String) {
        settings.update { it.copy(xboxConsoles = it.xboxConsoles - consoleId) }
        _hosts.value = _hosts.value - consoleId
        lastGamesSync.remove(consoleId)
        sync.removeHost(SourceEntry.xboxHost(consoleId))
    }

    /** Asks Xbox Live for the consoles' state now; their games are looked at again after a while. */
    suspend fun refresh() {
        if (!auth.isSignedIn || _hosts.value.isEmpty()) return
        val list = runCatching { client.consoles() }.onFailure(::onAuthError).getOrElse {
            Log.w("Xbox consoles: ${it.message}")
            return
        }
        val byId = list.associateBy { it.id }
        _hosts.value = _hosts.value.mapValues { (id, host) ->
            byId[id]?.let { XboxHost(it) } ?: host.copy(missing = true, console = host.console.copy(power = XboxPower.UNKNOWN))
        }
        // The account's presence names the game in front: it runs on the console that is on.
        val on = _hosts.value.values.filter { !it.missing && it.console.power == XboxPower.ON }
        val title = if (on.isEmpty()) null else activeTitle()
        _running.value = on.firstOrNull()?.takeIf { title != null }?.let { mapOf(it.console.id to title!!) } ?: emptyMap()
        _hosts.value.values.filter { !it.missing && it.console.power == XboxPower.ON }.forEach { host ->
            if (clock() - (lastGamesSync[host.console.id] ?: 0) > GAMES_INTERVAL_MS) {
                scope.launch { runCatching { syncGames(host.console.id) }.onFailure { Log.w("Xbox games: ${it.message}") } }
            }
        }
    }

    /** Power state of one console right now. */
    suspend fun power(consoleId: String): XboxPower = runCatching { client.power(consoleId) }
        .onFailure(::onAuthError).getOrDefault(XboxPower.UNKNOWN)
        .also { power -> _hosts.value[consoleId]?.let { _hosts.value = _hosts.value + (consoleId to it.copy(console = it.console.copy(power = power))) } }

    /** Polls the consoles' state every [POLL_MS] while the launcher is in front. */
    fun startPolling() {
        if (polling?.isActive == true) return
        polling = scope.launch {
            while (isActive) {
                refresh()
                delay(POLL_MS)
            }
        }
    }

    fun stopPolling() {
        polling?.cancel()
        polling = null
    }

    suspend fun wakeUp(consoleId: String): Boolean = runCatching { client.wakeUp(consoleId) }.onFailure(::onAuthError).getOrDefault(false)

    suspend fun turnOff(consoleId: String): Boolean = runCatching { client.turnOff(consoleId) }.onFailure(::onAuthError).getOrDefault(false)

    suspend fun launch(consoleId: String, productId: String): Boolean =
        runCatching { client.launch(consoleId, productId) }.onFailure(::onAuthError).getOrDefault(false)

    /**
     * Ends the game [productId] on the console (quit from the quick menu); without its app id the
     * console goes back to its home screen at least.
     */
    suspend fun quitGame(consoleId: String, productId: String?): Boolean = runCatching {
        val aumid = productId?.let { id -> client.installedApps(consoleId).firstOrNull { it.productId.equals(id, true) }?.aumid }
        (if (aumid != null) client.terminate(consoleId, aumid) else client.goHome(consoleId)).also { if (it) markRunning(consoleId, null) }
    }.onFailure(::onAuthError).getOrDefault(false)

    /** Title id of the game in front on the account's console, null when none (or unknown). */
    suspend fun activeTitle(): Long? = runCatching { client.activeTitle() }.getOrNull()

    /** Waits until the console is on (polls its state); false after [timeoutMs]. */
    suspend fun waitOn(consoleId: String, timeoutMs: Long): Boolean {
        val until = clock() + timeoutMs
        while (clock() < until) {
            if (power(consoleId) == XboxPower.ON) return true
            delay(3_000)
        }
        return false
    }

    // ---------------------------------------------------------------- games

    /** Reads the console's installed games into the library (with their licenses and store artwork). */
    suspend fun syncGames(consoleId: String) = gamesMutex.withLock {
        val host = _hosts.value[consoleId] ?: return@withLock
        val apps = client.installedApps(consoleId).filter { it.isGame }
        val now = clock()
        val unknown = apps.map { it.productId }.filter { id -> owned[id]?.let { now - it.second > LICENSE_TTL_MS } ?: true }
        if (unknown.isNotEmpty()) {
            val found = runCatching { client.ownedProducts(unknown) }.onFailure { Log.w("Xbox licenses: ${it.message}") }.getOrNull()
            if (found != null) unknown.forEach { owned[it] = (it in found) to now }
        }
        val mode = settings.current().xboxUnlicensed
        val entries = apps.mapNotNull { app ->
            // Unknown license (lookup failed): treated as owned, a start shows what the console says.
            val subscription = owned[app.productId]?.first == false
            if (subscription && mode == XboxUnlicensed.HIDE) return@mapNotNull null
            SourceEntry(
                targetId = SourceEntry.xboxId(consoleId, app.productId),
                type = TargetType.XBOX,
                name = app.name.trim().removeSuffix("™").removeSuffix("®").trim(),
                hostUuid = host.hostUuid,
                hostName = host.console.name,
                appId = app.titleId?.toInt(),
                appUuid = app.productId,
                companionGame = true,
                subscription = subscription,
            )
        }
        sync.syncXbox(host.hostUuid, entries)
        lastGamesSync[consoleId] = now
        suggestPlatform(host)
        applyStoreInfo(entries.mapNotNull { it.appUuid })
    }

    /** Games only on Xbox consoles without a platform yet get the console's (a metadata match may correct it). */
    private suspend fun suggestPlatform(host: XboxHost) {
        val dao = db.libraryDao()
        val platform = if (host.console.isSeries) "Xbox Series X|S" else "Xbox One"
        val itemIds = dao.getAllTargets().filter { it.type == TargetType.XBOX && it.hostUuid == host.hostUuid }.map { it.itemId }.distinct()
        for (id in itemIds) {
            val item = dao.getItem(id) ?: continue
            if (item.platform != null || dao.getTargetsForItem(id).any { it.type != TargetType.XBOX }) continue
            if (io.github.thecodingdad.gamelodge.data.library.PlatformResolver.mayReplace(item, io.github.thecodingdad.gamelodge.data.db.PlatformSource.SUGGESTED, platform)) {
                dao.setPlatform(id, platform, io.github.thecodingdad.gamelodge.data.db.PlatformSource.SUGGESTED)
            }
        }
    }

    /** Store artwork and texts for items that have none yet (fields set once or by hand stay). */
    private suspend fun applyStoreInfo(productIds: List<String>) {
        val dao = db.libraryDao()
        val targets = dao.getAllTargets().filter { it.type == TargetType.XBOX && it.appUuid in productIds }
        val needed = targets.filter { t -> dao.getItem(t.itemId)?.let { it.coverUrl == null || it.heroUrl == null || it.description == null } == true }
        if (needed.isEmpty()) return
        val locale = Locale.getDefault()
        val info = runCatching {
            client.productInfo(needed.mapNotNull { it.appUuid }, locale.country.ifEmpty { "US" }, locale.toLanguageTag())
        }.getOrElse { Log.w("Xbox store info: ${it.message}"); return }
        for (t in needed) {
            val p = info[t.appUuid] ?: continue
            val item = dao.getItem(t.itemId) ?: continue
            val m = item.manual
            fun <T> fill(field: MetaField, now: T?, new: T?): T? = if (field in m) now else now ?: new
            val updated = item.copy(
                coverUrl = fill(MetaField.COVER, item.coverUrl, p.cover),
                squareUrl = item.squareUrl ?: p.square,
                heroUrl = fill(MetaField.HERO, item.heroUrl, p.hero),
                logoUrl = fill(MetaField.LOGO, item.logoUrl, p.logo),
                description = fill(MetaField.DESCRIPTION, item.description, p.description),
                developer = fill(MetaField.DEVELOPER, item.developer, p.developer),
                publisher = fill(MetaField.PUBLISHER, item.publisher, p.publisher),
            )
            if (updated != item) dao.upsertItem(updated)
        }
    }

    /** A new XhomeSession for streaming from [consoleId]. */
    fun newSession(consoleId: String): XhomeSession {
        val series = _hosts.value[consoleId]?.console?.isSeries == true
        return XhomeSession(http, auth, consoleId, fullHd = series, locale = Locale.getDefault().toLanguageTag())
    }

    companion object {
        private const val POLL_MS = 30_000L
        private const val GAMES_INTERVAL_MS = 15 * 60_000L
        private const val LICENSE_TTL_MS = 24 * 3_600_000L

        /** Console id of a host uuid `xbox:<id>`, null for other hosts. */
        fun consoleId(hostUuid: String?): String? = hostUuid?.takeIf { it.startsWith("xbox:") }?.removePrefix("xbox:")
    }
}
