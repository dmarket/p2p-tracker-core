package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.support.steamAccessToken
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

private external fun encodeURIComponent(value: String): String

/**
 * The far side of a hosted proof: [runHostedProof] over the module's seams, and the target-response seam that
 * lets a host refuse to present an error page. The prover modules are fakes, as in [WasmProverHostTest].
 */
class HostedProofRunTest {
    private val now = Clock.System.now()
    private val token = steamAccessToken(STEAM_ID, now + 2.hours)
    private val request = notaryProofRequest(
        ProvenReadBinding(dealId = DealId("d1"), steamOfferId = OfferId("off-1")),
        ProvenReadKind.TRADE_OFFER,
        SteamId(STEAM_ID),
    )

    @BeforeTest
    fun reset() {
        js("globalThis.__hostedProof = { calls: [], status: 200 }")
    }

    @AfterTest
    fun clean() {
        js("delete globalThis.__hostedProof")
    }

    private fun seams(): dynamic = js("globalThis.__hostedProof")

    private fun calls(): List<String> = (seams().calls as Array<String>).toList()

    private fun host(onTargetResponse: ((Int) -> Unit)? = null, socket: Boolean = true) = WasmProverHost(
        assetUrl = { assetUrls.getValue(it) },
        notaryWebSocket = if (socket) ({ js("(function HeaderSocket() {})") }) else null,
        connectTarget = { _, _ -> Promise.resolve(channel()) },
        onTargetResponse = onTargetResponse,
    )

    @Test
    fun a_hosted_proof_connects_with_the_hosts_socket_and_returns_the_presentation() = runTest {
        val presentation = runHostedProof(HostedProofRequest(request, token, SESSION_ID), TrackerConfig.defaults(), host(), now)

        assertEquals("CQk=", presentation)
        val connect = calls().single { it.startsWith("connectNotary") }
        assertEquals("connectNotary(token=signed-upgrade, args=3, impl=HeaderSocket)", connect)
        assertFalse(calls().any { token in it }, "the Steam token reaches no notary call")
    }

    @Test
    fun a_hosted_proof_needs_a_header_socket() = runTest {
        assertFailsWith<IllegalArgumentException> {
            runHostedProof(HostedProofRequest(request, token, SESSION_ID), TrackerConfig.defaults(), host(socket = false), now)
        }
        assertTrue(calls().isEmpty())
    }

    @Test
    fun a_token_for_another_account_proves_nothing() = runTest {
        val other = steamAccessToken("76561198000000099", now + 2.hours)
        assertFailsWith<IllegalArgumentException> {
            runHostedProof(HostedProofRequest(request, other, SESSION_ID), TrackerConfig.defaults(), host(), now)
        }
        assertTrue(calls().isEmpty())
    }

    @Test
    fun the_target_status_reaches_the_host_and_a_refusal_stops_the_presentation() = runTest {
        seams().status = 401
        val seen = mutableListOf<Int>()
        val failure = assertFailsWith<IllegalStateException> {
            runHostedProof(
                HostedProofRequest(request, token, SESSION_ID),
                TrackerConfig.defaults(),
                host(onTargetResponse = { status ->
                    seen += status
                    check(status in 200..299) { "Steam answered HTTP $status" }
                }),
                now,
            )
        }
        assertEquals(listOf(401), seen)
        assertEquals("Steam answered HTTP 401", failure.message)
        assertFalse("present" in calls(), "no presentation of an error page")
    }

    @Test
    fun without_the_seam_the_status_is_not_looked_at() = runTest {
        seams().status = 401
        val presentation = runHostedProof(HostedProofRequest(request, token, SESSION_ID), TrackerConfig.defaults(), host(), now)
        assertEquals("CQk=", presentation)
    }

    private fun channel(): dynamic {
        val channel: dynamic = js("({})")
        channel.read = { Promise.resolve(null) }
        channel.write = { _: dynamic -> Promise.resolve(Unit) }
        channel.close = { Promise.resolve(Unit) }
        return channel
    }

    private companion object {
        const val STEAM_ID = "76561198000000001"
        const val SESSION_ID = "0123456789abcdef01234567"

        const val FAKE_PKG = """
            export default function init() { return Promise.resolve(); }
        """

        /** The façade's shape; `prove` reports the status the test set, and `present` records that it ran. */
        const val FAKE_TRANSPORT = """
            const seams = () => globalThis.__hostedProof;
            const channel = () => ({ read: async () => null, write: async () => {}, close: async () => {} });
            export function createProver(wasm) {
              return {
                async initialize() { await wasm.default?.(); },
                async prove(notaryIo, serverIo) {
                  await serverIo.write(new Uint8Array([22]));
                  return { attestation: new Uint8Array([1]), secrets: new Uint8Array([2]), response: { status: seams().status } };
                },
                present() { seams().calls.push("present"); return new Uint8Array([9, 9]); },
              };
            }
            export async function connectNotary(...args) {
              seams().calls.push("connectNotary(token=" + args[1] + ", args=" + args.length + ", impl=" + args[2]?.WebSocketImpl?.name + ")");
              return channel();
            }
            export async function connectProxy() { seams().calls.push("connectProxy"); return channel(); }
        """

        val assetUrls = mapOf(
            "pkg/client_wasm.js" to dataUrl(FAKE_PKG),
            "transport/dist/index.js" to dataUrl(FAKE_TRANSPORT),
        )

        fun dataUrl(source: String) = "data:text/javascript," + encodeURIComponent(source)
    }
}
