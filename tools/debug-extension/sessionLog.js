// IndexedDB-backed session log: a persistent ring buffer that survives MV3 service-worker teardown
// (in-memory state does not). Each entry is a JSON object stamped with a monotonic `seq` and `ts`.
//
// Redaction is layered so that an exported log carries no credential by default: the library redacts
// bodies, URLs and headers before an entry reaches here (see NetworkRedaction), and the service worker
// attaches cookie METADATA only (name/length/fingerprint — never the value). A session started with
// `revealSecrets` is the deliberate exception: its entries hold live Steam/DMarket credentials, so such
// an export must not leave the machine.

const DB_NAME = "p2p-debug-log";
const STORE = "entries";
const META = "meta";

let dbPromise = null;

function openDb() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE, { keyPath: "seq" });
      if (!db.objectStoreNames.contains(META)) db.createObjectStore(META);
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbPromise;
}

function tx(db, stores, mode, fn) {
  return new Promise((resolve, reject) => {
    const t = db.transaction(stores, mode);
    const result = fn(t);
    t.oncomplete = () => resolve(result);
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error);
  });
}

let seqCounter = null;

async function nextSeq(db) {
  if (seqCounter === null) {
    seqCounter = await new Promise((resolve) => {
      const t = db.transaction(META, "readonly");
      const r = t.objectStore(META).get("seq");
      r.onsuccess = () => resolve(r.result || 0);
      r.onerror = () => resolve(0);
    });
  }
  seqCounter += 1;
  return seqCounter;
}

/**
 * Appends an entry (a plain object with at least `category`), stamping `seq` + `ts`, and trims the
 * store to maxEntries. Returns the stored entry (for live broadcast). `ts` is supplied by the caller
 * since the service worker owns wall-clock time.
 */
export async function appendLog(entry, maxEntries, nowMs) {
  const db = await openDb();
  const seq = await nextSeq(db);
  const stored = { seq, ts: nowMs, ...entry };
  await tx(db, [STORE, META], "readwrite", (t) => {
    t.objectStore(STORE).put(stored);
    t.objectStore(META).put(seq, "seq");
  });
  await trim(db, maxEntries);
  return stored;
}

async function trim(db, maxEntries) {
  const count = await new Promise((resolve) => {
    const t = db.transaction(STORE, "readonly");
    const r = t.objectStore(STORE).count();
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => resolve(0);
  });
  const excess = count - maxEntries;
  if (excess <= 0) return;
  await tx(db, [STORE], "readwrite", (t) => {
    const store = t.objectStore(STORE);
    const cursorReq = store.openCursor(); // keyPath=seq, ascending → oldest first
    let removed = 0;
    cursorReq.onsuccess = () => {
      const cursor = cursorReq.result;
      if (cursor && removed < excess) {
        cursor.delete();
        removed += 1;
        cursor.continue();
      }
    };
  });
}

/** Returns all entries, oldest first. */
export async function readAllLogs() {
  const db = await openDb();
  return new Promise((resolve) => {
    const t = db.transaction(STORE, "readonly");
    const r = t.objectStore(STORE).getAll();
    r.onsuccess = () => resolve(r.result || []);
    r.onerror = () => resolve([]);
  });
}

/** Clears all entries and resets the sequence counter. */
export async function clearLogs() {
  const db = await openDb();
  seqCounter = 0;
  await tx(db, [STORE, META], "readwrite", (t) => {
    t.objectStore(STORE).clear();
    t.objectStore(META).put(0, "seq");
  });
}
