package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.engine.SuspiciousOfferCheck
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.CreateLease
import com.dmarket.p2p.tracker.model.steam.SuspicionLevel
import com.dmarket.p2p.tracker.model.steam.SuspiciousOffer
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.wire.TrackerJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Instant

/**
 * The suspicious-offer check's state: this device's `create_offer` leases, the partners the user trusts, which
 * flagged offers were already reported, and when the sent offers were last read for the check. Persisted because an
 * MV3 worker respawns between most cycles: a forgotten lease would flag this device's own offer, a forgotten report
 * would repeat the warning on every wake, and a forgotten scan time would read Steam on every wake.
 */
interface OfferGuardStore {
    /** Records [lease]; drops leases past [SuspiciousOfferCheck.LEASE_TTL]. */
    suspend fun addLease(lease: CreateLease)

    suspend fun leases(): List<CreateLease>

    /** The partners the user marked as trusted. Read from storage on every call, so a host write is seen at once. */
    suspend fun trustedPartners(): Set<SteamId>

    suspend fun trustPartner(partner: SteamId)

    /**
     * Records [found] as the offers currently flagged among [seen], the ids the Steam read listed, and returns what
     * changed since the last report: the offers not yet reported at their level, and the reported ones [seen] lists
     * that are no longer in [found]. A reported offer the read did not list is not cleared: the account-wide list is not
     * proven complete, so its absence says nothing about the offer. It stays on record, up to [MAX_UNLISTED_NOTIFIED].
     */
    suspend fun unreported(found: List<SuspiciousOffer>, seen: Set<OfferId>): GuardDelta

    suspend fun lastScanAt(): Instant?

    suspend fun setLastScanAt(at: Instant)
}

/** How many reported offers the reads no longer list [PersistedOfferGuardStore] keeps on record. */
internal const val MAX_UNLISTED_NOTIFIED = 200

/** What [OfferGuardStore.unreported] has to tell the host: offers to warn about now, and earlier warnings to take down. */
data class GuardDelta(val fresh: List<SuspiciousOffer>, val cleared: List<OfferId>)

@Serializable
private data class StoredLease(val dealId: String, val partner: String, val assetIds: List<String>, val createdAtMs: Long) {
    fun toDomain(): CreateLease? = runCatching {
        CreateLease(DealId(dealId), SteamId(partner), assetIds.mapTo(mutableSetOf(), ::AssetId), Instant.fromEpochMilliseconds(createdAtMs))
    }.getOrNull()
}

private fun CreateLease.toStored() = StoredLease(dealId.value, partner.value, assetIds.map { it.value }, createdAt.toEpochMilliseconds())

/**
 * The only [OfferGuardStore] implementation: in-memory state guarded by one [Mutex] and written through to a
 * [DeviceKeyValueStore], mirroring [PersistedUnprovenClaimStore]. A storage failure never propagates; the state then
 * lives in memory for this process, which costs at most a repeated warning or a flagged own offer after a restart.
 */
class PersistedOfferGuardStore(private val storage: DeviceKeyValueStore = InMemoryDeviceKeyValueStore()) : OfferGuardStore {
    private val leaseSerializer = ListSerializer(StoredLease.serializer())
    private val partnersSerializer = ListSerializer(String.serializer())
    private val notifiedSerializer = MapSerializer(String.serializer(), String.serializer())
    private val mutex = Mutex()
    private val leases = mutableListOf<CreateLease>()
    private val trusted = mutableSetOf<SteamId>()
    private val notified = mutableMapOf<OfferId, SuspicionLevel>()
    private var scannedAt: Instant? = null
    private var restored = false

    override suspend fun addLease(lease: CreateLease) = mutex.withLock {
        restore()
        leases.removeAll { lease.createdAt - it.createdAt >= SuspiciousOfferCheck.LEASE_TTL }
        leases += lease
        write(DeviceVaultKeys.CREATE_LEASES, TrackerJson.encodeToString(leaseSerializer, leases.map { it.toStored() }))
    }

    override suspend fun leases(): List<CreateLease> = mutex.withLock {
        restore()
        leases.toList()
    }

    override suspend fun trustedPartners(): Set<SteamId> = mutex.withLock {
        trusted += read(DeviceVaultKeys.GUARD_TRUSTED_PARTNERS, partnersSerializer).orEmpty().mapNotNull(::steamIdOrNull)
        trusted.toSet()
    }

    override suspend fun trustPartner(partner: SteamId) = mutex.withLock {
        trusted += read(DeviceVaultKeys.GUARD_TRUSTED_PARTNERS, partnersSerializer).orEmpty().mapNotNull(::steamIdOrNull)
        if (trusted.add(partner)) {
            write(DeviceVaultKeys.GUARD_TRUSTED_PARTNERS, TrackerJson.encodeToString(partnersSerializer, trusted.map { it.value }.sorted()))
        }
    }

    override suspend fun unreported(found: List<SuspiciousOffer>, seen: Set<OfferId>): GuardDelta = mutex.withLock {
        restore()
        val fresh = found.filter { notified[it.offerId] != it.level }
        val current = found.associate { it.offerId to it.level }
        val cleared = notified.keys.filter { it in seen && it !in current }
        // Kept so a later listing can still clear them, but only the most recently seen: an offer no read lists again
        // (paged out, or from a scan since turned off) would otherwise stay on record forever.
        val unlisted = notified.entries.filter { it.key !in seen }.takeLast(MAX_UNLISTED_NOTIFIED)
        val next = unlisted.associate { it.key to it.value } + current
        if (next != notified) {
            notified.clear()
            notified += next
            val stored = notified.entries.associate { (id, level) -> id.value to level.name }
            write(DeviceVaultKeys.GUARD_NOTIFIED, TrackerJson.encodeToString(notifiedSerializer, stored))
        }
        GuardDelta(fresh, cleared)
    }

    override suspend fun lastScanAt(): Instant? = mutex.withLock {
        restore()
        scannedAt
    }

    override suspend fun setLastScanAt(at: Instant) = mutex.withLock {
        restore()
        scannedAt = at
        write(DeviceVaultKeys.GUARD_SCANNED_AT, at.toEpochMilliseconds().toString())
    }

    /** Folds the persisted leases, reports and scan time into memory once. Call inside [mutex]. */
    private suspend fun restore() {
        if (restored) return
        restored = true
        leases += read(DeviceVaultKeys.CREATE_LEASES, leaseSerializer).orEmpty().mapNotNull { it.toDomain() }
        read(DeviceVaultKeys.GUARD_NOTIFIED, notifiedSerializer)?.forEach { (id, level) ->
            val parsed = SuspicionLevel.entries.firstOrNull { it.name == level } ?: return@forEach
            if (id.isNotBlank()) notified[OfferId(id)] = parsed
        }
        scannedAt = runCatching { storage.get(DeviceVaultKeys.GUARD_SCANNED_AT) }.getOrNull()
            ?.toLongOrNull()?.let(Instant::fromEpochMilliseconds)
    }

    private suspend fun <T> read(key: String, serializer: KSerializer<T>): T? {
        val json = runCatching { storage.get(key) }.getOrNull() ?: return null
        return runCatching { TrackerJson.decodeFromString(serializer, json) }.getOrNull()
    }

    private suspend fun write(key: String, value: String) {
        runCatching { storage.set(key, value) }
    }

    private fun steamIdOrNull(value: String): SteamId? = value.takeIf { it.isNotBlank() }?.let(::SteamId)
}
