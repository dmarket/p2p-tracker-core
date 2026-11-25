package com.dmarket.p2p.tracker.credential.steam

import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.PushedSteamToken
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamTokenCheck
import com.dmarket.p2p.tracker.net.SteamProxy
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.CredentialVault
import com.dmarket.p2p.tracker.port.notary.SteamProofCookieSource
import com.dmarket.p2p.tracker.port.steam.SteamSessionScraper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/** What a pushed Steam token did. No case carries the token's text. */
sealed interface SteamPushResult {
    /**
     * The token for [steamId] is held now, until [expiresAt]; the loop acts on it from its next credential read.
     * [proxyChanged]: the push moved the account's Steam traffic to another proxy, or to none, or off a direct
     * connection; `false` when it kept the one held, the same proxy named again included.
     */
    data class Held(val steamId: SteamId, val expiresAt: Instant, val proxyChanged: Boolean = false) : SteamPushResult

    /** The token names [tokenSteamId], not the account DMarket links ([linkedSteamId]); the held token stays. */
    data class WrongAccount(val tokenSteamId: SteamId, val linkedSteamId: SteamId?) : SteamPushResult

    /** The token is not one this library takes ([PushedSteamToken]); [reason] never quotes it. */
    data class Invalid(val reason: String) : SteamPushResult
}

/** What a token push does with the Steam proxy held before it. */
sealed interface SteamProxyUpdate {
    /** A renewal: the held proxy stays, or none if none was held. */
    data object Keep : SteamProxyUpdate

    /** No proxy from now on: Steam is reached directly. Only ever on the host's explicit word. */
    data object Clear : SteamProxyUpdate

    /** [proxy] from now on. */
    class Use(val proxy: SteamProxy) : SteamProxyUpdate {
        override fun toString(): String = "Use(<redacted>)"
    }
}

/**
 * A Steam session a host pushes in as an access token, for a host with no browser to scrape one from.
 *
 * Held in memory only: lost on restart, never written anywhere. Nothing refreshes it — the host pushes a
 * fresh token before this one expires. Wire [vault] and [scraper] into the loop in place of the platform's
 * own; they are the only way the loop reads the token, so a push reaches the loop's next credential read
 * at once rather than when the token it cached goes stale.
 *
 * **The Steam account is DMarket's to name, not the host's.** A token is held when its `sub` is the account
 * the loop's last heartbeat named as linked ([linkedSteamId]). That name only counts once a heartbeat signed
 * for the current DMarket account has come back ([heartbeatReturned]); after the DMarket account changes
 * ([forgetLinkedAccount]) the name the loop remembers is the previous account's. Until a name counts, the
 * first token is held on trust — there is nothing to check it against, and the loop's own guard blocks every
 * Steam action the moment the heartbeat disagrees — but a token for a *different* account than the one
 * already held is refused. Either way a refused token never evicts the held one.
 *
 * **Lib machinery:** [credential], [vault] and [scraper] hand out the raw token. Keep them off every
 * host-facing surface, like [SteamCredentialProvider].
 *
 * @param linkedSteamId the Steam id the loop's last heartbeat named as linked, or `null` when none did.
 */
class PushedSteamSession(private val clock: Clock, private val linkedSteamId: () -> SteamId?) {
    private val mutex = Mutex()

    // No @Volatile: JS is single-threaded; JVM callers share a single coroutine scope per loop.
    private var held: SteamCredential? = null
    private var heldProxy: SteamProxy? = null
    private var linkedNameCounts = false

    /** The held token, live or expired; `null` before the first accepted push. */
    val credential: SteamCredential? get() = held

    /**
     * The proxy the held token's Steam traffic goes through, pushed with it; `null` to connect directly. A secret
     * like the token: lib machinery, kept off every host-facing surface.
     */
    val proxy: SteamProxy? get() = heldProxy

    /** `true` while a token is held and has not expired. */
    fun ready(): Boolean = held?.let { it.expiresAt > clock.now() } ?: false

    /**
     * Checks [token], then holds it when it belongs to the linked account — see the class note — and applies [proxy]
     * to the proxy held before it: the token and the address Steam sees it from are one identity, so a refused token
     * changes neither. [SteamProxyUpdate.Keep], the default, is a renewal: the held proxy stays, so a push that names
     * none never moves the account back to the host's address; only [SteamProxyUpdate.Clear] does.
     */
    suspend fun push(token: String, proxy: SteamProxyUpdate = SteamProxyUpdate.Keep): SteamPushResult {
        val credential = when (val check = PushedSteamToken.check(token, clock.now())) {
            is SteamTokenCheck.Accepted -> check.credential
            is SteamTokenCheck.Refused -> return SteamPushResult.Invalid(check.reason)
        }
        return mutex.withLock {
            val subject = credential.subjectSteamId
            val linked = boundSteamId()
            val expected = linked ?: held?.subjectSteamId
            if (expected != null && expected != subject) return@withLock SteamPushResult.WrongAccount(subject, linked)
            held = credential
            val before = heldProxy
            heldProxy = when (proxy) {
                SteamProxyUpdate.Keep -> heldProxy
                SteamProxyUpdate.Clear -> null
                is SteamProxyUpdate.Use -> proxy.proxy
            }
            SteamPushResult.Held(subject, credential.expiresAt, proxyChanged = heldProxy != before)
        }
    }

    /**
     * The Steam account this session is bound to: the one the last heartbeat named as linked, once a heartbeat
     * signed for the current DMarket account has come back. `null` before that, or while DMarket names none.
     */
    fun boundSteamId(): SteamId? = linkedSteamId().takeIf { linkedNameCounts }

    /** A heartbeat signed for the current DMarket account came back: the linked id it named now counts. */
    fun heartbeatReturned() {
        linkedNameCounts = true
    }

    /**
     * The DMarket account changed: the linked id the loop remembers is the previous account's, so it stops
     * counting until the next heartbeat comes back. The held token stays.
     */
    fun forgetLinkedAccount() {
        linkedNameCounts = false
    }

    /**
     * The Steam web session for a cookie-authenticated proven read, built from the held token while it is live and
     * [sessionId], the `sessionid` value the tracker's writes send.
     */
    fun proofCookies(sessionId: String): SteamProofCookieSource =
        HeaderSteamProofCookieSource({ held?.takeIf { it.expiresAt > clock.now() } }, sessionId)

    /** Hands the loop the held token, or `null` once it has expired: nothing here can renew it. */
    val scraper: SteamSessionScraper = object : SteamSessionScraper {
        override suspend fun scrape(): SteamCredential? = held?.takeIf { it.expiresAt > clock.now() }
    }

    /**
     * The loop's credential cache, read straight from the held token, so no copy can lag behind a push.
     *
     * Only a push changes what is held; the loop's writes and clears are ignored. It writes back only what
     * [scraper] handed it, which is already held, and it clears only a browser session it found gone or
     * signed into another account — checks that report a live session when no browser is behind the token.
     * Honouring either would let one that raced a push undo it.
     */
    val vault: CredentialVault = object : CredentialVault {
        override suspend fun readSteamCredential(): SteamCredential? = held

        override suspend fun writeSteamCredential(credential: SteamCredential) = Unit

        override suspend fun clearSteamCredential() = Unit
    }
}
