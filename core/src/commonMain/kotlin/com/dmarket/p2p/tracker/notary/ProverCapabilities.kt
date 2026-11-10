package com.dmarket.p2p.tracker.notary

/** Capabilities observed from the installed package's exports, without initializing its WASM. */
data class ProverCapabilities(val proxy: Boolean, val egress: Boolean) {
    fun requireSupport(mode: IssuanceMode, transport: String) {
        check(mode != IssuanceMode.PROXY || proxy) {
            "proxy issuance unsupported: requires a proxy-capable steam-provenance package (missing createProxyProver or proveProxy)"
        }
        check(transport != "egress" || egress) {
            "egress transport unsupported: requires an egress-capable steam-provenance package (missing connectEgressNotary)"
        }
    }
}
