package com.dmarket.p2p.tracker.wire

import com.dmarket.p2p.tracker.engine.DealRoleBinding
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.model.marketplace.DealRole
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus
import com.dmarket.p2p.tracker.model.marketplace.P2PDealState
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusReport
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.support.deal
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class P2pSerializationTest {
    private inline fun <reified T> roundTrip(dto: T): T = TrackerJson.decodeFromString(TrackerJson.encodeToString(dto))

    // ---- heartbeat -------------------------------------------------------------------------

    @Test
    fun heartbeat_request_round_trips() {
        val dto = HeartbeatRequestDto(
            clientVersion = "0.3.0",
            platform = "web_chrome",
            foreground = true,
            steamId = "76561198000000001",
            deviceId = "device-abc-123",
        )
        assertEquals(dto, roundTrip(dto))
    }

    @Test
    fun heartbeat_response_decodes() {
        val json = """
            {
              "activeTracking": [
                {"dealId":"d1","proofRequired":true,"watch":["GetTradeOffer"]}
              ],
              "directives": [
                {"directiveId":"dir-1","action":"create_offer","dealId":"d1",
                 "partnerSteamId":"76561198000000002","assetIds":["a1"],"tradeToken":"tok","contextId":2}
              ],
              "ttlSeconds": 120
            }
        """.trimIndent()
        val dto = TrackerJson.decodeFromString<HeartbeatResponseDto>(json)
        val domain = dto.toDomain()
        assertEquals(120, domain.ttlSeconds)
        assertEquals(1, domain.activeTracking.size)
        assertEquals(DealId("d1"), domain.activeTracking[0].dealId)
        assertEquals(true, domain.activeTracking[0].proofRequired)
        assertEquals(null, domain.activeTracking[0].lastOfferCode, "absent means the backend has nothing to seed")
        assertEquals(1, domain.directives.size)
        assertEquals(DealId("d1"), domain.directives[0].dealId)
    }

    @Test
    fun heartbeat_response_carries_the_backends_last_settled_offer_code() {
        // Ahead of the frozen contract, like `role` was — so both shapes must decode: an entry that carries it
        // seeds the dedup baseline, an entry that omits it keeps today's behaviour. Getting the absent case
        // wrong in the other direction (a default code rather than null) would suppress reports the backend
        // still needs. See `BaselineSeed`.
        val json = """
            {
              "activeTracking": [
                {"dealId":"d1","proofRequired":true,"watch":["GetTradeOffer"],"lastOfferCode":6},
                {"dealId":"d2","proofRequired":true,"watch":["GetTradeOffer"]}
              ],
              "ttlSeconds": 92
            }
        """.trimIndent()
        val domain = TrackerJson.decodeFromString<HeartbeatResponseDto>(json).toDomain()

        assertEquals(6, domain.activeTracking[0].lastOfferCode)
        assertEquals(null, domain.activeTracking[1].lastOfferCode)
    }

    @Test
    fun an_explicit_null_last_offer_code_decodes_like_an_absent_one() {
        // Both shapes reach us from a proto3 backend depending on how it serialises an unset optional, and
        // they have to mean the same thing: nothing to seed.
        val json = """
            {"activeTracking":[{"dealId":"d1","watch":["GetTradeOffer"],"lastOfferCode":null}],"ttlSeconds":92}
        """.trimIndent()

        assertEquals(null, TrackerJson.decodeFromString<HeartbeatResponseDto>(json).toDomain().activeTracking[0].lastOfferCode)
    }

    // ---- DMA-280: the freshness mark ------------------------------------------------------------

    @Test
    fun heartbeat_response_carries_the_freshness_mark_and_the_trade_it_names() {
        // Ahead of the frozen contract, like `role` and `lastOfferCode`. Both halves have to arrive: a mark
        // that says WHEN but not WHICH trade leaves the client nothing to read, since the history axis's
        // proven read addresses one trade by id.
        val json = """
            {
              "activeTracking": [
                {"dealId":"d1","watch":["GetTradeStatus"],"proofRequired":true,
                 "steamTradeId":"744935517744884653","proveAfter":"2026-09-02T10:15:30Z"},
                {"dealId":"d2","watch":["GetTradeStatus"],"proofRequired":true}
              ],
              "ttlSeconds": 92
            }
        """.trimIndent()
        val tracking = TrackerJson.decodeFromString<HeartbeatResponseDto>(json).toDomain().activeTracking

        assertEquals(TradeId("744935517744884653"), tracking[0].steamTradeId)
        assertEquals(Instant.parse("2026-09-02T10:15:30Z"), tracking[0].proveAfter)
        assertNull(tracking[1].steamTradeId, "absent means the backend has nothing to re-attest yet")
        assertNull(tracking[1].proveAfter, "absent means no demand — today's behaviour")
    }

    @Test
    fun an_explicit_null_freshness_mark_decodes_like_an_absent_one() {
        // Both shapes reach us from a proto3 backend depending on how it serialises an unset optional.
        val json = """
            {"activeTracking":[{"dealId":"d1","steamTradeId":null,"proveAfter":null}],"ttlSeconds":92}
        """.trimIndent()
        val deal = TrackerJson.decodeFromString<HeartbeatResponseDto>(json).toDomain().activeTracking[0]

        assertNull(deal.steamTradeId)
        assertNull(deal.proveAfter)
    }

    @Test
    fun tracked_deal_round_trips_with_its_freshness_mark() {
        val dto = TrackedDealDto(
            dealId = "d1",
            watch = listOf("GetTradeStatus"),
            steamTradeId = "744935517744884653",
            proveAfter = "2026-09-02T10:15:30Z",
        )
        assertEquals(dto, roundTrip(dto))
    }

    /**
     * protojson renders an unset proto3 **string** as `""`, and `TradeId`'s own `init require` throws on a
     * blank. Inside `toDomain()` that throw is not scoped to one deal: it aborts the whole heartbeat decode.
     * Drop the `takeIf { it.isNotBlank() }` in the mapper and this test fails with
     * `IllegalArgumentException`, which is what makes it a regression test rather than a shape assertion.
     */
    @Test
    fun a_blank_trade_id_decodes_as_no_trade_rather_than_throwing() {
        val json = """{"activeTracking":[{"dealId":"d1","steamTradeId":""}],"ttlSeconds":92}"""

        assertNull(TrackerJson.decodeFromString<HeartbeatResponseDto>(json).toDomain().activeTracking[0].steamTradeId)
    }

    /**
     * The one that costs the whole install base. A malformed `proveAfter` must degrade to "no demand for
     * this one deal" — with a bare `Instant.parse` it throws out of `HeartbeatResponseDto.toDomain()`, the
     * loop's final `catch (_: Throwable)` counts it as a status-less heartbeat failure, and after
     * `SERVER_ERROR_THRESHOLD` cycles the user is shown "we lost connection with DMarket" on a healthy
     * connection — with no tracking list at all, and with the next-heartbeat schedule never advanced, so
     * every wake re-fails on the same body.
     *
     * Hence the assertions on `ttlSeconds` and the sibling entry: the point is not that the mark is null,
     * it is that everything ELSE in the heartbeat still decoded.
     */
    @Test
    fun a_malformed_freshness_mark_costs_its_own_deal_and_nothing_else() {
        val json = """
            {
              "activeTracking": [
                {"dealId":"d1","steamTradeId":"744935517744884653","proveAfter":"not-a-date"},
                {"dealId":"d2","steamTradeId":"744935517744884654","proveAfter":"2026-09-02T10:15:30Z"}
              ],
              "directives": [{"directiveId":"dir-1","action":"create_offer","dealId":"d2"}],
              "ttlSeconds": 92
            }
        """.trimIndent()
        val domain = TrackerJson.decodeFromString<HeartbeatResponseDto>(json).toDomain()

        assertNull(domain.activeTracking[0].proveAfter, "an unparseable mark is no demand, not a thrown decode")
        assertEquals(TradeId("744935517744884653"), domain.activeTracking[0].steamTradeId, "the rest of the entry survives")
        assertEquals(Instant.parse("2026-09-02T10:15:30Z"), domain.activeTracking[1].proveAfter)
        assertEquals(92, domain.ttlSeconds)
        assertEquals(1, domain.directives.size)
    }

    /**
     * A zero `google.protobuf.Timestamp` parses perfectly and would read as a mark every deal on the account
     * is behind — one full MPC session per tracked deal per cycle, across the install base. An absent demand
     * has to be an absent field, never a zero instant.
     */
    @Test
    fun an_epoch_freshness_mark_is_read_as_no_demand() {
        val json = """{"activeTracking":[{"dealId":"d1","proveAfter":"1970-01-01T00:00:00Z"}],"ttlSeconds":92}"""

        assertNull(TrackerJson.decodeFromString<HeartbeatResponseDto>(json).toDomain().activeTracking[0].proveAfter)
    }

    /**
     * The mark is a latch key compared against a value that has round-tripped through storage, and every
     * `Instant` this client persists is stored as epoch **millis**. protojson emits a `Timestamp` with 0, 3,
     * 6 or 9 fractional digits, so the same instant legitimately arrives spelled two ways — and without
     * flooring at one point, the nanosecond spelling read back from storage is strictly LESS than itself
     * re-parsed next heartbeat, so `incoming > satisfied` holds on every single wake and the deal re-proves
     * forever. Two spellings 502 µs apart must therefore decode equal.
     */
    @Test
    fun a_nanosecond_mark_decodes_equal_to_its_own_millisecond_spelling() {
        fun mark(text: String) = TrackerJson
            .decodeFromString<HeartbeatResponseDto>("""{"activeTracking":[{"dealId":"d1","proveAfter":"$text"}]}""")
            .toDomain().activeTracking[0].proveAfter

        val nanos = mark("2026-09-02T10:15:30.929502890Z")
        val millis = mark("2026-09-02T10:15:30.929Z")
        assertEquals(millis, nanos)
        assertEquals(Instant.fromEpochMilliseconds(nanos!!.toEpochMilliseconds()), nanos, "stored and held are one value")
    }

    /**
     * The live body (verbatim shape from a dev heartbeat, 2026-08-03) once the backend began serving the
     * watch to **both** sides of a deal: `active_tracking[]` mixes roles, and the entry-level `role` is the
     * only thing that says which is which. It is not in the frozen contract yet — hence the third entry,
     * which omits it and must decode as UNKNOWN rather than fail or default to a side.
     */
    @Test
    fun heartbeat_response_decodes_mixed_roles() {
        val json = """
            {
              "activeTracking": [
                {"dealId":"sale-1","steamOfferId":"9277091037","watch":["GetTradeHistory"],"role":"seller"},
                {"dealId":"purchase-1","steamOfferId":"9277092295","watch":["GetTradeHistory"],"role":"buyer"},
                {"dealId":"legacy-1","steamOfferId":"9276726728","watch":["GetTradeHistory"]}
              ],
              "ttlSeconds": 79,
              "linkedSteamId": "76561198077327619"
            }
        """.trimIndent()
        val tracking = TrackerJson.decodeFromString<HeartbeatResponseDto>(json).toDomain().activeTracking
        assertEquals(listOf(DealRole.SELLER, DealRole.BUYER, DealRole.UNKNOWN), tracking.map { it.role })
        // The whole point of the field: only the purchase is barred from the two Steam write surfaces.
        assertTrue(DealRoleBinding.allowsWrite(tracking, DealId("sale-1")))
        assertFalse(DealRoleBinding.allowsWrite(tracking, DealId("purchase-1")))
        assertTrue(DealRoleBinding.allowsWrite(tracking, DealId("legacy-1")))
    }

    @Test
    fun tracked_deal_round_trips_with_its_role() {
        val dto = TrackedDealDto(dealId = "d1", steamOfferId = "9277092295", watch = listOf("GetTradeHistory"), role = "buyer")
        assertEquals(dto, roundTrip(dto))
        assertEquals(DealRole.BUYER, roundTrip(dto).toDomain().role)
    }

    /**
     * Regression for the live dev-environment heartbeat body (protojson camelCase, verbatim from a session
     * log): a snake_case-keyed client silently lost `ttlSeconds` (→ 0, wrong cadence fallback).
     */
    @Test
    fun heartbeat_response_decodes_protojson_camel_case_body() {
        val json = """{"serverTime":"2026-07-06T12:27:00.929502890Z", "ttlSeconds":90}"""
        val domain = TrackerJson.decodeFromString<HeartbeatResponseDto>(json).toDomain()
        assertEquals(90, domain.ttlSeconds)
        assertEquals(0, domain.activeTracking.size)
        assertEquals(0, domain.directives.size)
    }

    @Test
    fun heartbeat_response_decodes_linked_steam_id() {
        val json = """{"ttlSeconds":90,"linkedSteamId":"76561198000000009"}"""
        val domain = TrackerJson.decodeFromString<HeartbeatResponseDto>(json).toDomain()
        assertEquals(SteamId("76561198000000009"), domain.linkedSteamId)
    }

    @Test
    fun heartbeat_response_without_linked_steam_id_is_null() {
        val json = """{"ttlSeconds":90}"""
        assertNull(TrackerJson.decodeFromString<HeartbeatResponseDto>(json).toDomain().linkedSteamId)
    }

    @Test
    fun heartbeat_response_blank_linked_steam_id_is_null() {
        val json = """{"ttlSeconds":90,"linkedSteamId":""}"""
        // A blank id must not construct a SteamId (non-blank require) — the guard maps it to null.
        assertNull(TrackerJson.decodeFromString<HeartbeatResponseDto>(json).toDomain().linkedSteamId)
    }

    /**
     * Regression for the live `/trade-actions` ack (protojson camelCase, verbatim from a session log):
     * a snake_case-keyed client failed to decode `directiveId` (required, no default) and swallowed
     * the rejection.
     */
    @Test
    fun report_directive_response_decodes_protojson_camel_case_body() {
        val json = """{"directiveId":"123", "reason":"deal_id is required"}"""
        val ack = TrackerJson.decodeFromString<ReportDirectiveResponseDto>(json).toDomain()
        assertEquals("123", ack.directiveId.value)
        assertEquals(false, ack.accepted)
        assertEquals("deal_id is required", ack.reason)
    }

    @Test
    fun directive_dto_round_trips() {
        val dto = DirectiveDto(
            directiveId = "dir-2",
            action = "create_offer",
            dealId = "d2",
            partnerSteamId = "76561198000000002",
            assetIds = listOf("a1", "a2"),
            tradeToken = "token",
            contextId = 2,
            steamOfferId = null,
        )
        assertEquals(dto, roundTrip(dto))
    }

    // ---- deal ------------------------------------------------------------------------------

    @Test
    fun deal_dto_round_trips_through_domain() {
        val json = """
            {"dealId":"d1","state":"P2P_DEAL_STATE_AWAITING_TRADE","buyerAccountId":"b1",
             "sellerAccountId":"s1","offerId":"of1","assetId":"a1",
             "price":{"currency":"USD","amount":"1599"},
             "steamOfferId":"st1",
             "trustedAcceptUri":"https://steamcommunity.com/tradeoffer/st1",
             "createTime":"2026-06-16T12:00:00Z","updateTime":"2026-06-16T13:00:00Z",
             "unknownFuture":"ignored"}
        """.trimIndent()
        val deal = TrackerJson.decodeFromString<DealDto>(json).toDomain()
        assertEquals(DealId("d1"), deal.dealId)
        assertEquals(P2PDealState.AWAITING_TRADE, deal.state)
        assertEquals(1599L, deal.price.amountCents)
        assertEquals("USD", deal.price.currencyCode)
        assertEquals("https://steamcommunity.com/tradeoffer/st1", deal.trustedAcceptUri)
    }

    @Test
    fun the_accept_link_is_read_under_either_spelling() {
        // The deal detail served `trustedAcceptUrl` until it was aligned onto the proto's
        // `trusted_accept_uri`. Reading only one key blanks the buyer's accept link on one side of that
        // deploy — silently, because the field is nullable and its absence looks like "not set yet".
        fun decode(acceptKey: String) = TrackerJson.decodeFromString<DealDto>(
            """
            {"dealId":"d1","state":"P2P_DEAL_STATE_AWAITING_TRADE","buyerAccountId":"b1",
             "sellerAccountId":"s1","offerId":"of1","assetId":"a1",
             "price":{"currency":"USD","amount":"1599"},
             "$acceptKey":"https://steamcommunity.com/tradeoffer/st1?partner=1&token=abc",
             "createTime":"2026-06-16T12:00:00Z","updateTime":"2026-06-16T13:00:00Z"}
            """.trimIndent(),
        ).toDomain().trustedAcceptUri

        val expected = "https://steamcommunity.com/tradeoffer/st1?partner=1&token=abc"
        assertEquals(expected, decode("trustedAcceptUri"), "the current spelling")
        assertEquals(expected, decode("trustedAcceptUrl"), "the legacy spelling still served pre-deploy")
    }

    @Test
    fun unknown_deal_state_degrades_to_unknown() {
        val json = """
            {"dealId":"d1","state":"P2P_DEAL_STATE_FUTURE","buyerAccountId":"b1",
             "sellerAccountId":"s1","offerId":"of1","assetId":"a1",
             "price":{"currency":"USD","amount":"1"},
             "createTime":"2026-06-16T12:00:00Z","updateTime":"2026-06-16T12:00:00Z"}
        """.trimIndent()
        val deal = TrackerJson.decodeFromString<DealDto>(json).toDomain()
        assertEquals(P2PDealState.UNKNOWN, deal.state)
    }

    @Test
    fun renamed_states_deserialise_correctly() {
        val awaitingTerminal = TrackerJson.decodeFromString<DealDto>(
            """{"dealId":"d1","state":"P2P_DEAL_STATE_AWAITING_TERMINAL","buyerAccountId":"b1",
               "sellerAccountId":"s1","offerId":"of1","assetId":"a1",
               "price":{"currency":"USD","amount":"1"},
               "createTime":"2026-06-16T12:00:00Z","updateTime":"2026-06-16T12:00:00Z"}""",
        ).toDomain()
        assertEquals(P2PDealState.AWAITING_TERMINAL, awaitingTerminal.state)

        val manualReview = TrackerJson.decodeFromString<DealDto>(
            """{"dealId":"d1","state":"P2P_DEAL_STATE_MANUAL_REVIEW","buyerAccountId":"b1",
               "sellerAccountId":"s1","offerId":"of1","assetId":"a1",
               "price":{"currency":"USD","amount":"1"},
               "createTime":"2026-06-16T12:00:00Z","updateTime":"2026-06-16T12:00:00Z"}""",
        ).toDomain()
        assertEquals(P2PDealState.MANUAL_REVIEW, manualReview.state)
    }

    @Test
    fun deal_action_response_decodes() {
        val result = TrackerJson.decodeFromString<DealActionResponseDto>(
            """{"state":"P2P_DEAL_STATE_COMMITTED","applied":true}""",
        ).toDomain()
        assertEquals(P2PDealState.COMMITTED, result.state)
        assertEquals(true, result.applied)
    }

    // ---- trade-events ----------------------------------------------------------------------

    @Test
    fun report_trade_status_request_round_trips() {
        val dto = ReportTradeStatusRequestDto(
            reports = listOf(
                TradeStatusReportDto(
                    dealId = "d1",
                    source = "offer",
                    steamStatusCode = 3,
                    clientTime = "2026-06-16T12:00:00Z",
                ),
            ),
        )
        assertEquals(dto, roundTrip(dto))
    }

    @Test
    fun a_history_rollback_report_carries_the_reversal_initiator_on_the_wire() {
        // The actor is the whole point of resolving attribution — dropping it at the mapper left the
        // backend with an unattributed rollback it can only park.
        val report = TradeStatusReport(
            dealId = DealId("d1"),
            source = TradeStatusSource.HISTORY,
            steamStatusCode = 12,
            clientTime = Instant.parse("2026-06-16T12:00:00Z"),
            reversalInitiatorSteamId = SteamId("76561198336610283"),
        )
        val encoded = TrackerJson.encodeToString(report.toDto())
        assertTrue(encoded.contains(""""reversalInitiatorSteamId":"76561198336610283""""), encoded)
        assertEquals(report.reversalInitiatorSteamId?.value, roundTrip(report.toDto()).reversalInitiatorSteamId)
    }

    @Test
    fun an_ordinary_status_report_omits_the_initiator_entirely() {
        // A field the backend may not know yet must not appear on every report: null is omitted, so the
        // steady-state batch is byte-identical to what shipped before the field existed.
        val report = TradeStatusReport(
            dealId = DealId("d1"),
            source = TradeStatusSource.OFFER,
            steamStatusCode = 3,
            clientTime = Instant.parse("2026-06-16T12:00:00Z"),
        )
        val encoded = TrackerJson.encodeToString(report.toDto())
        assertEquals(false, encoded.contains("reversalInitiatorSteamId"), encoded)
    }

    @Test
    fun a_history_report_carries_the_settlement_window_as_rfc3339() {
        // Steam publishes unix seconds; this surface is RFC3339, and the conversion is ours. Sending the
        // raw epoch would be silently accepted as a string and land the backend a nonsense window.
        val report = TradeStatusReport(
            dealId = DealId("d1"),
            source = TradeStatusSource.HISTORY,
            steamStatusCode = 3,
            clientTime = Instant.parse("2026-06-16T12:00:00Z"),
            settlementTime = Instant.fromEpochSeconds(1_786_356_000),
        )
        val encoded = TrackerJson.encodeToString(report.toDto())
        assertTrue(encoded.contains(""""settlementTime":"2026-08-10T10:00:00Z""""), encoded)
        assertEquals("2026-08-10T10:00:00Z", roundTrip(report.toDto()).settlementTime)
    }

    @Test
    fun an_ordinary_status_report_omits_the_settlement_window_entirely() {
        // Same contract as the initiator: absent means "window not established", so a null must not be
        // serialised at all — an ordinary batch stays byte-identical to before the field existed.
        val report = TradeStatusReport(
            dealId = DealId("d1"),
            source = TradeStatusSource.OFFER,
            steamStatusCode = 3,
            clientTime = Instant.parse("2026-06-16T12:00:00Z"),
        )
        val encoded = TrackerJson.encodeToString(report.toDto())
        assertEquals(false, encoded.contains("settlementTime"), encoded)
    }

    @Test
    fun report_trade_status_response_decodes() {
        val response = TrackerJson.decodeFromString<ReportTradeStatusResponseDto>(
            """{"results":[{"dealId":"d1","accepted":true}]}""",
        ).toDomain()
        assertEquals(1, response.size)
        assertEquals(DealId("d1"), response.single().dealId)
        assertEquals(true, response.single().accepted)
    }

    // ---- notary ----------------------------------------------------------------------------

    @Test
    fun submit_proof_request_round_trips() {
        val dto = SubmitProofRequestDto(dealId = "d1", proofPayload = "YmFzZTY0")
        assertEquals(dto, roundTrip(dto))
    }

    @Test
    fun submit_proof_response_decodes() {
        val result = TrackerJson.decodeFromString<SubmitProofResponseDto>(
            """{"dealId":"d1","verified":true}""",
        ).toDomain()
        assertEquals(DealId("d1"), result.dealId)
        assertEquals(true, result.verified)
    }

    // ---- trade-actions ---------------------------------------------------------------------

    @Test
    fun report_directive_request_round_trips() {
        val dto = ReportDirectiveRequestDto(
            directiveId = "dir-1",
            dealId = "d1",
            action = "create_offer",
            status = "needs_confirmation",
            steamOfferId = "offer-99",
            error = null,
        )
        assertEquals(dto, roundTrip(dto))
    }

    @Test
    fun report_directives_batch_round_trips_under_the_actions_envelope() {
        val dto = listOf(
            DirectiveOutcome(
                directiveId = DirectiveId("dir-1"),
                action = DirectiveAction.CREATE_OFFER,
                status = DirectiveStatus.NEEDS_CONFIRMATION,
                dealId = DealId("d1"),
                steamOfferId = OfferId("offer-99"),
            ),
            DirectiveOutcome(
                directiveId = DirectiveId("dir-2"),
                action = DirectiveAction.CANCEL_OFFER,
                status = DirectiveStatus.FAILED,
                dealId = DealId("d2"),
                error = "steam refused",
            ),
        ).toRequestDto()

        assertEquals(dto, roundTrip(dto))
        val json = TrackerJson.encodeToString(dto)
        // The envelope name is the contract, and it is the SAME field name /trade-events uses: `{reports:[…]}`.
        assertTrue(json.startsWith("""{"reports":["""), json)
        // explicitNulls = false: an absent steamOfferId/error is omitted, not sent as null.
        assertEquals(false, json.contains("null"), json)
    }

    @Test
    fun report_directives_response_parses_a_partial_batch() {
        // A backend may answer fewer results than actions; the mapper must surface exactly what it said and let
        // the caller treat the missing ones as unaccepted.
        val acks = TrackerJson.decodeFromString<ReportDirectivesResponseDto>(
            """{"results":[{"directiveId":"dir-1","accepted":true},{"directiveId":"dir-2","accepted":false,"reason":"stale lease"}]}""",
        ).toDomain()

        assertEquals(listOf(DirectiveId("dir-1"), DirectiveId("dir-2")), acks.map { it.directiveId })
        assertEquals(listOf(true, false), acks.map { it.accepted })
        assertEquals("stale lease", acks[1].reason)
    }

    @Test
    fun report_directives_response_with_no_results_parses_as_empty() {
        assertTrue(TrackerJson.decodeFromString<ReportDirectivesResponseDto>("{}").toDomain().isEmpty())
    }

    // ---- support fixtures ------------------------------------------------------------------

    @Test
    fun deal_fixture_helper_is_usable() {
        assertEquals(P2PDealState.AWAITING_TRADE, deal().state)
    }
}
