package com.dmarket.p2p.tracker.net

import kotlin.text.RegexOption.IGNORE_CASE

/**
 * Pure, platform-agnostic redaction of secrets from captured HTTP metadata. This is the
 * **security-critical** unit of the network-observability feature: it runs in `:domain` (so it is
 * covered by the module's coverage gate and reusable on every platform) and the `:core` Ktor plugin is
 * a thin shim that extracts raw strings, calls these functions, and only then constructs a
 * [com.dmarket.p2p.tracker.model.NetworkExchange]. No un-redacted value ever reaches the record.
 *
 * What it scrubs:
 * - **URL query params** whose name matches a secret (e.g. Steam's `?access_token=<jwt>`).
 * - **Headers** carrying credentials (`Authorization`, `Cookie`, `Set-Cookie`) — value fully replaced.
 * - **Bodies**: form/JSON assignments to secret-named keys, the Steam HTML token attribute, and — keyed on
 *   **shape** rather than on a name — any JWT-looking run ([JWT_SHAPE]).
 *
 * Name-keyed scrubbing alone is not enough, which is why [JWT_SHAPE] exists: a token is regularly echoed
 * with no recognisable key in front of it (`Bearer <jwt>` inside a server error string; Steam's `strError`,
 * whose value is *escaped* JSON so the `"name":"value"` pattern never matches; a single-quoted pseudo-JSON
 * dump), and a name-keyed set can never survive an upstream key rename. Both layers always run.
 */
object NetworkRedaction {
    const val REDACTED: String = "<redacted>"

    /** Header names (compared case-insensitively) whose entire value must be replaced. */
    private val SECRET_HEADER_NAMES: Set<String> = setOf("authorization", "cookie", "set-cookie")

    /**
     * Query/form/JSON key names (compared case-insensitively) whose value must be replaced. The caller
     * augments this with the config-driven Steam access-token param name so a config change cannot
     * desync the redactor from the real wire shape.
     */
    val DEFAULT_SECRET_PARAM_NAMES: Set<String> = setOf(
        "access_token",
        "token",
        "trade_offer_access_token",
        "sessionid",
        "steamloginsecure",
        "dm-trade-token",
        // The DMarket token-refresh exchange. The REQUEST body is `{"RefreshToken": …}` — a ~30-day bearer
        // for the whole account — and the RESPONSE carries a fresh pair under `AuthToken`/`RefreshToken`.
        // The access half is JWT-shaped and would also be caught by [JWT_SHAPE]; the refresh half is opaque,
        // so only its key name can save it. Both cookie names are here too because the same values travel as
        // cookies in host-side logs.
        "refreshtoken",
        "authtoken",
        "dm-trade-refresh-token",
        // Steam `settoken` re-mint secrets (logged by FetchSteamWebSessionGateway).
        "nonce",
        "auth",
        // The live `steamLoginSecure` access token, posted as the `prior` form field of `settoken`.
        "prior",
        // A bearer capability to send THIS user a trade offer. The wire key is literally `tradeToken`
        // (DirectiveDto), and the same value rides inside Deal.trustedAcceptUrl as `?token=`.
        "tradeToken",
    )

    /**
     * A JWT-shaped run: `eyJ` (the base64url of `{"`) followed by two more base64url segments. Keyed on
     * shape, so it catches a token that no key name precedes — see the class KDoc for why that is
     * required rather than defensive.
     */
    val JWT_SHAPE: Regex = Regex("eyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]*")

    /**
     * Returns [DEFAULT_SECRET_PARAM_NAMES] plus [name] — the one supported way to teach the redactor a
     * config-renamed parameter (e.g. `SteamEndpointsConfig.paramAccessToken`). Public because both `:core`
     * client factories and `:debug-harness` need it, and `internal` in `:core` is invisible to the latter
     * (it is a plain `api(project(":core"))` dependency, not a friend module).
     */
    fun plusSecretParam(name: String): Set<String> = if (name.isBlank()) DEFAULT_SECRET_PARAM_NAMES else DEFAULT_SECRET_PARAM_NAMES + name

