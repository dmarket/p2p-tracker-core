package com.dmarket.p2p.sdk

import com.dmarket.p2p.sdk.secrets.KeyPush
import com.dmarket.p2p.sdk.secrets.SdkSecrets
import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SecretsTest {

    @Test
    fun a_key_push_says_whether_the_key_changed() = runTest {
        val secrets = SdkSecrets()
        assertEquals(KeyPush.FIRST, secrets.putDmarketKey(testKey()))
        assertEquals(KeyPush.SAME, secrets.putDmarketKey(testKey()))
        val other = assertNotNull(TradingApiKey.parse("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", SECRET_SEED))
        assertEquals(KeyPush.REPLACED, secrets.putDmarketKey(other))
    }
}
