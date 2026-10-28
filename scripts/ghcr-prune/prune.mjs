import { pathToFileURL } from 'node:url';

const DAY = 24 * 60 * 60 * 1000;
const RELEASE = /^(v\d+\.\d+\.\d+|latest|snapshot)(-distroless)?$/;
const SNAPSHOT = /^(v\d+\.\d+\.\d+-[0-9a-f]{7}-snapshot|[0-9a-f]{40})(-distroless)?$/;
const TEST = /^test-/;
// Signatures and attestations sit under a tag named after the digest they cover (the OCI
// referrers tag fallback; GHCR has no referrers API).
const REFERRERS = /^sha256-([0-9a-f]{64})$/;
const INDEX_TYPES = new Set(['application/vnd.oci.image.index.v1+json', 'application/vnd.docker.distribution.manifest.list.v2+json']);
const MANIFEST_ACCEPT = [...INDEX_TYPES, 'application/vnd.oci.image.manifest.v1+json', 'application/vnd.docker.distribution.manifest.v2+json'].join(',');

export const DEFAULTS = { keepSnapshots: 10, testMaxAgeDays: 30, minAgeHours: 24, maxDeletes: 300, keepTags: [] };

const tagsOf = (version) => version.metadata?.container?.tags ?? [];
const variantOf = (tag) => (tag.endsWith('-distroless') ? 'distroless' : 'default');

/**
 * Decides which tagged versions stay. A GHCR delete removes a version with every tag on it,
 * so one release or keep tag on a version keeps the snapshot and SHA tags that share it.
 */
export function keepTagged(versions, now, options = DEFAULTS) {
  const reasons = new Map();
  const keep = (version, reason) => { if (!reasons.has(version.name)) reasons.set(version.name, reason); };
  const explicit = new Set(options.keepTags);
  const snapshots = { default: [], distroless: [] };
  for (const version of versions) {
    const tags = tagsOf(version);
    if (tags.length === 0) continue;
    if (tags.some((tag) => RELEASE.test(tag))) keep(version, 'release');
    else if (tags.some((tag) => explicit.has(tag))) keep(version, 'keep-tag');
    else if (now - Date.parse(version.created_at) < options.minAgeHours * 60 * 60 * 1000) keep(version, 'recent');
    else if (tags.some((tag) => SNAPSHOT.test(tag))) snapshots[variantOf(tags.find((tag) => SNAPSHOT.test(tag)))].push(version);
    else if (tags.some((tag) => TEST.test(tag))) {
      if (now - Date.parse(version.created_at) < options.testMaxAgeDays * DAY) keep(version, 'test');
    } else if (!tags.every((tag) => REFERRERS.test(tag))) keep(version, 'unknown-tag');
  }
  for (const list of Object.values(snapshots)) {
    list.sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at));
    list.slice(0, options.keepSnapshots).forEach((version) => keep(version, 'snapshot'));
  }
  // A signature or attestation stays exactly as long as the image it covers.
  for (const version of versions) {
    const covered = tagsOf(version).map((tag) => tag.match(REFERRERS)?.[1]).find(Boolean);
    if (covered && reasons.has(`sha256:${covered}`)) keep(version, 'referrer');
  }
  return reasons;
}

/**
 * Builds the full plan. Children of every kept index (the per-architecture images and the
 * attestation manifests) are kept too; GHCR does not cascade a delete, so the untagged
 * children of a deleted index are deleted with it.
 */
export async function plan(versions, childrenOf, now, options = DEFAULTS) {
  const reasons = keepTagged(versions, now, options);
  const byName = new Map(versions.map((version) => [version.name, version]));
  const queue = [...reasons.keys()];
  while (queue.length > 0) {
    const name = queue.pop();
    for (const child of await childrenOf(name)) {
      if (!reasons.has(child) && byName.has(child)) {
        reasons.set(child, 'child');
        queue.push(child);
      }
    }
  }
  const minAge = options.minAgeHours * 60 * 60 * 1000;
  const remove = versions
    .filter((version) => !reasons.has(version.name))
    // An untagged image pushed just before the inventory may belong to an index pushed after it.
    .filter((version) => now - Date.parse(version.created_at) >= minAge)
    .sort((a, b) => Date.parse(a.created_at) - Date.parse(b.created_at));
  return { keep: reasons, remove: remove.slice(0, options.maxDeletes), pending: Math.max(0, remove.length - options.maxDeletes) };
}

