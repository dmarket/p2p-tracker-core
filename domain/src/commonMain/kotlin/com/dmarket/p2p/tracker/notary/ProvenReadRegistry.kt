package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.config.ProvenRead

/**
 * Which Steam endpoints have a proven-read definition, and which of them the operator has turned on.
 *
 * **Why this is its own class rather than two fields on `NotaryConfig`.** That class is `@JsExport`ed, and a
 * `Map`/`Set` constructor parameter does not survive the JS code generation: the parameter is dropped from the
 * generated constructor, so the field reads back `undefined` and every lookup through it is a `TypeError` — a
 * green Kotlin/JVM build with a prover that cannot resolve a single read. (`@JsExport.Ignore` does not help;
 * it does not remove a parameter from the positional constructor either.) A parameter of a plain,
 * non-exported class type does survive — [ProvenRead] itself has been one for as long as the config has
 * existed — so the collections live in here and `NotaryConfig` carries one reference.
 *
 * The two belong together anyway: a definition nobody enabled is inert, and an enabled kind with no definition
 * is a misconfiguration. Holding them in one value is what lets `NotaryConfig.init` check that pairing once.
 */
data class ProvenReadRegistry(
    /**
     * Per-kind replacements for the built-in definitions, empty by default. Anything absent here resolves
     * through [ProvenReadCatalog] — see [definition].
     *
     * **Empty rather than pre-populated, and that is what stops this being pure waste.** `enabled` is
     * `{TRADE_OFFER, TRADE_STATUS}` by default, and both of those resolve from the named `NotaryConfig`
     * fields — so a stock build asks this registry for nothing. A default argument holding the full catalog
     * would instead construct all eight definitions (eight `init` blocks, sixteen host-allow-list URL parses,
     * a full percent-encode pass for the create body) on every service-worker spawn and read none of them.
     *
     * A `Map` and not a resolver function on purpose: this is a `data class` inside another `data class`, and a
     * function reference does not compare or hash reliably, so a lambda here would quietly break
     * `NotaryConfig` equality.
     */
    val overrides: Map<ProvenReadKind, ProvenRead> = emptyMap(),
    /**
     * Which reads may actually be spent on — **the switch this registry exists to give the operator.**
     *
     * The default is exactly the two axes that were provable before the registry existed, so adding the catalog
     * changed no behaviour: a stock build proves what it always proved. Adding a kind here is now the entire
     * cost of enabling it, because the definition, the placeholder fill, the disclosure policy and the tests
     * already exist for all ten.
     *
     * Enabling does **not** imply the disclosures. A `steamcommunity.com` kind additionally needs
     * `NotaryConfig.acknowledgeCommunityResponseDisclosure`, and a write kind needs
     * [ProvenRead.acknowledgeRequestBodyDisclosure] — separate decisions, kept separate so the first cannot
     * silently grant the rest.
     */
    val enabled: Set<ProvenReadKind> = setOf(ProvenReadKind.TRADE_OFFER, ProvenReadKind.TRADE_STATUS),
) {
    /**
     * The definition for [kind] — a host [overrides] entry if there is one, else the built-in catalog entry,
     * else `null` for the two kinds `NotaryConfig`'s named fields own.
     *
     * Constructed on demand, so nothing is built for a kind nobody names.
     */
    fun definition(kind: ProvenReadKind): ProvenRead? = overrides[kind] ?: ProvenReadCatalog.of(kind)
}