    /**
     * Replaces the value of every secret-named query parameter in [url]; leaves everything else intact.
     *
     * Scope: the **query string only**. A secret in the path or the fragment passes through. No request
     * this core builds puts one there (every secret is a query param, a form field, or a cookie), but a
     * caller handing this an arbitrary URL should not assume otherwise; [redactBody]'s [JWT_SHAPE] rule is
     * the shape-keyed backstop for a token that turns up somewhere unexpected.
     */
    fun redactUrl(url: String, secretParamNames: Set<String> = DEFAULT_SECRET_PARAM_NAMES): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        val base = url.substring(0, queryStart)
        val rest = url.substring(queryStart + 1)
        // Preserve a trailing fragment (#...) if present.
        val fragmentStart = rest.indexOf('#')
        val query = if (fragmentStart < 0) rest else rest.substring(0, fragmentStart)
        val fragment = if (fragmentStart < 0) "" else rest.substring(fragmentStart)
        val secrets = secretParamNames.toLowerCaseSet()
        val redactedQuery = query.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) {
                pair
            } else {
                val name = pair.substring(0, eq)
                if (name.lowercase() in secrets) "$name=$REDACTED" else pair
            }
        }
        return "$base?$redactedQuery$fragment"
    }

    /** Returns a copy of [headers] with the value of every credential-bearing header replaced. */
    fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (name, value) -> if (name.lowercase() in SECRET_HEADER_NAMES) REDACTED else value }

    /**
     * Scrubs secrets from [body], then truncates to [maxLen] characters. Handles form/query style
     * (`name=value`), JSON style (`"name":"value"`), the **percent-encoded** JSON that Steam's create body
     * carries (`%22name%22%3A%22value%22`, e.g. the buyer token inside `trade_offer_create_params`), the
     * Steam Community HTML `data-loyalty_webapi_token` attribute, and any [JWT_SHAPE] run regardless of
     * what precedes it. Returns `null` for a `null` body.
     *
     * Scrubbing runs **before** truncation, deliberately: capping first can cut a token mid-value, leaving
     * a partial credential that no longer matches any pattern.
     */
    fun redactBody(body: String?, secretParamNames: Set<String> = DEFAULT_SECRET_PARAM_NAMES, maxLen: Int = 4096): String? {
        if (body == null) return null
        val patterns = if (secretParamNames === DEFAULT_SECRET_PARAM_NAMES) DEFAULT_BODY_PATTERNS else bodyPatternsFor(secretParamNames)
        var scrubbed: String = body
        for ((regex, replacement) in patterns) {
            scrubbed = scrubbed.replace(regex, replacement)
        }
        // Steam Community HTML token attribute (defense-in-depth for any scraped HTML body).
        scrubbed = scrubbed.replace(LOYALTY_TOKEN_ATTR, "$1$REDACTED")
        // Shape-keyed backstop: a JWT with no key name in front of it (see the class KDoc). Runs last so
        // the name-keyed replacements above keep their readable `name=<redacted>` shape.
        scrubbed = scrubbed.replace(JWT_SHAPE, REDACTED)
        return if (scrubbed.length > maxLen) scrubbed.substring(0, maxLen) + "…[truncated]" else scrubbed
    }

    /** The three (regex → replacement) scrub patterns for one secret name. */
    private fun bodyPatternsFor(secretParamNames: Set<String>): List<Pair<Regex, String>> = secretParamNames.flatMap { name ->
        val escaped = Regex.escape(name)
        listOf(
            // form/query: name=value  (value runs until & or whitespace or quote)
            Regex("($escaped)=([^&\\s\"']+)", IGNORE_CASE) to "$1=$REDACTED",
            // JSON: "name": "value"  or  "name":"value"
            Regex("(\"$escaped\"\\s*:\\s*\")([^\"]*)(\")", IGNORE_CASE) to "$1$REDACTED$3",
            // percent-encoded JSON: %22name%22%3A%22value%22 (value runs until the next %22)
            Regex("(%22$escaped%22%3A%22).*?(%22)", IGNORE_CASE) to "$1$REDACTED$2",
        )
    }

    /** Steam Community HTML token attribute (constant — compiled once, not per body). */
    private val LOYALTY_TOKEN_ATTR: Regex = Regex("(data-loyalty_webapi_token=)(\\S+)", IGNORE_CASE)

    /** Precompiled patterns for the common (default) secret set; custom sets compile on the fly. */
    private val DEFAULT_BODY_PATTERNS: List<Pair<Regex, String>> = bodyPatternsFor(DEFAULT_SECRET_PARAM_NAMES)

    private fun Set<String>.toLowerCaseSet(): Set<String> = mapTo(HashSet(size)) { it.lowercase() }
}
