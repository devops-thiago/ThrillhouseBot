import assert from 'node:assert/strict';
import { test } from 'node:test';
import { DEFAULTS, Registry, keepTagged, main, optionsFromEnv, plan, summarize } from './prune.mjs';

const NOW = Date.parse('2026-10-28T00:00:00Z');
const daysAgo = (days) => new Date(NOW - days * 24 * 60 * 60 * 1000).toISOString();
const digest = (n) => `sha256:${n.toString(16).padStart(64, '0')}`;
let next = 1;
const version = (tags, days = 60, n = next++) => ({ id: n, name: digest(n), created_at: daysAgo(days), metadata: { container: { tags } } });
const noChildren = async () => [];
const options = (overrides = {}) => ({ ...DEFAULTS, ...overrides });
const removed = (result) => result.remove.map((v) => v.id).sort((a, b) => a - b);

test('a release keeps the snapshot and SHA tags that share its version', async () => {
  const release = version(['v0.6.8', 'v0.6.8-abc1234-snapshot', 'a'.repeat(40)]);
  const result = await plan([release], noChildren, NOW, options({ keepSnapshots: 0 }));
  assert.equal(result.keep.get(release.name), 'release');
  assert.deepEqual(result.remove, []);
});

test('latest and the moving snapshot tags are kept', () => {
  const tagged = [version(['latest']), version(['snapshot-distroless']), version(['v1.2.3-distroless'])];
  assert.deepEqual([...keepTagged(tagged, NOW, options({ keepSnapshots: 0 })).values()], ['release', 'release', 'release']);
});

test('only the newest snapshots of each variant are kept', async () => {
  const old = version(['v0.6.8-1111111-snapshot'], 40);
  const mid = version(['v0.6.8-2222222-snapshot'], 20);
  const fresh = version(['v0.6.9-3333333-snapshot'], 5);
  const distroless = version(['v0.6.8-1111111-snapshot-distroless'], 40);
  const bareSha = version(['b'.repeat(40)], 50);
  const result = await plan([old, mid, fresh, distroless, bareSha], noChildren, NOW, options({ keepSnapshots: 2 }));
  assert.deepEqual(removed(result), [old.id, bareSha.id].sort((a, b) => a - b));
  assert.equal(result.keep.get(distroless.name), 'snapshot');
});

test('test images expire by age unless a keep tag names them', async () => {
  const stale = version(['test-sha-1234567-native'], 31);
  const recent = version(['test-pr-12-native'], 3);
  const pinned = version(['test-v0.9.0-preview-native'], 90);
  const result = await plan([stale, recent, pinned], noChildren, NOW, options({ keepTags: ['test-v0.9.0-preview-native'] }));
  assert.deepEqual(removed(result), [stale.id]);
  assert.equal(result.keep.get(pinned.name), 'keep-tag');
});

test('a tag the rules do not know is kept', async () => {
  const odd = version(['nightly'], 400);
  assert.equal((await plan([odd], noChildren, NOW, options())).keep.get(odd.name), 'unknown-tag');
});

test('children of a kept index stay and children of a deleted index go', async () => {
  const keptIndex = version(['v1.0.0']);
  const amd = version([]), arm = version([]), attestation = version([]);
  const goneIndex = version(['test-sha-7654321-native'], 45);
  const orphan = version([]);
  const tree = new Map([[keptIndex.name, [amd.name, arm.name, attestation.name]], [goneIndex.name, [orphan.name]]]);
  const result = await plan([keptIndex, amd, arm, attestation, goneIndex, orphan], async (d) => tree.get(d) ?? [], NOW, options());
  assert.deepEqual(removed(result), [goneIndex.id, orphan.id]);
  assert.equal(result.keep.get(arm.name), 'child');
});

test('children absent from the inventory are ignored', async () => {
  const index = version(['v1.0.0']);
  const result = await plan([index], async () => [digest(9999)], NOW, options());
  assert.equal(result.keep.size, 1);
});

test('a signature tag lives exactly as long as the image it covers', async () => {
  const release = version(['v1.0.0']);
  const stale = version(['test-sha-1234567'], 45);
  const releaseSig = version([`sha256-${release.name.slice(7)}`]);
  const staleSig = version([`sha256-${stale.name.slice(7)}`]);
  const signature = version([]);
  const tree = new Map([[releaseSig.name, [signature.name]]]);
  const result = await plan([release, stale, releaseSig, staleSig, signature], async (d) => tree.get(d) ?? [], NOW, options());
  assert.equal(result.keep.get(releaseSig.name), 'referrer');
  assert.equal(result.keep.get(signature.name), 'child');
  assert.deepEqual(removed(result), [stale.id, staleSig.id].sort((a, b) => a - b));
});

test('nothing younger than the minimum age is deleted', async () => {
  const freshTest = version(['test-sha-1111111'], 0.5);
  const freshUntagged = version([], 0.5);
  const result = await plan([freshTest, freshUntagged], noChildren, NOW, options({ testMaxAgeDays: 0 }));
  assert.deepEqual(result.remove, []);
  assert.equal(result.keep.get(freshTest.name), 'recent');
});

