package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.model.SteamId

/**
 * Strict `steamID64` validation, used at the profile-service boundary before any network call.
 *
 * The [SteamId] value class deliberately keeps a loose invariant (non-blank only) because most ids
 * in this codebase arrive already-trusted from Steam responses. A user-supplied id, by contrast,
 * must be validated: a `steamID64` is a 17-digit decimal string in the individual-account range, so
 * it always starts with `7656` (the top 32 bits `0x0110000100000000` = `76561197960265728`).
 */

/** Thrown when a caller-supplied id is not a well-formed `steamID64`. */
class InvalidSteamId64Exception(value: String) :
    IllegalArgumentException("Invalid steamID64 '$value' (expected a 17-digit number starting with 7656)")

private val STEAM_ID64 = Regex("""^7656\d{13}$""") // 4-digit "7656" prefix + 13 digits = 17 total

/**
 * Validates [value] as a strict `steamID64` and wraps it in a [SteamId], or throws
 * [InvalidSteamId64Exception]. Use this at every public entry point that accepts a raw id string.
 */
fun requireSteamId64(value: String): SteamId {
    if (!STEAM_ID64.matches(value)) throw InvalidSteamId64Exception(value)
    return SteamId(value)
}

/** True if [SteamId.value] is a strict, well-formed `steamID64`. */
fun SteamId.isValidSteamId64(): Boolean = STEAM_ID64.matches(value)

/** The individual-account base: `steamid64 = STEAM_ID64_BASE + accountid`. */
private const val STEAM_ID64_BASE = 76561197960265728L

/**
 * The 32-bit Steam `accountid` this id expands from, or `null` if the value is not numeric.
 *
 * Steam uses both widths in the same flow and is not interchangeable about which: a trade offer's `partner`
 * field takes the steamid64, while the `Referer` that the create endpoint validates it against takes the
 * 32-bit form. Getting them the wrong way round is a `403` with a valid session, so the conversion lives here
 * rather than being re-derived at each call site (`FetchSteamOfferCreator` carries its own copy, which this
 * replaces the next time that actual is touched).
 */
fun SteamId.toAccountId(): Long? = value.toLongOrNull()?.let { it - STEAM_ID64_BASE }
