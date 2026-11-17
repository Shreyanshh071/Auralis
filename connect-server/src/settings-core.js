// Account settings as the apps already merge them (AccountPreferencesMerge in Kotlin): per key, the
// higher revision wins, and equal revisions are decided by the device id. Pure functions, so the
// tests and the server share them.

export const SETTINGS_PLATFORMS = ['android', 'windows'];
export const MAX_SETTINGS_KEYS = 400;
export const MAX_REVISION = 1_000_000_000;
/**
 * What an operator must send to retire an account's bridge. Retiring is never automatic: a quiet
 * month does not prove that every device updated, and a device left on an old version stops
 * exchanging settings and listening history with updated devices once the bridge is gone.
 */
export const RETIRE_ACKNOWLEDGEMENT = 'old devices on this account stop syncing settings and listening history with updated devices';

const isMap = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
export const validSettingKey = (k) => typeof k === 'string' && /^[a-z]\.[a-z0-9_]{1,64}$/.test(k);

/** One stored preference, checked; `minRevision` is 1 for edits and 0 for stored or seeded ones. */
export function preference(raw, minRevision = 1) {
  if (!isMap(raw)) return null;
  const { value, revision, device } = raw;
  if (typeof value !== 'string' || value.length > 1024) return null;
  if (!Number.isSafeInteger(revision) || revision < minRevision || revision > MAX_REVISION) return null;
  if (typeof device !== 'string' || device.length < 1 || device.length > 80) return null;
  return { value, revision, device };
}

/** Kotlin's compareValuesBy(revision, device). */
export const newer = (a, b) => a.revision > b.revision || (a.revision === b.revision && a.device > b.device);

/**
 * The merged map and the keys that changed. With an empty account, the seed (this device's current
 * values) becomes the starting point at revision 0, as AccountPreferencesMerge does.
 */
export function mergeSettings(remote, edits, seed) {
  const result = { ...remote };
  const changed = new Set();
  if (Object.keys(remote).length === 0 && isMap(seed)) {
    for (const [key, value] of Object.entries(seed)) {
      if (!validSettingKey(key) || typeof value !== 'string' || value.length > 1024) continue;
      result[key] = { value, revision: 0, device: 'initial' };
      changed.add(key);
    }
  }
  if (isMap(edits)) {
    for (const [key, raw] of Object.entries(edits)) {
      const edit = preference(raw, 1);
      if (!validSettingKey(key) || !edit) continue;
      if (!result[key] || newer(edit, result[key])) { result[key] = edit; changed.add(key); }
    }
  }
  return { result, changed: [...changed] };
}

/**
 * Whether updated apps still read and mirror Firestore for this account, so old app versions keep
 * syncing with them. On until an operator retires it for this account; time alone never turns it off.
 */
export function bridgeActive(meta) {
  return !meta.bridgeRetiredAt;
}

/** ACCOUNT_SYNC_TOPICS lists the topics this Worker serves; unset serves none. */
export const topicEnabled = (env, topic) => String(env.ACCOUNT_SYNC_TOPICS ?? '').split(/[\s,]+/).includes(topic);
