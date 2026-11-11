package com.dmarket.p2p.tracker

import com.dmarket.p2p.tracker.client.marketplace.platformEd25519Signer
import com.dmarket.p2p.tracker.client.platformHttpEngine
import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.runtime.startTracker
import io.ktor.client.engine.okhttp.OkHttpEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class AndroidActualsTest {
    @Test
    fun httpEngineIsOkHttp() {
        platformHttpEngine(credentialsInclude = true).use { assertIs<OkHttpEngine>(it) }
    }

    @Test
    fun ed25519SigningIsRefused() = runTest {
        val key = assertNotNull(TradingApiKey.parse(PUBLIC_KEY, SECRET_SEED))
        assertFailsWith<UnsupportedOperationException> { platformEd25519Signer(key) }
    }

    @Test
    fun startTrackerIsRefused() {
        assertFailsWith<UnsupportedOperationException> {
            startTracker()
        }
    }

    private companion object {
        // RFC 8032 test vector 1.
        const val PUBLIC_KEY = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        const val SECRET_SEED = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
    }
}
