package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.config.MarketplaceRetryConfig
import com.dmarket.p2p.tracker.model.DeviceId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatRequest
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceUnauthorizedException
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidMarketplaceClientFactoryTest {
    private class AlwaysRefreshes : MarketplaceAuthenticator {
        override suspend fun tokenOrNull(): String? = "t"

        override suspend fun refreshOnUnauthorized(rejectedToken: String?): Boolean = true
    }

    @Test
    fun theConfiguredRetryReachesTheClientOnTheHostStack() = runTest {
        var requests = 0
        val host = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requests++
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(401)
                        .message("Unauthorized")
                        .body("".toResponseBody())
                        .build()
                },
            ).build()
        val client = androidMarketplaceClient(
            hostOkHttp = host,
            baseUrl = "https://api.test",
            authenticator = AlwaysRefreshes(),
            retry = MarketplaceRetryConfig(maxRetries = 2, retryBaseDelayMs = 1, retryMaxDelayMs = 1),
        )

        assertFailsWith<MarketplaceUnauthorizedException> {
            client.heartbeat(
                HeartbeatRequest(
                    clientVersion = "1.0.0",
                    platform = "android",
                    foreground = true,
                    steamId = SteamId("76561198000000001"),
                    deviceId = DeviceId("d"),
                ),
            )
        }

        assertEquals(3, requests)
    }
}
