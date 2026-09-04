package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.game.Cs2GameAdapter
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Asserts the one place the Steam credential is joined to the proven request.
 *
 * It exists because that line had **no** coverage: deleting the substitution left every test green, and
 * the only symptom would have been a live proof issuing `?access_token={token}` verbatim — a Steam 401
 * inside MPC, which surfaces to the client as an opaque `ProofFailed`.
 */
class WasmProverRequestTest {

    private val token = "eyJhbGciOiJIUzI1NiJ9.steam-access-token.sig"

    private fun request(source: TradeStatusSource = TradeStatusSource.OFFER) = WasmProveRequest(
        notaryUrl = "wss://notary.test/",
        notaryToken = "dmarket-token",
        proxyBaseUrl = "wss://proxy.test",
        spec = SteamProofReadMapper(NotaryConfig()).readSpec(
            source = source,
            binding = ProvenReadBinding(
                dealId = DealId("d1"),
                steamOfferId = OfferId("off-1"),
                tradeId = com.dmarket.p2p.tracker.model.TradeId("trade-1"),
            ),
            subjectSteamId = SteamId("76561198000000001"),
            adapter = Cs2GameAdapter(),
        ),
        steamAccessToken = token,
        maxSentData = 4_096,
        maxRecvData = 16_384,
        maxRecvDataOnline = 512,
    )

    @Test
    fun the_production_path_leaves_the_root_store_untouched() {
        // The most important assertion of the three: `rootStorePem` defaults to null, and an ABSENT
        // `rootStore` key is what keeps the prover on its bundled Mozilla web-PKI set. Writing the key at all
        // on this path — even as the string "mozilla" — would change what every existing deployment runs on.
        assertEquals(null, NotaryConfig().rootStorePem)
        for (source in TradeStatusSource.entries) {
            val issuance = issuanceConfig(request(source))
            assertEquals(undefined, issuance.rootStore, "$source must not set rootStore when no PEM is configured")
        }
    }

    @Test
    fun the_online_receive_budget_reaches_the_prover_explicitly() {
        // Always written, never left to the artifact's own default: this value decides how much of the response
        // is decrypted online versus deferred, and the deferred path is the one under investigation for the
        // wedge. An absent key would mean "whatever this prover build defaults to", which is precisely the
        // variable the A/B is trying to control.
        for (source in TradeStatusSource.entries) {
            val issuance = issuanceConfig(request(source).copy(maxRecvDataOnline = 8_192))
            assertEquals(8_192, issuance.maxRecvDataOnline as Int, "$source must carry the configured online budget")
        }
        // Whatever the request carries is what reaches the prover — the fixture's own value, not a default.
        assertEquals(512, issuanceConfig(request()).maxRecvDataOnline as Int)
    }

    @Test
    fun the_shipped_online_budget_covers_the_measured_offer_response() {
        // 1024: the record layer itself reported the requirement (802 B for the offer response) when a 32 B budget
        // failed fast, and the configured value reaches it doubled. Below the artifact 2048 default on purpose: the
        // upload scales with this, 38.3 MB against 48.3 MB. Pinned because it is a measured setting, not a
        // passthrough — both directions are wrong: drifting up restores the 48 MB upload, drifting below 802 B
        // bricks every proof on build 423 with a deterministic record layer error.
        assertEquals(1_024, NotaryConfig().maxRecvDataOnline)
    }

    @Test
    fun the_record_budget_writes_no_key_until_an_operator_sets_one() {
        // The mirror image of the byte budget one test up, and the difference is the whole point: that one is
        // ALWAYS written because the artifact documents its default (2 KiB) and our config mirrors it. This one
        // has no documented default, so an absent key is the only way to say "whatever the contract default is"
        // — which is what every deployment before this field existed ran on. Writing a guess here could LOWER
        // the effective budget, and an online budget too small for the response head is the deterministic
        // `record layer error` that a 32 B byte budget produced.
        assertEquals(null, NotaryConfig().maxRecvRecordsOnline)
        for (source in TradeStatusSource.entries) {
            val issuance = issuanceConfig(request(source))
            assertEquals(undefined, issuance.maxRecvRecordsOnline, "$source must not set a record budget by default")
        }
    }

