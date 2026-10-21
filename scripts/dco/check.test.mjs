import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { DcoChecker, Git, GitHub, authenticatedAuthor } from './check.mjs';

const sha = (n) => n.toString(16).padStart(40, '0');
const base = sha(1), squash = sha(2), source = sha(3), repair = sha(4), merge = sha(5);
const repo = 'owner/repo';
const owner = { id: 12, login: 'alice', type: 'User' };
const signed = (email = 'alice@example.org') => ({ email, signoffs: [`Alice <${email}>`], remediation: [] });
const metadata = (id, author = owner) => ({ sha: id, author, committer: { id: 99, login: 'web-flow' }, commit: { verification: { verified: true } } });
function fixture({ missing = false } = {}) {
  const details = new Map([[squash, { ...signed('12+alice@users.noreply.github.com'), signoffs: missing ? [] : ['Alice <alice@example.org>'] }], [source, signed()], [repair, { ...signed(), remediation: [squash] }]]);
  const records = new Map([[squash, metadata(squash)], [source, metadata(source)], [repair, metadata(repair)]]);
  const pull = { number: 42, merged_at: '2026-10-21T00:00:00Z', merge_commit_sha: squash, base: { repo: { full_name: repo } }, head: { sha: source }, user: owner };
  const git = {
    range: (b) => b === base ? [squash] : [source],
    allRange: (b) => b === base ? [squash] : [source], details: (s) => structuredClone(details.get(s)),
    parents: (s) => s === squash ? [sha(10)] : [sha(11)],
    fetchPull: () => {}, mergeTree: () => sha(20), tree: () => sha(20),
  };
  const api = { commit: async (s) => structuredClone(records.get(s)), pulls: async () => [structuredClone(pull)] };
  const output = [];
  const checker = new DcoChecker(git, api, repo, (s) => output.push(s));
  return { checker, git, api, details, records, pull, output, run: () => checker.check(base, squash, owner) };
}

test('matching author sign-off passes without needing GitHub', async () => {
  const f = fixture(); f.details.set(squash, signed());
  f.api.commit = async () => { throw new Error('must not call API'); };
  assert.equal((await f.run()).accepted[0].method, 'matching-signoff');
});
for (const missing of [false, true]) test(`proven squash passes with ${missing ? 'discarded' : 'mismatched'} trailer`, async () => {
  const f = fixture({ missing }); const result = await f.run();
  assert.equal(result.failures.length, 0);
  assert.equal(result.accepted[0].method, missing ? 'signed-source-contributions' : 'verified-source-alias');
  assert.equal(result.evidence[0].sources[0].sha, source);
});

test('every source contribution needs a sign-off, even when the squash has an alias witness', async () => {
  const f = fixture(); const other = sha(6); f.git.range = (b) => b === base ? [squash] : [source, other];
  f.details.set(other, { ...signed('bob@example.org'), signoffs: [] });
  f.records.set(other, { ...metadata(other), committer: { id: 34, login: 'bob' } });
  assert.equal((await f.run()).failures.length, 1);
});

const invalidProvenance = {
  'unsigned squash': (f) => { f.records.get(squash).commit.verification.verified = false; },
  'another signer': (f) => { f.records.get(squash).committer = { id: 88, login: 'mallory' }; },
  'non-GitHub squash': (f) => { f.records.get(squash).committer = owner; },
  'wrong merged SHA': (f) => { f.pull.merge_commit_sha = sha(99); },
  'unmerged PR': (f) => { f.pull.merged_at = null; },
  'another repository': (f) => { f.pull.base.repo.full_name = 'attacker/repo'; },
  'ambiguous source PR': (f) => { f.api.pulls = async () => [f.pull, f.pull]; },
  'non-squash merge with uncertified edits': (f) => { f.git.parents = () => [base, source]; f.git.mergeTree = () => sha(99); },
  'invalid PR number': (f) => { f.pull.number = '../secret'; },
  'invalid source head': (f) => { f.pull.head.sha = '--help'; },
  'source PR ref changed': (f) => { f.git.fetchPull = () => { throw new Error('head changed'); }; },
  'changed squash content': (f) => { f.git.mergeTree = () => sha(99); },
  'conflicting merge': (f) => { f.git.mergeTree = () => { throw new Error('merge conflict'); }; },
  'empty source range': (f) => { f.git.range = (b) => b === base ? [squash] : []; },
  'source API unavailable': (f) => { f.api.pulls = async () => { throw new Error('HTTP 403'); }; },
  'mismatched source author': (f) => { f.records.get(source).author = { id: 70, login: 'bob' }; },
  'unverified source identity in another authors PR': (f) => { f.records.get(source).commit.verification.verified = false; f.pull.user = { id: 80, login: 'bob' }; },
  'trailer unrelated to source': (f) => { f.details.get(squash).signoffs = ['Bob <bob@example.org>']; },
  'malformed trailer': (f) => { f.details.get(squash).signoffs = ['alice@example.org']; },
};
for (const [name, mutate] of Object.entries(invalidProvenance)) test(`fail closed: ${name}`, async () => {
  const f = fixture(); mutate(f); const result = await f.run();
  assert.equal(result.failures.length, 1); assert.match(f.output[0], /require|Supply complete source evidence/);
});

