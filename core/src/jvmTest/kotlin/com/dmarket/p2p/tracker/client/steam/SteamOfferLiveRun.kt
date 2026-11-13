package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.adapter.host.SealedSteamCookieVault
import com.dmarket.p2p.tracker.adapter.host.SystemClock
import com.dmarket.p2p.tracker.client.createSteamHttpClient
import com.dmarket.p2p.tracker.config.SteamEndpointsConfig
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.steam.OfferDirection
import com.dmarket.p2p.tracker.model.steam.SteamCookie
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.model.steam.steamIdOfAccount
import com.dmarket.p2p.tracker.net.redactedSummary
import com.dmarket.p2p.tracker.port.steam.CancelOfferResult
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import io.ktor.http.Url
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Manual run against live Steam between two test accounts, on the mobile writers and readers (the vault session, as
 * on Android). Skipped unless these are set:
 *
 * - `P2P_LIVE_SENDER_LOGIN_SECURE` — the sender's `steamLoginSecure` cookie for `steamcommunity.com`;
 * - `P2P_LIVE_RECEIVER_LOGIN_SECURE` — the same for the receiver;
 * - `P2P_LIVE_PARTNER_TRADE_URL` — the receiver's trade URL;
 * - optional `P2P_LIVE_ASSET_ID` — the sender's CS2 asset to offer (default: the first one the inventory lists);
 * - optional `P2P_LIVE_HOLD_SECONDS` — a wait between the create and the cancel (default 0).
 *
 * It reads both accounts' offers and the sender's inventory, creates one offer giving the asset to the receiver,
 * expects Steam to hold it for mobile confirmation (state 9) and cancels it. It confirms nothing. The log goes to
 * `core/build/steam-live-run.log`: steamids, offer ids, states and outcomes, never a cookie or token value.
 */
class SteamOfferLiveRun {
    private val log = File("build/steam-live-run.log").apply {
        parentFile.mkdirs()
        writeText("")
    }

    private fun say(line: String) {
        println(line)
        log.appendText(line + "\n")
    }

    /** One account's mobile Steam stack over its own in-memory vault. */
    private class Account(loginSecure: String) {
        val vault = SealedSteamCookieVault(InMemoryDeviceKeyValueStore())
        val jar = VaultCookieJar(vault, SystemClock())
        val http = createSteamHttpClient(cookies = jar)
        val endpoints = SteamEndpointsConfig()
        val reader = KtorSteamReadClient(http, endpoints)
        val inventory = KtorSteamInventoryReader(http, endpoints.communityBaseUrl)
        val creator = HeaderSessionSteamOfferCreator(http, jar, endpoints.communityBaseUrl)
        val canceller = HeaderSessionSteamOfferCanceller(http, jar, endpoints.communityBaseUrl)
        private val cookie = SteamCookie("steamLoginSecure", loginSecure, "steamcommunity.com")

        suspend fun signIn(role: String): SteamCredential {
            vault.writeSteamCookies(listOf(cookie))
            return checkNotNull(KtorSteamSessionScraper(http, communityBaseUrl = endpoints.communityBaseUrl).scrape()) {
                "the $role's steamLoginSecure does not open a Steam session"
            }
        }
    }

    @Test
    fun createsAnOfferBetweenTwoTestAccountsStopsAtConfirmationAndCancelsIt() = runBlocking {
        val senderCookie = System.getenv("P2P_LIVE_SENDER_LOGIN_SECURE")
        val receiverCookie = System.getenv("P2P_LIVE_RECEIVER_LOGIN_SECURE")
        val tradeUrl = System.getenv("P2P_LIVE_PARTNER_TRADE_URL")
        assumeTrue("live Steam run: set the P2P_LIVE_* variables", senderCookie != null && receiverCookie != null && tradeUrl != null)

        val sender = Account(senderCookie!!)
        val receiver = Account(receiverCookie!!)
        val senderCredential = sender.signIn("sender")
        val receiverCredential = receiver.signIn("receiver")
        say("sender ${senderCredential.subjectSteamId.value}, token until ${senderCredential.expiresAt}")
        say("receiver ${receiverCredential.subjectSteamId.value}, token until ${receiverCredential.expiresAt}")

        val url = Url(tradeUrl!!)
        val partner = steamIdOfAccount(checkNotNull(url.parameters["partner"]?.toLongOrNull()) { "the trade URL has no partner" })
        val token = url.parameters["token"]
        assertEquals(receiverCredential.subjectSteamId, partner, "the trade URL is the receiver's")

        val scan = sender.inventory.scanOwnInventory(senderCredential)
        say("sender inventory: ${scan.assetIds.size} CS2 assets, complete=${scan.complete}")
        val asset =
            System.getenv("P2P_LIVE_ASSET_ID")?.let(::AssetId) ?: checkNotNull(scan.assetIds.firstOrNull()) { "no CS2 asset to offer" }
        check(asset in scan.assetIds) { "asset ${asset.value} is not in the sender's CS2 inventory" }
        say("sender sent offers before: ${sender.reader.sentOffers(senderCredential).size}")
        say(
            "receiver offers before: ${receiver.reader.offers(receiverCredential).count {
                it.direction == OfferDirection.RECEIVED
            }} received",
        )

        val created = sender.creator.createOffer(senderCredential, TradeDraft(partner, listOf(asset), token))
        say("create ${asset.value} -> ${partner.value}: ${created.describe()}")
        val offerId = when (created) {
            is CreateOfferResult.NeedsConfirmation -> created.offerId
            is CreateOfferResult.Created -> created.offerId
            else -> null
        }
        var cancelled: CancelOfferResult? = null
        try {
            if (offerId != null) {
                say("sender sees offer ${offerId.value} in state ${sender.stateOf(senderCredential, offerId)}")
                val hold = System.getenv("P2P_LIVE_HOLD_SECONDS")?.toIntOrNull() ?: 0
                if (hold > 0) {
                    say("holding ${hold}s before the cancel")
                    delay(hold.seconds)
                }
                val incoming = receiver.reader.offers(receiverCredential).firstOrNull { it.offerId == offerId }
                say("receiver sees offer ${offerId.value}: ${incoming?.let { "state ${it.state}" } ?: "no"}")
            }
        } finally {
            if (offerId != null) {
                cancelled = sender.canceller.cancelOffer(senderCredential, offerId)
                say("cancel ${offerId.value}: $cancelled")
                say("sender sees offer ${offerId.value} in state ${sender.stateOf(senderCredential, offerId)} after the cancel")
            }
        }
        assertIs<CreateOfferResult.NeedsConfirmation>(created, "Steam holds the offer for mobile confirmation (state 9)")
        assertEquals(CancelOfferResult.Cancelled, cancelled)
    }

    private suspend fun Account.stateOf(credential: SteamCredential, offerId: OfferId): String =
        runCatching { reader.offerSnapshots(credential, setOf(offerId))[offerId]?.state?.toString() ?: "unread" }
            .getOrElse { "unread (${it.redactedSummary()})" }

    private fun CreateOfferResult.describe(): String = when (this) {
        is CreateOfferResult.NeedsConfirmation -> "NeedsConfirmation(${offerId.value})"
        is CreateOfferResult.Created -> "Created(${offerId.value}), live without confirmation"
        is CreateOfferResult.Failed -> "Failed($error)"
        else -> toString()
    }
}