test('the deletion cap takes the oldest first and reports the rest', async () => {
  const versions = [version([], 10), version([], 30), version([], 20)];
  const result = await plan(versions, noChildren, NOW, options({ maxDeletes: 2 }));
  assert.deepEqual(result.remove.map((v) => v.created_at), [daysAgo(30), daysAgo(20)]);
  assert.equal(result.pending, 1);
  assert.deepEqual(summarize(result, versions), { versions: 3, keep: {}, remove: { tagged: 0, untagged: 2 }, pending: 1 });
});

test('options come from the environment and reject bad numbers', () => {
  assert.deepEqual(optionsFromEnv({ KEEP_SNAPSHOTS: '3', KEEP_TAGS: 'a, b\nc' }), { ...DEFAULTS, keepSnapshots: 3, keepTags: ['a', 'b', 'c'] });
  assert.throws(() => optionsFromEnv({ MAX_DELETES: '-1' }), /MAX_DELETES/);
  assert.throws(() => optionsFromEnv({ MIN_AGE_HOURS: 'x' }), /MIN_AGE_HOURS/);
});

function fakeFetch(routes) {
  const calls = [];
  const impl = async (url, init = {}) => {
    calls.push({ url, method: init.method ?? 'GET' });
    const route = routes.find(([pattern]) => pattern.test(url));
    const [, status, body, headers = {}] = typeof route[1] === 'function' ? route[1](calls) : route;
    return { ok: status >= 200 && status < 300, status, json: async () => body, headers: { get: (key) => headers[key] ?? null } };
  };
  return { impl, calls };
}

test('the registry pages through versions and reads index children', async () => {
  const first = Array.from({ length: 100 }, (_, i) => ({ id: i }));
  const { impl, calls } = fakeFetch([
    [/page=1$/, 200, first],
    [/page=2$/, 200, [{ id: 100 }]],
    [/ghcr\.io\/token/, 200, { token: 't' }],
    [/manifests\/sha256:index/, 200, { mediaType: 'application/vnd.oci.image.index.v1+json', manifests: [{ digest: 'sha256:a' }] }],
    [/manifests\/sha256:image/, 200, { mediaType: 'application/vnd.oci.image.manifest.v1+json' }],
  ]);
  const registry = new Registry('o', 'p', 'secret', impl);
  assert.equal((await registry.versions()).length, 101);
  assert.deepEqual(await registry.children('sha256:index'), ['sha256:a']);
  assert.deepEqual(await registry.children('sha256:image'), []);
  assert.equal(calls.filter((c) => c.url.includes('/token')).length, 1);
});

test('the registry fails closed on a manifest, token or listing error', async () => {
  const broken = new Registry('o', 'p', 's', fakeFetch([[/token/, 200, { token: 't' }], [/manifests/, 500, {}]]).impl);
  await assert.rejects(broken.children('sha256:x'), /Reading manifest/);
  const noToken = new Registry('o', 'p', 's', fakeFetch([[/token/, 401, {}]]).impl);
  await assert.rejects(noToken.children('sha256:x'), /Registry token/);
  const noList = new Registry('o', 'p', 's', fakeFetch([[/versions/, 403, {}]]).impl);
  await assert.rejects(noList.versions(), /Listing versions/);
});

test('the registry retries a rate limit and treats a missing version as deleted', async () => {
  const { impl, calls } = fakeFetch([[/versions\/1$/, (seen) => (seen.length === 1 ? [null, 429, {}, { 'retry-after': '0' }] : [null, 404, {}])], [/versions\/2$/, 500, {}]]);
  const registry = new Registry('o', 'p', 's', impl);
  await registry.delete({ id: 1 });
  assert.equal(calls.length, 2);
  assert.equal(calls[0].method, 'DELETE');
  await assert.rejects(registry.delete({ id: 2 }), /Deleting version 2/);
});

test('a dry run lists the plan and deletes nothing; apply deletes it', async () => {
  const stale = version(['test-sha-1234567'], 45);
  const kept = version(['v1.0.0']);
  const deleted = [];
  const registry = { versions: async () => [stale, kept], children: noChildren, delete: async (v) => deleted.push(v.id) };
  const lines = [];
  await main({}, (line) => lines.push(line), registry);
  assert.deepEqual(deleted, []);
  assert.match(lines.at(-1), /^would delete .* test-sha-1234567$/);
  await main({ APPLY: 'true' }, () => {}, registry);
  assert.deepEqual(deleted, [stale.id]);
});

test('an untagged version is logged by its digest', async () => {
  const orphan = version([], 45);
  const lines = [];
  await main({}, (line) => lines.push(line), { versions: async () => [orphan], children: noChildren, delete: async () => {} });
  assert.match(lines.at(-1), new RegExp(`${orphan.name}$`));
});
