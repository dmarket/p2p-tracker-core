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

    /** The offers currently on record as reported, at any level. */
    suspend fun reported(): Set<OfferId>

    suspend fun lastScanAt(): Instant?

    suspend fun setLastScanAt(at: Instant)

    /** When DMarket first answered "not ours" for each offer still on record, by DMarket's server clock. */
    suspend fun notOursSince(): Map<OfferId, Instant>

    /**
     * Folds one scan's DMarket [verdicts] in and returns the result of [notOursSince]: an offer answered `false` keeps
     * its first stamp, or gets its [serverTimes] entry (the reply's HTTP `Date`) when it has none, and none at all when
     * that reply carried no server time; one answered `true` is dropped; one with no answer keeps its stamp. Offers
     * [seen] does not list are dropped, and at most [MAX_NOT_OURS_SINCE] are kept, the most recent. Stamping on
     * DMarket's clock keeps the grace off the device clock, which a user can move between scans.
     */
    suspend fun recordNotOurs(
        verdicts: Map<OfferId, Boolean>,
        seen: Set<OfferId>,
        serverTimes: Map<OfferId, Instant>,
    ): Map<OfferId, Instant>
}

/** How many reported offers the reads no longer list [PersistedOfferGuardStore] keeps on record. */
internal const val MAX_UNLISTED_NOTIFIED = 200

/** How many "not ours" stamps [PersistedOfferGuardStore] keeps. */
internal const val MAX_NOT_OURS_SINCE = 200

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
    private val notOursSerializer = MapSerializer(String.serializer(), Long.serializer())
    private val mutex = Mutex()
    private val leases = mutableListOf<CreateLease>()
    private val trusted = mutableSetOf<SteamId>()
    private val notified = mutableMapOf<OfferId, SuspicionLevel>()
    private val notOurs = mutableMapOf<OfferId, Instant>()
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

    override suspend fun reported(): Set<OfferId> = mutex.withLock {
        restore()
        notified.keys.toSet()
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

    override suspend fun notOursSince(): Map<OfferId, Instant> = mutex.withLock {
        restore()
        notOurs.toMap()
    }

    override suspend fun recordNotOurs(
        verdicts: Map<OfferId, Boolean>,
        seen: Set<OfferId>,
        serverTimes: Map<OfferId, Instant>,
    ): Map<OfferId, Instant> = mutex.withLock {
        restore()
        val next = notOurs.filterKeys { it in seen && verdicts[it] != true }.toMutableMap()
        verdicts.forEach { (id, ours) ->
            val at = serverTimes[id]
            if (!ours && id in seen && at != null && id !in next) next[id] = at
        }
        val capped = next.entries.sortedBy { it.value }.takeLast(MAX_NOT_OURS_SINCE).associate { it.key to it.value }
        if (capped != notOurs) {
            notOurs.clear()
            notOurs += capped
            val stored = notOurs.entries.associate { (id, at) -> id.value to at.toEpochMilliseconds() }
            write(DeviceVaultKeys.GUARD_NOT_OURS_SINCE, TrackerJson.encodeToString(notOursSerializer, stored))
        }
        notOurs.toMap()
    }

    /** Folds the persisted leases, reports, "not ours" stamps and scan time into memory once. Call inside [mutex]. */
    private suspend fun restore() {
        if (restored) return
        restored = true
        leases += read(DeviceVaultKeys.CREATE_LEASES, leaseSerializer).orEmpty().mapNotNull { it.toDomain() }
        read(DeviceVaultKeys.GUARD_NOTIFIED, notifiedSerializer)?.forEach { (id, level) ->
            val parsed = SuspicionLevel.entries.firstOrNull { it.name == level } ?: return@forEach
            if (id.isNotBlank()) notified[OfferId(id)] = parsed
        }
        read(DeviceVaultKeys.GUARD_NOT_OURS_SINCE, notOursSerializer)?.forEach { (id, ms) ->
            if (id.isNotBlank()) notOurs[OfferId(id)] = Instant.fromEpochMilliseconds(ms)
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
