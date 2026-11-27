import { execFileSync } from 'node:child_process';
import { appendFileSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

// Checks a release tag before anything is promoted or signed from it. The release workflow runs
// this from its own ref, with the tag checked out in the working directory, so a tag cannot bring
// its own checker. Every check fails closed: a missing answer is a refusal.

const VERSION = /^v\d+\.\d+\.\d+$/;
const LOGIN = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$/;
const SSH_KEY = /^(?:sk-)?(?:ssh|ecdsa)-[a-z0-9@.-]+ [A-Za-z0-9+/=]+$/;

export class Git {
  constructor(cwd = process.cwd()) { this.cwd = cwd; }
  run(args, options = {}) {
    return execFileSync('git', args, { cwd: this.cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], ...options }).trim();
  }
  ok(args) {
    try { this.run(args); return true; } catch { return false; }
  }
  /** The kind of object the ref names: `tag` for an annotated tag, `commit` for a lightweight one. */
  type(ref) { return this.run(['cat-file', '-t', ref]); }
  commit(ref) { return this.run(['rev-parse', '--verify', `${ref}^{commit}`]); }
  isAncestor(commit, ref) { return this.ok(['merge-base', '--is-ancestor', commit, ref]); }
  /** True when the tag carries an SSH signature made by one of the given keys. */
  signedBy(ref, signers) {
    const dir = mkdtempSync(join(tmpdir(), 'release-signers-'));
    try {
      const file = join(dir, 'allowed_signers');
      writeFileSync(file, signers.map(({ login, key }) => `${login} namespaces="git" ${key}\n`).join(''));
      // Only an SSH signature counts: a GPG-signed tag would be judged by whatever keyring the
      // runner happens to have.
      if (!this.run(['cat-file', 'tag', ref]).includes('-----BEGIN SSH SIGNATURE-----')) return false;
      return this.ok(['-c', `gpg.ssh.allowedSignersFile=${file}`, 'verify-tag', ref]);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  }
}

const SIGNING_KEYS_PER_PAGE = 100;
const SIGNING_KEY_PAGES = 10;

/** Reads the signing keys GitHub publishes for an account, page by page. */
export async function fetchSigningKeys(login, token, fetchImpl = fetch) {
  const headers = { accept: 'application/vnd.github+json', 'user-agent': 'release-check-tag' };
  if (token) headers.authorization = `Bearer ${token}`;
  const keys = [];
  // Every page is read: a key past the first page would otherwise be missing from the allowed
  // signers and its tag refused as unsigned.
  for (let page = 1; page <= SIGNING_KEY_PAGES; page++) {
    const response = await fetchImpl(`https://api.github.com/users/${login}/ssh_signing_keys?per_page=${SIGNING_KEYS_PER_PAGE}&page=${page}`, { headers });
    if (!response.ok) throw new Error(`Reading the signing keys of ${login} failed with HTTP ${response.status}`);
    const entries = await response.json();
    keys.push(...entries.map((entry) => String(entry.key ?? '').trim().split(/\s+/).slice(0, 2).join(' ')));
    if (entries.length < SIGNING_KEYS_PER_PAGE) return keys;
  }
  throw new Error(`${login} lists more than ${SIGNING_KEY_PAGES * SIGNING_KEYS_PER_PAGE} signing keys; the list is not read past that`);
}

/** The project's own version: the `<version>` directly under `<project>`, not a parent's or a dependency's. */
export function pomVersion(xml) {
  const tags = /<!--[\s\S]*?-->|<\?[\s\S]*?\?>|<(\/?)([A-Za-z_][\w.:-]*)[^>]*?(\/?)>/g;
  const path = [];
  let start = -1;
  for (let match = tags.exec(xml); match; match = tags.exec(xml)) {
    const [text, closing, name, selfClosing] = match;
    if (name === undefined || selfClosing) continue;
    if (!closing) {
      path.push(name);
      if (path.join('/') === 'project/version') start = match.index + text.length;
    } else {
      if (path.join('/') === 'project/version' && start >= 0) return xml.slice(start, match.index).trim();
      path.pop();
    }
  }
  return null;
}

export function parseSigners(value) {
  const logins = String(value ?? '').split(',').map((login) => login.trim()).filter(Boolean);
  const bad = logins.find((login) => !LOGIN.test(login));
  if (bad !== undefined) throw new Error(`RELEASE_TAG_SIGNERS has an entry that is not a GitHub login: '${bad}'`);
  return logins;
}

/**
 * Returns `{ tag, sha }` for a tag that may be released, and throws with the reason otherwise.
 * `keysOf` maps a login to the SSH signing keys GitHub lists for it.
 */
export async function checkTag({ version, eventName, ref, mainRef = 'refs/remotes/origin/main', signers, pom, git, keysOf }) {
  if (!VERSION.test(version ?? '')) throw new Error(`Version must be a semver tag like v0.1.0, got '${version}'`);
  const tagRef = `refs/tags/${version}`;
  // A tag push always runs on its own tag. A manual run is accepted from main or from the tag,
  // so the workflow that signs is one that was reviewed, and the signing identity is one of the
  // two the README tells users to accept.
  const allowedRefs = eventName === 'push' ? [tagRef] : [tagRef, 'refs/heads/main'];
  if (!allowedRefs.includes(ref)) {
    throw new Error(`The release workflow must run on ${allowedRefs.join(' or ')}, not on '${ref}'`);
  }

  let sha;
  try { sha = git.commit(tagRef); } catch { throw new Error(`Tag ${version} does not exist`); }
  if (git.commit('HEAD') !== sha) throw new Error(`The working tree is not at tag ${version}`);

  if (!git.isAncestor(sha, mainRef)) {
    throw new Error(`Tag ${version} points at ${sha}, which is not on main. Release tags are cut from main (docs/RELEASING.md)`);
  }

  if (git.type(tagRef) !== 'tag') {
    throw new Error(`Tag ${version} is a lightweight tag. Release tags are signed annotated tags: git tag -s ${version}`);
  }
  if (signers.length === 0) throw new Error('RELEASE_TAG_SIGNERS names no account whose signature is accepted');
  const keys = [];
  for (const login of signers) {
    for (const key of await keysOf(login)) {
      if (SSH_KEY.test(key)) keys.push({ login, key });
    }
  }
  if (keys.length === 0) throw new Error(`No SSH signing key is published for ${signers.join(', ')}`);
  if (!git.signedBy(tagRef, keys)) {
    throw new Error(`Tag ${version} is not signed with an SSH signing key GitHub lists for ${signers.join(', ')}`);
  }

  const pomValue = pomVersion(pom);
  if (`v${pomValue}` !== version) throw new Error(`Tag ${version} does not match pom.xml version ${pomValue}`);
  return { tag: version, sha };
}

export async function main(env = process.env, log = console.log, git = new Git()) {
  try {
    const result = await checkTag({
      version: env.VERSION,
      eventName: env.GITHUB_EVENT_NAME,
      ref: env.GITHUB_REF,
      mainRef: env.MAIN_REF || undefined,
      signers: parseSigners(env.RELEASE_TAG_SIGNERS),
      pom: readFileSync(join(git.cwd, 'pom.xml'), 'utf8'),
      git,
      keysOf: (login) => fetchSigningKeys(login, env.GITHUB_TOKEN),
    });
    if (env.GITHUB_OUTPUT) appendFileSync(env.GITHUB_OUTPUT, `tag=${result.tag}\nsha=${result.sha}\n`);
    log(`Tag ${result.tag} is a signed tag on main at ${result.sha}`);
    return 0;
  } catch (error) {
    log(`::error::${error.message}`);
    return 1;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  process.exitCode = await main();
}