    @Test
    fun a_configured_record_budget_reaches_the_prover_unclamped() {
        // Deliberately paired with a receive ceiling far below it: records and bytes are different units, so the
        // clamp that guards `maxRecvDataOnline` must NOT be copied onto this one. A `minOf` here would silently
        // rewrite "4 records" as "512 bytes" — a config that means something else than it says, and the kind of
        // arithmetic that reads as correct in review.
        val issuance = issuanceConfig(request().copy(maxRecvRecordsOnline = 4, maxRecvData = 512))

        assertEquals(4, issuance.maxRecvRecordsOnline as Int)
    }

    @Test
    fun a_record_budget_below_one_is_rejected_but_no_relation_to_the_byte_cap_is_invented() {
        assertFailsWith<IllegalArgumentException> { NotaryConfig(maxRecvRecordsOnline = 0) }
        // …and a record count above the byte budget is ACCEPTED on purpose. "A record is at least one byte, so
        // records <= bytes" is our inference, not upstream's rule, and a guard built on it would reject the very
        // measurement run that would settle what this cap does.
        assertEquals(9_999, NotaryConfig(maxRecvDataOnline = 1_024, maxRecvRecordsOnline = 9_999).maxRecvRecordsOnline)
    }

    @Test
    fun the_trace_names_the_record_budget_as_the_wasm_sees_it() {
        // `default` rather than `null`, for the same reason `rootStore` prints `mozilla` when absent: the line
        // reports what the PROVER will do, and the number an absent key resolves to lives in the artifact, not
        // in any config this line could read. A reader of the log must not mistake it for "unset, therefore 0".
        assertTrue("maxRecvRecordsOnline=default" in issuanceLine(request()), issuanceLine(request()))
        assertTrue("maxRecvRecordsOnline=4" in issuanceLine(request().copy(maxRecvRecordsOnline = 4)))
    }

    @Test
    fun the_online_budget_cannot_exceed_the_receive_ceiling() {
        // Bytes cannot be preprocessed online that the record layer will refuse to receive at all, so this is a
        // config that cannot mean what it says.
        assertFailsWith<IllegalArgumentException> { NotaryConfig(maxRecvData = 4_096, maxRecvDataOnline = 8_192) }
    }

    @Test
    fun a_configured_pem_reaches_the_prover_as_the_object_form() {
        // The wasm's `RootStore` is `"mozilla" | { pem }`, so the object shape IS the contract — a bare string
        // here would be read as the preset name and the fixture CA would silently never apply.
        val pem = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n"
        val issuance = issuanceConfig(request().copy(rootStorePem = pem))

        assertEquals(pem, issuance.rootStore.pem as String)
    }

    @Test
    fun the_request_describes_the_pem_rather_than_printing_it() {
        // Not a secret — a root certificate is public — but a multi-kilobyte blob in every log line of a
        // failing proof helps nobody, and this `toString` is the one that reaches a session log.
        val pem = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n"
        val printed = request().copy(rootStorePem = pem).toString()

        assertTrue("rootStorePem=<${pem.length} chars>" in printed, printed)
        assertTrue("BEGIN CERTIFICATE" !in printed, printed)
        // The two credentials this request carries must still never appear.
        assertTrue(token !in printed, printed)
        assertTrue("dmarket-token" !in printed, printed)
    }

    @Test
    fun the_steam_token_is_substituted_into_the_uri_at_the_io_edge() {
        for (source in TradeStatusSource.entries) {
            val req = request(source)
            val uri = httpRequest(req).uri as String

            assertTrue("access_token=$token" in uri, "$source must carry the substituted token: $uri")
            assertTrue("{token}" !in uri, "$source must leave no unfilled placeholder: $uri")
            // The spec it came from still holds the placeholder — that separation is what keeps the
            // credential out of any pure value, and therefore out of logs and `toString`s.
            assertTrue("{token}" in req.spec.path, "the spec must be unchanged: ${req.spec.path}")
        }
    }

    @Test
    fun the_wire_policy_asks_for_the_whole_response_on_both_axes() {
        // The wasm's `BodyReveal` is an untagged union — `"all" | "none" | { jsonPaths }` — so the bare STRING
        // is the wire shape here, not an object with a discriminator. Asserted on the REAL object handed to
        // the module, per axis: the Kotlin-side reveal mode proves nothing if it maps to something the prover
        // reads differently, and `"none"` (unreachable from `ResponseBodyReveal`) is one typo away.
        for (source in TradeStatusSource.entries) {
            val policy = revealPolicy(request(source).spec)

            assertEquals("all", policy.revealResponseBody as String, "$source must ask for the whole response")
            // Unchanged by the widening, and the one thing that must never widen with it: the target carries
            // `access_token=<Steam JWT>`, and target disclosure is all-or-nothing.
            assertEquals(false, policy.revealRequestTarget as Boolean, "$source must still withhold the request target")
        }
    }

