package io.github.thecodingdad.gamelodge.desktop.games

import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.authentication.QrAuthSession
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.types.SteamID
import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64

/**
 * The Steam account for owned games: signed in by scanning a QR code with the Steam app (like the
 * companion did). The refresh token (about 200 days) renews the short-lived access token; both
 * stay in the encrypted secret store.
 */
class SteamAccount(private val secrets: SecretStore) {
    data class Status(val signedIn: Boolean, val accountName: String?)

    private val _status = MutableStateFlow(Status(secrets.get(REFRESH) != null, secrets.get(ACCOUNT)))
    val status: StateFlow<Status> = _status.asStateFlow()

    val steamId: String? get() = secrets.get(STEAM_ID)

    /** When the last renewal failed for a passing reason (network): no new try before [RETRY_MS]. */
    @Volatile private var failedAt = 0L
    /** One renewal at a time; the others wait and take its token. */
    private val renewing = Mutex()

    /** A valid access token (renewed when it runs out within ten minutes), or null when signed out. */
    suspend fun accessToken(): String? = renewing.withLock {
        val token = secrets.get(ACCESS)
        val refresh = secrets.get(REFRESH) ?: return@withLock null
        val id = steamId ?: return@withLock null
        if (token != null && expiry(token) - System.currentTimeMillis() > 10 * 60_000L) return@withLock token
        if (System.currentTimeMillis() - failedAt < RETRY_MS) return@withLock null
        try {
            // Steam renews the token of a client login only for a session logged on with it.
            withSteam { client, manager ->
                val loggedOn = CompletableDeferred<EResult>()
                manager.subscribe(LoggedOnCallback::class.java) { loggedOn.complete(it.result) }
                val user = client.getHandler(SteamUser::class.java) ?: error("no SteamUser handler")
                user.logOn(
                    LogOnDetails().apply {
                        username = secrets.get(ACCOUNT).orEmpty()
                        accessToken = refresh
                        shouldRememberPassword = true
                        // Its own login id, so the user's Steam client on this PC stays logged on.
                        loginID = LOGIN_ID
                    },
                )
                val result = withTimeout(30_000) { loggedOn.await() }
                if (result != EResult.OK) throw SteamLoginException(result)
                try {
                    val renewed = client.authentication.generateAccessTokenForApp(SteamID(id.toLong()), refresh, true).await()
                    secrets.put(ACCESS, renewed.accessToken)
                    renewed.refreshToken?.takeIf { it.isNotEmpty() }?.let { secrets.put(REFRESH, it) }
                    failedAt = 0
                    Log.i("Steam: login renewed")
                    renewed.accessToken
                } finally {
                    runCatching { user.logOff() }
                }
            }
        } catch (e: SteamLoginException) {
            if (e.result in DEAD) {
                // The login was revoked or ran out: retrying can't help, the user has to sign in again.
                Log.w("Steam: login no longer valid (${e.result}) – sign in again under Game sources")
                signOut()
            } else {
                failedAt = System.currentTimeMillis()
                Log.w("Steam: renewing the login failed (${e.result}), next try in an hour")
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failedAt = System.currentTimeMillis()
            Log.w("Steam: renewing the login failed, next try in an hour", e)
            null
        }
    }

    fun signOut() {
        listOf(ACCESS, REFRESH, STEAM_ID, ACCOUNT).forEach { secrets.put(it, null) }
        _status.value = Status(false, null)
    }

    /**
     * Signs in by QR code: [onChallenge] gets the URL to show as QR code (again whenever Steam
     * renews it). Returns true once the user confirmed in the Steam app; false when it failed.
     */
    suspend fun signInWithQr(deviceName: String, onChallenge: (String) -> Unit): Boolean = runCatching {
        withSteam { client, _ ->
            val session: QrAuthSession = client.authentication.beginAuthSessionViaQR(
                AuthSessionDetails().apply {
                    deviceFriendlyName = "GameLodge ($deviceName)"
                    persistentSession = true
                },
            ).await()
            onChallenge(session.challengeUrl)
            session.challengeUrlChanged = object : `in`.dragonbra.javasteam.steam.authentication.IChallengeUrlChanged {
                override fun onChanged(qrAuthSession: QrAuthSession?) = onChallenge((qrAuthSession ?: session).challengeUrl)
            }
            val result = session.pollingWaitForResult().await()
            val id = subject(result.accessToken) ?: error("unreadable token")
            secrets.put(ACCESS, result.accessToken)
            secrets.put(REFRESH, result.refreshToken)
            secrets.put(STEAM_ID, id)
            secrets.put(ACCOUNT, result.accountName)
            _status.value = Status(true, result.accountName)
            Log.i("Steam: signed in")
            true
        }
    }.onFailure { Log.w("Steam: QR sign-in ended", it) }.getOrDefault(false)

    /** Connects to Steam for the duration of [block]. */
    private suspend fun <T> withSteam(block: suspend (SteamClient, CallbackManager) -> T): T = withContext(Dispatchers.IO) {
        val client = SteamClient()
        val manager = CallbackManager(client)
        val connected = CompletableDeferred<Unit>()
        manager.subscribe(ConnectedCallback::class.java) { connected.complete(Unit) }
        manager.subscribe(DisconnectedCallback::class.java) { connected.completeExceptionally(java.io.IOException("Steam disconnected")) }
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val pump = Thread({ while (running.get()) manager.runWaitCallbacks(250) }, "steam-callbacks").apply { isDaemon = true; start() }
        try {
            client.connect()
            withTimeout(30_000) { connected.await() }
            block(client, manager)
        } finally {
            running.set(false)
            runCatching { client.disconnect() }
            pump.join(1_000)
        }
    }

    private class SteamLoginException(val result: EResult) : Exception("Steam logon: $result")

    private companion object {
        const val RETRY_MS = 60 * 60_000L
        /** Logon results that mean the refresh token is no good any more. */
        val DEAD = setOf(EResult.AccessDenied, EResult.InvalidPassword, EResult.Expired, EResult.Revoked)
        /** "GL": differs from the Steam client's own login id. */
        const val LOGIN_ID = 0x474C
        const val ACCESS = "steam_access_token"
        const val REFRESH = "steam_refresh_token"
        const val STEAM_ID = "steam_id"
        const val ACCOUNT = "steam_account"

        private fun payload(token: String) = runCatching {
            val part = token.split('.')[1]
            Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(part.padEnd((part.length + 3) / 4 * 4, '=')))).jsonObject
        }.getOrNull()

        /** SteamID ("sub") of an access token (a JWT). */
        fun subject(token: String) = payload(token)?.get("sub")?.jsonPrimitive?.content

        /** Expiry of an access token in ms; 0 when unreadable. */
        fun expiry(token: String) = payload(token)?.get("exp")?.jsonPrimitive?.long?.times(1000) ?: 0L
    }
}
