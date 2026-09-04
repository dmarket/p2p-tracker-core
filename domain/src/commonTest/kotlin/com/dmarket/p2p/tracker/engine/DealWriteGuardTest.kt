package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.ClaimPhase
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DealWriteClaim
import com.dmarket.p2p.tracker.model.DealWriteKey
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.support.T0
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class DealWriteGuardTest {

    private val ttl = 15.minutes

    private fun claim(
        dealId: String = "deal-1",
        action: DirectiveAction = DirectiveAction.CREATE_OFFER,
        phase: ClaimPhase = ClaimPhase.COMPLETED,
        ageMinutes: Int = 0,
        steamOfferId: String? = "offer-1",
    ) = DealWriteClaim(
        dealId = DealId(dealId),
        action = action,
        phase = phase,
        claimedAt = T0 - ageMinutes.minutes,
        directiveId = DirectiveId("dir-1"),
        outcome = if (phase == ClaimPhase.COMPLETED) {
            DirectiveOutcome(
                directiveId = DirectiveId("dir-1"),
                action = action,
                status = DirectiveStatus.NEEDS_CONFIRMATION,
                dealId = DealId(dealId),
                steamOfferId = steamOfferId?.let(::OfferId),
            )
        } else {
            null
        },
    )

    private fun tracked(dealId: String = "deal-1", steamOfferId: String? = "offer-1") =
        TrackedDeal(dealId = DealId(dealId), steamOfferId = steamOfferId?.let(::OfferId))

    private fun staleClaims(
        claims: Collection<DealWriteClaim>,
        activeTracking: List<TrackedDeal>,
        leasedWrites: Set<DealWriteKey> = emptySet(),
    ) = DealWriteGuard.staleClaims(claims, activeTracking, leasedWrites, T0, ttl)

    // ---- evaluate ------------------------------------------------------------------------------

    @Test
    fun no_claim_proceeds() {
        assertEquals(ClaimVerdict.Proceed, DealWriteGuard.evaluate(null, T0, ttl))
    }

    @Test
    fun completed_claim_blocks_and_carries_the_outcome_to_replay() {
        val verdict = DealWriteGuard.evaluate(claim(), T0, ttl)
        val completed = assertIs<ClaimVerdict.AlreadyCompleted>(verdict)
        assertEquals(OfferId("offer-1"), completed.claim.outcome?.steamOfferId)
    }

    @Test
    fun in_flight_claim_blocks_a_concurrent_duplicate() {
        assertIs<ClaimVerdict.InFlight>(DealWriteGuard.evaluate(claim(phase = ClaimPhase.IN_FLIGHT), T0, ttl))
    }

    @Test
    fun claim_older_than_the_ttl_proceeds() {
        assertEquals(ClaimVerdict.Proceed, DealWriteGuard.evaluate(claim(ageMinutes = 15), T0, ttl))
    }

    @Test
    fun claim_just_inside_the_ttl_still_blocks() {
        assertIs<ClaimVerdict.AlreadyCompleted>(DealWriteGuard.evaluate(claim(ageMinutes = 14), T0, ttl))
    }

    /** A stuck in-flight claim must not wedge the deal forever — the TTL is the only way out of it. */
    @Test
    fun expired_in_flight_claim_proceeds() {
        assertEquals(
            ClaimVerdict.Proceed,
            DealWriteGuard.evaluate(claim(phase = ClaimPhase.IN_FLIGHT, ageMinutes = 20), T0, ttl),
        )
    }

    // ---- staleClaims ---------------------------------------------------------------------------

    @Test
    fun no_claims_yields_nothing_to_release() {
        assertTrue(staleClaims(emptyList(), listOf(tracked())).isEmpty())
    }

    @Test
    fun completed_claim_for_a_live_deal_with_an_offer_is_kept() {
        assertTrue(staleClaims(listOf(claim()), listOf(tracked())).isEmpty())
    }

    @Test
    fun completed_claim_whose_deal_left_tracking_is_released() {
        assertEquals(
            setOf(DealWriteKey(DealId("deal-1"), DirectiveAction.CREATE_OFFER)),
            staleClaims(listOf(claim()), listOf(tracked("deal-other"))),
        )
    }

    @Test
    fun completed_claim_whose_deal_is_tracked_without_an_offer_id_is_released() {
        assertEquals(
            setOf(DealWriteKey(DealId("deal-1"), DirectiveAction.CREATE_OFFER)),
            staleClaims(listOf(claim()), listOf(tracked(steamOfferId = null))),
        )
    }

    /** A missing offer id is what a *successful* cancel looks like, so it must not release a cancel claim. */
    @Test
    fun completed_cancel_claim_is_not_released_by_a_missing_offer_id() {
        val cancel = claim(action = DirectiveAction.CANCEL_OFFER)
        assertTrue(staleClaims(listOf(cancel), listOf(tracked(steamOfferId = null))).isEmpty())
    }

    /**
     * The offer does not exist yet *because* the write is still running: releasing on that signal would
     * re-open the concurrent-duplicate race the claim exists to close.
     */
    @Test
    fun in_flight_claim_is_never_released_by_the_tracking_signals() {
        val inFlight = claim(phase = ClaimPhase.IN_FLIGHT)
        assertTrue(staleClaims(listOf(inFlight), listOf(tracked(steamOfferId = null))).isEmpty())
        assertTrue(staleClaims(listOf(inFlight), emptyList()).isEmpty())
    }

    @Test
    fun expired_in_flight_claim_is_released() {
        val stuck = claim(phase = ClaimPhase.IN_FLIGHT, ageMinutes = 20)
        assertEquals(
            setOf(DealWriteKey(DealId("deal-1"), DirectiveAction.CREATE_OFFER)),
            staleClaims(listOf(stuck), listOf(tracked(steamOfferId = null))),
        )
    }

    /**
     * The case the guard matters most in: the backend re-leases the create for a deal it is not (yet)
     * tracking with an offer id. Releasing here would hand that re-lease a second Steam write — exactly the
     * duplicate the ledger exists to stop.
     */
    @Test
    fun a_claim_whose_write_this_heartbeat_re_leases_is_kept() {
        val leased = setOf(DealWriteKey(DealId("deal-1"), DirectiveAction.CREATE_OFFER))
        assertTrue(staleClaims(listOf(claim()), emptyList(), leased).isEmpty())
        assertTrue(staleClaims(listOf(claim()), listOf(tracked(steamOfferId = null)), leased).isEmpty())
    }

    /** A lease for one action must not keep the other action's claim alive. */
    @Test
    fun a_leased_cancel_does_not_keep_the_create_claim() {
        val leased = setOf(DealWriteKey(DealId("deal-1"), DirectiveAction.CANCEL_OFFER))
        assertEquals(
            setOf(DealWriteKey(DealId("deal-1"), DirectiveAction.CREATE_OFFER)),
            staleClaims(listOf(claim()), emptyList(), leased),
        )
    }

    /** The TTL outranks the lease, so a genuinely stuck claim can never be pinned forever by re-leases. */
    @Test
    fun an_expired_claim_is_released_even_while_its_write_is_leased() {
        val leased = setOf(DealWriteKey(DealId("deal-1"), DirectiveAction.CREATE_OFFER))
        assertEquals(leased, staleClaims(listOf(claim(ageMinutes = 20)), emptyList(), leased))
    }

    @Test
    fun each_stale_claim_is_reported_by_its_own_deal_and_action_key() {
        val create = claim(dealId = "deal-1")
        val cancel = claim(dealId = "deal-2", action = DirectiveAction.CANCEL_OFFER)
        assertEquals(
            setOf(
                DealWriteKey(DealId("deal-1"), DirectiveAction.CREATE_OFFER),
                DealWriteKey(DealId("deal-2"), DirectiveAction.CANCEL_OFFER),
            ),
            staleClaims(listOf(create, cancel), emptyList()),
        )
    }
}
