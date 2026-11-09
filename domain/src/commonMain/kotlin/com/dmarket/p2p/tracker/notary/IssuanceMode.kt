package com.dmarket.p2p.tracker.notary

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** The issuance protocol; wire values are shared by JS configuration, SDK JSON and child IPC. */
@Serializable(with = IssuanceMode.Serializer::class)
enum class IssuanceMode(val wireName: String) {
    MPC("mpc"),
    PROXY("proxy"),
    ;

    companion object {
        /** Refuse unknown values without echoing host-supplied text into logs. */
        fun fromWire(value: String): IssuanceMode =
            entries.firstOrNull { it.wireName == value } ?: throw IllegalArgumentException("issuance mode must be mpc or proxy")
    }

    object Serializer : KSerializer<IssuanceMode> {
        override val descriptor = PrimitiveSerialDescriptor("IssuanceMode", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: IssuanceMode) = encoder.encodeString(value.wireName)

        override fun deserialize(decoder: Decoder): IssuanceMode = fromWire(decoder.decodeString())
    }
}
