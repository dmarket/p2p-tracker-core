package com.dmarket.p2p.tracker.net

import kotlin.io.encoding.Base64

/**
 * Why a Steam write was not sent: the Steam proxy the account's traffic goes through could not be reached. Fixed
 * text, with no address and no credential in it, for the loop's `SteamWriteDeferred` event.
 */
const val STEAM_PROXY_UNREACHABLE_REASON: String = "not sent: the Steam proxy could not be reached"

/**
 * The `code` a Steam transport puts on the error of a connection it could not open through the proxy, so the HTTP
 * layer can tell it from a failure past the proxy and never mistake it for Steam not answering.
 */
const val STEAM_PROXY_UNREACHABLE_CODE: String = "STEAM_PROXY_UNREACHABLE"

/**
 * The HTTP proxy one Steam account's traffic goes through, `http://user:pass@host:port`: every connection to Steam
 * is a `CONNECT` tunnel through it, so Steam sees the proxy's address for that account and never the host's.
 *
 * **A secret, all of it.** The credentials are, and so is the address: it is the account's identity to Steam, and
 * a list of them is the map of a trader's accounts. Nothing here prints any part of it — [toString] is redacted,
 * every refusal of [parse] names the rule broken and never the value — and [redact] takes it out of a text that
 * may carry it. Only the transport reads [host], [port] and [authorization], to dial; only the IPC to a prover
 * process carries [url].
 *
 * HTTP `CONNECT` only, to an `http://` proxy: the tunnel carries TLS end to end with Steam, so the proxy sees the
 * host name and nothing else. No SOCKS.
 */
class SteamProxy private constructor(
    val host: String,
    val port: Int,
    private val username: String?,
    private val password: String?,
    private val raw: String,
) {
    /** [host] as a socket dials it: an IPv6 literal without its brackets. */
    val dialHost: String get() = host.trim('[', ']')

    /** The `Proxy-Authorization` value for the `CONNECT`, or `null` for a proxy that takes none. */
    fun authorization(): String? {
        val user = username ?: return null
        return "Basic " + Base64.encode("$user:${password.orEmpty()}".encodeToByteArray())
    }

    /** The URL as the host gave it. For the IPC to a prover process only, beside the Steam token. */
    fun url(): String = raw

    /**
     * [text] with every part of this proxy that could identify it — the URL, the address, the credentials, raw and
     * percent-encoded — replaced by `<proxy>`. The backstop for a text this library did not write itself.
     */
    fun redact(text: String): String = secrets.fold(text) { acc, secret -> acc.replace(secret, REDACTED) }

    // Longest first, so the URL goes whole before its parts are looked for.
    private val secrets: List<String> by lazy {
        val authority = raw.substringAfter("://").substringBefore('/')
        val rawUserInfo = authority.substringBeforeLast('@', missingDelimiterValue = "")
        listOf(
            raw,
            authority,
            rawUserInfo,
            rawUserInfo.substringBefore(':'),
            rawUserInfo.substringAfter(':', missingDelimiterValue = ""),
            "$host:$port",
            host,
            dialHost,
            username.orEmpty(),
            password.orEmpty(),
            authorization()?.removePrefix("Basic ").orEmpty(),
        ).filter { it.length >= MIN_SECRET_LENGTH }.distinct().sortedByDescending { it.length }
    }

    override fun equals(other: Any?): Boolean = other is SteamProxy &&
        host == other.host &&
        port == other.port &&
        username == other.username &&
        password == other.password

    override fun hashCode(): Int = ((host.hashCode() * PRIME + port) * PRIME + username.hashCode()) * PRIME + password.hashCode()

    override fun toString(): String = "SteamProxy(<redacted>)"

    companion object {
        const val REDACTED: String = "<proxy>"

        /** Shorter values (a one-letter user name, say) would redact every occurrence of that letter: not secret. */
        private const val MIN_SECRET_LENGTH = 3
        private const val PRIME = 31
        private const val MAX_PORT = 65_535
        private const val MAX_URL_LENGTH = 2_048

        /** `http://`, case-insensitive. */
        private const val SCHEME = "http://"

        /** A host name or IPv4 address: letters, digits, dots and hyphens. */
        private val HOST_NAME = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")

        /** An IPv6 address inside its brackets. */
        private val IPV6 = Regex("^\\[[0-9A-Fa-f:.]+\\]$")

        /**
         * Reads `http://[user[:pass]@]host:port[/]`. The user and password may be percent-encoded. Throws
         * [IllegalArgumentException] naming the rule broken, never the value.
         */
        fun parse(url: String): SteamProxy {
            require(url.length <= MAX_URL_LENGTH) { "proxy is too long" }
            require(
                url.none {
                    it.isWhitespace() || it.code < SPACE || it.code == DELETE
                },
            ) { "proxy must not contain spaces or control characters" }
            val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
            require(scheme == "http") {
                if (scheme.startsWith("socks")) "proxy must be an http:// URL: SOCKS is not supported" else "proxy must be an http:// URL"
            }
            val rest = url.substring(SCHEME.length)
            val authority = rest.substringBefore('/')
            require(rest.length == authority.length || rest == "$authority/") { "proxy must have no path, query or fragment" }
            require('?' !in authority && '#' !in authority) { "proxy must have no path, query or fragment" }
            val at = authority.lastIndexOf('@')
            val userInfo = if (at >= 0) authority.substring(0, at) else null
            val hostPort = authority.substring(at + 1)
            val (host, port) = hostAndPort(hostPort)
            val (user, password) = userInfo?.let(::credentials) ?: (null to null)
            return SteamProxy(host, port, user, password, url)
        }

        private fun hostAndPort(hostPort: String): Pair<String, Int> {
            val colon = hostPort.lastIndexOf(':')
            require(colon > 0 && (hostPort.indexOf(']') < colon)) { "proxy must name a host and a port" }
            val host = hostPort.substring(0, colon)
            val port = hostPort.substring(colon + 1).takeIf { it.all(Char::isDigit) && it.length in 1..5 }?.toInt()
            require(port != null && port in 1..MAX_PORT) { "proxy port must be 1 to $MAX_PORT" }
            require(HOST_NAME.matches(host) || IPV6.matches(host)) { "proxy host must be a host name or an IP address" }
            return host to port
        }

        private fun credentials(userInfo: String): Pair<String, String?> {
            val colon = userInfo.indexOf(':')
            val user = percentDecoded(if (colon >= 0) userInfo.substring(0, colon) else userInfo)
            val password = if (colon >= 0) percentDecoded(userInfo.substring(colon + 1)) else null
            require(user.isNotEmpty()) { "proxy user must not be empty" }
            return user to password
        }

        /** `%XX` sequences as UTF-8; a malformed one is refused rather than guessed at. */
        private fun percentDecoded(text: String): String {
            if ('%' !in text) return text
            val bytes = ArrayList<Byte>(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%') {
                    val byte = text.substring(i + 1, minOf(i + PERCENT_LENGTH, text.length)).takeIf {
                        it.length == 2 && it.all(::isHexDigit)
                    }?.toIntOrNull(HEX)
                    require(byte != null) { "proxy credentials have a malformed percent escape" }
                    bytes += byte.toByte()
                    i += PERCENT_LENGTH
                } else {
                    c.toString().encodeToByteArray().forEach { bytes += it }
                    i++
                }
            }
            return try {
                bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                throw IllegalArgumentException("proxy credentials have a malformed percent escape")
            }
        }

        private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        private const val SPACE = 0x20
        private const val DELETE = 0x7f
        private const val HEX = 16
        private const val PERCENT_LENGTH = 3
    }
}
