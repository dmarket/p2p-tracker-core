package com.dmarket.p2p.sdk.secrets

/**
 * A DMarket Trading API key pair, as the bot pushes it: [publicKey] goes on every request as `X-Api-Key`,
 * [secretSeed] signs. Both lowercase hex.
 *
 * DMarket hands out the secret in NaCl form, the 32-byte seed followed by the 32-byte public key; a bare seed is
 * accepted too. When the public half is present it must equal [publicKey], which catches a pair pasted from two
 * different keys before the first request is refused for it.
 *
 * Held in memory only, and never printed: [toString] names the public key alone.
 */
class DmarketKey private constructor(val publicKey: String, val secretSeed: String) {

    override fun toString(): String = "DmarketKey(publicKey=$publicKey, secret=<redacted>)"

    override fun equals(other: Any?): Boolean = other is DmarketKey && other.publicKey == publicKey && other.secretSeed == secretSeed

    override fun hashCode(): Int = publicKey.hashCode()

    companion object {
        private const val KEY_HEX = 64
        private val HEX = Regex("^[0-9a-f]+$")

        /** The key pair, or `null` when either half is not hex of the right length or the two do not belong together. */
        fun parse(publicKey: String, secretKey: String): DmarketKey? {
            val public = publicKey.trim().lowercase()
            val secret = secretKey.trim().lowercase()
            if (public.length != KEY_HEX || !HEX.matches(public) || !HEX.matches(secret)) return null
            val seed = when (secret.length) {
                KEY_HEX -> secret
                2 * KEY_HEX -> secret.takeIf { it.substring(KEY_HEX) == public }?.substring(0, KEY_HEX)
                else -> null
            } ?: return null
            return DmarketKey(public, seed)
        }
    }
}
