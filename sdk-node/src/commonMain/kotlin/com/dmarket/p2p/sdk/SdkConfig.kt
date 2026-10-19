package com.dmarket.p2p.sdk

import com.dmarket.p2p.tracker.config.TrackerConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The SDK's settings, read once at start. The only secret here is [managementSecret], the bearer the local
 * management API requires; the DMarket key and the Steam token are never configured, only pushed.
 *
 * @property managementSecret the bearer every management request must carry. At least 32 characters.
 * @property dataDir where the loop's progress, claims and device id are kept across restarts, one directory per
 *   account: `<dataDir>/<linkedSteamId>/`. No secret is ever written there.
 * @property listenHost the management API's address. Loopback by default: the API accepts a trading key and a
 *   Steam token, so it must not be reachable from anywhere the bot is not.
 * @property listenPort the management API's port.
 * @property dmarketBaseUrl the DMarket gateway the loop talks to.
 * @property steamWrites `false` for a buying bot's SDK: it then creates and cancels no Steam offer, whatever DMarket
 *   leases it.
 */
@Serializable
data class SdkConfig(
    @SerialName("managementSecret") val managementSecret: String,
    @SerialName("dataDir") val dataDir: String,
    @SerialName("listenHost") val listenHost: String = "127.0.0.1",
    @SerialName("listenPort") val listenPort: Int = DEFAULT_PORT,
    @SerialName("dmarketBaseUrl") val dmarketBaseUrl: String = TrackerConfig.DEFAULT_DMARKET_BASE_URL,
    @SerialName("steamWrites") val steamWrites: Boolean = true,
) {
    init {
        require(managementSecret.length >= MIN_SECRET_LENGTH) { "managementSecret must be at least $MIN_SECRET_LENGTH characters" }
        require(dataDir.isNotBlank()) { "dataDir must not be blank" }
        require(listenPort in 1..65_535) { "listenPort must be a TCP port" }
    }

    override fun toString(): String =
        "SdkConfig(managementSecret=<redacted>, dataDir=$dataDir, listenHost=$listenHost, listenPort=$listenPort, " +
            "dmarketBaseUrl=$dmarketBaseUrl, steamWrites=$steamWrites)"

    companion object {
        const val DEFAULT_PORT: Int = 8790
        const val MIN_SECRET_LENGTH: Int = 32

        private val json = Json { ignoreUnknownKeys = false }

        /**
         * Parses the config file's JSON. A failed `require` throws [IllegalArgumentException] with a message safe
         * to print; malformed JSON throws a serialization exception whose message can quote the input — the
         * management secret included — so a caller must not print that one.
         */
        fun parse(text: String): SdkConfig = json.decodeFromString(serializer(), text)
    }
}
