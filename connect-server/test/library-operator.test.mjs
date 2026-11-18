import { test } from 'node:test';
import assert from 'node:assert/strict';
import { firestoreClient, Retryable } from '../tools/library-migrate.mjs';

test('operator OAuth authenticates document reads without a service-account key', async () => {
  const client = firestoreClient({ projectId: 'demo-auralis', accessToken: 'local-test-token',
    fetcher: async (url, init) => {
      assert.equal(init.headers.authorization, 'Bearer local-test-token');
      assert.equal(url, 'https://firestore.googleapis.com/v1/projects/demo-auralis/databases/(default)/documents/users/test');
      return Response.json({ updateTime: 'source-time', fields: { favorites: { arrayValue: { values: [] } } } });
    } });
  assert.deepEqual(await client.get('users/test'), { exists: true, updateTime: 'source-time', data: { favorites: [] } });
});

for (const status of [401, 403, 429, 503]) {
  test(`operator read HTTP ${status} stops migration instead of becoming an empty library`, async () => {
    const client = firestoreClient({ projectId: 'demo-auralis', accessToken: 'local-test-token',
      fetcher: async () => new Response('', { status }) });
    await assert.rejects(client.get('users/test'), Retryable);
  });
}

test('only a confirmed 404 is a missing source document', async () => {
  const client = firestoreClient({ projectId: 'demo-auralis', accessToken: 'local-test-token',
    fetcher: async () => new Response('', { status: 404 }) });
  assert.deepEqual(await client.get('users/test'), { exists: false, updateTime: null, data: null });
});
