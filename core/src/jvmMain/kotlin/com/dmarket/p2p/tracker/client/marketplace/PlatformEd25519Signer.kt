package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.port.marketplace.Ed25519Signer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/** The JDK's `Ed25519` provider (JDK 15+). A [Signature] is not thread-safe, so each call takes its own. */
actual suspend fun platformEd25519Signer(key: TradingApiKey): Ed25519Signer {
    val keys = KeyFactory.getInstance(ED25519)
    val privateKey = keys.generatePrivate(PKCS8EncodedKeySpec(Ed25519Der.pkcs8(key)))
    val publicKey = try {
        keys.generatePublic(X509EncodedKeySpec(Ed25519Der.spki(key)))
    } catch (_: GeneralSecurityException) {
        throw IllegalArgumentException("the public key is not an Ed25519 key")
    }
    val signer = Ed25519Signer { message ->
        Signature.getInstance(ED25519).run {
            initSign(privateKey)
            update(message)
            sign()
        }
    }
    return signer.ownedBy { message, signature ->
        // A public key that is not a curve point fails the check rather than escaping as a provider exception.
        runCatching {
            Signature.getInstance(ED25519).run {
                initVerify(publicKey)
                update(message)
                verify(signature)
            }
        }.getOrDefault(false)
    }
}

private const val ED25519 = "Ed25519"
