package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TradeId
import kotlinx.serialization.Serializable

/**
 * What the loop sends to whichever context can actually run the prover: the extension's offscreen document,
 * or a headless host's prover process.
 *
 * **Deliberately credential-free**, and it stays that way: the Steam JWT the proven read now needs is passed
 * as its own argument to the delegate, never folded in here. A credential in a JSON blob is how one ends up in
 * a log; a credential as a named parameter is visible at every call site. [subjectSteamId] is a public account
 * id.
 */
@Serializable
data class NotaryProofRequest(
    val dealId: String,
    val subjectSteamId: String,
    val steamOfferId: String? = null,
    val assetId: String? = null,
    /** Steam's own trade id — the history axis's binding key (`GetTradeStatus?tradeid=…`). */
    val tradeId: String? = null,
    /**
     * Which Steam endpoint to prove. **Required**, and it replaced a nullable field that fell back to deriving
     * the kind from a `source` axis.
     *
     * That fallback protected against a version skew that cannot happen: both ends of this JSON come from the
     * same npm bundle — the service worker encodes it, the offscreen document decodes it — and the message is
     * never persisted. What it did instead was turn a dropped or mangled field into a **silent proof of
     * `TRADE_OFFER`**, which is the one failure mode the rest of this path works to avoid (compare
     * `WasmNotaryProver`'s enabled-kind check, and `toKind`'s refusal to guess an unrecognised name).
     */
    val kind: String,
    /** The counterparty's steamid64 — the subject of a profile/level read, and a create body's `partner`. */
    val partnerSteamId: String? = null,
    /** The counterparty's public trade-offer access token, when the deal carries one. Not a device secret. */
    val tradeToken: String? = null,
    /** Assets a proven create offers. Empty for every read. */
    val assetIds: List<String> = emptyList(),
    /**
     * The online-decryption budget an earlier refusal taught for this deal, or `null` when nothing has been
     * learned. See [ProvenReadBinding.minOnlineBudget].
     *
     * It has to cross this boundary because the prover that consumes it runs on the far side: the service
     * worker owns the store the lesson lives in, the offscreen document owns the wasm that spends it. A field
     * missing here is not a compile error anywhere — it is a budget silently reverting to the configured
     * default, and therefore a proof that is refused again for exactly the reason we already paid to learn.
     */
    val minOnlineBudget: Int? = null,
)

/**
 * The request for proving [kind] for [binding], on behalf of [subjectSteamId]. Carries no credential: the one
 * the read needs travels beside it.
 */
internal fun notaryProofRequest(binding: ProvenReadBinding, kind: ProvenReadKind, subjectSteamId: SteamId): NotaryProofRequest =
    NotaryProofRequest(
        dealId = binding.dealId.value,
        kind = kind.name,
        subjectSteamId = subjectSteamId.value,
        steamOfferId = binding.steamOfferId?.value,
        assetId = binding.assetId?.value,
        tradeId = binding.tradeId?.value,
        partnerSteamId = binding.partnerSteamId?.value,
        tradeToken = binding.tradeToken,
        assetIds = binding.assetsToGive.map { it.value },
        minOnlineBudget = binding.minOnlineBudget,
    )

/** Decode a [NotaryProofRequest] back into the typed binding the prover needs. */
internal fun NotaryProofRequest.toBinding(): ProvenReadBinding = ProvenReadBinding(
    dealId = DealId(dealId),
    steamOfferId = steamOfferId?.let { OfferId(it) },
    assetId = assetId?.let { AssetId(it) },
    tradeId = tradeId?.let { TradeId(it) },
    partnerSteamId = partnerSteamId?.let { SteamId(it) },
    tradeToken = tradeToken,
    assetsToGive = assetIds.map { AssetId(it) },
    minOnlineBudget = minOnlineBudget,
)

/**
 * The endpoint to prove.
 *
 * Throws on a name this build does not define rather than falling back to anything: honouring an unrecognised
 * kind would mean guessing, and for a write kind that guess performs a real Steam POST.
 */
internal fun NotaryProofRequest.toKind(): ProvenReadKind = ProvenReadKind.entries.firstOrNull { it.name == kind }
    ?: error("unknown proven read kind '$kind' — this build does not define it")

internal fun NotaryProofRequest.toSubjectSteamId(): SteamId = SteamId(subjectSteamId)