test('unsigned source merge with hidden changes does not inherit its parents certifications', async () => {
  const f = fixture({ missing: true });
  f.details.set(merge, { ...signed(), signoffs: [] });
  f.git.allRange = (b) => b === base ? [squash] : [source, merge];
  f.git.parents = (s) => s === merge ? [sha(7), sha(8)] : [sha(10)];
  f.git.mergeTree = (p) => p === sha(7) ? sha(98) : sha(20);
  assert.match((await f.run()).failures[0].reason, /certification/);
});
test('unchanged automatic source merge is allowed', async () => {
  const f = fixture({ missing: true }); f.details.set(merge, { ...signed(), signoffs: [] });
  f.git.allRange = (b) => b === base ? [squash] : [source, merge];
  f.git.parents = (s) => s === merge ? [sha(7), sha(8)] : [sha(10)];
  assert.equal((await f.run()).failures.length, 0);
});
test('a signed source merge certifies its own conflict resolution', async () => {
  const f = fixture({ missing: true }); f.details.set(merge, signed());
  f.git.allRange = (b) => b === base ? [squash] : [source, merge];
  f.git.parents = (s) => s === merge ? [sha(7), sha(8)] : [sha(10)];
  assert.equal((await f.run()).failures.length, 0);
});

for (const invalid of [null, 'other-author', 'unsigned', 'invalid-signature', 'wrong-signer', 'short-sha', 'self-reference']) test(`author remediation: ${invalid || 'valid'}`, async () => {
  const f = fixture({ missing: true });
  f.git.allRange = (b) => b === base ? [repair, squash] : [source];
  f.api.pulls = async () => []; // No provenance fallback: remediation must stand on its own.
  if (invalid === 'other-author') f.records.get(repair).author = { id: 80, login: 'bob' };
  if (invalid === 'unsigned') f.details.get(repair).signoffs = [];
  if (invalid === 'invalid-signature') f.records.get(repair).commit.verification.verified = false;
  if (invalid === 'wrong-signer') f.records.get(repair).committer = { id: 80, login: 'bob' };
  if (invalid === 'short-sha') f.details.get(repair).remediation = ['abc123'];
  if (invalid === 'self-reference') f.details.get(repair).remediation = [repair];
  assert.equal((await f.run()).failures.length === 0, invalid === null);
});

test('bot exemption requires the actual authenticated bot that opened this PR', async () => {
  const f = fixture({ missing: true }); const bot = { id: 49699333, login: 'dependabot[bot]', type: 'Bot' };
  f.records.get(squash).author = bot;
  assert.equal((await f.checker.check(base, squash, bot)).accepted[0].method, 'authenticated-bot-pr');
  f.records.get(squash).author = owner;
  f.api.pulls = async () => [];
  assert.equal((await f.checker.check(base, squash, bot)).failures.length, 1);
});
test('no broad bot exception in a human release PR', async () => {
  const f = fixture({ missing: true }); f.records.get(squash).author = { id: 99, login: 'dependabot[bot]', type: 'Bot' };
  f.api.pulls = async () => [];
  assert.equal((await f.run()).failures.length, 1);
});
test('a failed git range fails the entire check', async () => {
  const f = fixture(); f.git.allRange = () => { throw new Error('missing base'); };
  await assert.rejects(f.run(), /missing base/);
});
test('workflow annotation output escapes injected newlines', async () => {
  const f = fixture(); f.api.commit = async () => { throw new Error('bad\n::notice::forged'); };
  await f.run(); assert.ok(!f.output[0].includes('\n')); assert.match(f.output[0], /%0A/);
});
test('identity requires the signer to authenticate the author', () => {
  assert.equal(authenticatedAuthor(metadata(source)), true);
  assert.equal(authenticatedAuthor({ ...metadata(source), committer: owner }), true);
  assert.equal(authenticatedAuthor({ ...metadata(source), author: null }), false);
});