    @Test
    fun a_path_reveal_still_maps_to_the_selective_wire_shape() {
        // Nothing produces `JsonPaths` today (see the mapper's `TODO(disclosure)`), so without this the branch
        // we intend to narrow back to would be uncovered — and a broken mapping would surface only on the day
        // we flip it, as a proof rejected for a reason nobody is looking at.
        val spec = request().spec.copy(responseBodyReveal = ResponseBodyReveal.JsonPaths(listOf("a.b", "c.0.d")))

        val body = revealPolicy(spec).revealResponseBody

        assertEquals(listOf("a.b", "c.0.d"), (body.jsonPaths as Array<String>).toList())
    }

    @Test
    fun the_request_adds_no_headers_so_nothing_can_leak_through_one() {
        // `client_core::issue` injects Host / Accept-Encoding: identity / Connection: close itself. This
        // request adds none — the credential rides the query string of a target the presentation withholds.
        // A header added here would be revealed IN FULL unless named in `redactRequestHeaderValues`.
        //
        // Still true now that headers CAN be sent, and that is the point of keeping it: the token-authed reads
        // — both trade axes — must stay byte-for-byte what they were, with no `headers` key at all rather than
        // an empty array.
        val http = httpRequest(request())
        assertEquals(undefined, http.headers, "the proven read must add no request headers")
        assertEquals(undefined, http.body, "a GET must carry no body")
        assertEquals("GET", http.method as String)
    }

    // ---- the cookie-authenticated surface --------------------------------------------------------

    private fun communityRequest(kind: ProvenReadKind) = WasmProveRequest(
        notaryUrl = "wss://notary.test/",
        notaryToken = "dmarket-token",
        proxyBaseUrl = "wss://proxy.test",
        spec = SteamProofReadMapper(NotaryConfig(acknowledgeCommunityResponseDisclosure = true)).readSpec(
            kind = kind,
            binding = ProvenReadBinding(
                dealId = DealId("d1"),
                steamOfferId = OfferId("off-1"),
                assetId = com.dmarket.p2p.tracker.model.AssetId("asset-1"),
                partnerSteamId = SteamId("76561198000000002"),
                tradeToken = "tok-1",
                assetsToGive = listOf(com.dmarket.p2p.tracker.model.AssetId("asset-1")),
            ),
            subjectSteamId = SteamId("76561198000000001"),
            adapter = Cs2GameAdapter(),
        ),
        steamAccessToken = token,
        maxSentData = 8_192,
        maxRecvData = 65_536,
        maxRecvDataOnline = 1_024,
        steamSessionCookie = COOKIE_VALUE,
        steamSessionId = SESSION_ID_VALUE,
    )

    @Test
    fun a_cookie_authed_read_sends_the_session_header_filled_at_the_io_edge() {
        // The gap that made the whole community surface unprovable: this function set no `headers` key at all,
        // so a read authenticating by `steamLoginSecure` could not be expressed even though the prover accepts
        // `headers?: [string, string][]`.
        val req = communityRequest(ProvenReadKind.OWN_INVENTORY)
        val headers = httpRequest(req).headers.unsafeCast<Array<Array<String>>>().map { it[0] to it[1] }

        assertEquals(COOKIE_VALUE, headers.single { it.first == "cookie" }.second)
        // The spec it came from still holds the placeholder — the same separation the Steam token relies on.
        assertTrue(req.spec.sendHeaders.any { "{cookie}" in it.valueTemplate }, "${req.spec.sendHeaders}")
    }

    @Test
    fun the_session_cookie_is_redacted_from_the_presentation_it_is_sent_in() {
        // Sending is not disclosing. The prover reveals every request header IN FULL unless its name is listed,
        // so this pairing is the only thing keeping the session out of the proof.
        val spec = communityRequest(ProvenReadKind.OWN_INVENTORY).spec
        val policy = revealPolicy(spec)

        val redacted = (policy.redactRequestHeaderValues as Array<String>).toList()
        assertTrue("cookie" in redacted, "the cookie header must be redacted: $redacted")
        assertTrue(spec.sendHeaders.any { it.name == "cookie" }, "…and it must actually be sent: ${spec.sendHeaders}")
    }

