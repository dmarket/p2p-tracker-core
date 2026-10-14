package com.dmarket.p2p.tracker.model.marketplace

/**
 * A DMarket Trading API key pair: [publicKey] goes on every signed request as `X-Api-Key`, [secretSeed] is the
 * Ed25519 seed that signs it. Both lowercase hex, 32 bytes each.
 *
 * DMarket hands out the secret in NaCl form, the 32-byte seed followed by the 32-byte public key; a bare seed is
 * accepted too. When the public half is present it must equal [publicKey], which catches a pair pasted from two
 * different keys before the gateway refuses the first request for it. A bare seed of another key passes here:
 * telling takes a signature, so the platform signer checks it when it is built.
 *
 * Held in memory only, and never printed: [toString] names the public key alone.
 */
class TradingApiKey private constructor(val publicKey: String, val secretSeed: String) {

    override fun toString(): String = "TradingApiKey(publicKey=$publicKey, secret=<redacted>)"

    override fun equals(other: Any?): Boolean = other is TradingApiKey && other.publicKey == publicKey && other.secretSeed == secretSeed

    override fun hashCode(): Int = publicKey.hashCode()

    companion object {
        private const val KEY_HEX = 64
        private val HEX = Regex("^[0-9a-f]+$")

        /**
         * The key pair, or `null` when either half is not hex of the right length or the NaCl secret's public half
         * is another key's. Case and surrounding whitespace are ignored.
         */
        fun parse(publicKey: String, secretKey: String): TradingApiKey? {
            val public = publicKey.trim().lowercase()
            val secret = secretKey.trim().lowercase()
            if (public.length != KEY_HEX || !HEX.matches(public) || !HEX.matches(secret)) return null
            val seed = when (secret.length) {
                KEY_HEX -> secret
                2 * KEY_HEX -> secret.takeIf { it.substring(KEY_HEX) == public }?.substring(0, KEY_HEX)
                else -> null
            } ?: return null
            return TradingApiKey(public, seed)
        }
    }
}
