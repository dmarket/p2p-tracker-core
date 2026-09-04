package com.dmarket.p2p.tracker.notary

/**
 * What a refused proof taught us about the online-decryption budget its read needs.
 *
 * **Why this is read out of an error string.** `NotaryConfig.maxRecvDataOnline` has to cover the response's
 * head, and the size of that head is a property of the *response*, not of anything the client configures: a
 * `GetTradeOffer` body grows with the offer's item count, so one fixed number is either wasteful for the small
 * case or a hard failure for the large one. On the current prover an exceeded budget is not a wedge but a
 * clean, fast, deterministic refusal — and that refusal states the requirement:
 *
 * ```
 * record layer error: attempted to decrypt more data in the online phase than was configured,
 * increase `max_recv_online` in the config: current=16, additional=786, max=32
 * ```
 *
 * So the prover is a better oracle for this number than any formula we could calibrate — especially since the
 * only response we have ever measured is the single-item one (549 B), which cannot calibrate a per-item cost.
 *
 * **It is a LOWER BOUND, not the total.** `current` is what has already been decrypted online and `additional`
 * is what this one operation wanted to add — records that would have followed are not counted, because the
 * prover stopped here. A budget raised to exactly this sum can therefore be refused again by a later record,
 * which is why [learn] takes the previous lesson and only ever moves up: repeated refusals converge on the
 * true requirement instead of oscillating.
 *
 * **The wording is upstream's, and this is the test that guards it.** The message comes from the vendored
 * tlsn artifact (`vendor/tlsn/VERSION`, pinned by build id rather than by schema), so a bump can reword it and
 * nothing here would fail loudly — the parser would simply stop matching and every refused deal would re-buy
 * its MPC session forever. `OnlineBudgetLessonTest` therefore pins the *verbatim* line as observed, and a
 * vendor bump that changes it is expected to break that test rather than production.
 */
object OnlineBudgetLesson {
    /**
     * The bytes the refused read is now known to need, or `null` when [error] is not this refusal.
     *
     * `null` is the common case and must stay cheap and silent: every other proof failure — a wedge, a torn
     * down realm, an unreachable notary — reaches here too, and none of them says anything about the budget.
     * The marker check is what makes that cheap, and it is load-bearing beyond speed: the two field names are
     * generic enough to appear in some unrelated error's payload.
     */
    fun requiredFrom(error: String?): Int? {
        val text = error ?: return null
        if (!text.contains("online phase")) return null
        val current = CURRENT.value(text) ?: return null
        val additional = ADDITIONAL.value(text) ?: return null
        // Sum, because the two are "already spent" + "wanted now" rather than a bound and an overage.
        return (current.toLong() + additional.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Fold a refusal into what this read is allowed to spend next time: the stated requirement plus
     * [marginPercent], never below [floor] and never below what a previous refusal already taught.
     *
     * [floor] is the configured default, and a requirement that fits under it returns `null` rather than the
     * floor itself: the mechanism is a safety net for responses that outgrow the default, not an optimiser for
     * the ones that fit. Recording a floor-equal value would also be actively wrong — it pins the deal to
     * today's number, so a later *raise* of the configured default would be silently overridden by a stored
     * value that never meant "at most this".
     *
     * The margin exists because the requirement is a lower bound *and* because the next response for the same
     * deal is a different byte count (a state change moves field lengths), so aiming exactly at the last one
     * would re-refuse on a near-identical read.
     *
     * @return the budget to remember, or `null` when there is nothing new to store — [error] taught nothing,
     *   the requirement fits under [floor], or [previous] already covers it. A `null` means "leave what the
     *   caller has alone", so no caller needs its own is-this-new check.
     */
    fun learn(error: String?, previous: Int?, floor: Int, marginPercent: Int): Int? {
        val required = requiredFrom(error) ?: return null
        // Clamped, not rejected: an absurd requirement should still be capped and floor-checked rather than
        // silently forgotten, which would drop the single largest lesson ever reported.
        val withMargin = (required.toLong() * (100 + marginPercent) / 100).coerceAtMost(Int.MAX_VALUE.toLong())
        // `previous` participates so a lower bound reported later can never walk the lesson back down.
        val raised = maxOf(withMargin.toInt(), previous ?: 0)
        return raised.takeIf { it > floor && it != previous }
    }

    /**
     * Match `current=123` / `additional=123` in the refusal's trailing field list.
     *
     * Two patterns rather than one alternation because the fields can arrive in either order and either may be
     * missing; deliberately tolerant of what surrounds them, since the message is upstream's, it is wrapped by
     * two error layers before it reaches us, and the host's session log truncates it — the observed line ends
     * mid-way through `max=`. Anything that still carries the two numbers we need must keep working.
     */
    private val CURRENT = Regex("""\bcurrent\s*=\s*(\d+)""")
    private val ADDITIONAL = Regex("""\badditional\s*=\s*(\d+)""")

    private fun Regex.value(text: String): Int? = find(text)?.groupValues?.get(1)?.toIntOrNull()
}
