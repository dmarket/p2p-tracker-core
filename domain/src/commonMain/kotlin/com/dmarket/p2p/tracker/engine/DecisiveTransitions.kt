package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.marketplace.ProofSite
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource

/**
 * The **fixed decisive set** a TLSN proof rides when a deal is `proof_required`.
 *
 * Not a client capability — the client always reports raw codes on `/trade-events`. This decides only which
 * transitions additionally carry a proof, and it must mirror the places the backend enforces, because an
 * enforced place that receives no proof does not move the deal.
 *
 * **The seven enforced places, and the Steam codes that reach them** (p2p#52 / DMA-247, one rule for all
 * seven: an enforced place does not accept an unsigned report):
 *
 * | place | axis + code |
 * |---|---|
 * | `offer_created` | none — it gates only how far an unsigned report may move the deal |
 * | `offer_confirmed` | offer `2 Active` (reached from `9 CreatedNeedsConfirmation`) |
 * | `offer_accepted` | offer `3 Accepted` |
 * | `offer_declined` | offer `7 Declined`, and `4 Countered` — the buyer's two ways of refusing |
 * | `offer_cancelled` | offer `6 Canceled`, `8 InvalidItems`, `10 CanceledBySecondFactor` |
 * | `trade_completed` | history `3 Complete`, `9 RollbackAbandoned` |
 * | `trade_reversed` | history `4 Failed`, `5`/`6`/`7` support rollbacks, `11 EscrowRollback`, `12 TradeProtectionRollback` |
 *
 * **History `3` is the one that cost money to omit.** It is the payout place: the backend holds the deal at
 * `AwaitingTerminal` until a positive history `Complete(3)` clears the protection window, and with proofs
 * enforced it will not take that transition unsigned. This set carried offer `{2,3,6}` + history `{12}`,
 * raised no proof intent for history `3`, and every proof-enforced deal therefore froze at payout —
 * permanently, with the funds locked, because nothing in the loop ever revisits it.
 *
 * **The history axis carries all eight of the backend's codes, not just `3` and `12`.** The backend moves money
 * on a refunding code (4, 5, 6, 7, 11, 12) only against a verified proof — in every phase, whatever the deal's
 * enforced places say — so a reversal Steam reports as 4, 5, 6, 7 or 11 reached it raw and was refused for
 * good: the buyer was never refunded through it. A raw `9` on a deal enforcing `trade_completed` was refused
 * the same way. Proving them is safe only because every phase that sees the history axis admits all eight; a
 * code the phase does not admit comes back `transition_mismatch`, and the report it holds is then withheld
 * for good, so this set may never run ahead of the backend's own mirror of it.
 *
 * **Offer `8 InvalidItems` and `10 CanceledBySecondFactor` are IN, reversing an earlier ruling that kept them
 * out.** That ruling was recorded when the cost was unknown; it has since been measured, and it was paid by
 * buyers. `offer_cancelled` covers 6, 8 and 10 and is enforced on every overlay, so an unsigned report of 8 or
 * 10 was refused every single time — not a race — and the deal then drifted to the 18-hour buyer-accept
 * deadline, which charges the buyer 4% for something the seller's client did.
 *
 * The proof is mintable wherever those two codes can arrive, which is the other half of why this is safe: a
 * `watch[]` naming only the history axis exists solely for a deal past acceptance, where an offer-axis closure
 * cannot mean anything anyway, and every phase that can still reach 8 or 10 publishes the offer axis. An entry
 * naming no axis at all is a pre-bind deal, which is the fail-open case behaving correctly.
 *
 * Adding a code here has a second effect worth knowing, because proofs now run *before* the reports they
 * corroborate: a transition in this set is one whose report is **withheld until its proof verifies**. That is
 * the intended behaviour for an enforced place (the backend would refuse the unsigned report anyway), but it
 * does mean a broken prover silences more of the report stream than before. It applies only to deals the
 * backend has flagged `proof_required`; every other deal reports exactly as it did.
 *
 * **This is the set a deal falls back to, not always the set it proves.** A deal that names its enforced sites
 * (`TrackedDeal.proofSites`) proves only the codes of those sites; [ProofSite] carries the per-site rows, and
 * `DecisiveTransitionsTest` pins their union to this set with each code on exactly one site.
 */
object DecisiveTransitions {
    private val DECISIVE_OFFER_CODES = setOf(2, 3, 4, 6, 7, 8, 10)
    private val DECISIVE_HISTORY_CODES = setOf(3, 9, 4, 5, 6, 7, 11, 12)

    /** Whether a raw Steam status on [source] is one of the decisive transitions that carries a proof. */
    fun isDecisive(source: TradeStatusSource, steamStatusCode: Int): Boolean = when (source) {
        TradeStatusSource.OFFER -> steamStatusCode in DECISIVE_OFFER_CODES
        TradeStatusSource.HISTORY -> steamStatusCode in DECISIVE_HISTORY_CODES
    }
}
