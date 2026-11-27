import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, test } from 'node:test';
import { Git, checkTag, fetchSigningKeys, main, parseSigners, pomVersion } from './check-tag.mjs';

// The developer's own git configuration (signing format, keys, hooks) must not decide a result.
process.env.GIT_CONFIG_GLOBAL = '/dev/null';
process.env.GIT_CONFIG_NOSYSTEM = '1';

const pom = (version) => `<?xml version="1.0"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <parent><groupId>p</groupId><artifactId>p</artifactId><version>9.9.9</version></parent>
  <!-- <version>8.8.8</version> -->
  <artifactId>bot</artifactId>
  <version>${version}</version>
  <dependencies><dependency><artifactId>d</artifactId><version>7.7.7</version></dependency></dependencies>
</project>
`;

const root = mkdtempSync(join(tmpdir(), 'check-tag-test-'));
after(() => rmSync(root, { recursive: true, force: true }));

function keypair(name) {
  const file = join(root, name);
  execFileSync('ssh-keygen', ['-q', '-t', 'ed25519', '-N', '', '-C', name, '-f', file]);
  return { file, key: readFileSync(`${file}.pub`, 'utf8').trim().split(/\s+/).slice(0, 2).join(' ') };
}
const maintainer = keypair('maintainer');
const stranger = keypair('stranger');

/**
 * A repository with one commit on main (pom at 1.2.3), a second commit only on a side branch,
 * and the working tree left at `checkout`.
 */
