package com.dmarket.p2p.headless

import com.dmarket.p2p.tracker.port.host.Clock
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Instant

const val STEAM_ID = "76561198000000001"
const val OTHER_STEAM_ID = "76561198000000099"

// RFC 8032 TEST 1 and TEST 2: real Ed25519 pairs, so the platform's own signer accepts both.
const val PUBLIC_KEY = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
const val SECRET_SEED = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
const val OTHER_PUBLIC_KEY = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
const val OTHER_SECRET_SEED = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"

/** The clock every test starts on: 2026-06-16T12:00:00Z. */
val T0: Instant = Instant.fromEpochSeconds(1_781_611_200)

/** A Steam access token shaped like Steam's: a JWT whose payload carries [sub], [aud] and [exp]. Unsigned. */
fun steamToken(sub: String = STEAM_ID, aud: List<String> = listOf("web", "mobile"), exp: Instant = T0 + Duration.parse("24h")): String {
    val audience = aud.joinToString(",") { "\"$it\"" }
    val payload = """{"iss":"r:test","sub":"$sub","aud":[$audience],"exp":${exp.epochSeconds},"iat":${T0.epochSeconds}}"""
    val encode = { text: String -> Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(text.encodeToByteArray()) }
    return "${encode("""{"alg":"EdDSA","typ":"JWT"}""")}.${encode(payload)}.c2lnbmF0dXJl"
}

/** The middle part of a JWT: what would show if a token leaked whole or in part. */
fun String.jwtPayload(): String = substringAfter('.').substringBefore('.')

class TestClock(var now: Instant = T0) : Clock {
    override fun now(): Instant = now

    fun advance(by: Duration) {
        now += by
    }
}