    @Test
    fun a_proven_write_carries_its_form_body_as_bytes() {
        val req = communityRequest(ProvenReadKind.CANCEL_OFFER)
        val http = httpRequest(req)

        assertEquals("POST", http.method as String)
        val body = (http.body as Array<Int>).map { it.toByte() }.toByteArray().decodeToString()
        assertEquals("sessionid=$SESSION_ID_VALUE", body)
        assertTrue("{sessionId}" !in body, "the IO edge must fill the sessionid slot: $body")
    }

    @Test
    fun the_create_body_reaches_the_prover_with_every_slot_filled() {
        val body = (httpRequest(communityRequest(ProvenReadKind.CREATE_OFFER)).body as Array<Int>)
            .map { it.toByte() }.toByteArray().decodeToString()

        assertTrue(SESSION_ID_VALUE in body, "sessionid must be filled: $body")
        assertTrue("partner=76561198000000002" in body, "the partner steamid64 must be filled: $body")
        assertTrue("{" !in body && "}" !in body, "no unfilled placeholder may reach the wire: $body")
    }

    @Test
    fun per_read_caps_reach_the_issuance_config() {
        // A create needs a far larger send budget than `196 + len(token)`; raising it globally would make every
        // trade-axis proof pay the pre-processing (42 MB measured for a 717 B request).
        val issuance = issuanceConfig(communityRequest(ProvenReadKind.CREATE_OFFER))

        assertEquals(8_192, issuance.maxSentData as Int)
        assertEquals(65_536, issuance.maxRecvData as Int)
    }

    @Test
    fun the_online_budget_is_clamped_to_the_effective_receive_ceiling() {
        // The two caps are now set independently per read, so a config that would ask the prover to preprocess
        // more bytes online than the record layer will receive has to be reconciled somewhere — upstream
        // rejects it outright.
        val issuance = issuanceConfig(communityRequest(ProvenReadKind.CANCEL_OFFER).copy(maxRecvData = 512, maxRecvDataOnline = 4_096))

        assertEquals(512, issuance.maxRecvDataOnline as Int)
    }

    @Test
    fun the_request_redacts_the_web_session_credentials_it_carries() {
        // A proof discloses `sessionid`; a log line has no reason to. Different channels, different rules.
        val printed = communityRequest(ProvenReadKind.CREATE_OFFER).toString()

        assertTrue(COOKIE_VALUE !in printed, printed)
        assertTrue(SESSION_ID_VALUE !in printed, printed)
        assertTrue("steamSessionCookie=<redacted>" in printed, printed)
        assertTrue("steamSessionId=<redacted>" in printed, printed)
    }

    @Test
    fun the_issuance_trace_reports_the_new_slots_without_printing_them() {
        // Same reasoning as the existing `tokenSlot=` flag: a host scrubber rewrites cookie-shaped values, so
        // the filled header cannot answer "did this request carry a session at all?" — which for a cookie-authed
        // read is the difference between a proof and an attested logged-out page.
        val line = issuanceLine(communityRequest(ProvenReadKind.CREATE_OFFER))

        assertTrue("cookieSlot=filled" in line, line)
        assertTrue("sessionIdSlot=filled" in line, line)
        assertTrue("bodyBytes=" in line, line)
        assertTrue(COOKIE_VALUE !in line, line)
        assertTrue(SESSION_ID_VALUE !in line, line)
    }

    @Test
    fun a_missing_credential_shows_up_in_the_trace_rather_than_going_out_empty() {
        // An empty cookie header would produce an unauthenticated Steam request that still looks well-formed,
        // and the attestation would faithfully prove a logged-out response. The prover fails before this point,
        // so reaching here is a bug — and the trace is what names it.
        val line = issuanceLine(communityRequest(ProvenReadKind.CREATE_OFFER).copy(steamSessionCookie = null))

        assertTrue("cookieSlot=MISSING" in line, line)
    }

    private companion object {
        const val COOKIE_VALUE = "steamLoginSecure=76561198000000001%7C%7Cjwt-value; sessionid=abc123"
        const val SESSION_ID_VALUE = "abc123"
    }
}
