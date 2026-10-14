package com.dmarket.p2p.sdk

import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.marketplace.Ed25519Signer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration
import kotlin.time.Instant

const val STEAM_ID = "76561198000000001"
const val OTHER_STEAM_ID = "76561198000000099"
const val PUBLIC_KEY = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
const val SECRET_SEED = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"

/** The clock every SDK test starts on: 2026-06-16T12:00:00Z. */
val T0: Instant = Instant.fromEpochSeconds(1_781_611_200)

class TestClock(var now: Instant = T0) : Clock {
    override fun now(): Instant = now

    fun advance(by: Duration) {
        now += by
    }
}

/** A Steam access token shaped like Steam's: a JWT whose payload carries [sub], [aud] and [exp]. Unsigned. */
@OptIn(ExperimentalEncodingApi::class)
fun steamToken(sub: String = STEAM_ID, aud: List<String> = listOf("web", "mobile"), exp: Instant = T0 + Duration.parse("24h")): String {
    val audience = aud.joinToString(",") { "\"$it\"" }
    val payload = """{"iss":"r:test","sub":"$sub","aud":[$audience],"exp":${exp.epochSeconds},"iat":${T0.epochSeconds}}"""
    val encode = { text: String -> Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(text.encodeToByteArray()) }
    return "${encode("""{"alg":"EdDSA","typ":"JWT"}""")}.${encode(payload)}.c2lnbmF0dXJl"
}

fun testKey(): TradingApiKey = requireNotNull(TradingApiKey.parse(PUBLIC_KEY, SECRET_SEED + PUBLIC_KEY))

/** Signs nothing: returns the message bytes, so a test can read what was signed off the header. */
val echoSigner = Ed25519Signer { message -> message }
