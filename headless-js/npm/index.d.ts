// Types of @dmarket/p2p-tracker-headless.
//
// Written by hand: the declarations the compiler generates cannot say what the functions return (a union keyed by
// `type`, a record of deadlines, `string | null`), and they declare the web extension's exports too, which this
// package's entry does not have. A change to what a function returns changes this file in the same commit.
//
// Times are epoch milliseconds, durations milliseconds, ids strings. A field the library has no value for is
// `null`, never missing.

declare const trackerHandle: unique symbol;

/**
 * Identifies one tracker. An empty frozen object with no prototype: it carries nothing, and the tracker it stands
 * for stays inside the library.
 */
export interface TrackerHandle {
    readonly [trackerHandle]: never;
}

/** The `prover` option: with it the tracker proves, each proof in a prover process of its own. */
export interface ProverOptions {
    /** The notary proofs go to, and the one each upgrade is signed for; the tracker's `notaryUrl` when unset. */
    notaryUrl?: string;
    /** Proofs at once, 1 or 2 (1 by default); each holds a process of up to ~700 MB. */
    maxConcurrency?: number;
    /** Proofs one prover process serves before it is ended, 1 to 5 (1 by default). */
    proofsPerChild?: number;
    /** The prover's MPC threads per process, 1 to 16 (2 by default). */
    threadCount?: number;
    /** Where the prover's `pkg/` and `transport/` are; this package's directory by default. */
    assetsDir?: string;
    /** From a proof's start to its process asking for the upgrade headers; 30 000 by default. */
    connectDeadlineMs?: number;
    /** From that ask to the proof's answer; 240 000 by default. */
    sessionDeadlineMs?: number;
}

/** Where a line given to `onProverLine` comes from: a prover process's output, or the host's own line about one. */
export type ProverLineStream = "stdout" | "stderr" | "host";

export interface HeadlessTrackerOptions {
    /** Where each Steam account's loop state is kept. The pushed secrets are never written there, or anywhere. */
    dataDir: string;
    /** The DMarket gateway; `defaultBaseUrl()` by default. */
    baseUrl?: string;
    /** `false` for an account that only buys: no Steam offer is created or cancelled. `true` by default. */
    steamWrites?: boolean;
    /** The notary proofs go to; `defaultNotaryUrl()` by default. */
    notaryUrl?: string;
    /** Without it the tracker makes no proof. */
    prover?: ProverOptions;
    /** Each of the loop's events, as a JSON string with no secret in it. */
    onEvent?: (json: string) => void;
    /** How each proof ended, before the loop's event about it. */
    onProofOutcome?: (outcome: ProofOutcome) => void;
    /** Each line of a prover process's output, and each of the host's lines about them (a JSON object), redacted. */
    onProverLine?: (stream: ProverLineStream, line: string) => void;
    /** The clock, in epoch milliseconds; the system clock by default. */
    nowMs?: () => number;
}

/** What became of a pushed key compared with the one held before it. */
export type KeyChange = "FIRST" | "SAME" | "ROTATED" | "REPLACED";

/** A Trading API key push's result. No case carries the secret key. */
export type TradingApiKeyPushResult =
    | { readonly type: "held"; readonly publicKey: string; readonly change: KeyChange }
    | { readonly type: "invalid"; readonly reason: string }
    | {
          readonly type: "accountChanged";
          readonly publicKey: string;
          readonly heldPublicKey: string;
          readonly linkedSteamId: string;
          readonly keyLinkedSteamId: string | null;
      }
    | { readonly type: "unverified"; readonly publicKey: string; readonly reason: string };

/** A Steam token push's result. No case carries the token. */
export type SteamTokenPushResult =
    | { readonly type: "held"; readonly steamId: string; readonly expiresAtMs: number }
    | { readonly type: "wrongAccount"; readonly tokenSteamId: string; readonly linkedSteamId: string | null }
    | { readonly type: "invalid"; readonly reason: string };

/** What the tracker holds right now, without either secret. */
export interface TrackerState {
    readonly hasTradingApiKey: boolean;
    readonly steamTokenSteamId: string | null;
    readonly steamTokenExpiresAtMs: number | null;
    readonly linkedSteamId: string | null;
    readonly readySinceMs: number | null;
    readonly trackedDealIds: readonly string[];
    readonly clockOffsetMs: number | null;
    readonly clockSkewed: boolean;
    readonly steamNotLinked: boolean;
    readonly updateRequired: boolean;
    readonly updateDueTimeMs: number | null;
    /** Each tracked deal's deadline, by deal id. */
    readonly dealDeadlines: Readonly<Record<string, number>>;
}

