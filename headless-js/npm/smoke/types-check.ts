// A host file against the package's types: every name imports, each push result narrows by `type`, and every field
// of the state reads with the type it has at runtime. Type-checked only (`tsc --noEmit`), never run.
import {
    createHeadlessTracker,
    cycle,
    defaultBaseUrl,
    defaultNotaryUrl,
    directiveActions,
    failedCancelKinds,
    headlessVersion,
    maxClockSkewMs,
    minWriteTokenLifetimeMs,
    pushSteamToken,
    pushTradingApiKey,
    reasons,
    runProverChild,
    start,
    steamNotLinkedCode,
    stop,
    trackerState,
    type HeadlessTrackerOptions,
    type KeyChange,
    type KillCause,
    type ProofFailure,
    type ProofOutcome,
    type ProverLineStream,
    type SteamTokenPushResult,
    type TrackerHandle,
    type TrackerState,
    type TradingApiKeyPushResult,
} from "@dmarket/p2p-tracker-headless";

function describeFailure(failure: ProofFailure): string {
    switch (failure.type) {
        case "upgrade_refused":
            return `${failure.status}`;
        case "session_closed":
            return `${failure.code} ${failure.reason ?? ""}`;
        case "notary_unreachable":
        case "version_mismatch":
        case "limits_exceeded":
        case "other":
            return failure.detail;
        case "steam_failed":
            return `${failure.status ?? "no status"} ${failure.detail}`;
        case "prover_killed": {
            const cause: KillCause = failure.cause;
            return `${cause} ${failure.detail ?? ""}`;
        }
        case "not_started":
            return failure.reason;
        default: {
            const unhandled: never = failure;
            return unhandled;
        }
    }
}

const options: HeadlessTrackerOptions = {
    dataDir: "/var/lib/tracker",
    baseUrl: defaultBaseUrl(),
    steamWrites: false,
    notaryUrl: defaultNotaryUrl(),
    prover: {
        notaryUrl: defaultNotaryUrl(),
        maxConcurrency: 1,
        proofsPerChild: 1,
        threadCount: 4,
        assetsDir: "/opt/prover",
        connectDeadlineMs: 30_000,
        sessionDeadlineMs: 240_000,
    },
    onEvent: (json: string) => void JSON.parse(json),
    onProofOutcome: (outcome: ProofOutcome) => {
        const dealId: string = outcome.dealId;
        const summary: string = outcome.failure === null ? "proved" : `${outcome.failure.summary} ${describeFailure(outcome.failure)}`;
        void dealId;
        void summary;
    },
    onProverLine: (stream: ProverLineStream, line: string) => {
        const fromHost: boolean = stream === "host";
        void fromHost;
        void line;
    },
    nowMs: () => Date.now(),
};

// @ts-expect-error an option the tracker does not have
const unknownOption: HeadlessTrackerOptions = { dataDir: "/tmp", pollMs: 5 };
// @ts-expect-error dataDir is required
const noDataDir: HeadlessTrackerOptions = {};
void unknownOption;
void noDataDir;

const handle: TrackerHandle = createHeadlessTracker(options);
start(handle);
// @ts-expect-error a plain object is not a handle
start({});

async function host(): Promise<void> {
    await cycle(handle);

    const key: TradingApiKeyPushResult = await pushTradingApiKey(handle, "public", "secret");
    switch (key.type) {
        case "held": {
            const change: KeyChange = key.change;
            const publicKey: string = key.publicKey;
            // @ts-expect-error a held key has no reason
            void key.reason;
            void change;
            void publicKey;
            break;
        }
        case "invalid": {
            const reason: string = key.reason;
            // @ts-expect-error an invalid key has no public key
            void key.publicKey;
            void reason;
            break;
        }
        case "accountChanged": {
            const ids: [string, string, string, string | null] = [key.publicKey, key.heldPublicKey, key.linkedSteamId, key.keyLinkedSteamId];
            // @ts-expect-error keyLinkedSteamId may be null
            const keyLinked: string = key.keyLinkedSteamId;
            void ids;
            void keyLinked;
            break;
        }
        case "unverified": {
            const unverified: [string, string] = [key.publicKey, key.reason];
            void unverified;
            break;
        }
        default: {
            const unhandled: never = key;
            void unhandled;
        }
    }
    // @ts-expect-error no push result has this type
    if (key.type === "rejected") void key;

    const token: SteamTokenPushResult = await pushSteamToken(handle, "token");
    switch (token.type) {
        case "held": {
            const held: [string, number] = [token.steamId, token.expiresAtMs];
            // @ts-expect-error a held token has no reason
            void token.reason;
            void held;
            break;
        }
        case "wrongAccount": {
            const ids: [string, string | null] = [token.tokenSteamId, token.linkedSteamId];
            void ids;
            break;
        }
        case "invalid": {
            const reason: string = token.reason;
            // @ts-expect-error an invalid token has no Steam id
            void token.steamId;
            void reason;
            break;
        }
        default: {
            const unhandled: never = token;
            void unhandled;
        }
    }
    // @ts-expect-error no push result has this type
    if (token.type === "expired") void token;

    const state: TrackerState = trackerState(handle);
    const hasTradingApiKey: boolean = state.hasTradingApiKey;
    const steamTokenSteamId: string | null = state.steamTokenSteamId;
    const steamTokenExpiresAtMs: number | null = state.steamTokenExpiresAtMs;
    const linkedSteamId: string | null = state.linkedSteamId;
    const readySinceMs: number | null = state.readySinceMs;
    const trackedDealIds: readonly string[] = state.trackedDealIds;
    const clockOffsetMs: number | null = state.clockOffsetMs;
    const clockSkewed: boolean = state.clockSkewed;
    const steamNotLinked: boolean = state.steamNotLinked;
    const updateRequired: boolean = state.updateRequired;
    const updateDueTimeMs: number | null = state.updateDueTimeMs;
    const dealDeadlines: Readonly<Record<string, number>> = state.dealDeadlines;
    // @ts-expect-error the state is read-only
    state.clockSkewed = true;
    void [
        hasTradingApiKey,
        steamTokenSteamId,
        steamTokenExpiresAtMs,
        linkedSteamId,
        readySinceMs,
        trackedDealIds,
        clockOffsetMs,
        clockSkewed,
        steamNotLinked,
        updateRequired,
        updateDueTimeMs,
        dealDeadlines,
    ];

    await stop(handle);
}

const reasonTexts: string[] = Object.values(reasons());
const createOffer: "create_offer" = directiveActions().createOffer;
const alreadyTraded: "already_traded" = failedCancelKinds().alreadyTraded;
const notLinked: "P2P_STEAM_NOT_LINKED" = steamNotLinkedCode();
// @ts-expect-error the constants are frozen
reasons().noProver = "changed";
const durations: [number, number] = [minWriteTokenLifetimeMs(), maxClockSkewMs()];
const version: string = headlessVersion();
const childEntry: () => void = runProverChild;
void [reasonTexts, createOffer, alreadyTraded, notLinked, durations, version, childEntry, host];