export function summarize(result, versions) {
  const counts = {};
  for (const reason of result.keep.values()) counts[reason] = (counts[reason] ?? 0) + 1;
  const removed = { tagged: 0, untagged: 0 };
  for (const version of result.remove) removed[tagsOf(version).length > 0 ? 'tagged' : 'untagged'] += 1;
  return { versions: versions.length, keep: counts, remove: removed, pending: result.pending };
}

export class Registry {
  constructor(owner, name, token, fetchImpl = fetch) {
    this.owner = owner; this.name = name; this.token = token; this.fetch = fetchImpl; this.bearer = null;
  }

  async request(url, init = {}, attempt = 1) {
    const response = await this.fetch(url, init);
    if ((response.status === 429 || response.status === 403) && response.headers.get('retry-after') && attempt < 5) {
      await new Promise((resolve) => setTimeout(resolve, Number(response.headers.get('retry-after')) * 1000));
      return this.request(url, init, attempt + 1);
    }
    return response;
  }

  api(path, method = 'GET') {
    return this.request(`https://api.github.com${path}`, {
      method,
      headers: { Authorization: `Bearer ${this.token}`, Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28' },
    });
  }

  async versions() {
    const all = [];
    for (let page = 1; ; page++) {
      const response = await this.api(`/users/${this.owner}/packages/container/${this.name}/versions?per_page=100&page=${page}`);
      if (!response.ok) throw new Error(`Listing versions failed: HTTP ${response.status}`);
      const batch = await response.json();
      all.push(...batch);
      if (batch.length < 100) return all;
    }
  }

  async children(digest) {
    if (!this.bearer) {
      const basic = Buffer.from(`${this.owner}:${this.token}`).toString('base64');
      const response = await this.request(`https://ghcr.io/token?scope=repository:${this.owner}/${this.name}:pull`, { headers: { Authorization: `Basic ${basic}` } });
      if (!response.ok) throw new Error(`Registry token failed: HTTP ${response.status}`);
      this.bearer = (await response.json()).token;
    }
    const response = await this.request(`https://ghcr.io/v2/${this.owner}/${this.name}/manifests/${digest}`, {
      headers: { Authorization: `Bearer ${this.bearer}`, Accept: MANIFEST_ACCEPT },
    });
    // A kept image whose children cannot be read must not lose them: stop instead.
    if (!response.ok) throw new Error(`Reading manifest ${digest} failed: HTTP ${response.status}`);
    const manifest = await response.json();
    return INDEX_TYPES.has(manifest.mediaType) ? (manifest.manifests ?? []).map((entry) => entry.digest) : [];
  }

  async delete(version) {
    const response = await this.api(`/users/${this.owner}/packages/container/${this.name}/versions/${version.id}`, 'DELETE');
    if (!response.ok && response.status !== 404) throw new Error(`Deleting version ${version.id} failed: HTTP ${response.status}`);
  }
}

export function optionsFromEnv(env) {
  const number = (key, fallback) => {
    if (!env[key]) return fallback;
    const value = Number(env[key]);
    if (!Number.isInteger(value) || value < 0) throw new Error(`${key} must be a whole number`);
    return value;
  };
  return {
    keepSnapshots: number('KEEP_SNAPSHOTS', DEFAULTS.keepSnapshots),
    testMaxAgeDays: number('TEST_MAX_AGE_DAYS', DEFAULTS.testMaxAgeDays),
    minAgeHours: number('MIN_AGE_HOURS', DEFAULTS.minAgeHours),
    maxDeletes: number('MAX_DELETES', DEFAULTS.maxDeletes),
    keepTags: (env.KEEP_TAGS ?? '').split(/[\s,]+/).filter(Boolean),
  };
}

export async function main(env = process.env, log = console.log, registry = new Registry(env.OWNER, env.PACKAGE, env.GITHUB_TOKEN)) {
  const options = optionsFromEnv(env);
  const apply = env.APPLY === 'true';
  const versions = await registry.versions();
  const result = await plan(versions, (digest) => registry.children(digest), Date.now(), options);
  log(JSON.stringify(summarize(result, versions), null, 2));
  for (const version of result.remove) {
    const tags = tagsOf(version);
    log(`${apply ? 'delete' : 'would delete'} ${version.id} ${version.created_at.slice(0, 10)} ${tags.length > 0 ? tags.join(',') : version.name}`);
    if (apply) await registry.delete(version);
  }
  return result;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  main().catch((error) => { console.error(`::error::${error.message}`); process.exit(1); });
}