/** Why a prover process ended before its proof did. */
export type KillCause = "out_of_memory" | "connect_deadline" | "session_deadline" | "exited";

/** Why a proof produced nothing. Every text in it, `summary` included, is redacted. */
export type ProofFailure =
    | { readonly type: "upgrade_refused"; readonly status: number; readonly summary: string }
    | { readonly type: "session_closed"; readonly code: number; readonly reason: string | null; readonly summary: string }
    | { readonly type: "notary_unreachable"; readonly detail: string; readonly summary: string }
    | { readonly type: "version_mismatch"; readonly detail: string; readonly summary: string }
    | { readonly type: "steam_failed"; readonly status: number | null; readonly detail: string; readonly summary: string }
    | { readonly type: "limits_exceeded"; readonly detail: string; readonly summary: string }
    | { readonly type: "prover_killed"; readonly cause: KillCause; readonly detail: string | null; readonly summary: string }
    | { readonly type: "not_started"; readonly reason: string; readonly summary: string }
    | { readonly type: "other"; readonly detail: string; readonly summary: string };

/** How a proof ended: `failure` is `null` for a proof made. */
export interface ProofOutcome {
    readonly dealId: string;
    readonly failure: ProofFailure | null;
}

/**
 * Builds a tracker for one process and one Steam account. Nothing runs until `start`. Throws on an unknown option
 * or a value of the wrong type, naming the option and never the value; with `prover`, throws "prover files not
 * found" when the child script or the prover's files are missing.
 */
export declare function createHeadlessTracker(options: HeadlessTrackerOptions): TrackerHandle;

/** Starts the driver: a cycle now, then one per wake the loop asks for. A second call does nothing. */
export declare function start(handle: TrackerHandle): void;

/** Runs one cycle now, behind the cycle in flight. */
export declare function cycle(handle: TrackerHandle): Promise<void>;

/** Stops the tracker and kills every prover process at once. Every later call with the handle is refused. */
export declare function stop(handle: TrackerHandle): Promise<void>;

/** Pushes the DMarket Trading API key pair (hex). */
export declare function pushTradingApiKey(
    handle: TrackerHandle,
    publicKey: string,
    secretKey: string,
): Promise<TradingApiKeyPushResult>;

/** Pushes a Steam access token. */
export declare function pushSteamToken(handle: TrackerHandle, accessToken: string): Promise<SteamTokenPushResult>;

/** What the tracker holds right now. */
export declare function trackerState(handle: TrackerHandle): TrackerState;

/** The `reason` texts of the events that say why a write or a proof did not run. The same frozen object each call. */
export declare function reasons(): Readonly<{
    offerWritesDisabled: "refused: offer writes are disabled on this client";
    steamSessionMissing: "not run: no usable Steam session on this client";
    tokenExpiringDefer: "the Steam token expires before a create could be confirmed";
    proofTokenExpiring: "the Steam token is too close to its expiry to start a proof";
    noProver: "no notary prover is configured on this host";
}>;

/** The `kind` of a failed-cancel check, by name. The same frozen object each call. */
export declare function failedCancelKinds(): Readonly<{
    alreadyInactive: "already_inactive";
    alreadyTraded: "already_traded";
    stillOpen: "still_open";
    unread: "unread";
}>;

/** The `kind` of a directive, by name. The same frozen object each call. */
export declare function directiveActions(): Readonly<{
    createOffer: "create_offer";
    cancelOffer: "cancel_offer";
    reportInventory: "report_inventory";
    unknown: "unknown";
}>;

/** The gateway's error code for a heartbeat refused because the DMarket account links no Steam account. */
export declare function steamNotLinkedCode(): "P2P_STEAM_NOT_LINKED";

/** Under this much token life no create or proof starts. */
export declare function minWriteTokenLifetimeMs(): number;

/** How far this host's clock may be off DMarket's before `clockSkewed` is set. */
export declare function maxClockSkewMs(): number;

/** The production notary: `notaryUrl`'s default. */
export declare function defaultNotaryUrl(): string;

/** The production DMarket gateway: `baseUrl`'s default. */
export declare function defaultBaseUrl(): string;

/** The version of the library inside this package: the one the tracker reports on every heartbeat. */
export declare function headlessVersion(): string;

/** The entry of a prover process; the package's `prover-child.mjs` calls it. A host never does. */
export declare function runProverChild(): void;