test('GitHub API paginates PR associations instead of accepting a truncated list', async () => {
  const paths = [];
  const api = new GitHub(repo, '', async (url) => {
    paths.push(url); return { ok: true, json: async () => url.endsWith('page=1') ? Array(100).fill({ number: 1 }) : [{ number: 42 }] };
  });
  assert.equal((await api.pulls(squash)).length, 101); assert.equal(paths.length, 2);
});
test('GitHub API rejects failures, redirects and mismatched commit responses', async () => {
  assert.throws(() => new GitHub('../bad'));
  const unavailable = new GitHub(repo, '', async () => ({ ok: false, status: 403 }));
  await assert.rejects(unavailable.commit(squash), /HTTP 403/);
  const wrong = new GitHub(repo, '', async (url, options) => {
    assert.equal(options.redirect, 'error'); return { ok: true, json: async () => metadata(source) };
  });
  await assert.rejects(wrong.commit(squash), /different commit/);
  await assert.rejects(wrong.commit('--help'), /Invalid/);
});

test('real git: read trailers, detect squash tree changes, and exclude imported base commits', () => {
  const dir = mkdtempSync(join(tmpdir(), 'dco-git-'));
  const git = new Git(dir);
  try {
    const run = (...args) => execFileSync('git', args, { cwd: dir, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
    run('init', '-b', 'main'); run('config', 'user.name', 'Alice'); run('config', 'user.email', 'alice@example.org'); run('config', 'commit.gpgsign', 'false');
    writeFileSync(join(dir, 'base'), 'base'); run('add', '.'); run('commit', '-s', '-m', 'base'); const b = run('rev-parse', 'HEAD');
    run('checkout', '-b', 'topic'); writeFileSync(join(dir, 'feature'), 'feature'); run('add', '.'); run('commit', '-s', '-m', 'feature'); const h = run('rev-parse', 'HEAD');
    run('checkout', 'main'); writeFileSync(join(dir, 'later'), 'later'); run('add', '.'); run('commit', '-s', '-m', 'later base'); const parent = run('rev-parse', 'HEAD');
    run('merge', '--squash', 'topic'); run('commit', '-m', 'squashed'); const s = run('rev-parse', 'HEAD');
    assert.equal(git.mergeTree(parent, h), git.tree(s)); assert.deepEqual(git.range(parent, h), [h]);
    assert.deepEqual(git.parents(s), [parent]); assert.deepEqual(git.details(s).signoffs, []);
    assert.deepEqual(git.details(h).signoffs, ['Alice <alice@example.org>']);
    assert.deepEqual(git.allRange(b, h), [h]);
    writeFileSync(join(dir, 'feature'), 'changed during squash'); run('add', '.'); run('commit', '-s', '-m', 'different');
    assert.notEqual(git.mergeTree(parent, h), git.tree(run('rev-parse', 'HEAD')));
    assert.throws(() => git.range(sha(99), h));
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('original DCO sign-off in the same authors authenticated PR needs no cryptographic source signature', async () => {
  const f = fixture(); f.records.get(source).commit.verification.verified = false;
  assert.equal((await f.run()).failures.length, 0);
});
test('an unsigned conflict-resolution merge can be explicitly remediated by its author', async () => {
  const f = fixture({ missing: true }); f.details.set(merge, { ...signed(), signoffs: [] });
  f.records.set(merge, metadata(merge)); f.details.get(repair).remediation = [merge];
  f.git.allRange = (b) => b === base ? [repair, squash] : [source]; f.git.allRange = (b) => b === base ? [repair, squash] : [source, merge];
  f.git.parents = (s) => s === merge ? [sha(7), sha(8)] : [sha(10)];
  f.git.mergeTree = (p) => { if (p === sha(7)) throw new Error('conflict'); return sha(20); };
  assert.equal((await f.run()).failures.length, 0);
});

test('GitHub request caches commit evidence and supplies read-only credentials without redirects', async () => {
  let calls = 0;
  const api = new GitHub(repo, 'test-token', async (url, options) => {
    calls++; assert.equal(options.headers.Authorization, 'Bearer test-token');
    assert.equal(options.redirect, 'error'); return { ok: true, json: async () => metadata(source) };
  });
  await api.commit(source); await api.commit(source); assert.equal(calls, 1);
  const invalid = new GitHub(repo, '', async () => ({ ok: true, json: async () => ({}) }));
  await assert.rejects(invalid.pulls(source), /Invalid source PR response/);
});
test('invalid ranges and recursive source histories cannot pass', async () => {
  const f = fixture(); await assert.rejects(f.checker.check('--all', squash, owner), /full commit SHAs/);
  await assert.rejects(f.checker.validate('--help', owner), /Invalid commit SHA/);
  f.git.range = (b) => b === base ? [squash] : [squash];
  assert.equal((await f.run()).failures.length, 1);
});
test('trailers match email addresses case-insensitively', async () => {
  const f = fixture(); f.details.set(squash, { ...signed('ALICE@EXAMPLE.ORG'), signoffs: ['Alice <alice@example.org>'] });
  assert.equal((await f.run()).accepted[0].method, 'matching-signoff');
});
test('real Git fetch pins the PR head, and CLI returns a failing status on incomplete evidence', () => {
  const dir = mkdtempSync(join(tmpdir(), 'dco-cli-'));
  try {
    const git = new Git(dir, 'fake-test-token');
    const run = (...args) => execFileSync('git', args, { cwd: dir, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
    run('init', '-b', 'main'); run('config', 'user.name', 'Alice'); run('config', 'user.email', 'alice@example.org'); run('config', 'commit.gpgsign', 'false');
    writeFileSync(join(dir, 'a'), 'base'); run('add', '.'); run('commit', '-s', '-m', 'base'); const b = run('rev-parse', 'HEAD');
    writeFileSync(join(dir, 'a'), 'feature'); run('add', '.'); run('commit', '-s', '-m', 'feature'); const h = run('rev-parse', 'HEAD');
    run('remote', 'add', 'origin', dir); run('update-ref', 'refs/pull/42/head', h);
    git.fetchPull(42, h); assert.throws(() => git.fetchPull(42, sha(99)), /head changed/);
    run('init', 'empty');
    const empty = new Git(join(dir, 'empty'), 'fake-test-token');
    empty.run('remote', 'add', 'origin', dir); empty.fetchPull(42, h);
    assert.equal(empty.run('rev-parse', 'FETCH_HEAD'), h);
    const event = join(dir, 'event.json'), evidence = join(dir, 'evidence.json');
    writeFileSync(event, JSON.stringify({ pull_request: { user: owner } }));
    const env = { ...process.env, BASE_SHA: b, HEAD_SHA: h, GITHUB_EVENT_PATH: event, GITHUB_REPOSITORY: repo, GITHUB_TOKEN: '', DCO_EVIDENCE_FILE: evidence };
    const script = new URL('./check.mjs', import.meta.url).pathname;
    const output = execFileSync(process.execPath, [script], { cwd: dir, env, encoding: 'utf8' });
    assert.match(output, /0 require remediation/);
    assert.equal(JSON.parse(readFileSync(evidence, 'utf8')).accepted[0].method, 'matching-signoff');
    assert.throws(() => execFileSync(process.execPath, [script], { cwd: dir, env: { ...env, BASE_SHA: '--all' }, stdio: 'pipe' }), (e) => e.status === 1);
    assert.throws(() => execFileSync(process.execPath, [script], { cwd: dir, env: { ...env, GITHUB_EVENT_PATH: join(dir, 'missing') }, stdio: 'pipe' }), (e) => e.status === 1);
    writeFileSync(join(dir, 'a'), 'unsigned'); run('add', 'a'); run('commit', '-m', 'unsigned contribution');
    const denied = join(dir, 'deny-api.mjs'); writeFileSync(denied, 'globalThis.fetch = async () => ({ ok: false, status: 403 });');
    assert.throws(() => execFileSync(process.execPath, ['--import', denied, script], {
      cwd: dir, env: { ...env, HEAD_SHA: run('rev-parse', 'HEAD') }, stdio: 'pipe',
    }), (e) => e.status === 1);
    assert.equal(JSON.parse(readFileSync(evidence, 'utf8')).failures.length, 1);

  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('a human release can prove the original narrow bot-PR exemption without a blanket bot-email exception', async () => {
  const f = fixture(); const bot = { id: 49699333, login: 'dependabot[bot]', type: 'Bot' };
  const email = '49699333+dependabot[bot]@users.noreply.github.com';
  f.pull.user = bot; f.records.get(squash).author = bot; f.records.get(source).author = bot;
  for (const id of [squash, source]) f.details.set(id, { ...signed(email), signoffs: ['dependabot[bot] <support@github.com>'] });
  assert.equal((await f.run()).failures.length, 0);
  f.details.get(squash).signoffs = ['Unknown <other@example.org>'];
  assert.equal((await f.run()).failures.length, 1);
});

for (const kind of ['edited', 'conflict', 'octopus', 'automatic', 'signed', 'remediated', 'wrong-remediator']) {
  test(`primary range merge: ${kind}`, async () => {
    const f = fixture();
    f.details.set(merge, { ...signed(), signoffs: kind === 'signed' ? signed().signoffs : [] });
    f.records.set(merge, metadata(merge));
    f.git.range = () => [source]; // The old non-merge scan would silently skip the merge.
    f.git.allRange = () => ['remediated', 'wrong-remediator'].includes(kind) ? [repair, merge, source] : [merge, source];
    f.git.parents = (s) => s === merge ? (kind === 'octopus' ? [sha(7), sha(8), sha(9)] : [sha(7), sha(8)]) : [sha(10)];
    f.git.mergeTree = () => { if (kind === 'conflict') throw new Error('conflict'); return kind === 'automatic' ? sha(20) : sha(99); };
    f.details.get(repair).remediation = [merge];
    if (kind === 'wrong-remediator') f.records.get(repair).author = { id: 80, login: 'bob' };
    const result = await f.run();
    const valid = ['automatic', 'signed', 'remediated'].includes(kind);
    assert.equal(result.failures.length, valid ? 0 : 1);
    if (valid) assert.ok(result.accepted.some(c => c.sha === merge));
    else assert.equal(result.failures[0].sha, merge);
  });
}

test('real Git primary range rejects unsigned edits made only in a merge commit', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'dco-merge-'));
  try {
    const git = new Git(dir); const run = (...args) => git.run(...args);
    run('init', '-b', 'main'); run('config', 'user.name', 'Alice'); run('config', 'user.email', 'alice@example.org'); run('config', 'commit.gpgsign', 'false');
    writeFileSync(join(dir, 'base'), 'base'); run('add', '.'); run('commit', '-s', '-m', 'base'); const b = run('rev-parse', 'HEAD');
    run('checkout', '-b', 'topic'); writeFileSync(join(dir, 'feature'), 'feature'); run('add', '.'); run('commit', '-s', '-m', 'feature');
    run('checkout', 'main'); writeFileSync(join(dir, 'main'), 'main'); run('add', '.'); run('commit', '-s', '-m', 'main'); const parent = run('rev-parse', 'HEAD');
    run('merge', '--no-ff', '--no-commit', 'topic'); writeFileSync(join(dir, 'hidden'), 'uncertified merge-only change'); run('add', '.'); run('commit', '-m', 'merge with extra edit'); const edited = run('rev-parse', 'HEAD');
    const api = { commit: async () => { throw new Error('No API proof expected'); } };
    const checker = new DcoChecker(git, api, repo, () => {});
    const result = await checker.check(b, edited, owner);
    assert.equal(result.failures.length, 1); assert.equal(result.failures[0].sha, edited);
    run('commit', '--amend', '-s', '-m', 'certify merge changes');
    assert.equal((await checker.check(b, run('rev-parse', 'HEAD'), owner)).failures.length, 0);
    run('checkout', '-b', 'clean', parent); run('merge', '--no-ff', '-m', 'automatic merge', 'topic'); const clean = run('rev-parse', 'HEAD');
    const automatic = await checker.check(b, clean, owner);
    assert.equal(automatic.failures.length, 0);
    assert.equal(automatic.accepted.find(c => c.sha === clean).method, 'unchanged-automatic-merge');
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
