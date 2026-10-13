package com.dmarket.p2p.sdk.node

import com.dmarket.p2p.sdk.secrets.DmarketKey
import com.dmarket.p2p.sdk.signing.Ed25519Signer

/**
 * Ed25519 over `node:crypto`, for one Trading API key.
 *
 * The 32-byte seed is wrapped in its PKCS#8 envelope and imported as a key object; the public key derived from
 * it must equal the one pushed alongside, so a mismatched pair is refused at push time rather than by DMarket on
 * the first request. The seed lives only inside the key object.
 */
fun nodeEd25519Signer(key: DmarketKey): Ed25519Signer {
    val crypto = NodeModules.crypto
    val der = NodeModules.buffer.from(PKCS8_ED25519_PREFIX + key.secretSeed, "hex")
    val options: dynamic = js("({format: 'der', type: 'pkcs8'})")
    options.key = der
    val privateKey = crypto.createPrivateKey(options)
    val spki = crypto.createPublicKey(privateKey).export(js("({format: 'der', type: 'spki'})"))
    val derivedPublic = (spki.subarray(spki.length - PUBLIC_KEY_BYTES).toString("hex") as String)
    require(derivedPublic == key.publicKey) { "the secret key does not belong to the public key" }
    return Ed25519Signer { message -> bufferToByteArray(crypto.sign(null, message.toBuffer(), privateKey)) }
}

/** The PKCS#8 DER header of an Ed25519 private key, before its 32-byte seed (RFC 8410). */
private const val PKCS8_ED25519_PREFIX = "302e020100300506032b657004220420"
private const val PUBLIC_KEY_BYTES = 32
