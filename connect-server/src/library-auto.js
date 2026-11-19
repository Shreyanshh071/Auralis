import { MAX_PINS, checksum, fromUserDocument, library, toUserDocument, validId } from './library-core.js';
import { reconcileLibrary, sameLibrary } from './library-bridge-core.js';
import { LegacyUnavailable } from './library-firestore.js';

// Persisted import/bridge journal. The adapter serializes library mutations, and `commit` saves
// the library + this journal in the same SQLite transaction. Nothing critical lives in a promise.
export class AutomaticLibrary {
  constructor({ firestore, load, save, snapshot, commit, now = Date.now }) {
    Object.assign(this, { firestore, load, save, snapshot, commit, now });
  }

  converted(source) {
    if (source.exists) {
      if (!source.data || typeof source.data !== 'object') throw new LegacyUnavailable('legacy-shape');
      for (const name of ['playlists', 'favorites', 'savedAlbums', 'savedArtists']) {
        const rows = source.data[name];
        if (rows !== undefined && (!Array.isArray(rows) || rows.some((x) => !x || typeof x !== 'object' || Array.isArray(x))))
          throw new LegacyUnavailable('legacy-shape');
      }
      for (const p of source.data.playlists ?? []) {
        if (p.tracks !== undefined && (!Array.isArray(p.tracks) || p.tracks.some((x) => !x || typeof x !== 'object' || Array.isArray(x))))
          throw new LegacyUnavailable('legacy-shape');
      }
      const sync = source.data.librarySync;
      if (sync !== undefined) {
        if (!sync || typeof sync !== 'object' || Array.isArray(sync)) throw new LegacyUnavailable('legacy-shape');
        if (sync.pins !== undefined && (!Array.isArray(sync.pins) || sync.pins.length > MAX_PINS || !sync.pins.every(validId)))
          throw new LegacyUnavailable('legacy-shape');
        if (sync.deleted !== undefined && (!Array.isArray(sync.deleted) || sync.deleted.some((d) => !d || !validId(d.id) || !Number.isFinite(d.at))))
          throw new LegacyUnavailable('legacy-shape');
      }
    }
    const converted = fromUserDocument(source.data, this.now());
    if (converted.dropped.tracks || converted.dropped.items || library(converted.library).error)
      throw new LegacyUnavailable('legacy-shape');
    return converted.library;
  }

  async initialize(uid) {
    const held = this.load();
    if (held?.phase === 'ready') return held;
    if ((await this.firestore.get(`libraryAuthority/${uid}`)).exists) throw new LegacyUnavailable('legacy-fenced');
    const source = await this.firestore.get(`users/${uid}`);
    const imported = this.converted(source);
    const sum = await checksum(imported);
    // Store the immutable import source for recovery before making the library authoritative.
    // A crash before this transaction can retry the read; after it the source is never reimported.
    const state = { uid, phase: 'ready', baseline: imported,
      picks: source.data?.starterTaste ?? null,
      imported: { at: this.now(), exists: source.exists, updateTime: source.updateTime, checksum: sum, library: imported },
      seenExists: source.exists, dirty: false, nextAt: 0, failures: 0, pending: null };
    await this.commit(imported, state, { importing: true, checksum: sum });
    return state;
  }

  async adoptFenced(uid, importedChecksum) {
    const held = this.load();
    if (held?.phase === 'ready') { await this.synchronize({ force: true }); return; }
    const fence = await this.firestore.get(`libraryAuthority/${uid}`);
    const source = await this.firestore.get(`users/${uid}`);
    const baseline = this.converted(source);
    if (!fence.exists || !source.exists || !importedChecksum || await checksum(baseline) !== importedChecksum)
      throw new LegacyUnavailable('legacy-adoption-source');
    // The old fence froze this exact import. Never infer a baseline from a different source.
    this.save({ uid, phase: 'ready', baseline, picks: source.data?.starterTaste ?? null,
      imported: { at: this.now(), exists: true, updateTime: source.updateTime, checksum: importedChecksum, library: baseline },
      adoptionFence: fence.updateTime, seenExists: true, dirty: true, nextAt: 0, failures: 0, pending: null });
    await this.synchronize({ force: true });
  }

  async synchronize({ force = false } = {}) {
    let state = this.load();
    if (state?.phase !== 'ready' || (!force && !state.pending && state.nextAt > this.now())) return;
    try {
      // Lost commit acknowledgements are resolved by a separate server-only journal document,
      // written atomically with the legacy library. An old app cannot erase this marker by
      // replacing users/{uid}. This prevents bridge echoes becoming new edits after a crash.
      if (state.pending) {
        const marker = await this.firestore.get(`libraryBridgeJournal/${state.uid}`);
        if (marker.exists && marker.data.id === state.pending.id) {
          state = { ...state, baseline: state.pending.library, pending: null };
          this.save(state);
        } else { state = { ...state, pending: null }; this.save(state); }
      }
      for (let attempt = 0; attempt < 4; attempt++) {
        const source = await this.firestore.get(`users/${state.uid}`);
        if (!source.exists && state.seenExists) throw new LegacyUnavailable('legacy-document-deleted');
        const legacy = this.converted(source);
        const cloud = this.snapshot();
        const target = reconcileLibrary(state.baseline, cloud, legacy, this.now());
        if (!sameLibrary(cloud, target)) {
          // Accept legacy changes on the service before mirroring; disconnected updated clients
          // are notified and replay local operations against the new revision as usual.
          await this.commit(target, state, { importing: false });
        }
        if (!sameLibrary(legacy, target)) {
          const pending = { id: crypto.randomUUID(), library: target, checksum: await checksum(target) };
          state = { ...state, pending }; this.save(state);
          const applied = await this.firestore.compareAndSet(state.uid, source, toUserDocument(target, this.now()), pending);
          if (!applied) { state = { ...state, pending: null }; this.save(state); continue; }
        }
        // No write/loop when legacy already contains our last accepted snapshot.
        // Do not release an old fence until the latest Cloudflare library has been mirrored.
        if (state.adoptionFence) await this.firestore.removeFence(state.uid, state.adoptionFence);
        state = { ...state, adoptionFence: null, baseline: target, pending: null, dirty: false, error: null,
          seenExists: state.seenExists || source.exists || !sameLibrary(legacy, target),
          failures: 0, lastSuccess: this.now(), nextAt: this.now() + 5 * 60_000 };
        this.save(state); return;
      }
      throw new LegacyUnavailable('legacy-conflict', 15_000);
    } catch (error) {
      const held = this.load() ?? state;
      const failures = (held.failures ?? 0) + 1;
      const wait = Math.max(error.retryMs ?? 60_000, Math.min(15 * 60_000, 15_000 * 2 ** Math.min(failures, 6)));
      this.save({ ...held, failures, error: error.code ?? 'legacy-unavailable', nextAt: this.now() + wait });
      throw error;
    }
  }
}