function repository() {
  const dir = mkdtempSync(join(root, 'repo-'));
  const run = (...args) => execFileSync('git', args, { cwd: dir, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  run('init', '-q', '-b', 'main');
  run('config', 'user.name', 'Test');
  run('config', 'user.email', 'test@example.org');
  run('config', 'commit.gpgsign', 'false');
  run('config', 'tag.gpgsign', 'false');
  writeFileSync(join(dir, 'pom.xml'), pom('1.2.3'));
  run('add', 'pom.xml');
  run('commit', '-q', '-m', 'release 1.2.3');
  const onMain = run('rev-parse', 'HEAD');
  run('update-ref', 'refs/remotes/origin/main', onMain);
  run('checkout', '-q', '-b', 'side');
  writeFileSync(join(dir, 'extra.txt'), 'not reviewed\n');
  run('add', 'extra.txt');
  run('commit', '-q', '-m', 'side work');
  const offMain = run('rev-parse', 'HEAD');
  const signedTag = (name, commit, key = maintainer) => {
    run('-c', 'gpg.format=ssh', '-c', `user.signingkey=${key.file}.pub`, 'tag', '-s', '-m', name, name, commit);
    run('checkout', '-q', '--detach', `refs/tags/${name}`);
  };
  return { dir, run, onMain, offMain, signedTag, git: new Git(dir) };
}

const keysOf = async (login) => (login === 'maintainer' ? [maintainer.key] : []);
const check = (repo, overrides = {}) => checkTag({
  version: 'v1.2.3', eventName: 'push', ref: 'refs/tags/v1.2.3', signers: ['maintainer'],
  pom: readFileSync(join(repo.dir, 'pom.xml'), 'utf8'), git: repo.git, keysOf, ...overrides,
});

test('a signed tag on main whose version matches the pom is accepted', async () => {
  const repo = repository(); repo.signedTag('v1.2.3', repo.onMain);
  assert.deepEqual(await check(repo), { tag: 'v1.2.3', sha: repo.onMain });
});

test('a tag on a commit that is not on main is refused', async () => {
  const repo = repository(); repo.signedTag('v1.2.3', repo.offMain);
  await assert.rejects(check(repo), /is not on main/);
});

test('a lightweight tag is refused', async () => {
  const repo = repository(); repo.run('tag', 'v1.2.3', repo.onMain); repo.run('checkout', '-q', '--detach', 'refs/tags/v1.2.3');
  await assert.rejects(check(repo), /lightweight tag/);
});

test('an annotated tag with no signature is refused', async () => {
  const repo = repository(); repo.run('tag', '-a', '-m', 'v1.2.3', 'v1.2.3', repo.onMain); repo.run('checkout', '-q', '--detach', 'refs/tags/v1.2.3');
  await assert.rejects(check(repo), /is not signed with an SSH signing key GitHub lists for maintainer/);
});

test('a tag signed with a key the maintainers do not publish is refused', async () => {
  const repo = repository(); repo.signedTag('v1.2.3', repo.onMain, stranger);
  await assert.rejects(check(repo), /is not signed with an SSH signing key/);
});

test('a GPG-signed tag is refused even where the local keyring would accept it', async () => {
  const repo = repository();
  // Stands in for a gpg whose keyring trusts the signer: it signs anything and verifies anything.
  const gpg = join(repo.dir, 'fake-gpg');
  writeFileSync(gpg, `#!/bin/sh
case "$*" in
  *--verify*) cat >/dev/null; echo "[GNUPG:] NEWSIG"; echo "[GNUPG:] GOODSIG 0000000000000000 Someone"
    echo "[GNUPG:] VALIDSIG 0000000000000000000000000000000000000000 2026-01-01 1 0 4 0 1 10 00 0000000000000000000000000000000000000000"
    echo "[GNUPG:] TRUST_ULTIMATE 0 pgp" ;;
  *) cat >/dev/null; echo "-----BEGIN PGP SIGNATURE-----"; echo; echo "ZmFrZQ=="; echo "-----END PGP SIGNATURE-----"; echo "[GNUPG:] SIG_CREATED D" >&2 ;;
esac
`);
  chmodSync(gpg, 0o755);
  repo.run('config', 'gpg.program', gpg);
  repo.run('-c', 'gpg.format=openpgp', '-c', 'user.signingkey=someone', 'tag', '-s', '-m', 'v1.2.3', 'v1.2.3', repo.onMain);
  repo.run('checkout', '-q', '--detach', 'refs/tags/v1.2.3');
  repo.run('verify-tag', 'v1.2.3');
  await assert.rejects(check(repo), /is not signed with an SSH signing key/);
});

test('a signature is judged only against the listed accounts', async () => {
  const repo = repository(); repo.signedTag('v1.2.3', repo.onMain, stranger);
  const everyone = async (login) => (login === 'stranger' ? [stranger.key] : [maintainer.key]);
  await assert.rejects(check(repo, { keysOf: everyone }), /is not signed/);
  assert.equal((await check(repo, { keysOf: everyone, signers: ['maintainer', 'stranger'] })).sha, repo.onMain);
});

test('no accepted signer or no published key refuses the tag', async () => {
  const repo = repository(); repo.signedTag('v1.2.3', repo.onMain);
  await assert.rejects(check(repo, { signers: [] }), /names no account/);
  await assert.rejects(check(repo, { keysOf: async () => [] }), /No SSH signing key is published/);
  await assert.rejects(check(repo, { keysOf: async () => ['not a key'] }), /No SSH signing key is published/);
  await assert.rejects(check(repo, { keysOf: async () => { throw new Error('HTTP 503'); } }), /HTTP 503/);
});

test('a tag that does not match the pom version is refused', async () => {
  const repo = repository(); repo.signedTag('v1.2.4', repo.onMain);
  await assert.rejects(check(repo, { version: 'v1.2.4', ref: 'refs/tags/v1.2.4' }), /does not match pom.xml version 1.2.3/);
});

test('the version must be a plain semver tag', async () => {
  const repo = repository(); repo.signedTag('v1.2.3', repo.onMain);
  for (const version of ['1.2.3', 'v1.2', 'v1.2.3-rc1', 'v1.2.3\nmain', 'main', '', undefined]) {
    await assert.rejects(check(repo, { version }), /must be a semver tag/);
  }
});

test('a missing tag and a working tree at another commit are refused', async () => {
  const repo = repository();
  await assert.rejects(check(repo), /does not exist/);
  repo.signedTag('v1.2.3', repo.onMain); repo.run('checkout', '-q', 'side');
  await assert.rejects(check(repo), /working tree is not at tag/);
});

test('a tag push runs on its own tag; a manual run on the tag or on main', async () => {
  const repo = repository(); repo.signedTag('v1.2.3', repo.onMain);
  await assert.rejects(check(repo, { ref: 'refs/heads/main' }), /must run on refs\/tags\/v1\.2\.3,/);
  await assert.rejects(check(repo, { ref: 'refs/tags/v9.9.9' }), /must run on/);
  const manual = { eventName: 'workflow_dispatch' };
  assert.equal((await check(repo, { ...manual, ref: 'refs/heads/main' })).tag, 'v1.2.3');
  assert.equal((await check(repo, { ...manual, ref: 'refs/tags/v1.2.3' })).tag, 'v1.2.3');
  await assert.rejects(check(repo, { ...manual, ref: 'refs/heads/side' }), /must run on refs\/tags\/v1\.2\.3 or refs\/heads\/main, not on 'refs\/heads\/side'/);
  await assert.rejects(check(repo, { ...manual, ref: 'refs/tags/v1.0.0' }), /must run on/);
});

test('pomVersion reads the project version, not a parent, dependency or commented one', () => {
  assert.equal(pomVersion(pom('0.9.4-SNAPSHOT')), '0.9.4-SNAPSHOT');
  assert.equal(pomVersion('<project><parent><version>1</version></parent></project>'), null);
  assert.equal(pomVersion('<project><properties><a.version>2</a.version></properties><modelVersion/><version> 3.0.0 </version></project>'), '3.0.0');
});

test('parseSigners splits the list and refuses anything that is not a login', () => {
  assert.deepEqual(parseSigners(' alice, bob-1 ,,'), ['alice', 'bob-1']);
  assert.deepEqual(parseSigners(undefined), []);
  assert.throws(() => parseSigners('alice,../orgs/x'), /not a GitHub login/);
});

test('fetchSigningKeys returns key type and body, and fails on an HTTP error', async () => {
  const calls = [];
  const ok = async (url, options) => {
    calls.push([url, options.headers.authorization]);
    return { ok: true, json: async () => [{ key: `${maintainer.key} comment` }, {}] };
  };
  assert.deepEqual(await fetchSigningKeys('alice', 'token', ok), [maintainer.key, '']);
  assert.deepEqual(calls, [['https://api.github.com/users/alice/ssh_signing_keys?per_page=100', 'Bearer token']]);
  await fetchSigningKeys('alice', '', async (url, options) => {
    assert.equal(options.headers.authorization, undefined);
    return { ok: true, json: async () => [] };
  });
  await assert.rejects(fetchSigningKeys('alice', 'token', async () => ({ ok: false, status: 404 })), /HTTP 404/);
});

test('main writes the tag and commit for the workflow, or an error annotation', async () => {
  const repo = repository(); repo.signedTag('v1.2.3', repo.onMain);
  const output = join(repo.dir, 'github-output');
  const realFetch = globalThis.fetch;
  globalThis.fetch = async () => ({ ok: true, json: async () => [{ key: maintainer.key }] });
  try {
    const env = { VERSION: 'v1.2.3', GITHUB_EVENT_NAME: 'push', GITHUB_REF: 'refs/tags/v1.2.3', RELEASE_TAG_SIGNERS: 'maintainer', GITHUB_OUTPUT: output };
    const lines = [];
    assert.equal(await main(env, (line) => lines.push(line), repo.git), 0);
    assert.equal(readFileSync(output, 'utf8'), `tag=v1.2.3\nsha=${repo.onMain}\n`);
    assert.equal(await main({ ...env, GITHUB_OUTPUT: '', MAIN_REF: 'refs/heads/side' }, (line) => lines.push(line), repo.git), 0);
    assert.equal(await main({ ...env, GITHUB_REF: 'refs/heads/side' }, (line) => lines.push(line), repo.git), 1);
    assert.match(lines.at(-1), /^::error::The release workflow must run on/);
    assert.equal(readFileSync(output, 'utf8'), `tag=v1.2.3\nsha=${repo.onMain}\n`);
  } finally {
    globalThis.fetch = realFetch;
  }
});
